package com.musicglass.personal

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

internal object PlusStatsUi : EditionStatsUi {
    @Composable override fun Panel(playback: PlaybackGateway, onNotice: (String) -> Unit, onDismiss: () -> Unit) =
        NerdStatsDialog(playback, onNotice, onDismiss)
    @Composable override fun Line(playback: PlaybackGateway, visible: Boolean, modifier: Modifier) =
        PlayerStatsLine(playback, visible, modifier)
    @Composable override fun SettingsSwitch(preferences: AccessibilitySettings, modifier: Modifier) =
        BentoToggle(string(R.string.show_stats_for_nerds), preferences.playerStats, preferences::updatePlayerStats,
            Icons.Rounded.Analytics, "settings_player_stats", modifier)
}
