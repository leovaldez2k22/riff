package com.musicglass.personal
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp

/** Isolated visual fixture: never writes the user's accessibility preferences or playback queue. */
class FeaturePreviewActivity:ComponentActivity() {
    var navBounds:Rect?=null
    var queueBounds:Rect?=null
    internal val fixture=FixturePlayback()
    internal val model=ShellModel()
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);window.setDecorFitsSystemWindows(false)
        window.attributes=window.attributes.apply {preferredRefreshRate=90f}
        val isolated=object:ContextWrapper(this) {
            override fun getApplicationContext():Context=this
            override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=super.getSharedPreferences("riff-visual-test",mode)
        }
        val settings=AccessibilitySettings(isolated).apply {updateSolidControls(intent.getStringExtra("scene")!="navigation_liquid")}
        setContent {CompositionLocalProvider(LocalAccessibilitySettings provides settings,LocalUiRefreshRate provides 90f) {MaterialTheme {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                if(intent.getStringExtra("scene")=="home") HomeFeedScreen(model,120.dp,fixture,
                    LocalCollections(this@FeaturePreviewActivity,YouTubeMusicProvider.shared(this@FeaturePreviewActivity).accountScopeKey()),
                    YouTubeMusicProvider.shared(this@FeaturePreviewActivity),{})
                else if(intent.getStringExtra("scene")=="queue") Box(Modifier.fillMaxWidth().height(500.dp).align(Alignment.Center).onGloballyPositioned {queueBounds=it.boundsInRoot()}) {
                    InlineQueue(fixture,YouTubeMusicProvider.shared(this@FeaturePreviewActivity),{},{},{})
                } else Box(Modifier.fillMaxWidth().padding(horizontal=24.dp).height(58.dp).align(Alignment.Center).onGloballyPositioned {navBounds=it.boundsInRoot()}) {LiquidTabBar(model,false,58.dp)}
            }
        }}}
    }
    override fun onDestroy() {deleteSharedPreferences("riff-visual-test");super.onDestroy()}
}
internal class FixturePlayback:PlaybackGateway {
    override var state by mutableStateOf(PlaybackUiState(ready=true,index=0,title="Queue fixture",queueTitle="Test collection",queueMessage="Collection order",queue=(0..50).map {QueueEntry("yt:video%06d".format(it),"Track $it","Artist",queueId="fixture:$it")}))
    override val positionMs=0L
    val moves=mutableListOf<Pair<Int,Int>>()
    override fun moveQueueItem(from:Int,to:Int) {if(from !in state.queue.indices || to !in state.queue.indices) return
        val active=state.queue[state.index];val queue=state.queue.toMutableList();queue.add(to,queue.removeAt(from));state=state.copy(queue=queue,index=queue.indexOf(active));moves+=from to to}
    override fun toggle() {};override fun pause() {};override fun resume() {};override fun next() {};override fun previous() {};override fun seekTo(positionMs:Long) {};override fun toggleShuffle() {};override fun cycleRepeat() {};override fun removeQueueItem(index:Int) {};override fun playProvider(track:ProviderTrack,stream:ProviderStream,startPositionMs:Long) {};override fun playLocal(track:LocalAudioTrack) {};override fun clearProviderPlayback() {};override fun close() {}
}
