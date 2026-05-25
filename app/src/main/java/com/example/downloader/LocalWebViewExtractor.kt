package com.example.downloader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.*
import com.example.model.VideoInfo
import com.example.model.VideoStream
import java.net.URLDecoder
import java.util.UUID

sealed class ExtractionEvent {
    object Idle : ExtractionEvent()
    data class Loading(val platform: String) : ExtractionEvent()
    data class Progress(val message: String) : ExtractionEvent()
    data class Success(val videoInfo: VideoInfo) : ExtractionEvent()
    data class Error(val message: String) : ExtractionEvent()
}

class LocalWebViewExtractor(
    private val context: Context,
    private val onEvent: (ExtractionEvent) -> Unit
) {
    private val TAG = "LocalWebViewExtractor"
    private var webView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentUrl: String = ""
    private var detectedStreams = mutableListOf<VideoStream>()
    private var isExtractionComplete = false
    private var timeoutRunnable: Runnable? = null

    // Run this on main thread
    @SuppressLint("SetJavaScriptEnabled")
    fun startExtraction(url: String) {
        mainHandler.post {
            cancelTimeout()
            isExtractionComplete = false
            currentUrl = url
            detectedStreams.clear()

            val platform = detectPlatform(url)
            onEvent(ExtractionEvent.Loading(platform))
            onEvent(ExtractionEvent.Progress("Initializing crawler background engine..."))

            // Clean previous web view if existing
            destroyWebView()

            // Construct new background web view
            val newWebView = WebView(context.applicationContext)
            webView = newWebView

            // Basic configurations
            val settings = newWebView.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false // Crucial for auto playing embed players
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            // Setup custom modern desktop/mobile standard user agent
            val defaultUa = "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/90.0.4430.210 Mobile Safari/537.36"
            settings.userAgentString = defaultUa

            newWebView.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    onEvent(ExtractionEvent.Progress("Loading platform scripts..."))
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    onEvent(ExtractionEvent.Progress("Analyzing video tags..."))
                    
                    // Periodic crawling of video tags
                    startDomExtractionLoop(newWebView, platform)
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val reqUrl = request?.url?.toString() ?: return null
                    handleNetworkRequest(reqUrl, platform)
                    return super.shouldInterceptRequest(view, request)
                }
            }

            // Load the target site
            Log.d(TAG, "Loading URL: $url")
            val targetUrl = prepareTargetUrl(url, platform)
            onEvent(ExtractionEvent.Progress("Connecting to $platform..."))
            newWebView.loadUrl(targetUrl)

            // 15 seconds extraction timeout safeguard
            scheduleTimeout()
        }
    }

    private fun prepareTargetUrl(url: String, platform: String): String {
        return if (platform == "YouTube") {
            val videoId = extractYouTubeVideoId(url)
            if (videoId != null) {
                // Load video in progressive embedded form
                "https://www.youtube.com/embed/$videoId?autoplay=1&mute=1"
            } else {
                url
            }
        } else {
            url
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

    private fun extractYouTubeVideoId(url: String): String? {
        val pattern = "(?<=watch\\?v=|/videos/|embed/|shorts/|youtu.be/|/v/|/e/|watch\\?.*v=)([^#&?]*)"
        val compiledPattern = java.util.regex.Pattern.compile(pattern)
        val matcher = compiledPattern.matcher(url)
        return if (matcher.find()) {
            matcher.group(1)
        } else {
            null
        }
    }

    @SuppressLint("DefaultLocale")
    private fun handleNetworkRequest(reqUrl: String, platform: String) {
        val lowerUrl = reqUrl.lowercase()
        
        // Match conditions
        var isStream = false
        var quality = "Standard Quality"
        var format = "mp4"

        when {
            // YouTube
            lowerUrl.contains("videoplayback") && (platform == "YouTube" || platform == "Generic Web") -> {
                isStream = true
                quality = decodeYouTubeItag(reqUrl)
                format = if (lowerUrl.contains("mime=video%2fwebm") || lowerUrl.contains("mime=video/webm")) "webm" else "mp4"
            }
            // TikTok
            (lowerUrl.contains(".tiktokcdn.com") || lowerUrl.contains("byteoversea.com") || lowerUrl.contains("/video/")) && lowerUrl.contains(".mp4") -> {
                isStream = true
                quality = "TikTok Stream HD"
                format = "mp4"
            }
            // Instagram
            lowerUrl.contains("cdninstagram.com") && lowerUrl.contains(".mp4") -> {
                isStream = true
                quality = "Instagram Stream HD"
                format = "mp4"
            }
            // Facebook
            lowerUrl.contains(".fbcdn.net") && (lowerUrl.contains(".mp4") || lowerUrl.contains(".m3u8") || lowerUrl.contains("/progressive_redirect/")) -> {
                isStream = true
                quality = if (lowerUrl.contains("_n.mp4") || lowerUrl.contains("bytestart")) "Facebook HD" else "Facebook SD"
                format = if (lowerUrl.contains(".m3u8")) "m3u8" else "mp4"
            }
            // Generic fallback
            lowerUrl.endsWith(".mp4") || lowerUrl.endsWith(".webm") || lowerUrl.endsWith(".m3u8") || lowerUrl.endsWith(".ts") -> {
                isStream = true
                quality = "Direct Link Extracted"
                format = when {
                    lowerUrl.endsWith(".m3u8") -> "m3u8"
                    lowerUrl.endsWith(".webm") -> "webm"
                    lowerUrl.endsWith(".ts") -> "ts"
                    else -> "mp4"
                }
            }
            // Generic query fallback
            (lowerUrl.contains(".mp4?") || lowerUrl.contains(".webm?") || lowerUrl.contains(".m3u8?") || lowerUrl.contains("video_stream")) -> {
                isStream = true
                quality = "Direct Stream Url"
                format = when {
                    lowerUrl.contains(".m3u8") -> "m3u8"
                    lowerUrl.contains(".webm") -> "webm"
                    else -> "mp4"
                }
            }
        }

        if (isStream) {
            onStreamIntercepted(reqUrl, quality, format)
        }
    }

    private fun decodeYouTubeItag(url: String): String {
        val uri = Uri.parse(url)
        val itag = uri.getQueryParameter("itag") ?: ""
        return when (itag) {
            "18" -> "360p (Progressive MP4 + Audio)"
            "22" -> "720p (Progressive MP4 + Audio)"
            "137" -> "1080p (Video Only - No Audio)"
            "136" -> "720p (Video Only - No Audio)"
            "135" -> "480p (Video Only - No Audio)"
            "134" -> "360p (Video Only - No Audio)"
            "140" -> "High Quality M4A Audio"
            "251" -> "WebM Opus Audio"
            else -> {
                // If it contains combined formats
                val qualityParam = uri.getQueryParameter("quality") ?: ""
                val sizeParam = uri.getQueryParameter("size") ?: ""
                if (qualityParam.isNotEmpty()) {
                    "$qualityParam ($itag)"
                } else if (sizeParam.isNotEmpty()) {
                    "$sizeParam ($itag)"
                } else {
                    "Stream Source ($itag)"
                }
            }
        }
    }

    private fun onStreamIntercepted(url: String, quality: String, format: String) {
        mainHandler.post {
            // Avoid adding duplicates
            if (detectedStreams.none { it.url == url }) {
                // Check cookie permissions
                val cookies = CookieManager.getInstance().getCookie(currentUrl) ?: ""
                val headers = mutableMapOf<String, String>()
                headers["User-Agent"] = webView?.settings?.userAgentString ?: "Mozilla/5.0"
                if (cookies.isNotEmpty()) {
                    headers["Cookie"] = cookies
                }
                headers["Referer"] = currentUrl

                val stream = VideoStream(
                    url = url,
                    quality = quality,
                    format = format,
                    isVideoAndAudio = !quality.contains("Video Only") && !quality.contains("Audio"),
                    headers = headers
                )
                detectedStreams.add(stream)
                
                onEvent(ExtractionEvent.Progress("Found stream quality Option: $quality"))
                
                // For TikTok/Instagram/Facebook, one stream is usually enough to succeed!
                val platform = detectPlatform(currentUrl)
                if (platform != "YouTube" && detectedStreams.size >= 1) {
                    completeExtraction()
                } else if (platform == "YouTube") {
                    // For YouTube, if we get a progressive format (e.g. 720p or 360p) allow instant selection
                    val progressiveFormat = detectedStreams.firstOrNull { it.quality.contains("Progressive") }
                    if (progressiveFormat != null) {
                        completeExtraction()
                    }
                }
            }
        }
    }

    private fun startDomExtractionLoop(view: WebView, platform: String) {
        val loopRunnable = object : Runnable {
            var retries = 0
            override fun run() {
                if (isExtractionComplete || webView == null) return

                val videoTagsCrawlerScript = """
                    (function() {
                        var videos = document.getElementsByTagName('video');
                        var urls = [];
                        for (var i = 0; i < videos.length; i++) {
                            if (videos[i].src) {
                                urls.push({src: videos[i].src, type: 'video'});
                            }
                            var sources = videos[i].getElementsByTagName('source');
                            for (var j = 0; j < sources.length; j++) {
                                if (sources[j].src) {
                                    urls.push({src: sources[j].src, type: sources[j].type || 'mp4'});
                                }
                            }
                        }
                        // Also inspect youtube InitialPlayerResponse JSON
                        if (typeof ytInitialPlayerResponse !== 'undefined') {
                            urls.push({src: 'yt_data', data: JSON.stringify(ytInitialPlayerResponse)});
                        }
                        return JSON.stringify(urls);
                    })()
                """.trimIndent()

                view.evaluateJavascript(videoTagsCrawlerScript) { result ->
                    if (result != null && result != "null" && result.isNotEmpty()) {
                        try {
                            val cleanResult = if (result.startsWith("\"") && result.endsWith("\"")) {
                                // Result is double-stringified JSON
                                URLDecoder.decode(result.substring(1, result.length - 1).replace("\\\"", "\""), "UTF-8")
                            } else {
                                result
                            }
                            parseCrawlerJson(cleanResult)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing JSON payload from JS client", e)
                        }
                    }
                }

                retries++
                if (retries < 15 && !isExtractionComplete) {
                    mainHandler.postDelayed(this, 1000)
                } else if (!isExtractionComplete) {
                    // Try to complete with whatever streams we captured, even if DOM loop hit limit
                    if (detectedStreams.isNotEmpty()) {
                        completeExtraction()
                    } else {
                        onEvent(ExtractionEvent.Error("No video streams found. Please make sure the URL contains a public, playable video."))
                    }
                }
            }
        }
        mainHandler.post(loopRunnable)
    }

    private fun parseCrawlerJson(jsonString: String) {
        try {
            // Minimal local regex or text splitting to avoid loading heavy JSON parser
            // Or parse using basic strings
            if (jsonString.contains("yt_data") && jsonString.contains("streamingData")) {
                // Parse YouTube Initial JSON streams
                extractStreamsFromYtJson(jsonString)
            }

            // Look for generic sources
            val srcPattern = java.util.regex.Pattern.compile("\"src\":\"([^\"]+)\"")
            val matcher = srcPattern.matcher(jsonString)
            while (matcher.find()) {
                val foundUrl = matcher.group(1)?.replace("\\/", "/") ?: ""
                if (foundUrl.startsWith("http") && !foundUrl.contains("yt_data")) {
                    handleNetworkRequest(foundUrl, detectPlatform(currentUrl))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in parseCrawlerJson", e)
        }
    }

    private fun extractStreamsFromYtJson(jsonString: String) {
        // Find format URL parameters
        // Example: "url":"https://..."
        val urlPattern = java.util.regex.Pattern.compile("\"url\":\"([^\"]+)\"")
        val matcher = urlPattern.matcher(jsonString)
        while (matcher.find()) {
            val rawUrl = matcher.group(1)?.replace("\\u0026", "&")?.replace("\\/", "/") ?: ""
            if (rawUrl.startsWith("http") && rawUrl.contains("videoplayback")) {
                handleNetworkRequest(rawUrl, "YouTube")
            }
        }
    }

    private fun completeExtraction() {
        if (isExtractionComplete) return
        isExtractionComplete = true
        cancelTimeout()

        mainHandler.post {
            if (detectedStreams.isEmpty()) {
                onEvent(ExtractionEvent.Error("No downloadable video links were captured. Try checking if the page is accessible without signing in."))
                return@post
            }

            val platform = detectPlatform(currentUrl)
            // Extract page metadata/title
            val scriptTitle = "document.title"
            webView?.evaluateJavascript(scriptTitle) { documentTitle ->
                val title = if (documentTitle != null && documentTitle != "null") {
                    documentTitle.trim().removeSurrounding("\"")
                } else {
                    "${platform}_Video_${UUID.randomUUID().toString().take(6)}"
                }
                
                // Group format options, deduplicate and deliver
                val finalVideoTitle = title.ifEmpty { "${platform} Download" }
                val videoInfo = VideoInfo(
                    url = currentUrl,
                    title = finalVideoTitle,
                    platform = platform,
                    streams = detectedStreams.distinctBy { it.quality }
                )
                onEvent(ExtractionEvent.Success(videoInfo))
                destroyWebView()
            }
        }
    }

    private fun scheduleTimeout() {
        timeoutRunnable = Runnable {
            if (!isExtractionComplete) {
                if (detectedStreams.isNotEmpty()) {
                    completeExtraction()
                } else {
                    onEvent(ExtractionEvent.Error("Connection or extraction timeout. Please check your network connection and ensure the video URL is correct and public."))
                    destroyWebView()
                }
            }
        }
        mainHandler.postDelayed(timeoutRunnable!!, 18000)
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let {
            mainHandler.removeCallbacks(it)
            timeoutRunnable = null
        }
    }

    fun destroyWebView() {
        mainHandler.post {
            cancelTimeout()
            webView?.apply {
                stopLoading()
                clearHistory()
                clearCache(true)
                webViewClient = WebViewClient()
                webChromeClient = WebChromeClient()
                loadUrl("about:blank")
            }
            webView = null
        }
    }
}
