package com.musicglass.personal

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow

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
    val onBeat: Boolean = false,
    /** Research report 31 Oct, 1.3: the bass swap on a downbeat; null, the 1 s swap at the blend's midpoint. */
    val swap: BassSwap? = null,
    /** Report 1.5: tempos too far apart to blend, so the outgoing song echoes out instead. */
    val echo: EchoOut? = null,
    /** Report 1.6: the trim of both songs' level, in dB, per [ENVELOPE_BLOCK_MS] of the outgoing song from [startMs]. */
    val trimDb: FloatArray? = null,
    /** Report 1.2: the outgoing song's phrase phase was found, so the start is on a phrase line. For the event log. */
    val phrase: Boolean = false,
    /** Report 1.4: beats of both songs singing at once, when both have synced lyrics. For the event log. */
    val vocalBeats: Double? = null) {
    val blendMs: Long get() = endMs - startMs
    /** The trim at [atMs] of the outgoing song: 0 outside the lane. */
    fun trimAt(atMs: Long): Float = trimDb?.getOrNull(((atMs - startMs) / ENVELOPE_BLOCK_MS).toInt().takeIf { atMs >= startMs } ?: -1) ?: 0f
}

/** The bass passes from one song to the other over [lengthMs] centred on [atMs] of the outgoing song; 0 is a hard swap. */
internal data class BassSwap(val atMs: Long, val lengthMs: Long)

/**
 * An echo-out (report 4.5): over the bar before [cutMs] the outgoing song is fed into a delay of [delayMs] (one of its
 * beats); at [cutMs] it stops and only the echoes ring on; the incoming song comes in there, on its own downbeat.
 */
internal data class EchoOut(val cutMs: Long, val delayMs: Long, val sendFromMs: Long)

/**
 * The blend for [outgoing]'s analysed end and [incoming]'s analysed start, set by the song alone: it starts at the
 * outro, or [fallbackMs] before the audible end when there is none, and lasts 3 to 12 s. The crossfade slider is
 * for the plain crossfade only (owner, phone check 30 Oct: with the slider at 3 s, every smart blend came out 4 s). The incoming song
 * enters at its first sound, past a quiet intro of up to 8 s when it is music. Null when the end could not be read.
 */
internal fun planSmartTransition(outgoing: TrackEnvelope?, incoming: TrackEnvelope?, fallbackMs: Long = SMART_FALLBACK_BLEND_MS, incomingIsMusic: Boolean = true,
    /** Automix (beta): stage 2 may move the blend onto the beat. Smart keeps stage 1's timing. */
    onBeat: Boolean = true,
    /** Report 1.4: where each song is sung, from its synced lyrics, when it has them. */
    outgoingVocals: List<LongRange>? = null, incomingVocals: List<LongRange>? = null,
    /** Report 1.6: each song's normalization gain, so the predicted levels are the ones heard. */
    outgoingGainDb: Float = 0f, incomingGainDb: Float = 0f): SmartPlan? {
    val stage = planStages(outgoing, incoming, fallbackMs, incomingIsMusic, onBeat, outgoingVocals, incomingVocals) ?: return null
    if (stage.echo != null) return stage
    val head = incoming?.takeIf { it.startMs < ENVELOPE_BLOCK_MS }
    return stage.copy(trimDb = loudnessTrim(stage, outgoing!!, head, outgoingGainDb, incomingGainDb))
}

private fun planStages(outgoing: TrackEnvelope?, incoming: TrackEnvelope?, fallbackMs: Long, incomingIsMusic: Boolean, onBeat: Boolean,
    outgoingVocals: List<LongRange>?, incomingVocals: List<LongRange>?): SmartPlan? {
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
    val head = incoming?.takeIf { it.startMs < ENVELOPE_BLOCK_MS }
    val sungOut = outgoingVocals?.takeIf { lyricsFitTheEnd(it, outgoing) }
    val sungIn = incomingVocals?.takeIf { head != null && lyricsFitTheStart(it, head) }
    if (!onBeat) return avoidVocals(plan, null, null, sungOut, sungIn)
    val grid = outgoing.beats
    val phase = grid?.let { phrasePhase(outgoing, it) }
    echoOut(plan, outgoing, head?.beats, phase, sungOut)?.let { return it.copy(phrase = phase != null) }
    val aligned = alignToBeats(plan, grid, head?.beats, phase)?.copy(phrase = phase != null) ?: plan
    val sung = avoidVocals(aligned, grid?.takeIf { aligned.onBeat }, phase, sungOut, sungIn)
    return sung.copy(swap = grid?.let { swapOnDownbeat(sung, it, head?.beats) })
}

