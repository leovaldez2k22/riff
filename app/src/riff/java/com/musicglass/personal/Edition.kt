package com.musicglass.personal

import android.content.Context

internal object Edition : EditionFeatures {
    override val plus = false
    override val statsCommand: String? = null
    override val statsUi: EditionStatsUi? = null
    override fun createStats(): EditionPlaybackStats? = null
    override val blendModes = listOf(BlendMode.Crossfade, BlendMode.Smart)
    override fun audioEffects(context: Context): EditionAudio = LightAudioEffects
    @androidx.compose.runtime.Composable
    override fun EqualizerEntry(model: ShellModel, modifier: androidx.compose.ui.Modifier) = PhoneEqualizerTile(modifier)
    @androidx.compose.runtime.Composable
    override fun EqualizerPage(model: ShellModel, bottom: androidx.compose.ui.unit.Dp, playback: PlaybackGateway?) = SoundSection(model, bottom)
    @androidx.compose.runtime.Composable
    override fun ReplayPeakSeek(playback: PlaybackGateway, mediaId: String, durationMs: Long, slider: @androidx.compose.runtime.Composable () -> Unit) = slider()
    @androidx.compose.runtime.Composable
    override fun downloadExport(track: ProviderTrack, scope: String, durationMs: Long, onNotice: (String) -> Unit): (() -> Unit)? = null
    override fun visualizerShown(context: Context): Boolean? = null
    override fun showVisualizer(context: Context, on: Boolean) = Unit
    @androidx.compose.runtime.Composable
    override fun Visualizer(playback: PlaybackGateway) = Unit
    @androidx.compose.runtime.Composable
    override fun PitchControl(playback: PlaybackGateway) = Unit
    @androidx.compose.runtime.Composable
    override fun HomeMixes(provider: YouTubeMusicProvider, playback: PlaybackGateway) = Unit
    override val songBackground = false
    override val sing = false
    @androidx.compose.runtime.Composable
    override fun BlendWithFriend(provider: YouTubeMusicProvider, playback: PlaybackGateway, onNotice: (String) -> Unit) = Unit
    @androidx.compose.runtime.Composable
    override fun SingPanel(playback: PlaybackGateway, onDismiss: () -> Unit) = Unit
    @androidx.compose.runtime.Composable
    override fun SingStorageRow(onNotice: (String) -> Unit) = Unit
    @androidx.compose.runtime.Composable
    override fun SongBackgroundPanel(title: String, artist: String, onDismiss: () -> Unit) = Unit
    override fun onActivityCreate(activity: android.app.Activity) = clearPlusLaunchScreen(activity)
    override fun startupLook(context: Context) = StartupLook()
    override fun widgetAccent(context: Context): Int? = null
    override val appearance = false
    @androidx.compose.runtime.Composable
    override fun AppearanceEntry(model: ShellModel) = Unit
    @androidx.compose.runtime.Composable
    override fun AppearancePage(model: ShellModel, bottom: androidx.compose.ui.unit.Dp) = Unit
    override val queueOrder = false
    @androidx.compose.runtime.Composable
    override fun QueueOrderAction(playback: PlaybackGateway, glass: RiffBackdrop?, modifier: androidx.compose.ui.Modifier) = Unit
    override fun hasBeatModel(context: Context) = BeatModel.present(context)
    override fun beatAnalysis(context: Context) = BeatModel.get(context)
}
