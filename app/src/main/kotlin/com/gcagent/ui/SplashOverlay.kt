package com.gcagent.ui

import android.annotation.SuppressLint
import android.graphics.Color as AColor
import android.media.MediaPlayer
import android.view.View
import android.widget.VideoView
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

private const val TAG = "GCAgentSplash"

private data class SplashVideo(val name: String, val width: Int, val height: Int)

private val SPLASH_VIDEOS = listOf(
    SplashVideo("splash_1080x1920.mp4", 1080, 1920),
    SplashVideo("splash_1080x2400.mp4", 1080, 2400),
    SplashVideo("splash_1224x2700.mp4", 1224, 2700),
    SplashVideo("splash_1920x1080.mp4", 1920, 1080),
    SplashVideo("splash_2048x1536.mp4", 2048, 1536),
    SplashVideo("splash_2520x1680.mp4", 2520, 1680),
)

/** Height-first fit; reject >1.5x or any width shortfall. */
private fun pickSplashVideo(screenW: Int, screenH: Int): SplashVideo? {
    val maxScale = 1.5f
    return SPLASH_VIDEOS.mapNotNull { v ->
        val scale = screenH.toFloat() / v.height
        if (scale > maxScale + 0.0001f) return@mapNotNull null
        val scaledWidth = v.width * scale
        if (scaledWidth + 0.5f < screenW) return@mapNotNull null
        Triple(v, scale, scaledWidth)
    }.minWithOrNull(
        compareBy<Triple<SplashVideo, Float, Float>> { abs(it.second - 1f) }
            .thenBy { it.third - screenW }
    )?.first
}

@Composable
fun SplashOverlay(screenW: Int, screenH: Int, onReady: () -> Unit, onDone: () -> Unit) {
    val video = remember(screenW, screenH) { pickSplashVideo(screenW, screenH) }
    Log.i(TAG, "overlay screen=${screenW}x${screenH} video=${video?.name ?: "webview"}")
    Box(Modifier.fillMaxSize().background(Color.White)) {
        if (video != null) VideoSplash(video, onReady, onDone) else WebSplash(onReady, onDone)
    }
}

@Composable
private fun VideoSplash(video: SplashVideo, onReady: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val ready = remember { AtomicBoolean(false) }
    val done = remember { AtomicBoolean(false) }
    val markReady = { if (ready.compareAndSet(false, true)) onReady() }
    val markDone = { if (done.compareAndSet(false, true)) onDone() }
    val file = remember(video.name) { assetToCache(ctx, video.name) }
    val vv = remember { VideoView(ctx).apply { setZOrderOnTop(true) } }

    DisposableEffect(Unit) {
        onDispose { try { vv.stopPlayback() } catch (_: Exception) {} }
    }
    AndroidView(factory = { vv }, modifier = Modifier.fillMaxSize())

    LaunchedEffect(file) {
        if (file == null) {
            Log.e(TAG, "asset copy failed")
            markDone()
            return@LaunchedEffect
        }
        Log.i(TAG, "video file=${file.absolutePath} size=${file.length()}")
        vv.post {
            vv.setVideoPath(file.absolutePath)
            vv.setOnPreparedListener { mp ->
                Log.i(TAG, "video prepared ${mp.videoWidth}x${mp.videoHeight}")
                mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                vv.start()
            }
            vv.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    Log.i(TAG, "video first frame")
                    markReady()
                }
                false
            }
            vv.setOnCompletionListener {
                Log.i(TAG, "video complete")
                vv.visibility = View.GONE
                markDone()
            }
            vv.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "video error what=$what extra=$extra")
                markDone(); true
            }
        }
        delay(3000)
        markReady()
    }
    LaunchedEffect(vv) {
        while (true) {
            val duration = vv.duration
            val position = vv.currentPosition
            if (duration > 0 && position >= duration - 150) {
                Log.i(TAG, "hide before last frame pos=$position dur=$duration")
                vv.visibility = View.GONE
                markDone()
                break
            }
            delay(40)
        }
    }
    LaunchedEffect(Unit) { delay(8000); markDone() }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebSplash(onReady: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val ready = remember { AtomicBoolean(false) }
    val done = remember { AtomicBoolean(false) }
    val readyCb by rememberUpdatedState(onReady)
    val doneCb by rememberUpdatedState(onDone)
    val main = remember { Handler(Looper.getMainLooper()) }
    val markReady = { if (ready.compareAndSet(false, true)) main.post { readyCb() } }
    val markDone = { if (done.compareAndSet(false, true)) main.post { doneCb() } }

    val loader = remember {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx.applicationContext))
            .build()
    }
    val bridge = remember {
        object {
            @JavascriptInterface fun onSplashReady() = markReady()
            @JavascriptInterface fun onSplashFinished() = markDone()
        }
    }
    val web = remember {
        WebView(ctx.applicationContext).apply {
            setBackgroundColor(AColor.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            addJavascriptInterface(bridge, "AndroidBridge")
            webViewClient = object : WebViewClientCompat() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try { web.removeJavascriptInterface("AndroidBridge") } catch (_: Exception) {}
            web.destroy()
        }
    }
    AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
    LaunchedEffect(web) {
        Log.i(TAG, "webview load")
        web.loadUrl("https://appassets.androidplatform.net/assets/splash/splash.html")
        delay(3500)
        markReady()
    }
    LaunchedEffect(Unit) { delay(9000); markDone() }
}

private fun assetToCache(ctx: android.content.Context, name: String): File? = try {
    val out = File(ctx.cacheDir, "splash_$name")
    if (out.length() == 0L) {
        ctx.assets.open("splash/$name").use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
    }
    out
} catch (_: Exception) { null }
