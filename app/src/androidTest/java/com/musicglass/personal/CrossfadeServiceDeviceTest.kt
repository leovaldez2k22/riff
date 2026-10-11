package com.musicglass.personal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** Tests the actual service, effects, media controller and app-wide visual signal on the phone. */
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class CrossfadeServiceDeviceTest {
    @Test fun serviceCrossfadeAndVisualProgressWorkWithBoostOff()=exercise("complete",false)
    @Test fun serviceCrossfadeAndVisualProgressWorkWithBoostOn()=exercise("complete",true)
    @Test fun manualServiceSkipCancelsTheVisualFade()=exercise("skip",true)
    @Test fun crossfadeOffNeverPublishesAVisualFade()=exercise("off",false)
    @Test fun streamedSongsCrossfadeWithBoostAndLiveVisualProgress()=exercise("stream",true)

    @Test fun streamedOneSecondFadeAfterSeekingSevenSecondsBeforeEnd()=exercise("stream",true,1,7000)
    @Test fun streamedTwoSecondFadeAfterSeekingSevenSecondsBeforeEnd()=exercise("stream",true,2,7000)
    @Test fun streamedThreeSecondFadeAfterSeekingSevenSecondsBeforeEnd()=exercise("stream",true,3,7000)

    private fun exercise(mode:String,boosted:Boolean,seconds:Int=6,seekLead:Long=12_000)=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val saved=phoneSavedQueue(context)
        val fadePrefs=context.getSharedPreferences(CROSSFADE_PREFS,Context.MODE_PRIVATE)
        val boostPrefs=context.getSharedPreferences(AUDIO_BOOST_PREFS,Context.MODE_PRIVATE)
        val originalFade=fadePrefs.getBoolean("enabled",false)
        val originalSeconds=fadePrefs.getInt("seconds",6)
        val originalBoost=boostPrefs.getBoolean("enabled",false)
        val originalPercent=boostPrefs.getInt("percent",200)
        val collections=context.getSharedPreferences("music_collections",Context.MODE_PRIVATE)
        val originalHistory=collections.getBoolean("history_enabled",true)
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.runOnMainSync {activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        val wav=File(context.cacheDir,"crossfade-service-fixture.wav")
        val rate=16000
        val pcm=ByteBuffer.allocate(30*rate*2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(30*rate) {pcm.putShort((1500*sin(2*PI*330*it/rate)).toInt().toShort())}
        val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36+pcm.capacity()).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate*2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(pcm.capacity())
        wav.outputStream().use {it.write(header.array());it.write(pcm.array())}
        var controller:MediaController?=null
        suspend fun await(label:String,predicate:()->Boolean) {
            try {withTimeout(if(mode=="stream") 60_000L else 20_000L) {while(!withContext(Dispatchers.Main) {predicate()}) delay(20)}}
            catch(e:TimeoutCancellationException) {
                val status=withContext(Dispatchers.Main) {"id=${controller?.currentMediaItem?.mediaId} state=${controller?.playbackState} position=${controller?.currentPosition} volume=${controller?.volume} progress=${SongCrossfadeState.active?.progress}"}
                throw AssertionError("$mode boost=$boosted at $label: $status",e)
            }
        }
        fun restoredItem(item:SavedQueueItem)=MediaItem.Builder().setMediaId(item.mediaId)
            .setUri(if(item.mediaId.startsWith("yt:")) "catalog://${item.mediaId.removePrefix("yt:")}" else item.localUri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).setArtist(item.artist).setAlbumTitle(item.album)
                .setArtworkUri(item.artworkUrl?.let(Uri::parse)).setExtras(Bundle().apply {
                    putString("artistId",item.artistId);putString("albumId",item.albumId)
                    putBoolean("live",item.live);putBoolean("video",item.live);putBoolean("music",item.music)
                }).build()).build()
        try {
            val connection=withContext(Dispatchers.Main) {MediaController.Builder(context,
                SessionToken(context,ComponentName(context,MusicPlaybackService::class.java))).buildAsync()}
            val player=withContext(Dispatchers.IO) {connection.get(20,java.util.concurrent.TimeUnit.SECONDS)}
            controller=player
            // Reset recommendation state through the normal app command, with autoplay disabled.
            val resetFile=File(context.cacheDir,"queue-request-${java.util.UUID.randomUUID()}.json")
                .apply {writeText(encodePlaybackSnapshot(PlaybackSnapshot(listOf(SavedQueueItem("yt:5qZQEq_C3vc","Fixture reset","Device verification")),0,0)))}
            val reset=withContext(Dispatchers.Main) {player.sendCustomCommand(SessionCommand(PLAY_CATALOG_QUEUE,Bundle.EMPTY),
                Bundle().apply {putString("queueFile",resetFile.name);putBoolean("autoplay",false)})}
            assertEquals(SessionResult.RESULT_SUCCESS,withContext(Dispatchers.IO) {reset.get(10,java.util.concurrent.TimeUnit.SECONDS)}.resultCode)
            withContext(Dispatchers.Main) {
                collections.edit().putBoolean("history_enabled",false).commit()
                fadePrefs.edit().putBoolean("enabled",mode!="off").putInt("seconds",seconds).commit()
                boostPrefs.edit().putBoolean("enabled",boosted).putInt("percent",300).commit()
                fun item(id:Int)=MediaItem.Builder().setMediaId("local:$id").setUri(Uri.fromFile(wav))
                    .setMediaMetadata(MediaMetadata.Builder().setTitle("Crossfade fixture $id").setArtist("Device verification")
                        .setExtras(Bundle().apply {putBoolean("music",true);putString("videoUri",null)}).build()).build()
                player.stop();player.repeatMode=Player.REPEAT_MODE_OFF;player.shuffleModeEnabled=false;player.volume=.8f
                val items=if(mode=="stream") listOf(
                    restoredItem(SavedQueueItem("yt:5qZQEq_C3vc","Numb","Linkin Park")),
                    restoredItem(SavedQueueItem("yt:4NRXx6U8ABQ","Blinding Lights","The Weeknd")))
                    else listOf(item(800000001),item(800000002),item(800000003))
                player.setMediaItems(items,0,if(mode=="stream") 0 else if(mode=="off") 26000 else 18000)
                player.prepare();player.play()
            }
            await("initial playback") {player.isPlaying && player.currentMediaItemIndex==0}
            if(mode=="stream") {
                await("stream duration") {player.duration>20_000}
                withContext(Dispatchers.Main) {player.seekTo(player.duration-seekLead)}
                await("stream tail seek") {player.isPlaying && player.currentPosition>=player.duration-seekLead-1000}
            }
            if(mode=="off") {
                val end=android.os.SystemClock.elapsedRealtime()+12_000
                while(withContext(Dispatchers.Main) {player.currentMediaItemIndex==0}) {
                    assertTrue(android.os.SystemClock.elapsedRealtime()<end)
                    withContext(Dispatchers.Main) {assertNull(SongCrossfadeState.active);assertEquals(.8f,player.volume,.001f)}
                    delay(20)
                }
                withContext(Dispatchers.Main) {assertNull(SongCrossfadeState.active);assertEquals(.8f,player.volume,.001f)}
            } else {
                await("actual blend") {SongCrossfadeState.active!=null && player.currentMediaItemIndex==1}
                val blend=withContext(Dispatchers.Main) {SongCrossfadeState.active!!}
                if(mode=="stream" && seekLead==7000L) assertTrue(
                    "Short streamed fade must retain its selected duration: ${blend.durationMs} ms for $seconds seconds",
                    blend.durationMs>=seconds*1000L-250)
                assertEquals(if(mode=="stream") "yt:5qZQEq_C3vc" else "local:800000001",blend.outgoing.mediaId)
                assertEquals(if(mode=="stream") "Numb" else "Crossfade fixture 800000001",blend.outgoing.title)
                assertEquals(if(mode=="stream") "Linkin Park" else "Device verification",blend.outgoing.artist)
                assertEquals(if(mode=="stream") "yt:4NRXx6U8ABQ" else "local:800000002",blend.incomingId)
                if(mode=="skip") {
                    withContext(Dispatchers.Main) {player.seekToNextMediaItem()}
                    await("manual selection") {player.currentMediaItemIndex==2 && SongCrossfadeState.active==null && player.volume>.799f}
                } else {
                    val samples=mutableListOf<Pair<Float,Float>>()
                    val sampleTimes=mutableListOf<Long>()
                    val began=android.os.SystemClock.elapsedRealtime()
                    while(withContext(Dispatchers.Main) {SongCrossfadeState.active===blend}) {
                        withContext(Dispatchers.Main) {samples+=blend.progress to player.volume;sampleTimes+=android.os.SystemClock.elapsedRealtime();assertNull(player.playerError)}
                        delay(20)
                        assertTrue("Blend must complete",android.os.SystemClock.elapsedRealtime()-began<(if(mode=="stream") 60_000 else 20_000))
                    }
                    await("restored volume") {player.volume>.799f}
                    assertTrue("Visuals span the audio blend",android.os.SystemClock.elapsedRealtime()-began>=blend.durationMs-200)
                    assertTrue("Sample the fade at its configured duration",samples.size>=maxOf(8,(blend.durationMs/40).toInt()))
                    samples.zipWithNext().forEachIndexed {index,(before,after)->
                        val elapsed=sampleTimes[index+1]-sampleTimes[index]
                        val allowed=maxOf(.05f,(elapsed+50).toFloat()/blend.durationMs)
                        assertTrue("Live progress must follow elapsed time",after.first-before.first in -.001f..allowed)
                        // Controller notifications can coalesce several renderer updates in a short fade.
                        // Exact per-tick gains are checked on the actual renderers in CrossfadeDeviceTest.
                        assertTrue("Controller volume rises without reversing",after.second>=before.second-.002f)
                        assertTrue("Controller volume stays within the original level",after.second in 0f.. .801f)
                    }
                    withContext(Dispatchers.Main) {assertTrue(player.isPlaying);assertNull(SongCrossfadeState.active)}
                    instrumentation.sendStatus(0,Bundle().apply {putString("service_crossfade","mode=$mode boost=$boosted seconds=$seconds seekLead=$seekLead samples=${samples.size} duration=${blend.durationMs}ms")})
                }
            }
        } finally {
            withContext(Dispatchers.Main) {
                fadePrefs.edit().putBoolean("enabled",false).commit()
                controller?.let {player->
                    player.pause();player.stop();player.volume=1f
                    if(saved!=null) {player.setMediaItems(saved.queue.map(::restoredItem),saved.index,saved.positionMs)
                        player.repeatMode=saved.repeat;player.shuffleModeEnabled=saved.shuffle
                        player.setPlaylistMetadata(MediaMetadata.Builder().setTitle(saved.collectionTitle).build())}
                    else player.clearMediaItems()
                    player.release()
                }
                fadePrefs.edit().putBoolean("enabled",originalFade).putInt("seconds",originalSeconds).commit()
                boostPrefs.edit().putBoolean("enabled",originalBoost).putInt("percent",originalPercent).commit()
                collections.edit().putBoolean("history_enabled",originalHistory).commit()
                activity.finish()
            }
            wav.delete()
        }
    }
}
