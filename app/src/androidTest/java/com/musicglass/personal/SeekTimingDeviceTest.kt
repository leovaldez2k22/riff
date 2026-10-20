package com.musicglass.personal

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Q18-057 (Q18-005 again): a downloaded song, stored as a download is, played by a player built as the service builds
 * it (its renderers, load control and the offline source), sought ahead and back the way the owner did. Android's own
 * WebM writer, which stored every download until now, left a seek point every 30 s: seeks took up to 3.3 s here.
 */
class SeekTimingDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var player: ExoPlayer? = null
    private val source by lazy { File(context.cacheDir, "seek-source.webm").apply {
        writeBytes(instrumentation.context.assets.open("quality/seek/seek-source.webm").use { it.readBytes() }) } }

    @After fun release() { instrumentation.runOnMainSync { player?.release() }; source.delete() }

    private fun <T> main(block: () -> T): T { var out: T? = null; instrumentation.runOnMainSync { out = block() }; @Suppress("UNCHECKED_CAST") return out as T }

    /** Milliseconds from the seek until the player plays from [targetMs] with its position moving. */
    private fun seek(targetMs: Long): Long {
        val start = SystemClock.elapsedRealtime()
        main { player!!.seekTo(targetMs) }
        while (SystemClock.elapsedRealtime() - start < 10_000) {
            val (state, playing, position) = main { Triple(player!!.playbackState, player!!.isPlaying, player!!.currentPosition) }
            if (state == Player.STATE_READY && playing && position >= targetMs + 60) return SystemClock.elapsedRealtime() - start
            Thread.sleep(5)
        }
        return 10_000
    }

    /** The owner's pattern: ahead past what is buffered, back into what was played, far back, ahead again. */
    private fun seeks(file: File, name: String): List<Long> {
        main {
            player?.release()
            player = ExoPlayer.Builder(context, riffAudioRenderers(context, VolumeBoostProcessor())).setLoadControl(riffLoadControl())
                .experimentalSetDynamicSchedulingEnabled(false).build().apply {
                    setMediaSource(offlineMediaSource(context, MediaItem.fromUri(Uri.fromFile(file)))); prepare(); play()
                }
        }
        assertTrue("never started", seek(1_000) < 10_000)
        Thread.sleep(3_000)
        return listOf(165_000L, 38_000L, 196_000L, 29_000L, 100_000L, 4_000L, 150_000L).map { seek(it).also { Thread.sleep(1_200) } }
            .also { Log.i("RiffQ18", "q18-057 $name seeks ms=$it") }
    }

    /**
     * Quick enough: none over a second and half of them under half a second. The old file took up to 3.3 s; on the
     * software-drawn emulator, under the whole lane's load, one seek in a good file took 520 ms.
     */
    private fun assertQuick(times: List<Long>) =
        assertTrue("seeks took $times ms", times.all { it < 1_000 } && times.sorted()[times.size / 2] < 500)

    /** How every download was stored before Q18-057: Android's WebM writer. */
    private fun oldStore(input: File, output: File) {
        val extractor = MediaExtractor(); extractor.setDataSource(input.absolutePath); extractor.selectTrack(0)
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM)
        val track = muxer.addTrack(extractor.getTrackFormat(0)); muxer.start()
        val buffer = ByteBuffer.allocate(1 shl 20); val info = MediaCodec.BufferInfo()
        while (true) {
            val size = extractor.readSampleData(buffer, 0); if (size < 0) break
            info.set(0, size, extractor.sampleTime, MediaCodec.BUFFER_FLAG_KEY_FRAME); muxer.writeSampleData(track, buffer, info); extractor.advance()
        }
        muxer.stop(); muxer.release(); extractor.release()
    }

    @Test fun aDownloadedSongPlaysAgainAtOnceAfterEverySeek() {
        val stored = File(context.cacheDir, "seek-download.webm")
        try {
            runBlocking { AndroidAudioAssembler.assemble(source, stored, 200_000) {} }
            val index = requireNotNull(WebmSeekIndex.read(stored))
            assertTrue("seek points ${index.timesUs.size}", (index.timesUs.toList() + index.durationUs).zipWithNext().all { (a, b) -> b - a <= 600_000 })
            val times = seeks(stored, "stored")
            assertQuick(times)
        } finally { stored.delete() }
    }

    @Test fun aDownloadStoredTheOldWayIsStoredAgainOnceAndSeeksAsFast() {
        val store = OfflineMusicStore.get(context)
        val scope = store.currentScope
        val id = "q18seek0057"
        val dir = store.directory(scope, id)
        try {
            store.remember(ProviderTrack(id, "Seek fixture", "Fixture Artist", artworkUrl = null), scope)
            dir.deleteRecursively(); check(dir.mkdirs())
            val audio = File(dir, BundleValidator.AUDIO).also { oldStore(source, it) }
            BundleLoudness.write(dir, -9.5)
            File(dir, BundleValidator.READY).writeText(audio.length().toString())
            assertNull(BundleValidator.problem(dir))
            assertTrue("the old writer's file is coarse", DownloadRepack.coarse(audio))
            seeks(audio, "old") // for the record: up to 2.8 s on the API 36 emulator

            // Not while the player's queue holds it; then once, and only once.
            runBlocking { DownloadRepack.repackAll(store, scope, inQueue = { it == id }, events = PlaybackEventLog(context)) }
            assertTrue(DownloadRepack.coarse(audio))
            assertTrue(runBlocking { DownloadRepack.repack(store, scope, id) })
            assertFalse(runBlocking { DownloadRepack.repack(store, scope, id) })
            val again = requireNotNull(store.audio(scope, id))
            assertNull(BundleValidator.problem(dir))
            assertFalse(DownloadRepack.coarse(again))
            assertEquals(-9.5, BundleLoudness.read(dir)!!, 0.0)
            assertEquals(WebmSeekIndex.read(source)!!.durationUs / 100_000, WebmSeekIndex.read(again)!!.durationUs / 100_000)
            assertFalse("nothing left behind", dir.listFiles()!!.any { it.name.endsWith(".part") })
            val after = seeks(again, "repacked")
            assertQuick(after)
        } finally { store.remove(scope, id, exclude = false); dir.deleteRecursively() }
    }
}
