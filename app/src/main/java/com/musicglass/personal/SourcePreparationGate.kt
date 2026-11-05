package com.musicglass.personal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Media3 may prepare following sources early; they must not compete with selected-track extraction. */
internal class SourcePreparationGate {
    private data class Selection(val entry: String?, val playing: Boolean)
    private val selection = MutableStateFlow(Selection(null, false))
    fun update(entry: String?, playing: Boolean) { selection.value = Selection(entry, playing) }
    suspend fun await(entry: String) { selection.first { it.entry == entry || it.playing } }
}
