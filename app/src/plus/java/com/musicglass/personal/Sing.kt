package com.musicglass.personal

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/*
 * Feature 20 (Riff+): Sing. A song on the phone (saved, or a file of the user's) is separated once with Deezer's
 * Spleeter 2-stems model (MIT, bundled: Riff+ has no size limit), and its instrumental kept as 16-bit PCM at the song's
 * own rate and channels. While Sing is on, SingProcessor puts the instrumental in place of the song's samples, frame for
 * frame, so lyrics, seeking, speed and pitch all stay as they are; turning it off or on glides over 30 ms.
 */

internal const val SING_MODEL_ASSET = "sing/spleeter-2stems.onnx"
/**
 * The instrumentals kept: no size cap (owner, 9 November 2026); the least recently used go first only when the phone
 * would have less than [CACHE_KEEP_FREE_BYTES] free.
 */
internal const val SING_CACHE_BYTES = Long.MAX_VALUE
private const val SING_PREFS = "sing"
private const val SING_HEADER = 32
private const val SING_MAGIC = 0x52534E47 // "RSNG"
private const val SING_VERSION = 1

/** Why a preparation stopped short. */
internal enum class SingFailure { NotOnPhone, Unsupported, Hot, Failed }

/** The Spleeter model on ONNX Runtime, open for one preparation; memory-mapped from the uncompressed asset. */
internal class SingModel private constructor(context: Context) : SpectrogramMask, AutoCloseable {
    private val env = OrtEnvironment.getEnvironment().also { it.setTelemetry(false) }
    private val session: OrtSession = context.assets.openFd(SING_MODEL_ASSET).use { fd ->
        // Mapped from the uncompressed asset: ONNX Runtime reads the model from a direct buffer, without a 79 MB copy on the heap.
        fd.createInputStream().use { stream ->
            env.createSession(stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength), OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            })
        }
    }
    private val shape = longArrayOf(1, SING_MODEL_FRAMES.toLong(), SING_MODEL_BINS.toLong(), 2)
    var check: () -> Unit = {}

    override fun mask(magnitudes: FloatArray): FloatArray {
        check()
        val started = android.os.SystemClock.elapsedRealtime()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(magnitudes), shape).use { input ->
            session.run(mapOf(session.inputNames.first() to input)).use { out ->
                val buffer = (out[0] as OnnxTensor).floatBuffer
                val mask = FloatArray(buffer.remaining()).also { buffer.get(it) }
                Log.i("RiffSing", "chunk in ${android.os.SystemClock.elapsedRealtime() - started} ms")
                return mask
            }
        }
    }

    override fun close() = session.close()

    companion object {
        /** Whether this build carries the model and its runtime, without loading either. */
        fun present(context: Context): Boolean =
            runCatching { Class.forName("ai.onnxruntime.OrtEnvironment") }.isSuccess &&
                runCatching { context.assets.openFd(SING_MODEL_ASSET).close() }.isSuccess

        fun open(context: Context) = SingModel(context)
    }
}

/** A kept instrumental, mapped: interleaved 16-bit samples at [rate] with [channels], [frames] long. */
internal class SingTrack(val mediaId: String, val rate: Int, val channels: Int, val frames: Long, val samples: ShortBuffer)

