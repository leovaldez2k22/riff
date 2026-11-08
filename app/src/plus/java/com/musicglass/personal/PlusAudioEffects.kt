package com.musicglass.personal

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi

/** Owns both equalizers, the outgoing echo, and their preference-listener lifetime. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class PlusAudioEffects(private val context: Context) : EditionAudio {
    /** Feature 20: Sing's instrumental in place of the song, first, so the equalizer and the booster apply to it too. */
    private val sing = SingProcessor { SingStore.get(context) }
    private val equalizer = EqualizerProcessor(tap = true)
    private var tailEqualizer: EqualizerProcessor? = null
    private var tailEcho: EchoProcessor? = null
    private val prefs = EqualizerSettings.prefs(context)
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> update() }
    override val beforeBoost: List<AudioProcessor> = listOf(sing, equalizer)
    override val shelves: (Shelves, Shelves) -> Unit = { outgoing, incoming ->
        tailEqualizer?.equalizer?.let { it.bassCutDb = outgoing.lowCutDb; it.aboveBassCutDb = outgoing.aboveCutDb }
        equalizer.equalizer.let { it.bassCutDb = incoming.lowCutDb; it.aboveBassCutDb = incoming.aboveCutDb }
    }
    override val echo: (EchoOut?) -> Unit = { tailEcho?.echo?.cue = it }
    private fun update() {
        val config = EqualizerSettings.read(prefs)
        equalizer.equalizer.config = config
        tailEqualizer?.equalizer?.config = config
    }
    override fun start() {
        SingSwitch.load(context)
        update()
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }
    override fun createTail(): TailAudio {
        val eq = EqualizerProcessor().also { it.equalizer.config = equalizer.equalizer.config }
        val echo = EchoProcessor()
        tailEqualizer = eq; tailEcho = echo
        // The outgoing song in a blend keeps Sing too, so its vocals do not come back for its last seconds.
        return TailAudio(listOf(SingProcessor { SingStore.get(context) }, eq, echo)) {
            if (tailEqualizer === eq) tailEqualizer = null
            if (tailEcho === echo) tailEcho = null
        }
    }
    override fun release() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        tailEqualizer = null; tailEcho = null
    }
}
