package com.musicglass.personal

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10

/*
 * Automix stage 1, the smart crossfade (engine report, section 6.2 stage B, without beats). From a song's loudness
 * in 100 ms blocks: where the sound really starts and ends, where the outro begins, where the intro has built up.
 * The planner places the blend from those, and the bass of the two songs is swapped at its midpoint.
 */

internal const val ENVELOPE_BLOCK_MS = 100
/** Below this a block is silence: the same line as Feature 9's. */
internal const val AUDIBLE_DBFS = SILENCE_DBFS.toFloat()
/** The outro starts where the level falls, for good, under 60 % of the song's body; the intro ends at 80 %. */
private val OUTRO_BELOW_DB = (20 * log10(.6)).toFloat()
private val INTRO_REACHES_DB = (20 * log10(.8)).toFloat()
/** The shortest blend: still room for the 1 s bass swap with a second either side (owner, 30 Oct: 3 s, was 4 s). */
internal const val SMART_MIN_BLEND_MS = 3_000L
internal const val SMART_MAX_BLEND_MS = 12_000L
/** A song whose outro can't be told from its body: the plain crossfade's default length. */
internal const val SMART_FALLBACK_BLEND_MS = 6_000L
/** The most of a quiet intro the incoming song may skip. */
internal const val SMART_MAX_INTRO_SKIP_MS = 8_000L
/** How deep the bass swap cuts, and how long each side takes to cross. */
internal const val BASS_SWAP_CUT_DB = -26f
internal const val BASS_SWAP_MS = 1_000L

/**
 * A stretch of a song as loudness: [loudnessDb] and [lowDb] (below 150 Hz) per [ENVELOPE_BLOCK_MS] block, the first
 * block starting at [startMs] into the song.
 */
internal class TrackEnvelope(val startMs: Long, val loudnessDb: FloatArray, val lowDb: FloatArray, val durationMs: Long,
    /** Automix stage 2: the beats in this stretch, when it has a pulse. */
    val beats: BeatGrid? = null) {
    val endMs: Long get() = startMs + loudnessDb.size.toLong() * ENVELOPE_BLOCK_MS
    private fun timeOf(block: Int) = startMs + block.toLong() * ENVELOPE_BLOCK_MS

    /** The loud part of the song: the level three quarters of the audible blocks stay under. */
    val bodyDb: Float? get() = loudnessDb.filter { it > AUDIBLE_DBFS }.sorted().let { if (it.size < 10) null else it[it.size * 3 / 4] }
    /** Where the first sound is. */
    fun audibleStartMs(): Long? = loudnessDb.indexOfFirst { it > AUDIBLE_DBFS }.takeIf { it >= 0 }?.let(::timeOf)
    /** Just after the last sound, with 300 ms left for a reverb. */
    fun audibleEndMs(): Long? = loudnessDb.indexOfLast { it > AUDIBLE_DBFS }.takeIf { it >= 0 }?.let { minOf(timeOf(it + 1) + 300, durationMs) }
    /** Where the level last falls under 60 % of the body and stays there. */
    fun outroStartMs(body: Float? = bodyDb): Long? {
        body ?: return null
        val last = loudnessDb.indexOfLast { it >= body + OUTRO_BELOW_DB }.takeIf { it >= 0 } ?: return null
        return timeOf(last + 1)
    }
    /** Where the level first reaches 80 % of the body. */
    fun introEndMs(body: Float? = bodyDb): Long? {
        body ?: return null
        return loudnessDb.indexOfFirst { it >= body + INTRO_REACHES_DB }.takeIf { it >= 0 }?.let(::timeOf)
    }
}

