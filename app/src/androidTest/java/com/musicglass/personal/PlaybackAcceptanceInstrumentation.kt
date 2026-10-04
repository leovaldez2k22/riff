package com.musicglass.personal

import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Uses the real saved listening queue and restores it paused; no test audio or account writes. */
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class PlaybackAcceptanceInstrumentation {
    @Test fun twelveSongsPlayInOneSessionWithoutRestartingTheApp() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val prefs=context.getSharedPreferences("last_playback",android.content.Context.MODE_PRIVATE)
        val backup=File(context.filesDir,"rc20-acceptance-original-queue.json")
        if(!backup.exists()) backup.writeText(prefs.getString("snapshot",null) ?: error("A real saved queue is required"))
        val saved=decodePlaybackSnapshot(backup.readText())
            ?: error("Invalid saved queue")
        val tracks=saved.queue.filter {it.mediaId.startsWith("yt:") && !it.live && it.music}
            .distinctBy {it.mediaId}.take(12).map {
                ProviderTrack(it.mediaId.removePrefix("yt:"),it.title,it.artist,it.artworkUrl,it.artistId,it.albumId,it.album)
            }.toMutableList()
        val provider=YouTubeMusicProvider.shared(context)
        for (query in listOf("Numb Linkin Park", "Yellow Coldplay", "Blinding Lights The Weeknd", "Imagine John Lennon", "Bad Guy Billie Eilish", "Bohemian Rhapsody Queen")) {
            if(tracks.size>=12) break
            val track=provider.search(query).getOrThrow().firstOrNull {it.isMusic && !it.isLive && tracks.none {savedTrack->savedTrack.videoId==it.videoId}}
            if(track!=null) tracks+=track
        }
        assertEquals("Need twelve real songs for acceptance",12,tracks.size)
        val gateway=withContext(Dispatchers.Main) {MusicPlaybackGateway(context)}
        var player:MediaController?=null
        var volume=1f
        val dataPrefs=context.getSharedPreferences(DATA_SAVER_PREFS,android.content.Context.MODE_PRIVATE)
        val saver=dataPrefs.getBoolean("enabled",false)
        try {
            withTimeout(15000) {while(!withContext(Dispatchers.Main) {gateway.state.ready}) delay(100)}
            player=withContext(Dispatchers.Main) {MusicPlaybackGateway::class.java.getDeclaredField("controller").apply {isAccessible=true}.get(gateway) as MediaController}
            volume=withContext(Dispatchers.Main) {player!!.volume}
            withContext(Dispatchers.Main) {player!!.volume=0f;gateway.playCollection(tracks,0,"Playback acceptance")}
            for ((index,track) in tracks.withIndex()) {
                // The second half also exercises fresh stream resolution with Data saver enabled.
                if(index==6) dataPrefs.edit().putBoolean("enabled",true).commit()
                if(index>0) withContext(Dispatchers.Main) {gateway.next()}
                withTimeout(60_000) {
                    while(!withContext(Dispatchers.Main) {gateway.state.mediaId=="yt:${track.videoId}" && gateway.state.playing}) {
                        val state=withContext(Dispatchers.Main) {gateway.state}
                        check(state.error==null) {"Track ${index+1}: ${state.error}"}
                        delay(200)
                    }
                }
                val start=withContext(Dispatchers.Main) {player!!.currentPosition}
                delay(10_000)
                withContext(Dispatchers.Main) {
                    assertNull("Playback failed during song ${index+1}",gateway.state.error)
                    assertTrue("Song ${index+1} stopped",gateway.state.playing)
                    assertTrue("Song ${index+1} did not advance",player!!.currentPosition>start+5000)
                }
                instrumentation.sendStatus(1,Bundle().apply {putString("riff_progress","Song ${index+1}/12 played: ${track.title}")})
            }
        } finally {
            dataPrefs.edit().putBoolean("enabled",saver).commit()
            player?.let { controller ->
                val file=File(context.cacheDir,"queue-request-${java.util.UUID.randomUUID()}.json").apply {writeText(encodePlaybackSnapshot(saved))}
                try {
                    val command=withContext(Dispatchers.Main) {controller.sendCustomCommand(SessionCommand(PLAY_CATALOG_QUEUE,Bundle.EMPTY),Bundle().apply {putString("queueFile",file.name);putBoolean("autoplay",false)})}
                    assertEquals(SessionResult.RESULT_SUCCESS,command.get(10,java.util.concurrent.TimeUnit.SECONDS).resultCode)
                    withContext(Dispatchers.Main) {controller.seekTo(saved.index,saved.positionMs);controller.shuffleModeEnabled=saved.shuffle;controller.repeatMode=saved.repeat;controller.volume=volume;controller.pause()}
                    delay(700)
                    prefs.edit().putString("snapshot",encodePlaybackSnapshot(saved)).commit()
                    backup.delete()
                } finally {file.delete()}
            }
            withContext(Dispatchers.Main) {gateway.close()}
        }
    }
}