/** Stage 2 asks this much of both songs' beats and downbeats (engine report, section 5.4, DJ-assisted). */
internal const val BEAT_CONFIDENCE = .35f
internal const val DOWNBEAT_CONFIDENCE = .3f
/** How far a downbeat may move the blend's start from where stage 1 put it. */
private const val BEAT_REACH_MS = 2_500L

/**
 * Automix stage 2, DJ-assisted: moves [plan]'s start onto the outgoing song's downbeat nearest to it, preferring a
 * phrase line ([phase], from [phrasePhase]), and lets the incoming song enter on its first downbeat at or after its
 * stage 1 entry. Both songs keep their speed; the downbeats meet as the blend begins. Null when either song's beats
 * are not sure enough, or no downbeat fits: then stage 1's timing stands.
 */
internal fun alignToBeats(plan: SmartPlan, outgoing: BeatGrid?, incoming: BeatGrid?, phase: Int? = null): SmartPlan? {
    outgoing ?: return null; incoming ?: return null
    if (minOf(outgoing.confidence, incoming.confidence) < BEAT_CONFIDENCE) return null
    if (minOf(outgoing.downbeatConfidence, incoming.downbeatConfidence) < DOWNBEAT_CONFIDENCE) return null
    val fits = outgoing.downbeatsMs.withIndex().filter { (_, ms) ->
        abs(ms - plan.startMs) <= BEAT_REACH_MS && plan.endMs - ms in SMART_MIN_BLEND_MS..SMART_MAX_BLEND_MS }
    if (fits.isEmpty()) return null
    // Report 1.2: the phrase test was `index % 4 == 0`, counted from wherever the analysed window happened to begin,
    // so it hit the song's real phrase lines about one time in four.
    val phrase = phase?.let { p -> fits.filter { (it.index - p).mod(PHRASE_BARS) == 0 }.minByOrNull { abs(it.value - plan.startMs) } }
    val start = phrase?.value ?: fits.minBy { abs(it.value - plan.startMs) }.value
    val entry = incoming.downbeatsMs.firstOrNull { it >= plan.incomingStartMs - 50 } ?: return null
    if (entry - plan.incomingStartMs > SMART_MAX_INTRO_SKIP_MS) return null
    return SmartPlan(start, plan.endMs, entry, onBeat = true)
}

/** Report 2.3: bars to a phrase, and how sure the phase must be (its best score in standard deviations above the mean). */
internal const val PHRASE_BARS = 4
internal const val PHRASE_CONFIDENCE = 1.5f
/** Below this the downbeats themselves may be wrong, and a phrase counted from them with them (report 2.3). */
internal const val PHRASE_DOWNBEAT_CONFIDENCE = .5f

/**
 * Report 1.2 and 2.3: which of the outgoing song's downbeats (index mod [PHRASE_BARS]) start its four-bar phrases.
 * Each downbeat scores how much the music changes across it: the bass and the loudness, bar against bar before
 * (the report also weighs harmony and novelty, which Riff doesn't compute yet), plus a bonus for the outro's start and
 * the last downbeat before the sound ends, which are nearly always on phrase lines. The phase whose downbeats score
 * best wins when it stands out by [PHRASE_CONFIDENCE]; failing that, the phrase is counted back from the outro's start.
 * Null when the downbeats are unsure, or nothing anchors the count.
 */
