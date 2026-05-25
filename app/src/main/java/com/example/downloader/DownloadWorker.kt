package com.example.downloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

class DownloadWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val TAG = "DownloadWorker"
    private val NOTIFICATION_CHANNEL_ID = "download_channel"
    private val NOTIFICATION_ID = 4055
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result {
        val videoUrl = inputData.getString("VIDEO_URL") ?: return Result.failure()
        val title = inputData.getString("VIDEO_TITLE") ?: "Video_${UUID.randomUUID().toString().take(6)}"
        val format = inputData.getString("VIDEO_FORMAT") ?: "mp4"
        
        val headerKeys = inputData.getStringArray("HEADER_KEYS") ?: emptyArray()
        val headerValues = inputData.getStringArray("HEADER_VALUES") ?: emptyArray()
        val headers = headerKeys.zip(headerValues).toMap()

        createNotificationChannel()

        // Setup notification early to run foreground work
        val initialNotification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Downloading Video")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, 0, true)
            .build()

        val foregroundInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, initialNotification)
        }
        
        try {
            setForeground(foregroundInfo)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot start foreground service, posting plain notification instead", e)
        }

        // Clean file name
        val rawFileName = title.replace("[\\\\/:*?\"<>|]".toRegex(), "_")
        val fileName = if (rawFileName.endsWith(".$format")) rawFileName else "$rawFileName.$format"
        val mimeType = when (format) {
            "webm" -> "video/webm"
            "m3u8" -> "application/x-mpegURL"
            "ts" -> "video/mp2t"
            else -> "video/mp4"
        }

        Log.d(TAG, "Starting download of $fileName from $videoUrl")
        
        // Build OkHttp Client with generous timeouts for larger videos
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        // Build request with headers (User-Agent, Cookies, Referer)
        val requestBuilder = Request.Builder().url(videoUrl)
        headers.forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
        }
        val request = requestBuilder.build()

        var inputStream: InputStream? = null
        var outputStream: OutputStream? = null
        var pendingUri: Uri? = null

        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Http request failed: ${response.code} ${response.message}")
                showFailureNotification(title, "Server responded with code ${response.code}")
                return Result.failure()
            }

            val body = response.body
            if (body == null) {
                Log.e(TAG, "Empty response body received from stream")
                showFailureNotification(title, "Empty server response")
                return Result.failure()
            }

            val totalBytes = body.contentLength()
            Log.d(TAG, "Download file size: $totalBytes bytes")

            inputStream = body.byteStream()

            // Define target path using Android MediaStore
            val contentResolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Downloads.IS_PENDING, 1) // File is writing
                }
            }

            pendingUri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (pendingUri == null) {
                Log.e(TAG, "Failed to create public MediaStore record in Downloads")
                showFailureNotification(title, "Storage allocation failed")
                return Result.failure()
            }

            outputStream = contentResolver.openOutputStream(pendingUri)
            if (outputStream == null) {
                Log.e(TAG, "Failed to open output stream for write")
                showFailureNotification(title, "Cannot open target file for writing")
                return Result.failure()
            }

            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalBytesRead: Long = 0
            var lastProgressUpdate: Long = 0

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead

                if (totalBytes > 0) {
                    val progress = ((totalBytesRead * 100) / totalBytes).toInt()
                    val currentTime = System.currentTimeMillis()
                    
                    // Throttle notification updates for high performance
                    if (progress - lastProgressUpdate >= 2 || currentTime - lastProgressUpdate >= 300) {
                        lastProgressUpdate = progress.toLong()
                        updateProgress(progress, title)
                        setProgress(workDataOf("progress" to progress, "title" to title))
                    }
                } else {
                    // Unknown length fallback
                    val kbs = (totalBytesRead / 1024)
                    setProgress(workDataOf("progress" to -1, "downloaded_kb" to kbs, "title" to title))
                }
            }

            outputStream.flush()

            // Remove IS_PENDING tag to register file inside Android shell databases
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(pendingUri, contentValues, null, null)
            }

            showSuccessNotification(title, fileName)
            setProgress(workDataOf("progress" to 100, "title" to title))
            return Result.success(workDataOf("fileName" to fileName))

        } catch (e: Exception) {
            Log.e(TAG, "Download execution intercepted exception", e)
            
            // Clean up partially completed video file so storage does not inflate
            pendingUri?.let { uri ->
                try {
                    context.contentResolver.delete(uri, null, null)
                } catch (delEx: Exception) {
                    Log.e(TAG, "Failed cleaning failed downloaded portion", delEx)
                }
            }
            
            showFailureNotification(title, "Error: ${e.localizedMessage}")
            return Result.failure()
        } finally {
            try {
                inputStream?.close()
                outputStream?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error cleanup streams", e)
            }
        }
    }

    private fun updateProgress(progress: Int, title: String) {
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Downloading: $progress%")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, progress, false)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun showSuccessNotification(title: String, finalFileName: String) {
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Download Finished!")
            .setContentText("Saved '$finalFileName' to Downloads folder")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun showFailureNotification(title: String, errorMessage: String) {
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Download Failed")
            .setContentText("$title: $errorMessage")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Video Downloads Manager",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows notifications of active stream video extraction downloads"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
}
