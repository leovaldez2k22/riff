package com.musicglass.personal

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Real Android decoders and two audio renderers, using silent PCM without touching the user's queue. */
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class CrossfadeDeviceTest {
    @Test fun automaticHandoffBlendsBothRenderersAndRestoresVolume() = exercise("complete")
    @Test fun pausingDuringTheBlendStopsTheTailAndRestoresVolume() = exercise("pause")
    @Test fun disablingCrossfadeClearsTheVisualBlendImmediately() = exercise("disable")
    @Test fun manualSkipDuringCrossfadeDoesNotFadeTheManuallySelectedSong() = exercise("skip")
    @Test fun manualSkipBeforeHandoffNeverPublishesAVisualBlend() = exercise("skip-before")
    @Test fun earlyOutgoingEndDoesNotJumpIncomingVolumeToFull() = exercise("tail-end")

    private fun exercise(scenario:String) = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val activity=instrumentation.startActivitySync(android.content.Intent(context,FeatureTestActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.runOnMainSync {activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        // Crossfader reads its preferences directly; isolate them from the real service.
        val isolated=object:android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("crossfade-device-fixture",mode)
        }
        isolated.getSharedPreferences(CROSSFADE_PREFS,Context.MODE_PRIVATE).edit()
            .putBoolean("enabled",true).putInt("seconds",6).commit()
        val wav=File(context.cacheDir,"crossfade-device-fixture.wav")
        val dataBytes=30*8000*2
        val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36+dataBytes).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1.toShort()).putShort(1.toShort()).putInt(8000).putInt(16000)
            .putShort(2.toShort()).putShort(16.toShort()).put("data".toByteArray()).putInt(dataBytes)
        wav.outputStream().use {it.write(header.array());it.write(ByteArray(dataBytes))}
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val events=mutableListOf<String>()
        var main:ExoPlayer?=null
        var tail:ExoPlayer?=null
        var crossfader:Crossfader?=null
        var tailReleased=false
        var visual:SongCrossfade?=null
        var visualStarts=0
        val samples=mutableListOf<Triple<Float,Float,Float>>()
        suspend fun await(label:String="condition",timeout:Long=15_000,predicate:()->Boolean) {
            try {withTimeout(timeout) {while(!withContext(Dispatchers.Main) {predicate()}) delay(25)}}
            catch(e:TimeoutCancellationException) {
                val status=withContext(Dispatchers.Main) {"events=$events index=${main?.currentMediaItemIndex} position=${main?.currentPosition} volume=${main?.volume} progress=${visual?.progress} tailReleased=$tailReleased"}
                throw AssertionError("$scenario timed out at $label: $status",e)
            }
        }
        try {
            withContext(Dispatchers.Main) {
                val player=ExoPlayer.Builder(context).build();main=player
                val extras=Bundle().apply {putBoolean("music",true);putString("videoUri",null)}
                fun item(id:String)=MediaItem.Builder().setMediaId("local:$id").setUri(wav.toURI().toString())
                    .setMediaMetadata(MediaMetadata.Builder().setExtras(extras).build()).build()
                player.volume=.8f
                player.setMediaItems(listOf(item("outgoing"),item("incoming"),item("manual")),0,18_000)
                crossfader=Crossfader(isolated,scope,createTail={
                    val copy=ExoPlayer.Builder(context).build();tail=copy
                    copy to {tailReleased=true}
                },sleepActive={false},onBlend={blend->
                    visual=blend
                    if(blend!=null) {
                        visualStarts++
                        scope.launch {
                            while(visual===blend) {
                                samples+=Triple(blend.progress,player.volume,tail!!.volume)
                                delay(8)
                            }
                        }
                    }
                },log={events+=it}).also {it.start(player)}
                player.prepare();player.play()
            }
            await("prepare") {events.any {it=="crossfade_prepared"}}
            if(scenario=="skip-before") {
                withContext(Dispatchers.Main) {main!!.seekToNextMediaItem()}
                await {tailReleased}
                withContext(Dispatchers.Main) {
                    assertNull(visual)
                    assertEquals(0,visualStarts)
                    assertEquals(.8f,main!!.volume,.001f)
                    assertEquals("local:incoming",main!!.currentMediaItem!!.mediaId)
                }
                return@runBlocking
            }
            await("audible handoff") {main!!.currentMediaItemIndex==1 && main!!.isPlaying && main!!.volume in .02f.. .78f}
            withContext(Dispatchers.Main) {
                assertTrue("Outgoing decoder must still play during handoff",tail!!.isPlaying)
                assertEquals("Outgoing song must hand off before its end",Player.STATE_READY,tail!!.playbackState)
                val power=main!!.volume*main!!.volume+tail!!.volume*tail!!.volume
                assertEquals("Equal-power blend",.64f,power,.04f)
                assertTrue(events.any {it.startsWith("crossfade_start")})
                assertNotNull("Visual fade must originate in the audio handoff",visual)
                assertEquals("local:incoming",visual!!.incomingId)
                when(scenario) {
                    "pause"->main!!.pause()
                    "disable"->isolated.getSharedPreferences(CROSSFADE_PREFS,Context.MODE_PRIVATE).edit().putBoolean("enabled",false).commit()
                    "skip"->main!!.seekToNextMediaItem()
                    "tail-end"->tail!!.seekTo(tail!!.duration)
                }
                if(scenario=="pause" || scenario=="skip") assertNull("Manual actions cancel the visual fade immediately",visual)
            }
            if(scenario=="tail-end") {
                await {tail!!.playbackState==Player.STATE_ENDED}
                withContext(Dispatchers.Main) {
                    assertNotNull("Incoming fade continues after an early outgoing end",visual)
                    assertTrue("No sudden full-volume restoration",main!!.volume<.78f)
                }
            }
            await("tail release") {tailReleased}
            if(scenario=="skip") await("manual decoder ready") {main!!.currentMediaItemIndex==2 && main!!.isPlaying}
            withContext(Dispatchers.Main) {
                assertEquals(.8f,main!!.volume,.001f)
                assertNull(main!!.playerError)
                assertNull(visual)
                assertEquals(1,visualStarts)
                if(scenario=="pause") assertFalse(main!!.isPlaying) else {
                    assertTrue(main!!.isPlaying)
                    if(scenario=="skip") assertEquals("local:manual",main!!.currentMediaItem!!.mediaId)
                    if(scenario=="complete" || scenario=="tail-end") {
                        assertTrue(main!!.currentPosition>=4000)
                        samples+=Triple(1f,.8f,0f)
                        assertTrue("Sample the entire audio ramp",samples.size>50)
                        for((progress,incoming,outgoing) in samples) {
                            val expected=crossfadeGains(progress)
                            assertEquals(.8f*expected.first,outgoing,.001f)
                            assertEquals(.8f*expected.second,incoming,.001f)
                        }
                        samples.zipWithNext().forEach {(before,after)->
                            assertTrue("Incoming gain must rise smoothly",after.second-before.second in -.001f.. .08f)
                            assertTrue("Outgoing gain must fall smoothly",before.third-after.third in -.001f.. .08f)
                        }
                    }
                }
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply {
                putString("crossfade_device","$scenario passed; visualStarts=$visualStarts gainSamples=${samples.size}")
            })
        } finally {
            withContext(Dispatchers.Main) {crossfader?.release()}
            // Let the canceled blend release its second renderer before releasing the main one.
            delay(100)
            withContext(Dispatchers.Main) {main?.release()}
            scope.cancel();wav.delete();context.deleteSharedPreferences("crossfade-device-fixture")
            instrumentation.runOnMainSync {activity.finish()}
        }
    }
}
