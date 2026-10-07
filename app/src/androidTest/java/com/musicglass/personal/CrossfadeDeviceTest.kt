package com.musicglass.personal

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import java.util.concurrent.atomic.AtomicLong
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Real Android decoders and two audio renderers, using silent PCM without touching the user's queue. */
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class, androidx.media3.common.util.ExperimentalApi::class])
class CrossfadeDeviceTest {
    @Test fun oneSecondCrossfadeUsesSmoothRendererGains()=exercise("short-one")
    @Test fun twoSecondCrossfadeUsesSmoothRendererGains()=exercise("short-two")
    @Test fun lateSeekStillProducesASafeShortCrossfade() = exercise("short-late")
    @Test fun threeSecondCrossfadeBlendsBothRenderers() = exercise("short-fade")
    @Test fun transientBufferingBeforeWarmupDoesNotAbandonThePreparedCrossfade() = exercise("buffer-before")
    @Test fun automaticHandoffBlendsBothRenderersAndRestoresVolume() = exercise("complete")
    @Test fun enablingCrossfadeAfterPlaybackStartsKeepsTheMonitorAlive() = exercise("enable-later")
    @Test fun pausingDuringTheBlendStopsTheTailAndRestoresVolume() = exercise("pause")
    @Test fun disablingCrossfadeClearsTheVisualBlendImmediately() = exercise("disable")
    @Test fun manualSkipDuringCrossfadeDoesNotFadeTheManuallySelectedSong() = exercise("skip")
    @Test fun manualSkipBeforeHandoffNeverPublishesAVisualBlend() = exercise("skip-before")
    @Test fun earlyOutgoingEndDoesNotJumpIncomingVolumeToFull() = exercise("tail-end")
    @Test fun outgoingDecoderCannotRestartAudiblyAfterLosingContinuity() = exercise("tail-restart")
    @Test fun consecutiveAutomaticTransitionsEachReleaseTheirOutgoingDecoder() = exercise("repeat")

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
            .putBoolean("enabled",scenario!="enable-later").putInt("seconds",when(scenario) {"short-one"->1;"short-two"->2;"short-fade","short-late"->3;else->6}).commit()
        val wav=File(context.cacheDir,"crossfade-device-fixture.wav")
        val dataBytes=(if(scenario=="buffer-before") 50 else 30)*8000*2
        val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36+dataBytes).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1.toShort()).putShort(1.toShort()).putInt(8000).putInt(16000)
            .putShort(2.toShort()).putShort(16.toShort()).put("data".toByteArray()).putInt(dataBytes)
        wav.outputStream().use {it.write(header.array());it.write(ByteArray(dataBytes))}
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val events=mutableListOf<String>()
        val clocks=ArrayDeque<String>()
        var main:ExoPlayer?=null
        var tail:ExoPlayer?=null
        var crossfader:Crossfader?=null
        var tailReleased=false
        var visual:SongCrossfade?=null
        var visualStarts=0
        var handoffDuration=0L
        var handoffStarted=0L
        val samples=mutableListOf<Triple<Float,Float,Float>>()
        val retiredSamples=mutableListOf<Boolean>()
        var tailStoppedDuringBlend=false
        val blockMainReadsUntil=AtomicLong(0)
        suspend fun await(label:String="condition",timeout:Long=15_000,predicate:()->Boolean) {
            try {withTimeout(timeout) {while(!withContext(Dispatchers.Main) {predicate()}) delay(25)}}
            catch(e:TimeoutCancellationException) {
                val status=withContext(Dispatchers.Main) {"events=$events index=${main?.currentMediaItemIndex} position=${main?.currentPosition} volume=${main?.volume} progress=${visual?.progress} tailReleased=$tailReleased"}
                throw AssertionError("$scenario timed out at $label: $status",e)
            }
        }
        try {
            withContext(Dispatchers.Main) {
                val builder=ExoPlayer.Builder(context).experimentalSetDynamicSchedulingEnabled(false)
                if(scenario=="buffer-before") {
                    val sources=DataSource.Factory {
                        val source=DefaultDataSource.Factory(context).createDataSource()
                        object:DataSource by source {
                            override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
                                while(android.os.SystemClock.elapsedRealtime()<blockMainReadsUntil.get()) Thread.sleep(10)
                                // Keep the small WAV from being fully cached before the stall is armed.
                                Thread.sleep(20)
                                return source.read(buffer,offset,minOf(length,1024))
                            }
                        }
                    }
                    builder.setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(sources))
                    builder.setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(1000,2000,200,200).build())
                }
                val player=builder.build();main=player
                val extras=Bundle().apply {putBoolean("music",true);putString("videoUri",null)}
                fun item(id:String)=MediaItem.Builder().setMediaId("local:$id").setUri(wav.toURI().toString())
                    .setMediaMetadata(MediaMetadata.Builder().setExtras(extras).build()).build()
                player.volume=.8f
                player.setMediaItems(listOf(item("outgoing"),item("incoming"),item("manual")),0,if(scenario in setOf("short-one","short-two","short-fade")) 14_000 else 18_000)
                crossfader=Crossfader(isolated,scope,createTail={
                    val copy=ExoPlayer.Builder(context).experimentalSetDynamicSchedulingEnabled(false).build();tail=copy
                    copy.addListener(object:Player.Listener {
                        override fun onIsPlayingChanged(isPlaying:Boolean) {
                            if(visual!=null && !isPlaying) tailStoppedDuringBlend=true
                        }
                    })
                    copy to {tailReleased=true}
                },sleepActive={false},onBlend={blend->
                    visual=blend
                    if(blend!=null) {
                        visualStarts++
                        handoffDuration=blend.durationMs
                        handoffStarted=android.os.SystemClock.elapsedRealtime()
                        tailStoppedDuringBlend=false
                        scope.launch {
                            while(visual===blend) {
                                samples+=Triple(blend.progress,player.volume,tail!!.volume)
                                retiredSamples+=tailStoppedDuringBlend
                                delay(8)
                            }
                        }
                    }
                },log={events+=it}).also {it.start(player)}
                player.prepare();player.play()
                if(scenario=="short-fade") scope.launch {
                    while(isActive) {
                        val copy=tail
                        if(copy!=null && !tailReleased && visual==null) {
                            clocks.addLast("remaining=${player.duration-player.currentPosition} delta=${player.currentPosition-copy.currentPosition} speed=${copy.playbackParameters.speed} playing=${copy.isPlaying}")
                            if(clocks.size>130) clocks.removeFirst()
                        }
                        delay(100)
                    }
                }
            }
            if(scenario=="enable-later") {
                await("initial playback") {main!!.isPlaying}
                delay(1_250) // Let the monitor observe the initially disabled setting.
                withContext(Dispatchers.Main) {
                    isolated.getSharedPreferences(CROSSFADE_PREFS,Context.MODE_PRIVATE).edit().putBoolean("enabled",true).commit()
                }
            }
            await("prepare") {events.any {it=="crossfade_prepared"}}
            if(scenario=="buffer-before") {
                blockMainReadsUntil.set(android.os.SystemClock.elapsedRealtime()+5_000)
                await("actual network buffering",8_000) {main!!.playbackState==Player.STATE_BUFFERING}
                await("buffer diagnostic",2_000) {events.any {it=="crossfade_waiting reason=main_buffering"}}
                withContext(Dispatchers.Main) {
                    assertFalse("A prepared outgoing decoder must survive a temporary main-player stall",tailReleased)
                    assertEquals(0f,tail!!.volume,0f)
                    assertEquals(.8f,main!!.volume,.001f)
                }
                await("stream resumes",8_000) {main!!.isPlaying}
            }
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
            await("audible handoff",if(scenario=="buffer-before") 40_000 else if(scenario in setOf("short-one","short-two","short-fade")) 30_000 else 15_000) {main!!.currentMediaItemIndex==1 && main!!.isPlaying && main!!.volume in .02f.. .78f}
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
                    "tail-restart"->tail!!.pause()
                }
                if(scenario=="pause" || scenario=="skip") assertNull("Manual actions cancel the visual fade immediately",visual)
                if(scenario=="pause" || scenario=="skip" || scenario=="disable") {
                    assertEquals("Cancellation silences the outgoing player synchronously",0f,tail!!.volume,0f)
                    assertFalse("Cancellation pauses the outgoing player synchronously",tail!!.playWhenReady)
                }
            }
            if(scenario=="tail-restart") {
                await("tail retired") {tail!!.volume==0f}
                withContext(Dispatchers.Main) {tail!!.play()}
                delay(200)
                withContext(Dispatchers.Main) {
                    assertEquals("A resumed old decoder must remain silent",0f,tail!!.volume,0f)
                    assertNotNull("Incoming fade keeps running",visual)
                }
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
                    if(scenario=="complete" || scenario=="enable-later" || scenario=="tail-end" || scenario=="buffer-before" || scenario in setOf("short-one","short-two","short-fade","short-late")) {
                        assertTrue("Incoming position=${main!!.currentPosition}, declared overlap=$handoffDuration, events=$events",
                            android.os.SystemClock.elapsedRealtime()-handoffStarted>=handoffDuration-50 && main!!.currentPosition>0)
                        instrumentation.sendStatus(0,Bundle().apply {putString("crossfade_fixture","scenario=$scenario overlap=${handoffDuration}ms incoming=${main!!.currentPosition}ms")})
                        if(scenario in setOf("short-one","short-two","short-fade")) {
                            val expected=when(scenario) {"short-one"->1000;"short-two"->2000;else->3000}
                            assertTrue("Selected fade became too short: ${handoffDuration}ms for $expected ms; $events; clocks=$clocks",handoffDuration>=expected-250)
                        }
                        samples+=Triple(1f,.8f,0f)
                        retiredSamples+=true
                        assertTrue("Sample the entire audio ramp",samples.size>50)
                        for((index,sample) in samples.withIndex()) {
                            val (progress,incoming,outgoing)=sample
                            val expected=crossfadeGains(progress)
                            if(scenario!="tail-end" && !retiredSamples[index]) assertEquals(.8f*expected.first,outgoing,.001f)
                            else assertTrue("An ended tail must stay silent or retain its ramp gain",outgoing==0f || kotlin.math.abs(.8f*expected.first-outgoing)<.001f)
                            assertEquals(.8f*expected.second,incoming,.001f)
                        }
                        samples.zipWithNext().forEach {(before,after)->
                            assertTrue("Incoming gain must rise smoothly",after.second-before.second in -.001f.. .08f)
                            if(scenario!="tail-end") assertTrue("Outgoing gain must fall smoothly",before.third-after.third in -.001f.. .08f)
                            else assertTrue("An ended outgoing decoder must never regain volume",after.third<=before.third+.001f)
                        }
                    }
                }
            }
            if(scenario=="repeat") {
                withContext(Dispatchers.Main) {
                    tailReleased=false
                    main!!.seekTo(18_000)
                }
                await("second handoff") {main!!.currentMediaItemIndex==2 && visualStarts==2 && visual!=null}
                await("second outgoing release") {tailReleased && visual==null}
                withContext(Dispatchers.Main) {
                    assertEquals(.8f,main!!.volume,.001f)
                    assertTrue(main!!.isPlaying)
                    assertNull(main!!.playerError)
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
