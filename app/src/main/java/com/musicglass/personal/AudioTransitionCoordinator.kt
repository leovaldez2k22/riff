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

    private fun currentBoostGain() = boostGain(boostPrefs.getBoolean("enabled", false), boostPrefs.getInt("percent", 200))
    private fun updateBoost() {
        audioEffects.setGain(currentBoostGain())
        tailAudioEffects?.setGain(currentBoostGain())
        update()
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
    }
    /** The main player's renderers, with the booster in its PCM stage. */
    fun renderers() = riffAudioRenderers(context, volumeBoost)
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
            val tail = ExoPlayer.Builder(context, riffAudioRenderers(context, processor)).setMediaSourceFactory(mediaFactory).setLoadControl(riffLoadControl())
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
            tail to { if (tailAudioEffects === effects) tailAudioEffects = null; if (tailNormalization?.first === tail) tailNormalization = null; effects.release() }
        }, sleepActive = sleepActive, tailItem = tailItem,
            onBlend = { SongCrossfadeState.active = it }, clock = runtime.clocks::monotonicMillis, wait = runtime.wait) { events.append(it) }
    }
    /** Called while the main player is being built, once it has an audio session. */
    fun attach(main: ExoPlayer) { audioEffects.attach(main.audioSessionId); crossfader?.start(main) }
    fun audioSessionChanged(audioSessionId: Int) = audioEffects.attach(audioSessionId)
    /** Ends a blend in progress at once: the queue is being replaced, or a sleep timer was set. */
    fun cancelBlend() { crossfader?.cancel() }
    fun release() {
        boostPrefs.unregisterOnSharedPreferenceChangeListener(boostListener)
        crossfader?.release()
        audioEffects.release()
    }
}