/** The instrumentals on the phone, in noBackupFilesDir/sing, one file each, the oldest used removed past [SING_CACHE_BYTES]. */
internal class SingStore(private val dir: File, private val limit: Long = SING_CACHE_BYTES,
    private val keepFree: Long = CACHE_KEEP_FREE_BYTES, private val free: () -> Long = { dir.usableSpace }) {
    /** Bumped whenever an instrumental is added or removed, so the player and the screens look again. */
    val generation = MutableStateFlow(0)

    fun file(mediaId: String) = File(dir, MessageDigest.getInstance("SHA-256").digest(mediaId.toByteArray()).joinToString("") { "%02x".format(it) }.take(32) + ".pcm")

    fun has(mediaId: String) = file(mediaId).isFile

    /** The instrumental for [mediaId], mapped, or null when there is none or it is unreadable (then it is removed). */
    fun open(mediaId: String): SingTrack? {
        val file = file(mediaId).takeIf { it.isFile } ?: return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteBuffer.allocate(SING_HEADER).order(ByteOrder.LITTLE_ENDIAN)
                raf.channel.read(header, 0); header.flip()
                require(header.int == SING_MAGIC && header.int == SING_VERSION)
                val rate = header.int; val channels = header.int; val frames = header.long
                require(rate in 8_000..384_000 && channels in 1..2 && frames > 0 && SING_HEADER + frames * channels * 2 <= raf.length())
                val data = raf.channel.map(FileChannel.MapMode.READ_ONLY, SING_HEADER.toLong(), frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
                file.setLastModified(System.currentTimeMillis())
                SingTrack(mediaId, rate, channels, frames, data.asShortBuffer())
            }
        } catch (e: Exception) {
            file.delete(); null
        }
    }

    /** Writes an instrumental through [write] into a part file, then puts it in place and trims the store. */
    fun put(mediaId: String, rate: Int, channels: Int, write: (BufferedOutputStream) -> Long): Boolean {
        dir.mkdirs()
        val part = File(dir, file(mediaId).name + ".part")
        try {
            val frames = BufferedOutputStream(FileOutputStream(part), 1 shl 16).use { out ->
                out.write(ByteArray(SING_HEADER))
                write(out)
            }
            if (frames <= 0) { part.delete(); return false }
            RandomAccessFile(part, "rw").use { raf ->
                raf.write(ByteBuffer.allocate(SING_HEADER).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(SING_MAGIC).putInt(SING_VERSION).putInt(rate).putInt(channels).putLong(frames).array())
                raf.fd.sync()
            }
            if (!part.renameTo(file(mediaId))) { part.delete(); return false }
            trim(keep = file(mediaId))
            generation.value++
            return true
        } catch (e: Exception) {
            part.delete(); throw e
        }
    }

    fun remove(mediaId: String) { if (file(mediaId).delete()) generation.value++ }
    /** Settings' Clear: every instrumental goes; songs prepare again when Sing is next used. */
    fun clear() { dir.listFiles()?.forEach { it.delete() }; generation.value++ }

    /** Bytes kept now. */
    fun size(): Long = dir.listFiles()?.filter { it.name.endsWith(".pcm") }?.sumOf { it.length() } ?: 0

    private fun trim(keep: File) {
        val files = dir.listFiles()?.filter { it.name.endsWith(".pcm") || it.name.endsWith(".part") }.orEmpty().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= limit && free() >= keepFree) break
            if (f == keep) continue
            total -= f.length(); f.delete()
        }
    }

    companion object {
        @Volatile private var instance: SingStore? = null
        fun get(context: Context): SingStore = instance ?: synchronized(this) {
            instance ?: SingStore(File(context.applicationContext.noBackupFilesDir, "sing")).also { instance = it }
        }
    }
}

/** Whether Sing is on: kept across songs and restarts, read by the player on its own thread. */
internal object SingSwitch {
    @Volatile var on = false
        private set

