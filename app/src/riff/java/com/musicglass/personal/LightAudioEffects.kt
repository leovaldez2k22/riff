package com.musicglass.personal

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi

/** No extra PCM stages: the shared silence watch and booster remain in the renderer. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal object LightAudioEffects : EditionAudio {
    override val beforeBoost = emptyList<AudioProcessor>()
    override val shelves: ((Shelves, Shelves) -> Unit)? = null
    override val echo: ((EchoOut?) -> Unit)? = null
    // This edition owns neither a preference listener nor tail resources.
    override fun start() = Unit
    override fun createTail() = TailAudio(emptyList()) { }
    override fun release() = Unit
}
