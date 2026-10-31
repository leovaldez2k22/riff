package com.musicglass.personal

import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

/*
 * Feature 10: the equalizer's shape, apart from the audio. Ten filter sections: seven for the Manual tab and three
 * for the Dynamic tab's tone pad. Both tabs feed the same sections, so switching tabs glides. Filters are
 * trapezoidal state-variable filters (Zavalishin's TPT SVF, with Simper's bell and shelf mixes): their response is
 * the analogue prototype's, read at the pre-warped frequency, which is what [sectionGainDb] computes.
 */

internal enum class EqShape { LowShelf, Bell, HighShelf }

/** One filter section: [gainDb] at [frequency] Hz, [q] wide. A section at 0 dB is skipped by the processor. */
internal data class EqSection(val shape: EqShape, val frequency: Float, val q: Float, val gainDb: Float = 0f)

internal enum class EqMode(val key: String) { Dynamic("dynamic"), Manual("manual") }

internal object EqLayout {
    val manualFrequencies = floatArrayOf(60f, 150f, 400f, 1_000f, 2_500f, 6_000f, 14_000f)
    val manualLabels = listOf("60", "150", "400", "1k", "2.5k", "6k", "14k")
    const val MANUAL_Q = 1f
    const val MANUAL_RANGE_DB = 12f
    /** The tone pad: 11 × 11 steps, −5..5 on each axis, 1.2 dB a step. */
    const val TONE_STEPS = 5
    const val TONE_STEP_DB = 1.2f
    const val SECTIONS = 10
    /** Within this of 0 dB a manual fader snaps to flat. */
    const val DETENT_DB = .75f
}

/** Everything the equalizer needs to shape the sound; stored by [EqualizerSettings]. */
internal data class EqualizerConfig(
    val enabled: Boolean = false,
    val mode: EqMode = EqMode.Dynamic,
    val toneX: Int = 0, val toneY: Int = 0, val focused: Boolean = false,
    val manual: List<Float> = List(EqLayout.manualFrequencies.size) { 0f },
    val preset: String = "Flat",
    /** −1 (left) to 1 (right). */
    val balance: Float = 0f,
)

/** The ten sections for [config]: the tab not in use, and a switched-off equalizer, leave theirs flat. */
internal fun eqSections(config: EqualizerConfig): List<EqSection> {
    val manualOn = config.enabled && config.mode == EqMode.Manual
    val dynamicOn = config.enabled && config.mode == EqMode.Dynamic
    val manual = EqLayout.manualFrequencies.mapIndexed { i, f ->
        val shape = when (i) { 0 -> EqShape.LowShelf; EqLayout.manualFrequencies.lastIndex -> EqShape.HighShelf; else -> EqShape.Bell }
        EqSection(shape, f, EqLayout.MANUAL_Q, if (manualOn) config.manual.getOrElse(i) { 0f }.coerceIn(-EqLayout.MANUAL_RANGE_DB, EqLayout.MANUAL_RANGE_DB) else 0f)
    }
    val x = config.toneX.coerceIn(-EqLayout.TONE_STEPS, EqLayout.TONE_STEPS); val y = config.toneY.coerceIn(-EqLayout.TONE_STEPS, EqLayout.TONE_STEPS)
    val shelfQ = if (config.focused) .9f else .5f; val bellQ = if (config.focused) 2.2f else .7f
    val tone = listOf(
        EqSection(EqShape.LowShelf, 250f, shelfQ, if (dynamicOn) -x * EqLayout.TONE_STEP_DB else 0f),
        EqSection(EqShape.Bell, 1_000f, bellQ, if (dynamicOn) y * EqLayout.TONE_STEP_DB else 0f),
        EqSection(EqShape.HighShelf, 4_000f, shelfQ, if (dynamicOn) x * EqLayout.TONE_STEP_DB else 0f))
    return manual + tone
}

/** A section's gain in dB at [frequency], for a player running at [sampleRate]. */
internal fun sectionGainDb(section: EqSection, frequency: Float, sampleRate: Int = 48_000): Float {
    if (section.gainDb == 0f) return 0f
    val nyquist = sampleRate / 2f
    val fc = section.frequency.coerceAtMost(nyquist * .99f); val f = frequency.coerceAtMost(nyquist * .999f)
    // The bilinear transform pre-warped at the section's frequency: Ω = tan(πf/fs) / tan(πfc/fs).
    val w = (tan(PI * f / sampleRate) / tan(PI * fc / sampleRate))
    val a = 10.0.pow(section.gainDb / 40.0); val q = section.q.toDouble(); val w2 = w * w
    // |H(jΩ)| = |numerator| / |denominator|, each a + jb.
    val (nr, ni, dr, di) = when (section.shape) {
        EqShape.Bell -> listOf(1 - w2, w * a / q, 1 - w2, w / (a * q))
        EqShape.LowShelf -> listOf(a * (a - w2), a * w * sqrt(a) / q, 1 - a * w2, w * sqrt(a) / q)
        EqShape.HighShelf -> listOf(a * (1 - a * w2), a * w * sqrt(a) / q, a - w2, w * sqrt(a) / q)
    }
    return (20 * log10(hypot(nr, ni) / hypot(dr, di))).toFloat()
}

