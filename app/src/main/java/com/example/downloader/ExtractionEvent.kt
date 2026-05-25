package com.example.downloader

import com.example.model.VideoInfo

sealed class ExtractionEvent {
    object Idle : ExtractionEvent()
    data class Loading(val platform: String) : ExtractionEvent()
    data class Progress(val message: String) : ExtractionEvent()
    data class Success(val videoInfo: VideoInfo) : ExtractionEvent()
    data class Error(val message: String) : ExtractionEvent()
}
