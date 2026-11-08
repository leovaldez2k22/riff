package com.musicglass.personal

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