internal fun phrasePhase(envelope: TrackEnvelope, grid: BeatGrid): Int? {
    val downbeats = grid.downbeatsMs
    if (grid.downbeatConfidence < PHRASE_DOWNBEAT_CONFIDENCE || downbeats.size < 2) return null
    val outro = envelope.outroStartMs()?.let { at -> downbeats.indices.minBy { abs(downbeats[it] - at) }.takeIf { abs(downbeats[it] - at) <= grid.beatMs * 2 } }
    val end = envelope.audibleEndMs()?.let { at -> downbeats.indexOfLast { it <= at } }?.takeIf { it >= 0 }
    if (downbeats.size >= PHRASE_BARS * 2) {
        val evidence = FloatArray(downbeats.size) { k ->
            val bar = if (k > 0) downbeats[k] - downbeats[k - 1] else downbeats[1] - downbeats[0]
            val at = downbeats[k]
            fun change(levels: FloatArray): Float {
                val before = envelope.meanDb(levels, at - bar, at) ?: return 0f
                val after = envelope.meanDb(levels, at, at + bar) ?: return 0f
                return abs(after - before)
            }
            .6f * change(envelope.lowDb) + .4f * change(envelope.loudnessDb)
        }
        val bonus = evidence.sorted()[evidence.size * 9 / 10]
        listOfNotNull(outro, end).forEach { evidence[it] += bonus }
        val scores = FloatArray(PHRASE_BARS) { p -> evidence.filterIndexed { k, _ -> k % PHRASE_BARS == p }.average().toFloat() }
        val mean = scores.average().toFloat()
        val spread = kotlin.math.sqrt(scores.map { (it - mean) * (it - mean) }.average()).toFloat()
        val best = scores.indices.maxBy { scores[it] }
        if (spread > 1e-3f && (scores[best] - mean) / spread >= PHRASE_CONFIDENCE) return best
    }
    return outro?.mod(PHRASE_BARS)
}

/** The mean power, in dB, of [levels] (one of this envelope's lanes) from [fromMs] to [toMs]; null outside the envelope. */
internal fun TrackEnvelope.meanDb(levels: FloatArray, fromMs: Long, toMs: Long): Float? {
    val from = ((fromMs - startMs) / ENVELOPE_BLOCK_MS).toInt(); val to = ((toMs - startMs) / ENVELOPE_BLOCK_MS).toInt()
    if (fromMs < startMs || to > levels.size || to <= from) return null
    var sum = 0.0
    for (i in from until to) sum += 10.0.pow(levels[i] / 10.0)
    return (10 * log10(sum / (to - from))).toFloat()
}

/** Report 1.3: the outgoing song's downbeats are this sure before the swap goes on one; both this sure, and it is hard. */
internal const val SWAP_DOWNBEAT_CONFIDENCE = .5f
internal const val HARD_SWAP_CONFIDENCE = .7f

/**
 * Report 1.3: the bass swap on the outgoing song's downbeat nearest the blend's midpoint, one beat long, instead of
 * 1 s at the exact midpoint; a hard swap when both songs' downbeats are sure. Null when they aren't sure enough.
 */
internal fun swapOnDownbeat(plan: SmartPlan, outgoing: BeatGrid, incoming: BeatGrid?): BassSwap? {
    if (outgoing.downbeatConfidence < SWAP_DOWNBEAT_CONFIDENCE) return null
    val beat = outgoing.beatMs.toLong()
    val middle = (plan.startMs + plan.endMs) / 2
    val at = outgoing.downbeatsMs.filter { it - beat >= plan.startMs && it + beat <= plan.endMs }.minByOrNull { abs(it - middle) } ?: return null
    val hard = minOf(outgoing.downbeatConfidence, incoming?.downbeatConfidence ?: 0f) >= HARD_SWAP_CONFIDENCE
    return BassSwap(at, if (hard) 0 else beat)
}

/** How much of the bass the incoming song carries at [atMs] of the outgoing song: 0 to 1, in amplitude. */
internal fun bassShare(atMs: Long, swap: BassSwap): Float =
    if (swap.lengthMs <= 0) (if (atMs >= swap.atMs) 1f else 0f)
    else ((atMs - (swap.atMs - swap.lengthMs / 2)).toFloat() / swap.lengthMs).coerceIn(0f, 1f)

