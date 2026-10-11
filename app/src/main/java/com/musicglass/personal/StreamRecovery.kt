package com.musicglass.personal

import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/**
 * A delayed recovery must not replace a song selected in the meantime, or resume a user pause.
 * [startMs] replaces the position kept by default; a live stream restarts at its live edge.
 */
internal fun replaceFailedStream(player: Player, expected: MediaItem, replacement: MediaItem, startMs: Long? = null): Boolean {
    if (player.currentMediaItem !== expected) return false
    val index = player.currentMediaItemIndex
    val position = startMs ?: player.currentPosition.coerceAtLeast(0)
    val wantsPlayback = player.playWhenReady
    player.replaceMediaItem(index, replacement)
    player.seekTo(index, position)
    player.prepare()
    player.playWhenReady = wantsPlayback
    return true
}
