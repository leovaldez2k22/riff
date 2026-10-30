package com.musicglass.personal

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.DataSaverOn
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The sections' own colours, after the mock-ups the owner chose (30 Oct). Pink stays Riff's. */
internal val Lavender = Color(0xFFAFA9EC)
internal val Mint = Color(0xFF5DCAA5)
internal val Sky = Color(0xFF85B7EB)
internal val Coral = Color(0xFFF0997B)
internal val Stone = Color(0xFFD3D1C7)
/** Words and icons on a solid pink tile. */
internal val PinkInk = Color(0xFF4B1528)

/** A settings page: back to Settings, the page's title, then its tiles. */
@Composable internal fun SettingsPage(model: ShellModel, bottom: Dp, title: String, content: LazyListScope.() -> Unit) {
    ScreenList(model, bottom) {
        item { BackHeading(string(R.string.settings), model::back); ScreenTitle(title) }
        content()
    }
}

/** The page for one of the opening page's tiles. */
@Composable internal fun SettingsSectionScreen(model: ShellModel, section: SettingsSection, bottom: Dp, onNotice: (String) -> Unit) {
    when (section) {
        SettingsSection.Sound -> SoundSection(model, bottom)
        SettingsSection.Look -> LookSection(model, bottom)
        SettingsSection.Lyrics -> LyricsSection(model, bottom)
        SettingsSection.Downloads -> DownloadsSection(model, bottom, onNotice)
        SettingsSection.Library -> LibrarySection(model, bottom, onNotice)
        SettingsSection.About -> AboutSection(model, bottom, onNotice)
        SettingsSection.Moods -> MoodsSection(model, bottom)
        else -> Unit
    }
}

/** Downloads and data: what is kept as a ring, Data saver, the quality per network (the tab bar's pill), the rest. */
@Composable internal fun DownloadsSection(model: ShellModel, bottom: Dp, onNotice: (String) -> Unit) {
    val context = LocalContext.current
    val dataSaver = remember(context) { DataSaverSettings(context) }
    SettingsPage(model, bottom, string(R.string.settings_downloads_and_data)) {
        bentoRows(
            {
                BentoBeside(
                    tall = { m -> StorageTile(m) { model.push(Route.Downloads) } },
                    top = { m -> BentoToggle(string(R.string.data_saver), dataSaver.enabled, dataSaver::updateEnabled,
                        Icons.Rounded.DataSaverOn, "settings_data_saver", m, string(R.string.lower_bitrate_audio_and_up_to_360p)) },
                    bottom = { m ->
                        // Feature 13: Android opens these links in Riff only once allowed here (Riff cannot verify YouTube's domains).
                        BentoLink(string(R.string.open_links_in_riff), Icons.Rounded.Link, "settings_open_links", m, tint = Sky) {
                            runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
                                android.net.Uri.parse("package:${context.packageName}"))) }
                        }
                    })
            },
            { SongQualityTile(dataSaver) },
            { BentoTile(Modifier.fillMaxWidth()) { DownloadSettings(model, onNotice) } })
    }
}

/** The songs kept on this phone, as a ring against the storage budget. */
@Composable private fun StorageTile(modifier: Modifier, onOpen: () -> Unit) {
    val context = LocalContext.current
    val motion = LocalMotionPolicy.current
    val store = remember(context) { OfflineMusicStore.get(context) }
    val revision by store.revision.collectAsStateWithLifecycle()
    val kept by produceState(0L to 0, revision) {
        value = withContext(Dispatchers.IO) {
            val done = store.rows().filter { it.status == DownloadState.Complete }
            done.sumOf { it.bytes } to done.size
        }
    }
    val budget = remember(revision) { store.budgetBytes }
    val share by animateFloatAsState(if (budget > 0) (kept.first.toFloat() / budget).coerceIn(0f, 1f) else 0f,
        GlassMotion.settle(motion), label = "storage ring")
    BentoTile(modifier, onClick = onOpen, tag = "settings_storage") {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(RiffSettings.ring)) {
                val stroke = RiffSettings.dialStroke.toPx()
                val box = Size(size.width - stroke, size.height - stroke)
                val corner = Offset(stroke / 2f, stroke / 2f)
                drawArc(White.copy(alpha = .1f), -90f, 360f, false, corner, box, style = Stroke(stroke))
                if (share > 0f) drawArc(Sky, -90f, 360f * share.coerceAtLeast(.02f), false, corner, box, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(storageLabel(kept.first), color = White, fontSize = RiffSettings.title, fontWeight = FontWeight.SemiBold)
                Text(quantity(R.plurals.n_songs, kept.second), color = Muted, fontSize = RiffType.detail)
            }
        }
        BentoTitle(string(R.string.settings_kept_on_phone))
    }
}

