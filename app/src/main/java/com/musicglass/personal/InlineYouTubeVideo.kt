package com.musicglass.personal
import android.webkit.*
import android.view.ViewGroup
import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.*

/** Visible in-player YouTube renderer for broadcasts whose native transport is unavailable. */
@Composable internal fun InlineYouTubeVideo(playback:PlaybackGateway,id:String,modifier:Modifier=Modifier) {
    val context=LocalContext.current;val lifecycle=LocalLifecycleOwner.current.lifecycle
    val gateway=playback as? MusicPlaybackGateway
    var error by remember(id) {mutableStateOf<String?>(null)}
    val web=remember(id) {WebView(context).apply {
        layoutParams=ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(android.graphics.Color.BLACK)
        settings.javaScriptEnabled=true;settings.domStorageEnabled=true;settings.mediaPlaybackRequiresUserGesture=false
        settings.allowFileAccess=false;settings.allowContentAccess=false
        webChromeClient=WebChromeClient()
        webViewClient=object:WebViewClient() {
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean {
                // Keep navigation inside the visible player; never launch a browser or another app.
                return request.url.scheme!="https" || request.url.host?.let {it=="youtube.com" || it.endsWith(".youtube.com")}!=true
            }
            override fun onReceivedError(view:WebView,request:WebResourceRequest,failure:WebResourceError) {if(request.isForMainFrame) error="Could not load YouTube. Check your connection."}
        }
        val origin="https://${context.packageName}"
        loadUrl("https://www.youtube.com/embed/$id?autoplay=1&playsinline=1&controls=0&origin=$origin",mapOf("Referer" to "$origin/"))
    }}
    DisposableEffect(web,lifecycle,gateway) {
        fun pause() {web.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){v.pause()})",null);gateway?.reportEmbedded(false)}
        gateway?.bindEmbedded {play->web.evaluateJavascript(if(play) "document.querySelectorAll('video').forEach(function(v){v.play()})" else "document.querySelectorAll('video').forEach(function(v){v.pause()})",null)}
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP) {pause();web.onPause()} else if(event==Lifecycle.Event.ON_RESUME) web.onResume()}
        lifecycle.addObserver(observer)
        onDispose {pause();gateway?.bindEmbedded(null);lifecycle.removeObserver(observer);web.stopLoading();web.loadUrl("about:blank");web.destroy()}
    }
    LaunchedEffect(web) {
        while(isActive) {
            if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) web.evaluateJavascript("JSON.stringify({playing:Array.from(document.querySelectorAll('video')).some(function(v){return !v.paused && v.readyState>=2})})") {raw->
                runCatching {val decoded=JSONTokener(raw).nextValue() as? String ?: return@runCatching;gateway?.reportEmbedded(JSONObject(decoded).optBoolean("playing"))}
            }
            delay(500)
        }
    }
    Box(modifier.background(Color.Black)) {
        AndroidView(factory={web},modifier=Modifier.fillMaxSize().testTag("youtube_live_player"))
        error?.let {Text(it,color=Pink,modifier=Modifier.align(Alignment.BottomCenter))}
    }
}
