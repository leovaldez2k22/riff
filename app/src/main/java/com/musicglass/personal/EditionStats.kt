package com.musicglass.personal

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands

/** An optional collector: the light edition constructs no analytics listener. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal interface EditionPlaybackStats {
    val listener: AnalyticsListener
    fun snapshot(player: ExoPlayer, blend: AutomixNote?): Bundle
}

/** An absent UI cannot poll playback, even when a Plus display preference is stored. */
internal interface EditionStatsUi {
    @Composable fun Panel(playback: PlaybackGateway, onNotice: (String) -> Unit, onDismiss: () -> Unit)
    @Composable fun Line(playback: PlaybackGateway, visible: Boolean, modifier: Modifier)
    @Composable fun SettingsSwitch(preferences: AccessibilitySettings, modifier: Modifier)
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun SessionCommands.Builder.addEditionStats(): SessionCommands.Builder = apply {
    Edition.statsCommand?.let { add(SessionCommand(it, Bundle.EMPTY)) }
    // Feature 18 is Riff+'s: Riff's session does not take the command.
    if (Edition.queueOrder) add(SessionCommand(REORDER_QUEUE, Bundle.EMPTY))
}

/** The same policy is applied to catalog songs and local files. Order and other actions are preserved. */
internal fun editionPlayerOptions(options: List<String>): List<String> =
    (if (Edition.plus) options else options.filterNot { it == "Equalizer" || it == "Stats for nerds" }).filter { it.isNotEmpty() }

/** Feature 21: the preference file Riff+'s Appearance writes; Riff only reads "launch_screen_set" from it. */
internal const val APPEARANCE_PREFS = "appearance"

/**
 * Feature 21: Riff after Riff+. Android keeps the launch-screen theme an app sets, so Riff resets it once when Riff+
 * had set one (Riff+'s launcher aliases are gone with its manifest; Riff's own launcher entry was never disabled).
 */
internal fun clearPlusLaunchScreen(activity: android.app.Activity) {
    val prefs = activity.getSharedPreferences(APPEARANCE_PREFS, android.content.Context.MODE_PRIVATE)
    if (!prefs.getBoolean("launch_screen_set", false)) return
    runCatching { activity.splashScreen.setSplashScreenTheme(android.content.res.Resources.ID_NULL) }
    prefs.edit().putBoolean("launch_screen_set", false).apply()
}

/** Feature 17: the menu's "About this song", "" where the edition has none. */
internal fun songBackgroundOption(): String = if (Edition.songBackground) "About this song" else ""

/** Feature 20: the menu's Sing item, "" where the edition has none or for live radio and episodes. */
internal fun singOption(state: PlaybackUiState): String = if (Edition.sing && state.music && !state.live) "Sing" else ""

/** Feature 10: the menu's visualizer item, "" where the edition has none. */
internal fun visualizerOption(context: android.content.Context): String =
    when (Edition.visualizerShown(context)) { true -> "Hide visualizer"; false -> "Show visualizer"; null -> "" }