private operator fun List<Double>.component4() = this[3]

/** Automix's bass swap: a low shelf at 150 Hz, steep enough to take a song's low end out. Flat at 0 dB. */
internal fun bassSwapSection(cutDb: Float) = EqSection(EqShape.LowShelf, 150f, .7f, cutDb.coerceIn(-30f, 0f))
/** Its counterpart (research report 31 Oct, 1.1): a high shelf at the same corner, so a song's body can fade while its bass holds. */
internal fun aboveBassSection(cutDb: Float) = EqSection(EqShape.HighShelf, 150f, .7f, cutDb.coerceIn(-30f, 0f))

/** 64 frequencies from 20 Hz to 20 kHz, evenly spaced on a log scale: the curve preview and the pre-amp read these. */
internal val eqProbeFrequencies: FloatArray = FloatArray(64) { (20.0 * 1_000.0.pow(it / 63.0)).toFloat() }

/** The summed response of [sections] at each probe frequency, in dB. */
internal fun eqCurve(sections: List<EqSection>, sampleRate: Int = 48_000): FloatArray =
    FloatArray(eqProbeFrequencies.size) { i -> sections.sumOf { sectionGainDb(it, eqProbeFrequencies[i], sampleRate).toDouble() }.toFloat() }

/**
 * The pre-amp that keeps the loudest point of the summed curve at 0 dB: overlapping sections add up, so the largest
 * single section under-reads it. Never boosts.
 */
internal fun preampFor(sections: List<EqSection>, sampleRate: Int = 48_000): Float = -max(0f, eqCurve(sections, sampleRate).max())

/** Constant-power balance: each side's gain for [balance] from −1 to 1; 1 and 1 at the centre. */
internal fun balanceGains(balance: Float): Pair<Float, Float> {
    val b = balance.coerceIn(-1f, 1f)
    if (b == 0f) return 1f to 1f
    val angle = PI * (b + 1) / 4
    return (kotlin.math.cos(angle) * sqrt(2.0)).toFloat().coerceAtMost(1f) to (kotlin.math.sin(angle) * sqrt(2.0)).toFloat().coerceAtMost(1f)
}

/** A manual fader's value: half-decibel steps, flat within the detent. */
internal fun manualStep(db: Float): Float {
    val clamped = db.coerceIn(-EqLayout.MANUAL_RANGE_DB, EqLayout.MANUAL_RANGE_DB)
    if (kotlin.math.abs(clamped) < EqLayout.DETENT_DB) return 0f
    return kotlin.math.round(clamped * 2) / 2
}

/** "+6", "−3.5", "0": a gain as the faders and readouts write it. */
internal fun eqLabel(db: Float): String {
    if (db == 0f) return "0"
    val magnitude = kotlin.math.abs(db)
    val text = if (magnitude % 1f == 0f) magnitude.toInt().toString() else "%.1f".format(java.util.Locale.ROOT, magnitude)
    return (if (db > 0) "+" else "−") + text
}

/** Manual presets: 60 Hz to 14 kHz. */
internal val EQ_PRESETS: Map<String, List<Float>> = linkedMapOf(
    "Flat" to listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f),
    "Bass boost" to listOf(6f, 4f, 1f, 0f, 0f, 0f, 0f),
    "Bass reduce" to listOf(-6f, -4f, -1f, 0f, 0f, 0f, 0f),
    "Treble boost" to listOf(0f, 0f, 0f, 0f, 2f, 4f, 6f),
    "Vocal" to listOf(-2f, -1f, 1f, 3f, 3f, 1f, 0f),
    "Rock" to listOf(4f, 2f, -1f, -1f, 1f, 3f, 4f),
    "Pop" to listOf(-1f, 1f, 3f, 3f, 1f, -1f, -1f),
    "Electronic" to listOf(5f, 3f, 0f, -1f, 1f, 3f, 4f),
    "Hip-hop" to listOf(5f, 4f, 1f, 0f, 1f, 1f, 2f),
    "Jazz" to listOf(3f, 2f, 0f, 1f, 1f, 2f, 3f),
    "Classical" to listOf(3f, 2f, 0f, 0f, 0f, 2f, 3f),
    "Acoustic" to listOf(2f, 2f, 1f, 1f, 2f, 2f, 1f),
    "Loudness" to listOf(5f, 3f, 0f, -1f, 0f, 3f, 5f),
    "Small speakers" to listOf(-4f, 3f, 2f, 1f, 2f, 1f, -1f),
)
