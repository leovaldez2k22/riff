package com.musicglass.personal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Feature 12, "Your history with this" (12.2 extras, `EncoreDetail.kt` in 12.6): one song, album or artist across all
 * of Encore. When it was first played, its plays and time in all, its rank among its kind this year, and its listening
 * over time. Opened from a song's menu, an album or artist card's menu, and a long press on an Encore row.
 */
@Composable internal fun EncoreDetailScreen(model: ShellModel, bottom: Dp, route: Route.EncoreDetail, provider: YouTubeMusicProvider,
    playback: PlaybackGateway, onNotice: (String) -> Unit, process: AppContainer = AppContainer.get(LocalContext.current)) {
    val scope = rememberCoroutineScope()
    val revision by process.history.revision.collectAsStateWithLifecycle()
    val accountKey = remember(provider.authRevision, provider.storageRevision) { provider.storageScopeKey() }
    var history by remember(route) { mutableStateOf<EncoreHistory?>(null) }
    var loaded by remember(route) { mutableStateOf(false) }
    LaunchedEffect(route, accountKey, revision) {
        val scopes = listOfNotNull(accountKey.takeIf { it != UNVERIFIED_SCOPE_KEY }, DEVICE_LOCAL_SCOPE_KEY)
        history = withContext(process.runtime.dispatchers.io) {
            suspendCatching { process.stateReady.await(); process.encore.history(scopes, route.kind, route.key, LocalDate.now()) }.getOrNull()
        }
        loaded = true
    }
    val shown = history
    ScreenList(model, bottom) {
        item(key = "back") { BackHeading(string(R.string.back), model::back) }
        item(key = "title") { ScreenTitle(shown?.title ?: route.title, string(R.string.encore_your_history)) }
        when {
            !loaded -> item(key = "loading") { ListSkeleton(string(R.string.encore_loading), rows = 3) }
            shown == null -> item(key = "none") { ContentEmpty(string(R.string.encore_history_none)) }
            else -> {
                item(key = "numbers") {
                    EncoreCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CatalogArtwork(shown.artwork, route.key, modifier = Modifier.size(RiffEncore.rankArt)
                                .clip(if (route.kind == EncoreKind.ARTIST) CircleShape else RoundedCornerShape(RiffRadius.artwork)).background(White.copy(alpha = .08f)))
                            Text(shown.subtitle.ifBlank { shown.title }, color = Muted, fontSize = RiffType.body, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(start = RiffSpace.rowGap))
                        }
                        HabitLine(string(R.string.encore_history_first), shown.firstDay.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
                        HabitLine(string(R.string.encore_history_plays), shown.plays.toString())
                        HabitLine(string(R.string.encore_history_time), encoreDuration(shown.listenedMs))
                        shown.rankThisYear?.let { HabitLine(string(R.string.encore_history_rank), string(R.string.encore_history_rank_value, it)) }
                    }
                }
                if (shown.bars.any { it.listenedMs > 0 }) item(key = "chart") {
                    // Each bar is a month (a year, past two years); there is no period page for one song to open.
                    EncoreCard { SectionTitle(string(R.string.encore_over_time)); EncoreBarChart(shown.bars, EncoreRange.All) { } }
                }
                val track = EncoreItem(route.kind, route.key, shown.title, shown.subtitle, shown.artwork, shown.plays, shown.listenedMs).track()
                if (track != null) item(key = "play") {
                    ToolLink(string(R.string.play)) { scope.launch { playRealTrack(provider, playback, track, onNotice) } }
                }
            }
        }
    }
}

/** The menu item that opens [kind]'s Encore history; shown only inside the shell, and only for a key Encore keeps. */
@Composable internal fun EncoreHistoryAction(kind: String, key: String?, title: String, onDismiss: () -> Unit) {
    val model = LocalShellModel.current ?: return
    val route = Route.encoreDetail(kind, key, title) ?: return
    // Like "View album": the player closes, or the page opens behind it.
    SongActionItem("Your history with this") { onDismiss(); model.sheet = ""; model.push(route) }
}

/** A song's Encore key: the media id its plays are recorded under. */
internal fun encoreSongKey(videoId: String) = "yt:$videoId"
/** An artist's Encore key, from their channel id; Encore keys the first credited artist this way when it is known. */
internal fun encoreArtistKey(channelId: String) = "id:$channelId"

/**
 * A long press on an Encore row: play it (or open it), play a song next, and "Your history with this". Inside the
 * shell only; a row without one opens as a tap does.
 */
@Composable internal fun EncoreRowMenu(item: EncoreItem, expanded: Boolean, onDismiss: () -> Unit, onOpen: (EncoreItem) -> Unit,
    provider: YouTubeMusicProvider?, playback: PlaybackGateway?, onNotice: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    SongActionMenu(expanded, onDismiss, Modifier.testTag("encore_row_menu")) {
        val track = item.track()
        SongActionItem(if (track != null) "Play" else "Open") { onDismiss(); onOpen(item) }
        if (track != null && provider != null && playback != null) SongActionItem("Play next") {
            onDismiss(); scope.launch { enqueueRealTrack(provider, playback, ProviderHomeCard(item.title, item.subtitle, item.artwork, videoId = track.videoId), true, onNotice) }
        }
        if (item.kind != ENCORE_GENRE_KIND) EncoreHistoryAction(item.kind, item.key, item.title, onDismiss)
    }
}