    fun load(context: Context) { on = context.getSharedPreferences(SING_PREFS, Context.MODE_PRIVATE).getBoolean("on", false) }
    fun set(context: Context, value: Boolean) {
        on = value
        context.getSharedPreferences(SING_PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", value).apply()
    }

    @androidx.annotation.VisibleForTesting internal fun setForTest(value: Boolean) { on = value }
}

/** Whether the phone is too hot to go on separating: only at SEVERE, since the user asked for this song now. */
internal fun singTooHot(context: Context): Boolean =
    (context.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: 0) >= PowerManager.THERMAL_STATUS_SEVERE

private class SingStopped : Exception()

/**
 * Separates [mediaId]'s vocals from the rest and keeps its instrumental. [progress] gets 0 to 1 by the song's
 * [durationMs] (when known). Null when it is kept, else why not.
 */
internal suspend fun prepareSing(context: Context, mediaId: String, durationMs: Long, progress: (Float) -> Unit): SingFailure? {
    // A streamed song is read from the music cache once the player has loaded all of it (owner, 9 November 2026).
    val streamed = if (djSource(context, mediaId) == null) wholeStreamedSong(context, mediaId) ?: return SingFailure.NotOnPhone else null
    val source = streamed?.let(Uri::fromFile) ?: djSource(context, mediaId) ?: return SingFailure.NotOnPhone
    val job = coroutineContext
    val started = android.os.SystemClock.elapsedRealtime()
    // The decoder turns any exception but cancellation into "not decoded", so a stop records its reason first.
    var failure: SingFailure? = null
    fun stop(why: SingFailure): Nothing { failure = why; throw SingStopped() }
    var rate = 0; var channels = 0; var frames = 0L
    var chain: SingChain? = null
    var out: BufferedOutputStream? = null
    val tmp = File(context.cacheDir, "sing-${System.nanoTime()}.raw")
    val ok = try {
        SingModel.open(context).use { model ->
            model.check = { job.ensureActive(); if (singTooHot(context)) stop(SingFailure.Hot) }
            val decoded = TrackAnalysis.window(context, source, 0.0, 24L * 3_600_000) { r, c, _ ->
                rate = r; channels = c
                if (c > 2) stop(SingFailure.Unsupported)
                val stream = BufferedOutputStream(FileOutputStream(tmp), 1 shl 16).also { out = it }
                val built = SingChain(model, r, c) { pcm, n -> writePcm16(stream, pcm, n); frames += n / c }.also { chain = it }
                val total = if (durationMs > 0) durationMs * r / 1000 else 0L
                var fed = 0L
                PcmSink { samples, count ->
                    built.feed(samples, count)
                    fed += count / c
                    if (total > 0) progress((fed.toFloat() / total).coerceIn(0f, .99f))
                }
            }
            if (decoded == null || failure != null) return@use false
            chain?.finish()
            out?.close(); out = null
            frames > 0 && SingStore.get(context).put(mediaId, rate, channels) { o -> tmp.inputStream().use { it.copyTo(o, 1 shl 16) }; frames }
        }
    } catch (e: SingStopped) {
        false
    } finally {
        runCatching { out?.close() }
        tmp.delete()
        streamed?.delete()
    }
    job.ensureActive()
    Log.i("RiffSing", "${if (ok) "kept" else "not kept ($failure)"} in ${android.os.SystemClock.elapsedRealtime() - started} ms")
    if (ok) progress(1f)
    return if (ok) null else failure ?: SingFailure.Failed
}

/** Whether [mediaId] is a streamed song the music cache holds whole, so Sing can prepare it. */
internal fun streamedWhole(context: Context, mediaId: String): Boolean =
    mediaId.startsWith("yt:") && MusicDiskCache.playable(context, OfflineMusicStore.get(context).currentScope, mediaId.removePrefix("yt:"),
        AppContainer.get(context).provider.streamQualityName())?.second == CachedPart.Whole

/** A streamed song copied whole out of the music cache for one preparation, or null when it is not all there yet. */
private fun wholeStreamedSong(context: Context, mediaId: String): File? {
    if (!mediaId.startsWith("yt:")) return null
    val into = File(context.cacheDir, "sing-source-${System.nanoTime()}")
    val ok = MusicDiskCache.copyWhole(context, OfflineMusicStore.get(context).currentScope, mediaId.removePrefix("yt:"),
        AppContainer.get(context).provider.streamQualityName(), into)
    return if (ok) into else { into.delete(); null }
}

private fun writePcm16(out: BufferedOutputStream, pcm: FloatArray, n: Int) {
    val bytes = ByteArray(n * 2)
    for (i in 0 until n) {
        val s = (pcm[i] * 32768f).roundToInt().coerceIn(-32768, 32767)
        bytes[2 * i] = s.toByte(); bytes[2 * i + 1] = (s shr 8).toByte()
    }
    out.write(bytes)
}

/**
 * The song's own rate and channels → 44.1 kHz stereo → Spleeter → back to its rate and channels: what goes in at the
 * song's rate comes out at it, as many frames (give or take the resampler's rounding), in [out] as interleaved floats.
 */
internal class SingChain(model: SpectrogramMask, rate: Int, private val channels: Int, out: (FloatArray, Int) -> Unit) {
    private val back = Resampler(SING_RATE, rate, channels, out)
    private val separator = SingSeparator(model) { stereo, n ->
        if (channels == 2) back.feed(stereo, n)
        else { val mono = FloatArray(n / 2) { (stereo[2 * it] + stereo[2 * it + 1]) / 2 }; back.feed(mono, mono.size) }
    }
    private val to44 = Resampler(rate, SING_RATE, 2) { stereo, n -> separator.feed(stereo, n) }

    init { require(channels in 1..2) }

    fun feed(samples: ShortArray, count: Int) {
        val frames = count / channels
        val stereo = FloatArray(frames * 2)
        for (f in 0 until frames) {
            val l = samples[f * channels] / 32768f
            stereo[2 * f] = l; stereo[2 * f + 1] = if (channels == 2) samples[f * 2 + 1] / 32768f else l
        }
        to44.feed(stereo, stereo.size)
    }