/** Builds a [TrackEnvelope] from 16-bit PCM, fed in pieces as it is decoded. */
internal class EnvelopeBuilder(private val sampleRate: Int, private val channels: Int, private val startMs: Long,
    /** Automix stage 4: when there is one, the beats are the model's, from the window kept here as one channel. */
    private val model: BeatLogits? = null) {
    private val blockFrames = (sampleRate * ENVELOPE_BLOCK_MS / 1_000).coerceAtLeast(1)
    // Two one-pole low-passes at 150 Hz: a gentle split, enough to tell where the kick and bass are.
    private val lowCoefficient = (1 - exp(-2 * PI * 150 / sampleRate)).toFloat()
    private var low1 = 0f; private var low2 = 0f
    private var frames = 0; private var sum = 0.0; private var lowSum = 0.0
    private val loudness = ArrayList<Float>(); private val low = ArrayList<Float>()
    private val onsets = OnsetBuilder(sampleRate, channels, startMs)
    private var window = FloatArray(if (model != null) sampleRate * 8 else 0); private var windowSize = 0

    fun add(samples: ShortArray, count: Int = samples.size) {
        onsets.add(samples, count)
        var i = 0
        while (i + channels <= count) {
            var mono = 0f
            for (c in 0 until channels) mono += samples[i + c] / 32768f
            mono /= channels; i += channels
            if (model != null) { if (windowSize == window.size) window = window.copyOf(windowSize * 2); window[windowSize++] = mono }
            low1 += (mono - low1) * lowCoefficient; low2 += (low1 - low2) * lowCoefficient
            sum += mono * mono; lowSum += low2 * low2
            if (++frames == blockFrames) {
                loudness += db(sum / frames); low += db(lowSum / frames)
                frames = 0; sum = 0.0; lowSum = 0.0
            }
        }
    }
    private fun db(power: Double) = if (power <= 1e-12) -120f else (10 * log10(power)).toFloat()
    fun build(durationMs: Long) = TrackEnvelope(startMs, loudness.toFloatArray(), low.toFloatArray(), durationMs, beats())

    /** The model's beats when there is a model and it found a pulse, else stage 2's. */
    private fun beats(): BeatGrid? = model?.let { m ->
        try { modelBeats(window.copyOf(windowSize), sampleRate, startMs, m) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
    } ?: onsets.beats()
}

/**
 * Where a smart blend goes. The outgoing song blends from [startMs] to [endMs], its audible end; the incoming one
 * enters at [incomingStartMs].
 */
internal data class SmartPlan(val startMs: Long, val endMs: Long, val incomingStartMs: Long,
    /** Automix stage 2: the blend starts on the outgoing song's downbeat and the incoming one enters on its own. */
    val onBeat: Boolean = false) {
    val blendMs: Long get() = endMs - startMs
}

/**
 * The blend for [outgoing]'s analysed end and [incoming]'s analysed start, set by the song alone: it starts at the
 * outro, or [fallbackMs] before the audible end when there is none, and lasts 3 to 12 s. The crossfade slider is
 * for the plain crossfade only (owner, phone check 30 Oct: with the slider at 3 s, every smart blend came out 4 s). The incoming song
 * enters at its first sound, past a quiet intro of up to 8 s when it is music. Null when the end could not be read.
 */
internal fun planSmartTransition(outgoing: TrackEnvelope?, incoming: TrackEnvelope?, fallbackMs: Long = SMART_FALLBACK_BLEND_MS, incomingIsMusic: Boolean = true,
    /** Automix (beta): stage 2 may move the blend onto the beat. Smart keeps stage 1's timing. */
    onBeat: Boolean = true): SmartPlan? {
    outgoing ?: return null
    val end = outgoing.audibleEndMs() ?: return null
    val outro = outgoing.outroStartMs()
    val start = outro ?: (end - fallbackMs)
    val blend = (end - start).coerceIn(SMART_MIN_BLEND_MS, SMART_MAX_BLEND_MS)
    val firstSound = incoming?.takeIf { it.startMs < ENVELOPE_BLOCK_MS }?.audibleStartMs() ?: 0L
    val built = incoming?.takeIf { incomingIsMusic && it.startMs < ENVELOPE_BLOCK_MS }?.introEndMs()
    // A quiet intro is skipped to a moment before the song has built up; a long one is played as it is.
    val entry = if (built != null && built - firstSound in 1..SMART_MAX_INTRO_SKIP_MS) maxOf(firstSound, built - 1_000) else firstSound
    val plan = SmartPlan((end - blend).coerceAtLeast(0), end, entry.coerceAtLeast(0))
    if (!onBeat) return plan
    return alignToBeats(plan, outgoing.beats, incoming?.takeIf { it.startMs < ENVELOPE_BLOCK_MS }?.beats) ?: plan
}

/** Stage 2 asks this much of both songs' beats and downbeats (engine report, section 5.4, DJ-assisted). */
internal const val BEAT_CONFIDENCE = .35f
internal const val DOWNBEAT_CONFIDENCE = .3f
/** How far a downbeat may move the blend's start from where stage 1 put it. */
private const val BEAT_REACH_MS = 2_500L

/**
 * Automix stage 2, DJ-assisted: moves [plan]'s start onto the outgoing song's downbeat nearest to it, preferring the
 * first beat of a four-bar phrase, and lets the incoming song enter on its first downbeat at or after its stage 1
 * entry. Both songs keep their speed; the downbeats meet as the blend begins. Null when either song's beats are not
 * sure enough, or no downbeat fits: then stage 1's timing stands.
 */
internal fun alignToBeats(plan: SmartPlan, outgoing: BeatGrid?, incoming: BeatGrid?): SmartPlan? {
    outgoing ?: return null; incoming ?: return null
    if (minOf(outgoing.confidence, incoming.confidence) < BEAT_CONFIDENCE) return null
    if (minOf(outgoing.downbeatConfidence, incoming.downbeatConfidence) < DOWNBEAT_CONFIDENCE) return null
    val fits = outgoing.downbeatsMs.withIndex().filter { (_, ms) ->
        abs(ms - plan.startMs) <= BEAT_REACH_MS && plan.endMs - ms in SMART_MIN_BLEND_MS..SMART_MAX_BLEND_MS }
    if (fits.isEmpty()) return null
    val phrase = fits.filter { it.index % 4 == 0 }.minByOrNull { abs(it.value - plan.startMs) }
    val start = phrase?.value ?: fits.minBy { abs(it.value - plan.startMs) }.value
    val entry = incoming.downbeatsMs.firstOrNull { it >= plan.incomingStartMs - 50 } ?: return null
    if (entry - plan.incomingStartMs > SMART_MAX_INTRO_SKIP_MS) return null
    return SmartPlan(start, plan.endMs, entry, onBeat = true)
}

/**
 * The bass swap at a moment of a blend: how far each side's low end is cut. The incoming song comes in without its
 * bass; at the midpoint, over [BASS_SWAP_MS], the outgoing song's bass goes out as the incoming one's comes in.
 */
internal fun bassSwapCuts(progress: Float, blendMs: Long): Pair<Float, Float> {
    val half = (BASS_SWAP_MS / 2f / blendMs.coerceAtLeast(1)).coerceAtMost(.5f)
    val t = ((progress.coerceIn(0f, 1f) - (.5f - half)) / (2 * half)).coerceIn(0f, 1f)
    return BASS_SWAP_CUT_DB * t + 0f to BASS_SWAP_CUT_DB * (1 - t) + 0f // + 0f: never −0 dB
}

/** A smart blend: [plan] when the outgoing song's end could be read, else only the bass swap on the plain timing. */
internal class SmartBlend(val plan: SmartPlan?)

/** The last smart blend planned, for the stats panel. */
internal data class AutomixNote(val mode: BlendMode, val blendMs: Long, val onBeat: Boolean)

/** Classical music blends plainly (plan, Feature 20): its quiet ends and long builds are the music, not padding. */
internal fun classicalGenres(genres: List<String>): Boolean =
    genres.any { it.contains("classical", ignoreCase = true) || it.equals("opera", ignoreCase = true) }

/** The artist names in a song's artist line ("A, B & C"), as Encore keeps genres for each. */
internal fun artistNames(line: String?): List<String> =
    line.orEmpty().split(", ", " & ", " x ", " feat. ", " ft. ").map(String::trim).filter(String::isNotEmpty)

/** Apple leaves an album played in order alone: consecutive tracks of the same album blend plainly. */
internal fun albumInOrder(outgoingAlbum: String?, outgoingTrack: Int?, incomingAlbum: String?, incomingTrack: Int?): Boolean =
    !outgoingAlbum.isNullOrBlank() && outgoingAlbum == incomingAlbum && outgoingTrack != null && incomingTrack == outgoingTrack + 1