/** Q18-099 and Feature 17: High, Medium or Low, for Wi-Fi and for mobile data; Data saver wins while it is on. */
@Composable private fun SongQualityTile(dataSaver: DataSaverSettings) {
    BentoTile(Modifier.fillMaxWidth().testTag("settings_song_quality")) {
        BentoTitle(string(R.string.song_quality))
        for (metered in listOf(false, true)) {
            Text(string(if (metered) R.string.on_mobile_data else R.string.on_wifi), color = Muted, fontSize = RiffType.detail,
                modifier = Modifier.padding(top = RiffSpace.item, bottom = RiffSpace.tight))
            val chosen = if (metered) dataSaver.meteredQuality else dataSaver.unmeteredQuality
            GlassSegmented(SongQuality.entries.map(::songQualityLabel), SongQuality.entries.indexOf(chosen),
                { dataSaver.updateNetworkQuality(metered, SongQuality.entries[it]) }, if (metered) "settings_song_quality_mobile" else "settings_song_quality",
                tags = SongQuality.entries.map { if (metered) "settings_song_quality_mobile_${it.key}" else "settings_song_quality_${it.key}" })
        }
        Text(string(if (dataSaver.enabled) R.string.song_quality_data_saver_on else R.string.song_quality_note),
            color = Muted, fontSize = RiffType.detail, modifier = Modifier.padding(top = RiffSpace.item).testTag("settings_song_quality_note"))
    }
}

/** Library and privacy: what Riff keeps and what it leaves out, and the widgets. */
@Composable internal fun LibrarySection(model: ShellModel, bottom: Dp, onNotice: (String) -> Unit) {
    val context = LocalContext.current
    val hidden = remember(context) { NotInterestedStore.get(context) }
    SettingsPage(model, bottom, string(R.string.settings_library_and_privacy)) {
        bentoRows(
            {
                BentoPair(
                    { m -> BentoTile(m, onClick = { model.push(Route.Settings(SettingsSection.PrivacyData)) }, tag = "settings_privacy_data", lit = true, tint = Coral) {
                        SettingIcon(Icons.Rounded.Lock, true, tint = Coral)
                        Spacer(Modifier.weight(1f).height(RiffSettings.tileGap))
                        BentoTitle(string(R.string.privacy_and_data))
                        BentoHelp(string(R.string.settings_privacy_help))
                    } },
                    { m -> BentoTile(m, onClick = { model.push(Route.Settings(SettingsSection.NotInterested)) }, tag = "settings_not_interested") {
                        SettingIcon(Icons.Rounded.Block, true, tint = Coral)
                        Spacer(Modifier.weight(1f).height(RiffSettings.tileGap))
                        Text("${hidden.entries.size}", color = White, fontSize = RiffSettings.big, fontWeight = FontWeight.SemiBold)
                        BentoTitle(string(R.string.not_interested))
                    } })
            },
            { BentoHeading(string(R.string.widgets)) },
            { BentoTile(Modifier.fillMaxWidth()) { WidgetSettings(onNotice) } })
    }
}

/** About: the version large, then updates, what's new and diagnostics. */
@Composable internal fun AboutSection(model: ShellModel, bottom: Dp, onNotice: (String) -> Unit) {
    val context = LocalContext.current
    val updates = remember(context) { AppContainer.get(context).updates }
    val scope = rememberCoroutineScope()
    SettingsPage(model, bottom, string(R.string.about)) {
        bentoRows(
            {
                BentoTile(Modifier.fillMaxWidth(), lit = true, tint = Stone) {
                    Text(string(R.string.settings_riff_version, BuildConfig.VERSION_NAME), color = White, fontSize = RiffSettings.big, fontWeight = FontWeight.SemiBold)
                    Text(androidx.compose.ui.res.stringResource(R.string.about_build_identity, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE,
                        BuildIdentity.current().shortId), color = Muted, fontSize = RiffType.detail)
                }
            },
            {
                // Q12A: shown only in a build that has a release feed. Off sends nothing, the manual check included.
                val toggle: @Composable (Modifier) -> Unit = { m -> BentoToggle(string(R.string.check_for_updates), updates.enabled, updates::updateEnabled,
                    Icons.Rounded.Update, "settings_check_updates", m, string(R.string.check_for_updates_detail, updates.feedHost.orEmpty())) }
                if (updates.configured && !updates.enabled) toggle(Modifier.fillMaxWidth())
                else if (updates.configured) BentoPair(toggle,
                    { m ->
                        val newer = updates.available
                        BentoLink(newer?.let { string(R.string.riff_version_is_available, it.version.toString()) } ?: string(R.string.check_for_updates_now),
                            Icons.Rounded.Update, "settings_update_row", m) {
                            if (newer != null) openWebPage(context, newer.page, updates.pageHosts)
                            else scope.launch { updateResultText(updates.checkNow())?.let(onNotice) }
                        }
                    })
            },
            {
                BentoPair(
                    // Feature 29: the release's sheet again, or the tips when this release has none.
                    { m -> BentoLink(string(R.string.whats_new_and_tips), Icons.Rounded.NewReleases, "settings_whats_new", m) {
                        whatsNewShowing = if (loadWhatsNew(context)?.cards.isNullOrEmpty()) WhatsNewShow.Tips else WhatsNewShow.Release
                    } },
                    // Q15A: last exits, errors and the reports are on their own screen.
                    { m -> BentoLink(string(R.string.diagnostics), Icons.Rounded.BugReport, "settings_diagnostics", m, tint = Stone) {
                        model.push(Route.Settings(SettingsSection.Diagnostics))
                    } })
            })
    }
}