    fun finish() { to44.finish(); separator.finish(); back.finish() }
}

/** Where a preparation stands, for the panel: running (with progress, null while unknown), or how it ended. */
internal data class SingJob(val mediaId: String, val progress: Float?, val failure: SingFailure? = null, val running: Boolean = true)

/** The one preparation at a time; it carries on if the panel closes. */
internal object SingWork {
    val state = MutableStateFlow<SingJob?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun start(context: Context, mediaId: String, durationMs: Long) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        state.value = SingJob(mediaId, if (durationMs > 0) 0f else null)
        job = scope.launch {
            val thread = Process.myTid()
            val priority = Process.getThreadPriority(thread)
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            try {
                val failure = suspendCatching { prepareSing(app, mediaId, durationMs) { p -> if (durationMs > 0) state.value = SingJob(mediaId, p) } }
                    .getOrElse { Log.w("RiffSing", "failed", it); SingFailure.Failed }
                state.value = SingJob(mediaId, 1f, failure, running = false)
            } finally {
                Process.setThreadPriority(priority)
            }
        }.also { it.invokeOnCompletion { cause -> if (cause is kotlinx.coroutines.CancellationException) state.value = null } }
    }

    fun cancel() { job?.cancel(); job = null; state.value = null }
}

/**
 * First in Riff+'s chain: while Sing is on and the song playing has a kept instrumental, its samples in place of the
 * song's. It learns which song and where from each flush (Media3 hands every processor the timeline, the period and
 * the position of the first sample after it) and counts frames from there. Past the instrumental's end, for another
 * format, or with Sing off, the song passes through untouched.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class SingProcessor(private val store: () -> SingStore?) : BaseAudioProcessor() {
    private var mediaId: String? = null
    private var frame = 0L
    private var track: SingTrack? = null
    private var seenGeneration = -1
    /** 0 = the song, 1 = its instrumental; glides toward Sing's switch. */
    private var mix = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        mediaId = runCatching {
            val timeline = streamMetadata.timeline; val uid = streamMetadata.periodUid
            if (uid == null || timeline.isEmpty) null
            else timeline.getWindow(timeline.getPeriodByUid(uid, Timeline.Period()).windowIndex, Timeline.Window()).mediaItem.mediaId
        }.getOrNull()
        val rate = inputAudioFormat.sampleRate
        frame = if (rate > 0) (streamMetadata.positionOffsetUs.coerceAtLeast(0) * rate + 500_000) / 1_000_000 else 0
        if (track?.mediaId != mediaId) { track = null; seenGeneration = -1 }
        mix = if (SingSwitch.on && usable() != null) 1f else 0f
    }

    override fun onReset() { mediaId = null; track = null; seenGeneration = -1; frame = 0; mix = 0f }

    private fun usable(): SingTrack? {
        val id = mediaId ?: return null
        val s = store() ?: return null
        val generation = s.generation.value
        if (generation != seenGeneration) { seenGeneration = generation; track = s.open(id) }
        return track?.takeIf { it.rate == inputAudioFormat.sampleRate && it.channels == inputAudioFormat.channelCount }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val channels = inputAudioFormat.channelCount
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val frames = bytes / (2 * channels)
        val out = replaceOutputBuffer(bytes)
        val want = if (SingSwitch.on) 1f else 0f
        val instrumental = if (want > 0f || mix > 0f) usable() else null
        if (instrumental == null || (want == 0f && mix == 0f)) {
            out.put(inputBuffer); out.flip(); frame += frames
            if (instrumental == null) mix = 0f
            return
        }
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN); out.order(ByteOrder.LITTLE_ENDIAN)
        val step = 1f / (.03f * inputAudioFormat.sampleRate)
        val samples = instrumental.samples
        for (f in 0 until frames) {
            mix = if (mix < want) minOf(want, mix + step) else maxOf(want, mix - step)
            val at = frame + f
            val inside = at < instrumental.frames
            for (c in 0 until channels) {
                val original = inputBuffer.short
                val value = if (inside) {
                    val replaced = samples.get((at * channels + c).toInt())
                    (original + (replaced - original) * mix).roundToInt().coerceIn(-32768, 32767).toShort()
                } else original
                out.putShort(value)
            }
        }
        frame += frames
        out.flip()
    }
}
