package com.musicglass.personal

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.metrolist.innertubex.sabr.SabrFailureKind

internal enum class StreamRecoveryAction { REFRESH_ATTESTATION, REFRESH_STREAM, EXCLUDE_PROFILE }

/** One credential refresh and at most three alternate profiles for each queue item. */
internal class StreamRecoveryBudget {
    private var attestationRefreshed = false
    private var streamRefreshed = false
    val excludedProfiles = linkedSetOf<String>()

    fun next(kind: SabrFailureKind?, httpStatus: Int?, profile: String?): StreamRecoveryAction? {
        if (kind == SabrFailureKind.ATTESTATION_REQUIRED && !attestationRefreshed) {
            attestationRefreshed = true
            return StreamRecoveryAction.REFRESH_ATTESTATION
        }
        if (httpStatus in setOf(403, 410) && !streamRefreshed) {
            streamRefreshed = true
            return StreamRecoveryAction.REFRESH_STREAM
        }
        if (kind == null && httpStatus !in setOf(403, 410)) return null
        if (profile.isNullOrBlank() || excludedProfiles.size >= 3 || !excludedProfiles.add(profile)) return null
        return StreamRecoveryAction.EXCLUDE_PROFILE
    }
}

/** A delayed recovery must not replace a song selected in the meantime, or resume a user pause. */
internal fun replaceFailedStream(player: Player, expected: MediaItem, replacement: MediaItem): Boolean {
    if (player.currentMediaItem !== expected) return false
    val index = player.currentMediaItemIndex
    val position = player.currentPosition.coerceAtLeast(0)
    val wantsPlayback = player.playWhenReady
    player.replaceMediaItem(index, replacement)
    player.seekTo(index, position)
    player.prepare()
    player.playWhenReady = wantsPlayback
    return true
}
