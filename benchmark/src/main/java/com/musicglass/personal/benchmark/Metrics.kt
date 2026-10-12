@file:OptIn(ExperimentalMetricApi::class)

package com.musicglass.personal.benchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.traceprocessor.TraceProcessor

/**
 * Every completed "riff:<section>" in the trace, in milliseconds. The app opens these itself (PerfTrace in
 * :app), on a thread or across threads, so the name alone identifies them. A measured block may hold several;
 * all of them are kept, and the report's percentiles are over every one.
 */
internal class SectionMetric(private val section: String) : TraceMetric() {
    override fun getMeasurements(captureInfo: CaptureInfo, traceSession: TraceProcessor.Session): List<Measurement> {
        val durations = traceSession.query("SELECT dur FROM slice WHERE name = 'riff:$section' AND dur > 0 ORDER BY ts")
            .map { it.long("dur") / 1_000_000.0 }.toList()
        return if (durations.isEmpty()) emptyList() else listOf(Measurement("${section}Ms", durations))
    }
}

/**
 * Frames the app finished after their deadline, as the platform's frame timeline recorded them, and how many
 * frames there were. This is the budget's "missed-deadline frames"; FrameTimingMetric's percentiles of
 * overrun cannot give a percentage of frames.
 */
internal class MissedDeadlineMetric : TraceMetric() {
    override fun getMeasurements(captureInfo: CaptureInfo, traceSession: TraceProcessor.Session): List<Measurement> {
        val row = traceSession.query("""
            SELECT COUNT(*) AS frames, COALESCE(SUM(CASE WHEN a.on_time_finish = 0 THEN 1 ELSE 0 END), 0) AS missed
            FROM actual_frame_timeline_slice a JOIN process USING (upid)
            WHERE process.name = '${captureInfo.targetPackageName}' AND a.dur > 0
        """.trimIndent()).firstOrNull() ?: return emptyList()
        val frames = row.long("frames")
        if (frames == 0L) return emptyList()
        return listOf(Measurement("frameCount", frames.toDouble()),
            Measurement("missedDeadlinePercent", 100.0 * row.long("missed") / frames))
    }
}
