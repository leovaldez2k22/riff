package com.musicglass.personal

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Q18-056, a probe for the owner's phone, not a lane test: it runs only with `-e probe q18-056 -e pkg <package>`.
 * The app named by pkg plays riff-click-test.wav (a 4 ms click every 2 s) out loud; this records the microphone,
 * finds each click and compares the time it was heard with the position that app's session reported for that moment.
 * A positive lag means the position runs ahead of what is heard (lyrics early); negative, behind it (lyrics late).
 * The microphone's own delay counts against the result, so the number is a ceiling for "ahead" and a floor for "behind".
 */
class LyricsLatencyProbeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).let { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().decodeToString() }
        }

    /** The session's position, the elapsed-realtime ms it was taken at, and its speed. */
    private fun session(pkg: String): Triple<Long, Long, Float>? {
        val dump = shell("dumpsys media_session")
        val block = dump.substringAfter("package=$pkg", "").takeIf { it.isNotEmpty() } ?: return null
        val state = Regex("state=PlaybackState \\{state=[A-Z_]+\\((\\d+)\\), position=(\\d+),.*?speed=([0-9.]+), updated=(\\d+)")
            .find(block) ?: return null
        if (state.groupValues[1] != "3") return null
        return Triple(state.groupValues[2].toLong(), state.groupValues[4].toLong(), state.groupValues[3].toFloat())
    }

    private fun report(line: String) {
        Log.i("RiffQ18", line)
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString(android.app.Instrumentation.REPORT_KEY_STREAMRESULT, "\n$line\n") })
    }

    @Test fun clickLag() {
        assumeTrue("probe argument ${args.getString("probe")}", args.getString("probe") == "q18-056")
        val pkg = requireNotNull(args.getString("pkg"))
        val seconds = args.getString("seconds")?.toIntOrNull() ?: 20
        // Android silences the microphone for an app in the background: bring this one to the front first.
        val context = instrumentation.targetContext
        context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(3_000)
        val rate = 48_000
        val size = maxOf(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), rate)
        @Suppress("MissingPermission")
        val record = AudioRecord(MediaRecorder.AudioSource.UNPROCESSED, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size * 4)
        check(record.state == AudioRecord.STATE_INITIALIZED) { "no microphone" }
        val samples = ShortArray(rate * seconds)
        val stamps = ArrayList<Pair<Long, Long>>() // frame, elapsed-realtime ns
        val sessions = ArrayList<Triple<Long, Long, Float>>()
        record.startRecording()
        var read = 0
        val stamp = AudioTimestamp()
        var nextSession = 0L
        while (read < samples.size) {
            val n = record.read(samples, read, minOf(4800, samples.size - read))
            if (n <= 0) break
            read += n
            if (record.getTimestamp(stamp, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) stamps += stamp.framePosition to stamp.nanoTime
            if (SystemClock.elapsedRealtime() >= nextSession) { session(pkg)?.let { sessions += it }; nextSession = SystemClock.elapsedRealtime() + 2_000 }
        }
        record.stop(); record.release()
        check(stamps.isNotEmpty()) { "no capture timestamps" }
        check(sessions.isNotEmpty()) { "$pkg is not playing" }
        val (frame0, ns0) = stamps.last()
        fun heardAt(frame: Int) = ns0 / 1_000_000.0 + (frame - frame0) * 1000.0 / rate

        // Clicks: the first sample over 8 times the noise floor after at least 0.5 s below it.
        val sorted = samples.map { abs(it.toInt()) }.sorted()
        val floor = maxOf(sorted[sorted.size / 2], 20)
        val loudest = sorted.last()
        val threshold = maxOf(floor * 8, loudest / 4)
        val onsets = ArrayList<Int>()
        var quietSince = 0
        for (i in samples.indices) {
            if (abs(samples[i].toInt()) >= threshold) {
                if (i - quietSince >= rate / 2) onsets += i
                quietSince = i
            }
        }
        val lags = onsets.map { onset ->
            val at = heardAt(onset)
            val (position, updated, speed) = sessions.minBy { abs(it.second - at) }
            val reported = position + (at - updated) * speed
            val click = Math.round(reported / 2000.0) * 2000.0
            reported - click
        }
        val sortedLags = lags.sorted()
        report("q18-056 pkg=$pkg floor=$floor loudest=$loudest clicks=${onsets.size} sessions=${sessions.size} " +
            "lagsMs=${lags.map { it.toInt() }} median=${sortedLags.getOrNull(sortedLags.size / 2)?.toInt()}")
        check(onsets.size >= seconds / 4) { "heard ${onsets.size} clicks (floor $floor, loudest $loudest)" }
    }
}
