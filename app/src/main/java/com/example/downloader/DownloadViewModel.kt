package com.example.downloader

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.model.VideoInfo
import com.example.model.VideoStream
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class DownloadViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "DownloadViewModel"
    private val workManager = WorkManager.getInstance(application)
    private var webViewExtractor: LocalWebViewExtractor? = null

    // UI Input field
    private val _urlInput = MutableStateFlow("")
    val urlInput: StateFlow<String> = _urlInput.asStateFlow()

    // Extraction State
    private val _extractionEvent = MutableStateFlow<ExtractionEvent>(ExtractionEvent.Idle)
    val extractionEvent: StateFlow<ExtractionEvent> = _extractionEvent.asStateFlow()

    // Active Extract results
    private val _extractedVideoInfo = MutableStateFlow<VideoInfo?>(null)
    val extractedVideoInfo: StateFlow<VideoInfo?> = _extractedVideoInfo.asStateFlow()

    // Download monitoring states
    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()

    private val _downloadTitle = MutableStateFlow("")
    val downloadTitle: StateFlow<String> = _downloadTitle.asStateFlow()

    // Error / Success Toast event triggers
    private val _showSnackbar = MutableSharedFlow<String>()
    val showSnackbar: SharedFlow<String> = _showSnackbar.asSharedFlow()

    init {
        // Build direct local webview extractor with application context
        webViewExtractor = LocalWebViewExtractor(application) { event ->
            handleExtractionEvent(event)
        }
    }

    fun onUrlChange(newUrl: String) {
        _urlInput.value = newUrl
    }

    fun startExtraction() {
        val url = _urlInput.value.trim()
        if (url.isEmpty()) {
            announceMsg("Please enter or paste a valid video link")
            return
        }

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            announceMsg("Invalid URL structure. Ensure it begins with http:// or https://")
            return
        }

        _extractedVideoInfo.value = null
        webViewExtractor?.startExtraction(url)
    }

    private fun handleExtractionEvent(event: ExtractionEvent) {
        _extractionEvent.value = event
        when (event) {
            is ExtractionEvent.Success -> {
                _extractedVideoInfo.value = event.videoInfo
                announceMsg("Successfully detected visual stream options!")
            }
            is ExtractionEvent.Error -> {
                announceMsg(event.message)
            }
            else -> {}
        }
    }

    fun triggerStreamDownload(stream: VideoStream) {
        val videoInfo = _extractedVideoInfo.value ?: return
        
        // Prepare arrays from headers map to avoid WorkData limitation of heavy structures
        val keysArray = stream.headers.keys.toTypedArray()
        val valuesArray = stream.headers.values.toTypedArray()

        val inputData = Data.Builder()
            .putString("VIDEO_URL", stream.url)
            .putString("VIDEO_TITLE", videoInfo.title)
            .putString("VIDEO_FORMAT", stream.format)
            .putStringArray("HEADER_KEYS", keysArray)
            .putStringArray("HEADER_VALUES", valuesArray)
            .build()

        val uniqueWorkName = "media_download_${UUID.randomUUID().toString().take(6)}"
        
        val downloadWorkRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(inputData)
            .addTag("video_downloads")
            .build()

        _isDownloading.value = true
        _downloadProgress.value = 0
        _downloadTitle.value = videoInfo.title

        workManager.enqueueUniqueWork(
            uniqueWorkName,
            ExistingWorkPolicy.REPLACE,
            downloadWorkRequest
        )

        // Listen to Work states
        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(downloadWorkRequest.id).collect { workInfo ->
                if (workInfo != null) {
                    when (workInfo.state) {
                        WorkInfo.State.RUNNING -> {
                            val progress = workInfo.progress.getInt("progress", 0)
                            _downloadProgress.value = progress
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            _downloadProgress.value = 100
                            _isDownloading.value = false
                            val fileName = workInfo.outputData.getString("fileName") ?: "video"
                            announceMsg("Video successfully saved in Downloads as: $fileName")
                            resetStates()
                        }
                        WorkInfo.State.FAILED -> {
                            _isDownloading.value = false
                            _downloadProgress.value = 0
                            announceMsg("Download failed. Content URL might be secured or invalid.")
                        }
                        WorkInfo.State.CANCELLED -> {
                            _isDownloading.value = false
                            _downloadProgress.value = 0
                            announceMsg("Download cancelled by user.")
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    private fun announceMsg(message: String) {
        viewModelScope.launch {
            _showSnackbar.emit(message)
        }
    }

    fun resetStates() {
        _urlInput.value = ""
        _extractedVideoInfo.value = null
        _extractionEvent.value = ExtractionEvent.Idle
        webViewExtractor?.destroyWebView()
    }

    override fun onCleared() {
        super.onCleared()
        webViewExtractor?.destroyWebView()
    }
}
