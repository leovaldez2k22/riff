package com.musicglass.personal

import android.content.Context

/** The light edition has no model or runtime; shared analysis uses its signal tracker. */
internal object BeatModel {
    fun present(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = false
    fun get(@Suppress("UNUSED_PARAMETER") context: Context): BeatAnalysis? = null
}
