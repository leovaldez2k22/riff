package com.musicglass.personal

import java.io.File
import kotlin.math.min
import kotlin.math.pow

/**
 * Normalize volume (Q08A). The provider says, per track, how many decibels the recording is above its
 * reference loudness. Riff turns such a track down by that amount, by at most [NORMALIZE_MAX_ATTENUATION_DB],
 * and never turns anything up. No audio is analysed on the device, and audio from the phone's own library
 * has no such value and is left alone.
 */
internal const val NORMALIZE_MAX_ATTENUATION_DB = 12.0
/** The lowest gain normalization applies: 12 dB down, about a quarter of the amplitude. */
internal val NORMALIZE_MIN_GAIN: Float = 10.0.pow(-NORMALIZE_MAX_ATTENUATION_DB / 20).toFloat()

/** A loudness value worth keeping: a real number within what a recording can plausibly be. Anything else is no value. */
internal fun validLoudnessDb(value: Double?): Double? = value?.takeIf { it.isFinite() && it in -60.0..60.0 }

/** The gain for one track: 1 when off, unknown, invalid or not louder than the reference; otherwise below 1. */
internal fun normalizationGain(enabled: Boolean, loudnessDb: Double?): Float {
    val loudness = validLoudnessDb(loudnessDb) ?: return 1f
    if (!enabled || loudness <= 0.0) return 1f
    return 10.0.pow(-min(loudness, NORMALIZE_MAX_ATTENUATION_DB) / 20).toFloat().coerceIn(NORMALIZE_MIN_GAIN, 1f)
}

/**
 * Q18-077: what one track had, once per track: whether its loudness is known and the gain it got. "normalize gain_db"
 * is written only when the gain changes, so a track left at 0.0 could not tell an unknown value from a quiet song.
 */
internal fun loudnessTrackEvent(enabled: Boolean, loudnessDb: Double?, gain: Float): String {
    val loudness = validLoudnessDb(loudnessDb)
    return "loudness_track known=${loudness != null} enabled=$enabled" +
        (loudness?.let { " loudness_db=" + String.format(java.util.Locale.ROOT, "%.1f", it) } ?: "") +
        " gain_db=${normalizationDecibels(gain)}"
}

/**
 * Q18-106: loudness_track is written once a track's stream or download has been opened (when its loudness, or the lack
 * of it, is settled) and only when that differs from the line last written. Before, the line went out as the item
 * became current, known=false, and again a moment later with the value.
 */
internal fun loudnessTrackDue(entry: String, loudnessDb: Double?, opened: Boolean, logged: Pair<String, Double?>?): Boolean =
    entry.isNotEmpty() && (loudnessDb != null || opened) && (entry to loudnessDb) != logged

/** A gain as text for the event log, one decimal: "-6.0", or "0.0" for unchanged. */
internal fun normalizationDecibels(gain: Float): String =
    String.format(java.util.Locale.ROOT, "%.1f", if (gain >= 1f) 0.0 else 20 * kotlin.math.log10(gain.toDouble()))

/**
 * The value kept with a download, in the bundle's own folder next to its audio. It is a property of the
 * recording: it does not expire and names no account, stream or address.
 */
internal object BundleLoudness {
    private const val NAME = "loudness"
    fun write(bundle: File, loudnessDb: Double?) {
        val value = validLoudnessDb(loudnessDb) ?: return
        runCatching {
            val part = File(bundle, "$NAME.part"); val target = File(bundle, NAME)
            part.writeText(value.toString())
            // Where a rename does not replace, the earlier value goes first; it is the same recording's value.
            if (!part.renameTo(target)) { target.delete(); if (!part.renameTo(target)) part.delete() }
        }
    }
    /** Null for a bundle made before this existed, or whose value cannot be read. Such a bundle plays unchanged. */
    fun read(bundle: File?): Double? = runCatching {
        val file = File(bundle ?: return null, NAME)
        if (!file.isFile || file.length() > 64) null else validLoudnessDb(file.readText().trim().toDoubleOrNull())
    }.getOrNull()
}