/** How far one player's equalizer cuts the bass, and everything above it, in dB. */
internal data class Shelves(val lowCutDb: Float = 0f, val aboveCutDb: Float = 0f)

/** One player in a smart blend: its volume, and its shelves. */
internal data class DeckLevel(val volume: Float, val shelves: Shelves)

private val SWAP_FLOOR = 10.0.pow(BASS_SWAP_CUT_DB / 20.0).toFloat()

/**
 * Report 1.1: one song's player in a smart blend, where its body follows the equal-power fade ([body]) and its bass
 * the swap ([bass]), both as levels. The player plays at the louder of the two and its equalizer cuts the other part
 * down to its level. Before, the bass followed the fade too, and both songs' shelves met at −13 dB mid-swap: the low
 * end fell about 10 dB for half a second, and 3 dB either side of it.
 */
internal fun deckLevel(body: Float, bass: Float): DeckLevel {
    val volume = maxOf(body, bass)
    if (volume <= 0f) return DeckLevel(0f, Shelves())
    fun cut(part: Float) = (20 * log10(maxOf(part / volume, SWAP_FLOOR))).coerceIn(BASS_SWAP_CUT_DB, 0f) + 0f // + 0f: never −0 dB
    return DeckLevel(volume, Shelves(cut(bass), cut(body)))
}

/** Report 1.4: how many beats of a blend both songs may sing at once. */
internal const val VOCAL_CLASH_BEATS = 1.0
/** A lyric line without its own end lasts at most this long: an instrumental break between two lines isn't sung. */
internal const val LYRIC_LINE_MAX_MS = 10_000L
/** How far synced lyrics may be from the sound before they are taken to be for another edit of the song. */
internal const val LYRICS_SLACK_MS = 1_000L

/** Report 1.4: where a song is sung, from its synced lyric lines; instrumental gaps and untimed lines are left out. */
internal fun vocalSpans(lines: List<LyricLine>): List<LongRange> = lines.filter { !it.gap && it.startMs >= 0 && it.text.isNotBlank() }.map { line ->
    val end = if (line.knownEnd) line.untilMs else minOf(line.untilMs, line.startMs + LYRIC_LINE_MAX_MS)
    line.startMs..maxOf(line.startMs, end)
}

/** The outgoing song's lyrics end before its sound does: else they're for another edit (report 2.7). */
private fun lyricsFitTheEnd(spans: List<LongRange>, tail: TrackEnvelope): Boolean {
    val end = tail.audibleEndMs() ?: return false
    return spans.isNotEmpty() && spans.last().first <= end + LYRICS_SLACK_MS
}
/** The incoming song's lyrics start after its sound does. */
private fun lyricsFitTheStart(spans: List<LongRange>, head: TrackEnvelope): Boolean {
    val start = head.audibleStartMs() ?: return false
    return spans.isNotEmpty() && spans.first().first >= start - LYRICS_SLACK_MS
}

/**
 * Report 2.7: beats of [plan] in which both songs are audibly sung: the smaller of their two equal-power levels where
 * both sing, summed. A last line fading under an instrumental intro barely counts; two choruses on top of each other do.
 */
internal fun vocalOverlapBeats(plan: SmartPlan, outgoing: List<LongRange>, incoming: List<LongRange>, beatMs: Double): Double {
    val step = 50L
    var sum = 0.0
    var t = plan.startMs
    while (t < plan.endMs) {
        val x = (t - plan.startMs).toFloat() / plan.blendMs
        if (outgoing.any { t in it } && incoming.any { plan.incomingStartMs + t - plan.startMs in it }) {
            val (a, b) = crossfadeGains(x)
            sum += minOf(a, b) * step
        }
        t += step
    }
    return sum / beatMs
}

/**
 * Report 1.4: when both songs would sing over each other for more than [VOCAL_CLASH_BEATS], the blend starts elsewhere:
 * on another downbeat in reach (phrase lines first), or later, which shortens it. The first start that keeps the voices
 * apart is taken; when none does, the one with the least overlap. [grid] set: starts stay on its downbeats.
 */
