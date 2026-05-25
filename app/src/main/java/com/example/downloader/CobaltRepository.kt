package com.example.downloader

import android.net.Uri
import android.util.Log
import com.example.model.VideoInfo
import com.example.model.VideoStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

class CobaltRepository {
    private val TAG = "CobaltRepository"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val mediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun extractVideo(videoUrl: String): VideoInfo = withContext(Dispatchers.IO) {
        val apiUrl = "https://co.imput.net/"
        
        // Prepare JSON Body
        val jsonRequest = JSONObject().apply {
            put("url", videoUrl)
            put("videoQuality", "720") // standard quality to fetch
        }

        val request = Request.Builder()
            .url(apiUrl)
            .post(jsonRequest.toString().toRequestBody(mediaType))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .build()

        try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: throw IOException("Empty response from server")
            
            if (!response.isSuccessful) {
                val errorMsg = try {
                    JSONObject(responseBody).optString("text", "Server returned code ${response.code}")
                } catch (e: Exception) {
                    "Error code ${response.code}"
                }
                throw IOException(errorMsg)
            }

            Log.d(TAG, "Cobalt Raw Response: $responseBody")
            val jsonResponse = JSONObject(responseBody)
            val status = jsonResponse.optString("status", "")

            if (status == "error") {
                val errorText = jsonResponse.optString("text", "Unknown extraction error")
                throw IOException(errorText)
            }

            val platform = detectPlatform(videoUrl)
            val defaultTitle = "${platform}_Video_${System.currentTimeMillis() % 100000}"

            when (status) {
                "redirect", "stream", "success" -> {
                    val streamUrl = jsonResponse.optString("url", "")
                    if (streamUrl.isEmpty()) {
                        throw IOException("No download URL returned by extractor")
                    }
                    val filename = jsonResponse.optString("filename", defaultTitle)
                    val format = extractFormat(streamUrl, filename)

                    val stream = VideoStream(
                        url = streamUrl,
                        quality = "Direct Stream HD",
                        format = format,
                        isVideoAndAudio = true,
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                            "Accept" to "*/*"
                        )
                    )

                    VideoInfo(
                        url = videoUrl,
                        title = filename.substringBeforeLast("."),
                        platform = platform,
                        streams = listOf(stream)
                    )
                }
                "picker" -> {
                    val pickerArray = jsonResponse.optJSONArray("picker")
                    if (pickerArray == null || pickerArray.length() == 0) {
                        throw IOException("Empty media picker items from source")
                    }

                    val streamsList = mutableListOf<VideoStream>()
                    for (i in 0 until pickerArray.length()) {
                        val item = pickerArray.getJSONObject(i)
                        val itemUrl = item.optString("url", "")
                        if (itemUrl.isNotEmpty()) {
                            val quality = item.optString("quality", "HD")
                            val type = item.optString("type", "video")
                            val format = if (type == "audio") "mp3" else "mp4"

                            streamsList.add(
                                VideoStream(
                                    url = itemUrl,
                                    quality = "$quality ($type)",
                                    format = format,
                                    isVideoAndAudio = type == "video",
                                    headers = mapOf(
                                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                        "Accept" to "*/*"
                                    )
                                )
                            )
                        }
                    }

                    if (streamsList.isEmpty()) {
                        throw IOException("No selectable streams found in picker list")
                    }

                    VideoInfo(
                        url = videoUrl,
                        title = defaultTitle,
                        platform = platform,
                        streams = streamsList
                    )
                }
                else -> {
                    throw IOException("Unsupported response status: $status")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error performing video extraction", e)
            throw e
        }
    }

    private fun detectPlatform(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains("youtube.com") || lower.contains("youtu.be") -> "YouTube"
            lower.contains("tiktok.com") -> "TikTok"
            lower.contains("instagram.com") -> "Instagram"
            lower.contains("facebook.com") || lower.contains("fb.watch") || lower.contains("fb.com") -> "Facebook"
            else -> "Generic Web"
        }
    }

    private fun extractFormat(streamUrl: String, filename: String): String {
        val extension = filename.substringAfterLast(".", "").lowercase()
        if (extension.isNotEmpty() && extension.length <= 4) return extension
        
        val uri = Uri.parse(streamUrl)
        val path = uri.path ?: ""
        val pathExtension = path.substringAfterLast(".", "").lowercase()
        if (pathExtension.isNotEmpty() && pathExtension.length <= 4) return pathExtension

        return "mp4"
    }
}
