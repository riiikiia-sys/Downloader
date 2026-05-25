package com.example.model

import java.io.Serializable

data class VideoInfo(
    val url: String,
    val title: String,
    val platform: String,
    val streams: List<VideoStream>
) : Serializable

data class VideoStream(
    val url: String,
    val quality: String,
    val format: String, // "mp4", "webm", "m3u8", "ts", etc.
    val isVideoAndAudio: Boolean = true,
    val headers: Map<String, String> = emptyMap()
) : Serializable