private fun avoidVocals(plan: SmartPlan, grid: BeatGrid?, phase: Int?, outgoing: List<LongRange>?, incoming: List<LongRange>?): SmartPlan {
    outgoing ?: return plan; incoming ?: return plan
    val beat = grid?.beatMs ?: 500.0
    fun at(start: Long) = plan.copy(startMs = start).let { it.copy(vocalBeats = vocalOverlapBeats(it, outgoing, incoming, beat)) }
    val first = at(plan.startMs)
    if (first.vocalBeats!! <= VOCAL_CLASH_BEATS) return first
    fun fits(start: Long) = plan.endMs - start in SMART_MIN_BLEND_MS..SMART_MAX_BLEND_MS
    val starts = if (grid != null) {
        val near = grid.downbeatsMs.withIndex().filter { abs(it.value - plan.startMs) <= BEAT_REACH_MS && it.value != plan.startMs && fits(it.value) }
        val (lines, others) = near.partition { phase != null && (it.index - phase).mod(PHRASE_BARS) == 0 }
        lines.sortedBy { abs(it.value - plan.startMs) }.map { it.value } + others.sortedBy { abs(it.value - plan.startMs) }.map { it.value } +
            grid.downbeatsMs.filter { it > plan.startMs + BEAT_REACH_MS && fits(it) }
    } else generateSequence(plan.startMs + 500) { it + 500 }.takeWhile { fits(it) }.toList()
    var best = first
    for (start in starts) {
        val candidate = at(start)
        if (candidate.vocalBeats!! <= VOCAL_CLASH_BEATS) return candidate
        if (candidate.vocalBeats < best.vocalBeats!!) best = candidate
    }
    return best
}

/** Report 1.5: tempos further apart than this, after folding half and double time, don't blend. */
internal const val ECHO_TEMPO_GAP = .08
/** How long the echoes ring after the outgoing song stops: at a feedback of 0.55, about 30 dB down at 128 BPM. */
internal const val ECHO_RING_MS = 3_000L

/** Report 3.1: how far apart two tempos are, after folding half, two-thirds, three-halves and double time. */
internal fun foldedTempoGap(outgoingBpm: Double, incomingBpm: Double): Double =
    listOf(.5, 2.0 / 3, 1.0, 1.5, 2.0).minOf { abs(incomingBpm / (outgoingBpm * it) - 1) }

/**
 * Report 1.5 and the worked example in 4.5: when the two tempos can't be matched (more than [ECHO_TEMPO_GAP] apart),
 * the outgoing song is cut on one of its phrase downbeats, nearest where stage 1's blend would cross, and echoes out
 * (one beat of delay) while the incoming song starts on its own downbeat at the cut. The cut avoids a sung line, since
 * an echoed half-word sounds like a glitch. Null when the tempos are close enough to blend, or either song's beats are
 * unsure.
 */
private fun echoOut(plan: SmartPlan, outgoing: TrackEnvelope, incoming: BeatGrid?, phase: Int?, vocals: List<LongRange>?): SmartPlan? {
    val grid = outgoing.beats ?: return null; incoming ?: return null
    if (minOf(grid.confidence, incoming.confidence) < BEAT_CONFIDENCE || grid.downbeatConfidence < PHRASE_DOWNBEAT_CONFIDENCE) return null
    if (foldedTempoGap(grid.bpm, incoming.bpm) <= ECHO_TEMPO_GAP) return null
    val beat = grid.beatMs.toLong(); val bar = beat * 4
    val end = outgoing.audibleEndMs() ?: return null
    val middle = (plan.startMs + plan.endMs) / 2
    val cuts = grid.downbeatsMs.withIndex().filter { (k, at) ->
        (phase == null || (k - phase).mod(PHRASE_BARS) == 0) && at - bar >= outgoing.startMs && at <= end && at + ECHO_RING_MS <= outgoing.durationMs }
        .sortedBy { abs(it.value - middle) }.map { it.value }
    val cut = cuts.firstOrNull { at -> vocals?.none { at in it } != false } ?: cuts.firstOrNull() ?: return null
    val entry = if (incoming.downbeatConfidence >= DOWNBEAT_CONFIDENCE) incoming.downbeatsMs.firstOrNull { it >= plan.incomingStartMs - 50 }
        ?.takeIf { it - plan.incomingStartMs <= SMART_MAX_INTRO_SKIP_MS } ?: plan.incomingStartMs else plan.incomingStartMs
    // The incoming song plays muted for the bar before the cut, so its downbeat is where it is at the cut.
    return SmartPlan(cut - bar, cut + ECHO_RING_MS, (entry - bar).coerceAtLeast(0), onBeat = true, echo = EchoOut(cut, beat, cut - bar))
}

