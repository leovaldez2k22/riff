package com.musicglass.personal

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi

/** Edition effects before the shared booster; the light edition owns no effects or preference listeners. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal interface EditionAudio {
    val beforeBoost: List<AudioProcessor>
    val shelves: ((Shelves, Shelves) -> Unit)?
    val echo: ((EchoOut?) -> Unit)?
    fun start()
    fun createTail(): TailAudio
    fun release()
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal data class TailAudio(val processors: List<AudioProcessor>, val release: () -> Unit)
