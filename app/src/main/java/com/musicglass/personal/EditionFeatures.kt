package com.musicglass.personal

import android.content.Context

/** The capabilities supplied by the installed edition. Shared playback owns no model runtime. */
internal interface EditionFeatures {
    val plus: Boolean
    val statsCommand: String?
    val statsUi: EditionStatsUi?
    fun createStats(): EditionPlaybackStats?
    val blendModes: List<BlendMode>
    fun audioEffects(context: Context): EditionAudio
    @androidx.compose.runtime.Composable
    fun EqualizerEntry(model: ShellModel, modifier: androidx.compose.ui.Modifier)
    @androidx.compose.runtime.Composable
    /** [playback] is null where no player is at hand (previews and tests); Riff+ then leaves out the key control. */
    fun EqualizerPage(model: ShellModel, bottom: androidx.compose.ui.unit.Dp, playback: PlaybackGateway?)
    /** Feature 4: the player's seek bar, with Riff+'s most-replayed mark and button around it; Riff shows the bar alone. */
    @androidx.compose.runtime.Composable
    fun ReplayPeakSeek(playback: PlaybackGateway, mediaId: String, durationMs: Long, slider: @androidx.compose.runtime.Composable () -> Unit)
    /**
     * Feature 15: Riff+'s "Export as M4A" for a finished download; the call starts it (a warning first when the audio
     * must be converted, then Android's file picker). Riff offers nothing: null.
     */
    @androidx.compose.runtime.Composable
    fun downloadExport(track: ProviderTrack, scope: String, durationMs: Long, onNotice: (String) -> Unit): (() -> Unit)?
    /** Feature 10: whether Riff+'s visualizer is on (null: not offered), turning it on or off, and the strip itself. */
    fun visualizerShown(context: Context): Boolean?
    fun showVisualizer(context: Context, on: Boolean)
    @androidx.compose.runtime.Composable
    fun Visualizer(playback: PlaybackGateway)
    /** Feature 23: Riff+'s pitch steps under the speed control; Riff shows nothing. */
    @androidx.compose.runtime.Composable
    fun PitchControl(playback: PlaybackGateway)
    /** Feature 13: Riff+'s mixes from Encore, a Home shelf below speed dial; Riff shows nothing. */
    @androidx.compose.runtime.Composable
    fun HomeMixes(provider: YouTubeMusicProvider, playback: PlaybackGateway)
    /** Feature 17: whether Riff+'s "About this song" is offered, and its panel; Riff offers neither. */
    val songBackground: Boolean
    @androidx.compose.runtime.Composable
    fun SongBackgroundPanel(title: String, artist: String, onDismiss: () -> Unit)
    /**
     * Feature 21: called as MainActivity starts, before anything is drawn. Riff+ applies its accent, launch screen
     * and launcher icon; Riff clears a launch screen Riff+ left behind.
     */
    fun onActivityCreate(activity: android.app.Activity)
    /** Feature 21: how the startup logo is drawn; it follows the launcher icon. */
    fun startupLook(context: Context): StartupLook
    /** Feature 21: the widgets' accent (ARGB) when Riff+ tints the app; null keeps Riff's pink. */
    fun widgetAccent(context: Context): Int?
    /** Feature 21: Riff+'s Appearance page, opened from Look and feel; Riff has none. */
    val appearance: Boolean
    @androidx.compose.runtime.Composable
    fun AppearanceEntry(model: ShellModel)
    @androidx.compose.runtime.Composable
    fun AppearancePage(model: ShellModel, bottom: androidx.compose.ui.unit.Dp)
    /** Feature 18: Riff+ orders the songs to come by key and tempo; Riff's session has no such command. */
    val queueOrder: Boolean
    /** Feature 20 (Riff+): Sing, vocals removed from songs on the phone; false where the edition has none. */
    val sing: Boolean
    /** Feature 24 (Riff+): Blend with a friend by QR, on Phone playlists; nothing where the edition has none. */
    @androidx.compose.runtime.Composable
    fun BlendWithFriend(provider: YouTubeMusicProvider, playback: PlaybackGateway, onNotice: (String) -> Unit)
    @androidx.compose.runtime.Composable
    fun SingPanel(playback: PlaybackGateway, onDismiss: () -> Unit)
    /** Settings, Downloads: Sing's storage and its Clear (Riff+). */
    @androidx.compose.runtime.Composable
    fun SingStorageRow(onNotice: (String) -> Unit)
    /** Feature 18: the queue's action for it, beside Reshuffle; Riff shows nothing. */
    @androidx.compose.runtime.Composable
    fun QueueOrderAction(playback: PlaybackGateway, glass: RiffBackdrop?, modifier: androidx.compose.ui.Modifier)
    fun hasBeatModel(context: Context): Boolean
    fun beatAnalysis(context: Context): BeatAnalysis?
}

/**
 * The startup logo's colours: [ribbons] in front; [partner] for the crossing pair of style D; [tile] under the
 * ribbons for style C. Riff's is its pink ribbons.
 */
internal data class StartupLook(val ribbons: Long = 0xFFF45B9A, val partner: Long? = null, val tile: Long? = null)

/** A decoded window can be analysed without exposing a native runtime to shared playback. */
internal fun interface BeatAnalysis {
    fun beats(mono: FloatArray, rate: Int, startMs: Long): BeatGrid?
}