/** Report 6.3: how far the predicted trim may turn the blend down or up, and how smooth it is. */
internal const val TRIM_DOWN_DB = -3f
internal const val TRIM_UP_DB = 1.5f
private const val TRIM_SMOOTH_BLOCKS = 10

/**
 * Report 1.6 and 6.3: the blend's level, predicted from both songs' envelopes and their equal-power gains, against a
 * line from the outgoing song's level where the blend starts to the incoming one's where it ends. The difference is
 * the trim, between [TRIM_DOWN_DB] and [TRIM_UP_DB], smoothed over a second either way (the plan is known ahead, so
 * the smoothing doesn't lag). It answers the blend's continuity, not the songs' own dynamics. Null without both songs.
 */
internal fun loudnessTrim(plan: SmartPlan, outgoing: TrackEnvelope, incoming: TrackEnvelope?, outgoingGainDb: Float, incomingGainDb: Float): FloatArray? {
    incoming ?: return null
    val blocks = (plan.blendMs / ENVELOPE_BLOCK_MS).toInt()
    if (blocks < 2) return null
    // Short-term loudness (3 s), or momentary (400 ms) for a blend too short for it (report 6.3).
    val reach = if (plan.blendMs < 4_000) 2 else 15
    fun level(envelope: TrackEnvelope, atMs: Long, gainDb: Float): Float? {
        val block = ((atMs - envelope.startMs) / ENVELOPE_BLOCK_MS).toInt()
        if (block < 0 || block >= envelope.loudnessDb.size) return null
        val from = (block - reach).coerceAtLeast(0); val to = (block + reach + 1).coerceAtMost(envelope.loudnessDb.size)
        var sum = 0.0
        for (i in from until to) sum += 10.0.pow(maxOf(envelope.loudnessDb[i], -70f) / 10.0)
        return (10 * log10(sum / (to - from))).toFloat() + gainDb
    }
    val a = FloatArray(blocks) { level(outgoing, plan.startMs + it * ENVELOPE_BLOCK_MS, outgoingGainDb) ?: return null }
    val b = FloatArray(blocks) { level(incoming, plan.incomingStartMs + it * ENVELOPE_BLOCK_MS, incomingGainDb) ?: return null }
    val raw = FloatArray(blocks) { i ->
        val x = i.toFloat() / (blocks - 1)
        val (ga, gb) = crossfadeGains(x)
        val mix = 10 * log10(ga * ga * 10.0.pow(a[i] / 10.0) + gb * gb * 10.0.pow(b[i] / 10.0) + 1e-12).toFloat()
        val target = a[0] + (b[blocks - 1] - a[0]) * x
        (target - mix).coerceIn(TRIM_DOWN_DB, TRIM_UP_DB)
    }
    return FloatArray(blocks) { i ->
        val from = (i - TRIM_SMOOTH_BLOCKS / 2).coerceAtLeast(0); val to = (i + TRIM_SMOOTH_BLOCKS / 2 + 1).coerceAtMost(blocks)
        // The ends stay at 0, so the trim comes in and goes out with the blend.
        val taper = minOf(1f, i.toFloat() / TRIM_SMOOTH_BLOCKS, (blocks - 1 - i).toFloat() / TRIM_SMOOTH_BLOCKS)
        (raw.slice(from until to).average().toFloat() * taper) + 0f
    }
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
