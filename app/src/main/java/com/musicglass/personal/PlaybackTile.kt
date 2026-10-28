package com.musicglass.personal

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

/**
 * Feature 27: a Quick Settings tile. Tapping plays or pauses what is loaded; with nothing loaded it opens Riff on
 * Resume, which brings back the saved queue. Long-pressing opens Riff (MainActivity takes the tile's preferences
 * action). It reads the session only while the shade is open.
 */
class PlaybackTile : TileService() {
    private var controller: ListenableFuture<MediaController>? = null
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = show(player)
    }

    override fun onStartListening() {
        super.onStartListening()
        val future = MediaController.Builder(this, SessionToken(this, ComponentName(this, MusicPlaybackService::class.java))).buildAsync()
        controller = future
        future.addListener({ runCatching { future.get() }.getOrNull()?.let { it.addListener(listener); show(it) } }, mainExecutor)
    }

    override fun onStopListening() {
        controller?.let(MediaController::releaseFuture)
        controller = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val player = controller?.takeIf { it.isDone }?.let { runCatching { it.get() }.getOrNull() }
        if (player == null || player.mediaItemCount == 0) { openRiff(AppShortcut.Resume); return }
        if (player.isPlaying) player.pause() else { if (player.playbackState == Player.STATE_IDLE) player.prepare(); player.play() }
    }

    // Android 12 and 13 have only the Intent form; 14 and later get the PendingIntent one.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openRiff(shortcut: AppShortcut) {
        val intent = Intent(shortcut.action).setClass(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (android.os.Build.VERSION.SDK_INT >= 34)
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        else @Suppress("DEPRECATION") startActivityAndCollapse(intent)
    }

    private fun show(player: Player) {
        val tile = qsTile ?: return
        tile.state = tileState(player.mediaItemCount > 0, player.isPlaying)
        tile.label = string(R.string.tile_label)
        tile.subtitle = player.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() } ?: string(R.string.tile_nothing_queued)
        tile.icon = Icon.createWithResource(this, R.drawable.tile_riff)
        tile.updateTile()
    }
}

/** Active while something plays, inactive while paused, and still tappable when nothing is queued. */
internal fun tileState(queued: Boolean, playing: Boolean): Int = if (queued && playing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
