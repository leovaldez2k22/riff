package com.musicglass.personal

import android.view.SurfaceView
import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext

@Composable internal fun NativeSongVideo(playback:PlaybackGateway,modifier:Modifier) {
    val context=LocalContext.current
    val surface=remember(context) {SurfaceView(context).apply {
        layoutParams=android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,android.view.ViewGroup.LayoutParams.MATCH_PARENT)
    }}
    DisposableEffect(playback,surface) {onDispose {playback.detachVideo(surface)}}
    BoxWithConstraints(modifier.background(Color.Black),contentAlignment=Alignment.Center) {
        val ratio=playback.state.videoAspectRatio.coerceIn(.3f,4f)
        val width=minOf(maxWidth,maxHeight*ratio)
        AndroidView(factory={surface.also(playback::attachVideo)},modifier=Modifier.size(width,width/ratio),update={playback.attachVideo(it)})
    }
}
