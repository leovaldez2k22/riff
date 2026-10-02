package com.musicglass.personal

import android.content.Intent
import android.net.Uri
import android.webkit.*
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner

/** YouTube's visible player handles its live broadcasts, rather than a Music audio endpoint. */
@Composable internal fun YouTubeLiveScreen(model:ShellModel,playback:PlaybackGateway,bottom:Dp) {
    val context=LocalContext.current
    val track=model.liveTrack?:playback.state.let {ProviderTrack(it.mediaId.removePrefix("yt:"),it.title,it.artist,it.artworkUrl,isLive=true)}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var error by remember(track.videoId) {mutableStateOf<Int?>(null)}
    var loading by remember(track.videoId) {mutableStateOf(true)}
    val web=remember(track.videoId) {WebView(context).apply {
        layoutParams=android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(android.graphics.Color.BLACK)
        settings.javaScriptEnabled=true;settings.domStorageEnabled=true
        settings.mediaPlaybackRequiresUserGesture=false
        settings.allowFileAccess=false;settings.allowContentAccess=false
        val origin="https://${context.packageName}"
        webChromeClient=object:WebChromeClient() {
            override fun onProgressChanged(view:WebView,progress:Int) {if(progress==100) loading=false}
        }
        webViewClient=object:WebViewClient() {
            override fun onPageFinished(view:WebView,url:String?) {
                loading=false
                for(delayMs in listOf(5000L,10000L)) view.postDelayed({if(view.isAttachedToWindow) view.evaluateJavascript("JSON.stringify(Array.from(document.querySelectorAll(\"video\")).map(function(v){return {playing:!v.paused,time:Math.floor(v.currentTime),ready:v.readyState}}))") {Log.i("RiffYouTube","Playback state: $it")}},delayMs)
                view.evaluateJavascript("JSON.stringify({ready:document.readyState,video:!!document.querySelector(\"video\"),height:document.body?document.body.offsetHeight:0,viewport:innerHeight,player:!!document.querySelector(\"#player\")})") {Log.i("RiffYouTube","Page state: $it size=${view.width}x${view.height}")}
            }
            override fun onReceivedError(view:WebView,request:WebResourceRequest,failure:WebResourceError) {
                if(request.isForMainFrame) {error=failure.errorCode;loading=false}
            }
            override fun onReceivedHttpError(view:WebView,request:WebResourceRequest,response:WebResourceResponse) {
                if(request.isForMainFrame) {error=response.statusCode;loading=false}
            }
        }
        loadUrl("https://www.youtube.com/embed/${track.videoId}?autoplay=1&playsinline=1&controls=1&origin=$origin",mapOf("Referer" to "$origin/"))

    }}
    DisposableEffect(web,lifecycle) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_RESUME) web.onResume() else if(event==Lifecycle.Event.ON_STOP) {web.evaluateJavascript("document.querySelectorAll(\"video\").forEach(function(v){v.pause()})",null);web.onPause()}}
        lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer);web.stopLoading();web.loadUrl("about:blank");web.destroy()}
    }
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().padding(bottom=bottom)) {
        Box(Modifier.padding(horizontal=16.dp)) {BackHeading("Radio",model::back)}
        Text(track.title,color=White,fontSize=22.sp,modifier=Modifier.padding(horizontal=20.dp,vertical=10.dp))
        Text(track.artist,color=Muted,modifier=Modifier.padding(horizontal=20.dp))
        Spacer(Modifier.height(20.dp))
        AndroidView(factory={web},modifier=Modifier.fillMaxWidth().heightIn(min=240.dp).aspectRatio(16f/9f).testTag("youtube_live_player"))
        if(loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal=20.dp).testTag("youtube_loading"))
        error?.let {Text("YouTube could not play this broadcast (code $it).",color=Pink,modifier=Modifier.padding(20.dp))}
        TextButton({context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.youtube.com/watch?v=${track.videoId}")))}) {Text("Open on YouTube")}
    }
}
