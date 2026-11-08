package com.musicglass.personal

import android.content.Context

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal object Edition : EditionFeatures {
    override val plus = true
    override val statsCommand = GET_NERD_STATS
    override val statsUi: EditionStatsUi = PlusStatsUi
    override fun createStats(): EditionPlaybackStats = NerdStatsCollector()
    override val blendModes = BlendMode.entries.toList()
    override fun audioEffects(context: Context): EditionAudio = PlusAudioEffects(context)
    @androidx.compose.runtime.Composable
    override fun EqualizerEntry(model: ShellModel, modifier: androidx.compose.ui.Modifier) = PlusEqualizerEntry(model, modifier)
    @androidx.compose.runtime.Composable
    override fun EqualizerPage(model: ShellModel, bottom: androidx.compose.ui.unit.Dp, playback: PlaybackGateway?) = EqualizerScreen(model, bottom, playback)
    @androidx.compose.runtime.Composable
    override fun ReplayPeakSeek(playback: PlaybackGateway, mediaId: String, durationMs: Long, slider: @androidx.compose.runtime.Composable () -> Unit) =
        ReplayPeakBar(playback, mediaId, durationMs, slider = slider)
    @androidx.compose.runtime.Composable
    override fun downloadExport(track: ProviderTrack, scope: String, durationMs: Long, onNotice: (String) -> Unit): (() -> Unit)? =
        rememberM4aExport(track, scope, durationMs, onNotice)
    override fun visualizerShown(context: Context): Boolean? { VisualizerSettings.revision; return VisualizerSettings.shown(context) }
    override fun showVisualizer(context: Context, on: Boolean) = VisualizerSettings.setShown(context, on)
    @androidx.compose.runtime.Composable
    override fun Visualizer(playback: PlaybackGateway) = VisualizerStrip(playback)
    @androidx.compose.runtime.Composable
    override fun PitchControl(playback: PlaybackGateway) = PitchSteps(playback)
    @androidx.compose.runtime.Composable
    override fun HomeMixes(provider: YouTubeMusicProvider, playback: PlaybackGateway) = EncoreMixShelf(provider, playback)
    override fun onActivityCreate(activity: android.app.Activity) = Appearance.onActivityCreate(activity)
    override fun startupLook(context: Context) = Appearance.choice(context).startupLook()
    override fun widgetAccent(context: Context): Int? = Appearance.widgetAccent(context)
    override val appearance = true
    @androidx.compose.runtime.Composable
    override fun AppearanceEntry(model: ShellModel) = AppearanceTile(model)
    @androidx.compose.runtime.Composable
    override fun AppearancePage(model: ShellModel, bottom: androidx.compose.ui.unit.Dp) = AppearanceScreen(model, bottom)
    override val queueOrder = true
    @androidx.compose.runtime.Composable
    override fun QueueOrderAction(playback: PlaybackGateway, glass: RiffBackdrop?, modifier: androidx.compose.ui.Modifier) = DjOrderAction(playback, glass, modifier)
    override val songBackground = true
    override val sing = true
    @androidx.compose.runtime.Composable
    override fun BlendWithFriend(provider: YouTubeMusicProvider, playback: PlaybackGateway, onNotice: (String) -> Unit) =
        com.musicglass.personal.BlendEntry(provider, playback, onNotice)
    @androidx.compose.runtime.Composable
    override fun SingPanel(playback: PlaybackGateway, onDismiss: () -> Unit) = SingDialog(playback, onDismiss)
    @androidx.compose.runtime.Composable
    override fun SingStorageRow(onNotice: (String) -> Unit) = SingStorageSettings(onNotice)
    @androidx.compose.runtime.Composable
    override fun SongBackgroundPanel(title: String, artist: String, onDismiss: () -> Unit) = SongBackgroundDialog(title, artist, onDismiss)
    override fun hasBeatModel(context: Context) = BeatModel.present(context)
    override fun beatAnalysis(context: Context): BeatAnalysis? = BeatModel.get(context)?.let { model ->
        BeatAnalysis { mono, rate, startMs -> modelBeats(mono, rate, startMs, model) }
    }
}
