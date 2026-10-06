package com.musicglass.personal

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Real provider audio through the service, bypassing downloaded files. */
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class AudioContinuityInstrumentation : Instrumentation() {
    override fun onCreate(arguments:Bundle?) {super.onCreate(arguments);start()}
    override fun onStart() {
        val result=Bundle()
        try {
            // The phone can freeze a cached process before an unprepared media service
            // becomes foreground. Start like a real user, then test background playback.
            val activity=startActivitySync(android.content.Intent(targetContext,MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            runOnMainSync {activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            runOnMainSync {YouTubeMusicProvider.shared(targetContext)}
            val provider=YouTubeMusicProvider.shared(targetContext)
            val saved=targetContext.getSharedPreferences("last_playback",android.content.Context.MODE_PRIVATE)
                .getString("snapshot",null)?.let(::decodePlaybackSnapshot)
            runBlocking {
                val gateway=withContext(Dispatchers.Main) {MusicPlaybackGateway(targetContext)}
                var player:MediaController?=null
                try {
                    withTimeout(15_000) {while(!withContext(Dispatchers.Main) {gateway.state.ready}) delay(50)}
                    val connection=withContext(Dispatchers.Main) {
                        MediaController.Builder(targetContext,SessionToken(targetContext,
                            android.content.ComponentName(targetContext,MusicPlaybackService::class.java))).buildAsync()
                    }
                    val controller=withContext(Dispatchers.IO) {connection.get(15,java.util.concurrent.TimeUnit.SECONDS)}
                    player=controller
                    val tracks=listOfNotNull(saved?.queue?.getOrNull(saved.index)?.takeIf {it.mediaId.startsWith("yt:") && !it.live && it.music}?.let {
                        ProviderTrack(it.mediaId.removePrefix("yt:"),it.title,it.artist)
                    },ProviderTrack("5qZQEq_C3vc","Numb","Linkin Park"),
                        ProviderTrack("4NRXx6U8ABQ","Blinding Lights","The Weeknd")).distinctBy {it.videoId}
                    val measurements=JSONArray()
                    suspend fun awaitPlaying(expectedId:String?=null) {
                        withTimeout(45_000) {
                            var stableSince=0L
                            while(true) {
                                val ready=withContext(Dispatchers.Main) {
                                    controller.playerError?.let {error(it.errorCodeName)}
                                    controller.isPlaying && controller.playbackState==Player.STATE_READY &&
                                        (expectedId==null || controller.currentMediaItem?.mediaId==expectedId)
                                }
                                val now=android.os.SystemClock.elapsedRealtime()
                                if(!ready) stableSince=0L
                                else if(stableSince==0L) stableSince=now
                                else if(now-stableSince>=1000) break
                                delay(100)
                            }
                        }
                    }
                    suspend fun continuous(seconds:Int) {
                        var previous=withContext(Dispatchers.Main) {controller.currentPosition}
                        val startPosition=previous
                        repeat(seconds*4) {
                            delay(250)
                            val now=withContext(Dispatchers.Main) {
                                controller.playerError?.let {error(it.errorCodeName)}
                                check(controller.playbackState==Player.STATE_READY && controller.isPlaying) {
                                    "Audio interrupted state=${controller.playbackState} playing=${controller.isPlaying} suppression=${controller.playbackSuppressionReason} position=${controller.currentPosition} buffered=${controller.bufferedPosition}"
                                }
                                check(controller.playlistMetadata.extras?.getBoolean("providerRetrying")!=true) {"Stream recovery interrupted audio"}
                                controller.currentPosition
                            }
                            // MediaController extrapolates position between IPC updates; the first
                            // renderer timestamp can correct that estimate by a few milliseconds.
                            check(now+1000>=previous) {"Audio restarted: $previous -> $now"}
                            previous=now
                        }
                        check(previous>=startPosition+seconds*1000-1500) {"Audio did not advance continuously"}
                    }
                    for(track in tracks) {
                        provider.invalidateStream(track.videoId)
                        val started=android.os.SystemClock.elapsedRealtime()
                        val stream=withTimeout(60_000) {provider.resolve(track.videoId).getOrThrow()}
                        check(stream.sabrBootstrap==null) {"Ordinary audio selected SABR"}
                        check(stream.mimeType?.contains("mpegurl",true)!=true) {"Ordinary audio selected adaptive HLS"}
                        withContext(Dispatchers.Main) {gateway.playProvider(track,stream)}
                        awaitPlaying("yt:${track.videoId}")
                        val startup=android.os.SystemClock.elapsedRealtime()-started
                        continuous(40)
                        val beforeSeek=withContext(Dispatchers.Main) {controller.currentPosition}
                        withContext(Dispatchers.Main) {gateway.seekTo(60_000)}
                        awaitPlaying();continuous(10)
                        check(withContext(Dispatchers.Main) {controller.currentPosition>=69_000}) {"Seek lost its position"}
                        measurements.put(JSONObject().put("title",track.title).put("profile",stream.profileId)
                            .put("mime",stream.mimeType).put("itag",stream.itag).put("bitrate",stream.bitrate)
                            .put("startup_ms",startup).put("continuous_ms",beforeSeek).put("seek_playing",true))
                        sendStatus(1,Bundle().apply {putString("tracks",measurements.toString())})
                    }
                    withContext(Dispatchers.Main) {activity.moveTaskToBack(true)}
                    continuous(15)
                    result.putString("tracks",measurements.toString());result.putBoolean("background_playing",true)
                } finally {
                    player?.let {controller->
                        if(saved!=null) {
                            val file=java.io.File(targetContext.cacheDir,"queue-request-${java.util.UUID.randomUUID()}.json")
                                .apply {writeText(encodePlaybackSnapshot(saved))}
                            val command=withContext(Dispatchers.Main) {
                                controller.sendCustomCommand(SessionCommand(PLAY_CATALOG_QUEUE,Bundle.EMPTY),Bundle().apply {
                                    putString("queueFile",file.name);putBoolean("autoplay",false)
                                })
                            }
                            check(command.get(10,java.util.concurrent.TimeUnit.SECONDS).resultCode==SessionResult.RESULT_SUCCESS)
                            withContext(Dispatchers.Main) {controller.seekTo(saved.index,saved.positionMs);controller.repeatMode=saved.repeat;controller.pause()}
                        } else withContext(Dispatchers.Main) {controller.pause()}
                    }
                    withContext(Dispatchers.Main) {player?.release();gateway.close()}
                }
            }
            finish(Activity.RESULT_OK,result)
        } catch(e:Exception) {
            result.putString("error",e.javaClass.simpleName+":"+e.message?.takeUnless {it.contains("https")})
            finish(Activity.RESULT_CANCELED,result)
        }
    }
}
