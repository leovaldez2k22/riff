package com.musicglass.personal

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.ForwardingSimpleBasePlayer

/**
 * What the media session shows Android, the lock screen, a watch or a car. While Riff is still recovering a song
 * (waiting for a connection, trying again, or held by a pause), the player's error is Riff's to handle and not a
 * failure: the session says buffering, or paused once the user pauses, instead of ERROR (Q18-020). A song Riff has
 * given up on keeps its error. Riff's own code drives the wrapped player directly; only the session reads this.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class WaitingSessionPlayer(player: Player, private val recovering: () -> Boolean) : ForwardingSimpleBasePlayer(player) {
    override fun getState(): State {
        val state = super.getState()
        return if (state.playerError != null && recovering()) maskRecoverable(state) else state
    }
    /** Called whenever recovery may have started, held or stopped, so the session reads the state again. */
    fun refresh() = invalidateState()
}

/** [state] without the error Riff is recovering from: buffering, which Android shows as paused while not asked to play. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun maskRecoverable(state: androidx.media3.common.SimpleBasePlayer.State): androidx.media3.common.SimpleBasePlayer.State =
    if (state.playlist.isEmpty()) state.buildUpon().setPlayerError(null).setPlaybackState(Player.STATE_IDLE).build()
    else state.buildUpon().setPlayerError(null).setPlaybackState(Player.STATE_BUFFERING).setIsLoading(false).build()
