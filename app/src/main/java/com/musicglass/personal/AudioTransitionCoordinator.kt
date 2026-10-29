package com.musicglass.personal

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** How often, in silence-watch turns (250 ms while playing), a stream's cached tail is looked for. */
private const val CACHED_TAIL_EVERY = 8
/** How far a cached file's length may be from the player's before its tail is not trusted. */
private const val CACHED_LENGTH_SLACK_MS = 2_000L

/**
 * The level of what is heard: the booster, each track's normalization gain, and the crossfade with its second
 * player. Owned by the playback service and released with it. The main player's own volume is left to the
 * crossfade and the sleep timer; nothing here starts, stops or reorders playback.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class, androidx.media3.common.util.ExperimentalApi::class])
internal class AudioTransitionCoordinator(private val context: Context, private val scope: CoroutineScope,
    private val runtime: RuntimeDependencies, private val events: PlaybackEventLog, private val player: () -> Player?) {
    private val volumeBoost = VolumeBoostProcessor()
    private val audioEffects = AudioSessionEffects(context, volumeBoost.boost) { events.append(it) }
    private val boostPrefs = context.getSharedPreferences(AUDIO_BOOST_PREFS, Context.MODE_PRIVATE)
    private val boostListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> updateBoost() }
    /** Feature 10: the equalizer, on the main player and on the crossfade's tail alike. */
    private val equalizer = EqualizerProcessor()
    /** Feature 9: watches for the silence at the end of a song; the main player only. */
    private val silenceWatch = SilenceWatchProcessor()
    private var silenceMonitor: kotlinx.coroutines.Job? = null
    /** Where the last seek landed, in which song: silence the user sought into is left alone. */
    private var sought: Pair<String, Long>? = null
    /** Automix stage 1: analysed ends ("id:tail") and starts ("id:head") of songs on the phone; null when unreadable. */
    private val envelopes = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, TrackEnvelope?>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackEnvelope?>?) = size > 24
    })
    private val crossfadePrefs = context.getSharedPreferences(CROSSFADE_PREFS, Context.MODE_PRIVATE)
    /**
     * Reads [item]'s [part] once. A streamed song's tail is read from the cache when all of it is there, so until then
     * its key is left unset and the silence watch asks again; [playerDurationMs] checks the cached file is that song.
     */
    private fun analyse(item: MediaItem?, part: String, playerDurationMs: Long = C.TIME_UNSET) {
        item ?: return
        val key = "${item.mediaId}:$part"
        // Feature 9's Path A reads the same tail, for where the sound ends.
        val wanted = crossfadePrefs.getBoolean(SMART_CROSSFADE_KEY, false) || part == "tail" && boostPrefs.getBoolean(SKIP_END_SILENCE_KEY, false)
        if (!wanted || envelopes.containsKey(key)) return
        envelopes[key] = null
        scope.launch(runtime.dispatchers.io) {
            val local = TrackAnalysis.localSource(context, item)
            val cached = try { if (local == null && part == "tail") TrackAnalysis.cachedSource(context, item, playerDurationMs) else null }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) {
                    // Left marked as read, so a stream whose cache cannot be copied is not tried every 2 s.
                    events.append("automix_analysis part=$part found=0 source=cache failure=${e::class.simpleName ?: "error"}"); return@launch
                }
            if (local == null && cached == null) {
                // Not on the phone and not cached yet: a tail is asked for again. A song is often asked for before its
                // stream is resolved, when it has no cache key yet, so the silence watch decides what is worth asking.
                if (part == "tail") envelopes.remove(key)
                return@launch
            }
            val known = playerDurationMs.takeIf { it > 0 }
            val began = runtime.clocks.monotonicMillis()
            // Why a cached tail was not used: it could not be decoded, or its end is not where the player's is.
            var refused = ""
            val envelope = when {
                cached != null -> TrackAnalysis.envelope(context, cached, known)
                    .also { if (it == null) refused = " reason=decode" }
                    // A file whose end is not where the player's is would cut the song in the wrong place.
                    ?.takeIf { (known != null && kotlin.math.abs(it.durationMs - known) <= CACHED_LENGTH_SLACK_MS && kotlin.math.abs(it.endMs - known) <= CACHED_LENGTH_SLACK_MS)
                        .also { fits -> if (!fits) refused = " reason=length delta=${(it.endMs - (known ?: 0)) / 100 * 100}" } }
                part == "tail" -> TrackAnalysis.envelope(context, local!!, -ANALYSIS_TAIL_MS, ANALYSIS_TAIL_MS)
                else -> TrackAnalysis.envelope(context, local!!, 0, ANALYSIS_HEAD_MS)
            }
            envelopes[key] = envelope
            val beats = envelope?.beats
            events.append("automix_analysis part=$part found=${if (envelope == null) 0 else 1} source=${if (cached != null) "cache" else "file"} ms=${runtime.clocks.monotonicMillis() - began}$refused" +
                if (beats == null) "" else " bpm=${beats.bpm.toInt()} beat=${"%.2f".format(java.util.Locale.ROOT, beats.confidence)} downbeat=${"%.2f".format(java.util.Locale.ROOT, beats.downbeatConfidence)}")
        }
    }
    private fun analyseAround(main: Player) {
        analyse(main.currentMediaItem, "tail", main.duration)
        main.nextMediaItemIndex.takeIf { it >= 0 }?.let { analyse(main.getMediaItemAt(it), "head") }
    }
    /** The smart blend between two songs, or null where Automix leaves the plain crossfade: an album played in order. */
    private fun smartBlend(outgoing: MediaItem, incoming: MediaItem): SmartBlend? {
        val a = outgoing.mediaMetadata; val b = incoming.mediaMetadata
        if (albumInOrder(a.albumTitle?.toString(), a.trackNumber, b.albumTitle?.toString(), b.trackNumber)) return null
        val fadeMs = crossfadeSeconds(crossfadePrefs.getInt("seconds", 6)) * 1_000L
        return SmartBlend(planSmartTransition(envelopes["${outgoing.mediaId}:tail"], envelopes["${incoming.mediaId}:head"], fadeMs))
    }
    private var tailEqualizer: EqualizerProcessor? = null
    private val equalizerPrefs = EqualizerSettings.prefs(context)
    private val equalizerListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, _ -> updateEqualizer(prefs) }
    private var crossfader: Crossfader? = null
    private var tailAudioEffects: AudioSessionEffects? = null
    private var tailNormalization: Pair<Player, PcmVolumeBoost>? = null
    private var loggedNormalization = 1f
    /** Q18-077: the entry and loudness last written as loudness_track, so each track says once what it had. */
    private var loggedTrack: Pair<String, Double?>? = null
    /** Provider loudness by queue entry, learned when an entry's stream or download is opened. Durable values only. */
    private val entryLoudness = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, Double>(64, .75f, true) {
        // Most recently used last; the playing entry is read at every change, so it is never the one dropped.
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Double>?) = size > 2048
    })
    /** Q18-106: entries whose stream or download was opened without a usable loudness, so their line can be written. */
    private val openedWithoutLoudness = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, Unit>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > 2048
    })

    /** Path A: where [item]'s sound ends, when its tail has been read and the switch is on. */
    private fun audibleEnd(item: MediaItem): Long? = if (!boostPrefs.getBoolean(SKIP_END_SILENCE_KEY, false)) null
        else envelopes["${item.mediaId}:tail"]?.audibleEndMs()
    /** The song whose unread stream tail was last written to the event log, so each says it once. */
    private var notedTail: String? = null
    /** Near the end of a stream whose tail was never read, what the cache held of it: why Path B was left to do it. */
    private fun noteMissingTail(main: Player, item: MediaItem) {
        if (!boostPrefs.getBoolean(SKIP_END_SILENCE_KEY, false) || item.localConfiguration?.customCacheKey == null) return
        val duration = main.duration
        if (duration <= 0 || duration - main.currentPosition > ANALYSIS_TAIL_MS / 2 || envelopes["${item.mediaId}:tail"] != null) return
        val key = silenceKey(main)
        if (notedTail == key) return
        notedTail = key
        scope.launch(runtime.dispatchers.io) {
            val state = TrackAnalysis.cachedState(context, item, duration) ?: return@launch
            events.append("stream_tail_unread known=${state.known} init=${state.head} cached=${state.tail} ms=${state.tailMs}")
        }
    }
    private fun silenceKey(main: Player) = "${main.currentMediaItemIndex}:${main.currentMediaItem?.mediaId}"
    private fun watchSilence(main: ExoPlayer) {
        analyseAround(main)
        main.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = analyseAround(main)
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = analyseAround(main)
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) sought = silenceKey(main) to newPosition.positionMs
            }
        })
        silenceMonitor?.cancel()
        silenceMonitor = scope.launch {
            var ticks = 0
            while (true) {
                runtime.wait(if (main.isPlaying) 250 else 1_000)
                main.skipSilenceEnabled = boostPrefs.getBoolean(SKIP_ALL_SILENCE_KEY, false)
                val item = main.currentMediaItem ?: continue
                // A stream's tail, once the player has cached it to the end (about 50 s ahead of what is heard).
                if (++ticks % CACHED_TAIL_EVERY == 0 && item.localConfiguration?.customCacheKey != null) analyse(item, "tail", main.duration)
                noteMissingTail(main, item)
                val quiet = silenceWatch.meter.quietMs
                if (SongCrossfadeState.active != null) continue
                val end = audibleEnd(item)
                if (shouldSkipSilence(boostPrefs.getBoolean(SKIP_END_SILENCE_KEY, false), quiet, main.currentPosition, main.duration, main.isPlaying,
                        main.hasNextMediaItem(), main.repeatMode == Player.REPEAT_MODE_ONE, crossfadeSkipsItem(item),
                        sought?.takeIf { it.first == silenceKey(main) }?.second, end)) {
                    events.append("silence_skip at=${main.currentPosition} quiet_ms=$quiet path=${if (end == null) "b" else "a"}")
                    main.seekToNextMediaItem()
                }
            }
        }
    }
    private fun currentBoostGain() = boostGain(boostPrefs.getBoolean("enabled", false), boostPrefs.getInt("percent", 200))
    private fun updateBoost() {
        audioEffects.setGain(currentBoostGain())
        tailAudioEffects?.setGain(currentBoostGain())
        update()
    }
    private fun updateEqualizer(prefs: SharedPreferences) {
        val config = EqualizerSettings.read(prefs)
        equalizer.equalizer.config = config; tailEqualizer?.equalizer?.config = config
    }
    private fun normalizationFor(item: MediaItem?) = normalizationGain(boostPrefs.getBoolean(NORMALIZE_KEY, false), item?.entryId()?.let(entryLoudness::get))

    /** May be called from a loader thread, usually before the entry's first audio is decoded. */
    fun learnLoudness(item: MediaItem, loudnessDb: Double?) {
        val entry = item.entryId(); val value = validLoudnessDb(loudnessDb)
        if (entry.isEmpty()) return
        if (value == null) { if (openedWithoutLoudness.put(entry, Unit) == null) scope.launch { update() }; return }
        if (entryLoudness.put(entry, value) == value) return
        scope.launch { update() }
    }
    /**
     * Normalize volume: each player's own static gain for the track it is playing, applied in its PCM stage. The
     * player's level stays with crossfade and the sleep timer; the booster keeps its own gain.
     */
    fun update() {
        player()?.let {
            val gain = normalizationFor(it.currentMediaItem); volumeBoost.boost.trim = gain
            if (gain != loggedNormalization) { loggedNormalization = gain; events.append("normalize gain_db=${normalizationDecibels(gain)}") }
            val entry = it.currentMediaItem?.entryId().orEmpty()
            val track = entry to entryLoudness[entry]
            if (loudnessTrackDue(entry, track.second, openedWithoutLoudness.containsKey(entry), loggedTrack)) {
                loggedTrack = track
                events.append(loudnessTrackEvent(boostPrefs.getBoolean(NORMALIZE_KEY, false), track.second, gain))
            }
        }
        tailNormalization?.let { (tail, stage) -> stage.trim = normalizationFor(tail.currentMediaItem) }
    }

    /** Reads the saved settings and follows later changes to them. Before the players exist. */
    fun start() {
        updateBoost()
        boostPrefs.registerOnSharedPreferenceChangeListener(boostListener)
        updateEqualizer(equalizerPrefs)
        equalizerPrefs.registerOnSharedPreferenceChangeListener(equalizerListener)
    }
    /** The main player's renderers, with the equalizer and then the booster in its PCM stage. */
    fun renderers() = riffAudioRenderers(context, volumeBoost, silenceWatch, equalizer)
    /**
     * Builds the crossfade. The outgoing song plays on a second player made here on demand: same sources and
     * loudness, no audio focus and no session of its own.
     */
    fun createCrossfade(mediaFactory: MediaSource.Factory, sleepActive: () -> Boolean, tailItem: (MediaItem) -> MediaItem) {
        val audioAttributes = AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).setUsage(C.USAGE_MEDIA).build()
        val pcmOnly = TrackSelectionParameters.AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED).build()
        crossfader = Crossfader(context, scope, createTail = {
            // Match the main player's frequent position updates for muted synchronization.
            val processor = VolumeBoostProcessor()
            val tailEq = EqualizerProcessor().also { it.equalizer.config = equalizer.equalizer.config; tailEqualizer = it }
            val tail = ExoPlayer.Builder(context, riffAudioRenderers(context, processor, tailEq)).setMediaSourceFactory(mediaFactory).setLoadControl(riffLoadControl())
                .experimentalSetDynamicSchedulingEnabled(false).build()
            tail.setAudioAttributes(audioAttributes, false)
            tail.trackSelectionParameters = tail.trackSelectionParameters.buildUpon().setAudioOffloadPreferences(pcmOnly).build()
            val effects = AudioSessionEffects(context, processor.boost)
            tailAudioEffects = effects
            effects.setGain(currentBoostGain()); effects.attach(tail.audioSessionId)
            tailNormalization = tail to processor.boost
            tail.addListener(object : Player.Listener {
                override fun onAudioSessionIdChanged(audioSessionId: Int) = effects.attach(audioSessionId)
                // The outgoing song keeps its own gain to the end of the blend.
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val gain = normalizationFor(mediaItem); processor.boost.trim = gain
                    if (gain != 1f) events.append("normalize_outgoing gain_db=${normalizationDecibels(gain)}")
                }
            })
            tail to { if (tailEqualizer === tailEq) tailEqualizer = null; if (tailAudioEffects === effects) tailAudioEffects = null; if (tailNormalization?.first === tail) tailNormalization = null; effects.release() }
        }, sleepActive = sleepActive, tailItem = tailItem,
            onBlend = { SongCrossfadeState.active = it }, clock = runtime.clocks::monotonicMillis, wait = runtime.wait,
            smart = ::smartBlend, audibleEnd = ::audibleEnd, bassSwap = { outgoing, incoming -> tailEqualizer?.equalizer?.bassCutDb = outgoing; equalizer.equalizer.bassCutDb = incoming }) { events.append(it) }
    }
    /** Called while the main player is being built, once it has an audio session. */
    fun attach(main: ExoPlayer) { audioEffects.attach(main.audioSessionId); crossfader?.start(main); watchSilence(main) }
    fun audioSessionChanged(audioSessionId: Int) = audioEffects.attach(audioSessionId)
    /** Ends a blend in progress at once: the queue is being replaced, or a sleep timer was set. */
    fun cancelBlend() { crossfader?.cancel() }
    fun release() {
        boostPrefs.unregisterOnSharedPreferenceChangeListener(boostListener)
        equalizerPrefs.unregisterOnSharedPreferenceChangeListener(equalizerListener)
        silenceMonitor?.cancel()
        crossfader?.release()
        audioEffects.release()
    }
}
