package com.musicglass.personal

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies that lazy stream resolution reaches the metadata consumed by the actual player. */
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class PodcastMetadataDeviceTest {
    @Test fun resolvedPodcastChannelReplacesViewCountsInThePlayer() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val wav=File(context.cacheDir,"podcast-metadata-fixture.wav")
        val bytes=8000*2*2
        val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36+bytes).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1.toShort()).putShort(1.toShort()).putInt(8000).putInt(16000)
            .putShort(2.toShort()).putShort(16.toShort()).put("data".toByteArray()).putInt(bytes)
        wav.outputStream().use {it.write(header.array());it.write(ByteArray(bytes))}
        var player:ExoPlayer?=null
        try {
            withContext(Dispatchers.Main) {
                val listed=ProviderTrack("video000001","Episode","230K views",isMusic=false)
                val stream=ProviderStream(wav.toURI().toString(),emptyMap(),"audio/wav",resolvedAuthor="Podcast Channel",resolvedChannelId="UC_podcast")
                val resolved=resolvedPlaybackTrack(listed,stream)
                val item=MediaItem.Builder().setMediaId("yt:video000001").setUri("catalog://video000001")
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(listed.title).setArtist(listed.artist).build()).build()
                val source=DeferredProviderSource(item) {
                    DefaultMediaSourceFactory(context).createMediaSource(item.buildUpon().setUri(wav.toURI().toString())
                        .setMediaMetadata(item.mediaMetadata.buildUpon().setArtist(resolved.artist).build()).build())
                }
                player=ExoPlayer.Builder(context).build().also {it.volume=0f;it.setMediaSource(source);it.prepare()}
            }
            withTimeout(10_000) {while(!withContext(Dispatchers.Main) {player!!.playbackState==Player.STATE_READY}) delay(25)}
            withContext(Dispatchers.Main) {
                assertEquals("Podcast Channel",player!!.mediaMetadata.artist.toString())
                assertEquals("Podcast Channel",player!!.currentMediaItem!!.mediaMetadata.artist.toString())
            }
        } finally {
            withContext(Dispatchers.Main) {player?.release()}
            wav.delete()
        }
    }
}
