package com.musicglass.personal

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.abs

/**
 * Feature 12, section 12.2: the Encore page. A glass period control, a stepper (or a swipe on the headline) through
 * earlier periods, then the headline, the chart, peak hours, the top lists, habits and the calendar. Everything is
 * read off the main thread from the daily totals; the page follows the signed-in account and the phone's own files.
 */
@Composable internal fun EncoreScreen(model: ShellModel, bottom: Dp, provider: YouTubeMusicProvider, playback: PlaybackGateway,
    onNotice: (String) -> Unit, process: AppContainer = AppContainer.get(LocalContext.current)) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision by process.history.revision.collectAsStateWithLifecycle()
    val accountKey = remember(provider.authRevision, provider.storageRevision) { provider.storageScopeKey() }
    val scopes = remember(accountKey) { listOfNotNull(accountKey.takeIf { it != UNVERIFIED_SCOPE_KEY }, DEVICE_LOCAL_SCOPE_KEY) }
    val today = remember(revision) { LocalDate.now() }
    val firstDay = remember { EncorePeriod.firstDayOfWeek() }
    var rangeIndex by rememberSaveable { mutableIntStateOf(EncoreRange.Week.ordinal) }
    var anchor by rememberSaveable { mutableStateOf(today.toString()) }
    var sort by rememberSaveable { mutableStateOf(EncoreSort.Minutes) }
    var genresOn by remember { mutableStateOf(encoreGenresOn(context)) }
    var goalHours by remember { mutableIntStateOf(encoreGoalHours(context)) }
    LaunchedEffect(Unit) { withContext(process.runtime.dispatchers.io) { EncoreRecap.schedule(context) } }
    var genresFound by remember { mutableIntStateOf(0) }
    var summary by remember { mutableStateOf<EncoreSummary?>(null) }
    var extras by remember { mutableStateOf<Pair<List<EncoreItem>, List<EncoreItem>>?>(null) }
    var story by remember { mutableStateOf<EncorePeriod?>(null) }
    var historyStart by remember { mutableStateOf<LocalDate?>(null) }
    val rowActions = remember(provider, playback, onNotice) { EncoreRowActions(provider, playback, onNotice) }
    var saving by remember { mutableStateOf<List<ProviderTrack>?>(null) }
    var csvFor by remember { mutableStateOf<EncoreSummary?>(null) }
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val chosen = csvFor; csvFor = null
        if (uri != null && chosen != null) scope.launch {
            val ok = withContext(process.runtime.dispatchers.io) {
                runCatching { context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(encoreCsv { chosen.top(it, EncoreSort.Minutes) }.toByteArray(Charsets.UTF_8)) } }.isSuccess
            }
            onNotice(string(if (ok) R.string.encore_csv_saved else R.string.encore_csv_failed))
        }
    }
    val range = EncoreRange.entries[rangeIndex]
    val period = remember(range, anchor, historyStart) { EncorePeriod.containing(range, LocalDate.parse(anchor), firstDay, historyStart) }
    LaunchedEffect(scopes, period, revision, genresOn, genresFound) {
        val read = withContext(process.runtime.dispatchers.io) {
            suspendCatching {
                process.stateReady.await()
                val start = process.encore.historyStart(scopes)
                Triple(start, process.encore.summary(scopes, EncorePeriod.containing(range, LocalDate.parse(anchor), firstDay, start), System.currentTimeMillis()),
                    process.encore.onThisDay(scopes, today) to process.encore.forgotten(scopes, today))
            }.getOrNull()
        } ?: return@LaunchedEffect
        historyStart = read.first; summary = read.second; extras = read.third
    }
    // The optional genre lookup (12.4): only with the setting on, on Wi-Fi, for the top artists of all time.
    LaunchedEffect(genresOn, scopes) {
        if (!genresOn) return@LaunchedEffect
        val found = withContext(process.runtime.dispatchers.io) {
            suspendCatching {
                val all = process.encore.top(scopes, EncorePeriod.containing(EncoreRange.All, today, firstDay, process.encore.historyStart(scopes)),
                    EncoreKind.ARTIST, EncoreSort.Minutes, ENCORE_GENRE_ARTISTS)
                process.encoreGenres.refresh(all.map { it.title })
            }.getOrDefault(0)
        }
        if (found > 0) genresFound += found
    }
    fun open(next: EncorePeriod) { rangeIndex = next.range.ordinal; anchor = next.start.toString() }
    fun step(forward: Boolean) {
        val next = if (forward) period.next(today) else period.previous()?.takeIf { p -> historyStart?.let { p.end.isAfter(it) } ?: false }
        if (next != null) { uiHaptic(HapticCue.Selection); anchor = next.start.toString() }
    }
    story?.let { shown -> EncoreStories(scopes, shown, process, onClose = { story = null }, onPeriod = { story = it }) }
    saving?.let { tracks ->
        SaveTracksPlaylistDialog(provider, string(R.string.encore_playlist_name, encorePeriodLabel(period, today)), tracks, onDismiss = { saving = null }) { name, count ->
            saving = null; onNotice(string(R.string.encore_saved_playlist, count, name))
        }
    }
    val current = summary
    ScreenList(model, bottom) {
        item(key = "back") { BackHeading(string(R.string.library), model::back) }
        item(key = "title") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { ScreenTitle(string(R.string.encore), string(R.string.encore_subtitle)) }
                if (current != null && !current.empty) StoryRing(current, seen = encoreStorySeen(context, current.period)) { story = current.period }
            }
        }
        item(key = "periods") {
            GlassSegmented(EncoreRange.entries.map(::encoreRangeLabel), rangeIndex, { rangeIndex = it; anchor = today.toString() }, "encore_range",
                onLongPress = { index -> story = EncorePeriod.containing(EncoreRange.entries[index], today, firstDay, historyStart) })
        }
        item(key = "stepper") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val canBack = range != EncoreRange.All && period.previous()?.let { p -> historyStart?.let { p.end.isAfter(it) } } == true
                IconButton({ step(false) }, enabled = canBack, modifier = Modifier.testTag("encore_previous")) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, string(R.string.encore_previous_period), tint = if (canBack) White else Faded)
                }
                Text(encorePeriodLabel(period, today), color = White, style = RiffText.rowTitle, modifier = Modifier.weight(1f).testTag("encore_period"),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                val canNext = period.next(today) != null
                IconButton({ step(true) }, enabled = canNext, modifier = Modifier.testTag("encore_next")) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, string(R.string.encore_next_period), tint = if (canNext) White else Faded)
                }
            }
        }
        if (!historyEnabled(context)) item(key = "off") {
            EncoreCard {
                Text(string(R.string.encore_history_off), color = White, fontSize = RiffType.body)
                TextButton({ uiHaptic(); model.push(Route.Settings(SettingsSection.PrivacyData)) }) { Text(string(R.string.encore_open_privacy), color = Pink) }
            }
        }
        when {
            current == null -> item(key = "loading") { ListSkeleton(string(R.string.encore_loading)) }
            current.empty -> item(key = "empty") {
                Box(Modifier.pointerInput(period) { swipeSteps(::step) }) { ContentEmpty(string(R.string.encore_empty)) }
            }
            else -> {
                item(key = "headline") { Box(Modifier.pointerInput(period) { swipeSteps(::step) }) { Headline(current, goalHours) } }
                item(key = "chart") { EncoreCard { SectionTitle(string(R.string.encore_over_time)); EncoreBarChart(current.bars, range, ::open) } }
                item(key = "peak") {
                    EncoreCard {
                        SectionTitle(string(R.string.encore_peak_hours))
                        EncoreClock(current.hours)
                        current.weekHours?.takeIf { range != EncoreRange.Day }?.let { EncoreWeekHeat(it, firstDay) }
                    }
                }
                item(key = "sort") {
                    GlassSegmented(listOf(string(R.string.encore_by_minutes), string(R.string.encore_by_plays)), sort.ordinal,
                        { sort = EncoreSort.entries[it] }, "encore_sort")
                }
                val lists = listOf(EncoreKind.SONG to R.string.encore_top_songs, EncoreKind.ALBUM to R.string.encore_top_albums,
                    EncoreKind.ARTIST to R.string.encore_top_artists, ENCORE_GENRE_KIND to R.string.encore_top_genres)
                lists.forEach { (kind, title) ->
                    val items = current.top(kind, sort)
                    if (items.isNotEmpty()) item(key = "top:$kind") {
                        TopList(string(title), items, compared = current.previousListenedMs != null, sort, kind, menu = rowActions) { item -> openItem(item, model, provider, playback, onNotice, scope) }
                    }
                }
                item(key = "habits") { Habits(current) }
                if (current.calendar.isNotEmpty()) item(key = "calendar") {
                    EncoreCard { SectionTitle(string(R.string.encore_calendar)); EncoreCalendar(current.calendar, period, firstDay, ::open) }
                }
                if (current.milestones.isNotEmpty()) item(key = "milestones") {
                    EncoreCard {
                        SectionTitle(string(R.string.encore_milestones))
                        current.milestones.forEach { Text(string(R.string.encore_milestone_line, encoreMilestoneName(it)), color = White, fontSize = RiffType.body) }
                    }
                }
                val top = current.top(EncoreKind.SONG, EncoreSort.Minutes).mapNotNull(EncoreItem::track).take(25)
                if (top.size > 1) item(key = "play_top") {
                    ToolLink(string(R.string.encore_play_top, top.size)) { playback.playCollection(top, 0, string(R.string.encore_play_top, top.size)) }
                }
                // Riff's own playlist create, so the account write is the one the owner starts here, never automatic.
                if (top.size > 1 && provider.capabilities.editPlaylists.available) item(key = "save_top") {
                    ToolLink(string(R.string.encore_save_playlist)) { uiHaptic(); saving = top }
                }
                if (current.top(EncoreKind.SONG, EncoreSort.Minutes).isNotEmpty()) item(key = "save_csv") {
                    ToolLink(string(R.string.encore_save_csv)) { csvFor = current; saveCsv.launch(encoreCsvName(current.period)) }
                }
            }
        }
        extras?.first?.takeIf { it.isNotEmpty() }?.let { onThisDay ->
            item(key = "on_this_day") {
                TopList(string(R.string.encore_on_this_day), onThisDay, compared = false, EncoreSort.Plays, EncoreKind.SONG,
                    detail = string(R.string.encore_on_this_day_detail), menu = rowActions) { openItem(it, model, provider, playback, onNotice, scope) }
            }
        }
        extras?.second?.takeIf { it.size >= 3 }?.let { forgotten ->
            item(key = "forgotten") {
                TopList(string(R.string.encore_forgotten), forgotten, compared = false, EncoreSort.Minutes, EncoreKind.SONG,
                    detail = string(R.string.encore_forgotten_detail), menu = rowActions) { openItem(it, model, provider, playback, onNotice, scope) }
            }
        }
        item(key = "goal_setting") { EncoreGoalSetting(goalHours) { uiHaptic(); goalHours = it; setEncoreGoalHours(context, it) } }
        item(key = "recap_setting") { EncoreRecapSetting() }
        item(key = "genres_setting") {
            EncoreCard {
                Row(Modifier.fillMaxWidth().clickable {
                    uiHaptic(); genresOn = !genresOn; setEncoreGenresOn(context, genresOn)
                    if (!genresOn) scope.launch { withContext(process.runtime.dispatchers.io) { process.encoreGenres.clear() } }
                }.testTag("encore_genres_toggle"), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(string(R.string.encore_find_genres), color = White, style = RiffText.body)
                        Text(string(R.string.encore_find_genres_detail), color = Muted, fontSize = RiffType.detail)
                    }
                    Spacer(Modifier.width(RiffSpace.item))
                    GlassSwitch(genresOn)
                }
            }
        }
        item(key = "footnote") {
            Text(string(R.string.encore_footnote), color = Muted, fontSize = RiffType.detail, modifier = Modifier.padding(vertical = RiffSpace.item))
        }
    }
}

private val Faded = White.copy(alpha = .25f)

/** What a long press on an Encore row needs to play a song next. */
private class EncoreRowActions(val provider: YouTubeMusicProvider, val playback: PlaybackGateway, val onNotice: (String) -> Unit)

/** A horizontal swipe steps through periods: right for earlier, left for later. */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.swipeSteps(step: (Boolean) -> Unit) {
    var travel = 0f
    detectHorizontalDragGestures(onDragStart = { travel = 0f }, onDragEnd = {
        if (abs(travel) > size.width / 5f) step(travel < 0)
    }) { _, amount -> travel += amount }
}

@Composable internal fun EncoreCard(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().riffGlass(GlassRole.Card, RiffRadius.hero, backdrop = LocalPageBackdrop.current).padding(RiffSpace.block),
        verticalArrangement = Arrangement.spacedBy(RiffSpace.item)) { content() }
}

@Composable internal fun SectionTitle(text: String) {
    Text(text, color = White, style = RiffText.sectionHeading, modifier = Modifier.semantics { heading() })
}

/** Listening time counting up, the counts, and the change against the previous period. */
@Composable private fun Headline(summary: EncoreSummary, goalHours: Int = 0) {
    val motion = LocalMotionPolicy.current
    val shown = remember(summary.period, summary.listenedMs) { Animatable(if (motion.reduced) summary.listenedMs.toFloat() else 0f) }
    LaunchedEffect(summary.period, summary.listenedMs) {
        if (!motion.reduced) shown.animateTo(summary.listenedMs.toFloat(), GlassMotion.timed(GlassMotion.ambientMs, motion))
    }
    EncoreCard {
        Text(string(R.string.encore_listening_time), color = Muted, fontSize = RiffType.body)
        Text(encoreDuration(shown.value.toLong()), color = White, fontSize = RiffEncore.headline, fontWeight = FontWeight.Bold,
            fontFamily = InterDisplayFamily, modifier = Modifier.testTag("encore_total").semantics { contentDescription = encoreDuration(summary.listenedMs) })
        Text(listOf(quantity(R.plurals.encore_n_plays, summary.plays), quantity(R.plurals.encore_n_songs, summary.songs),
            quantity(R.plurals.encore_n_artists, summary.artists), quantity(R.plurals.encore_n_albums, summary.albums)).joinToString(" · "),
            color = White, fontSize = RiffType.body)
        if (goalHours > 0 && summary.period.range == EncoreRange.Week) EncoreGoalLine(summary.listenedMs, goalHours)
        summary.previousListenedMs?.let { before ->
            val change = summary.listenedMs - before
            if (abs(change) >= 60_000L) Text(string(if (change > 0) R.string.encore_change_up else R.string.encore_change_down,
                encoreDuration(change), encorePreviousName(summary.period.range)),
                color = if (change > 0) Positive else Muted, fontSize = RiffType.body, modifier = Modifier.testTag("encore_change"))
        }
        if (summary.estimated) Text(string(R.string.encore_estimated), color = Muted, fontSize = RiffType.detail)
        summary.personality?.let {
            Spacer(Modifier.heightIn(min = RiffSpace.tight))
            Text(string(R.string.encore_personality_line, encorePersonalityName(it)), color = White, style = RiffText.rowTitle)
            Text(encorePersonalityRule(it), color = Muted, fontSize = RiffType.detail)
        }
    }
}

private val Positive = androidx.compose.ui.graphics.Color(0xFF5BD68B)

/** A top list: five rows, "See all" for up to a hundred, each with its rank movement. */
@OptIn(ExperimentalFoundationApi::class)
@Composable private fun TopList(title: String, items: List<EncoreItem>, compared: Boolean, sort: EncoreSort, kind: String,
    detail: String? = null, menu: EncoreRowActions? = null, onOpen: (EncoreItem) -> Unit) {
    var all by rememberSaveable(title) { mutableStateOf(false) }
    EncoreCard {
        SectionTitle(title)
        detail?.let { Text(it, color = Muted, fontSize = RiffType.detail) }
        items.take(if (all) ENCORE_TOP_LIMIT else 5).forEachIndexed { index, item ->
            var held by remember(item.key) { mutableStateOf(false) }
            menu?.let { EncoreRowMenu(item, held, { held = false }, onOpen, it.provider, it.playback, it.onNotice) }
            Row(Modifier.fillMaxWidth().heightIn(min = RiffSpace.listRow).clip(RoundedCornerShape(RiffRadius.artwork))
                .combinedClickable(onClick = { uiHaptic(); onOpen(item) }, onLongClick = menu?.let { { uiHaptic(HapticCue.Threshold); held = true } },
                    onLongClickLabel = menu?.let { string(R.string.song_options) })
                .testTag("encore_${kind}_$index"), verticalAlignment = Alignment.CenterVertically) {
                Text((index + 1).toString(), color = Muted, fontSize = RiffEncore.rank, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.width(RiffEncore.rankArt / 2))
                if (kind != ENCORE_GENRE_KIND) CatalogArtwork(item.artwork, item.key, modifier = Modifier.size(RiffEncore.rankArt)
                    .clip(if (kind == EncoreKind.ARTIST) CircleShape else RoundedCornerShape(RiffRadius.artwork)).background(White.copy(alpha = .08f)))
                Column(Modifier.weight(1f).padding(start = RiffSpace.rowGap)) {
                    Text(item.title, color = White, fontSize = RiffType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val value = if (sort == EncoreSort.Plays) quantity(R.plurals.encore_n_plays, item.plays) else encoreDuration(item.listenedMs)
                    Text(if (item.subtitle.isBlank()) value else string(R.string.encore_value_line, item.subtitle, value), color = Muted,
                        fontSize = RiffType.detail, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                encoreMovement(item, compared)?.let { mark ->
                    Text(mark, color = if (mark.startsWith("▲") || !item.wasRanked) Pink else Muted, fontSize = RiffType.detail, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (items.size > 5) TextButton({ uiHaptic(); all = !all }, modifier = Modifier.testTag("encore_${kind}_all")) {
            Text(string(if (all) R.string.encore_show_fewer else R.string.see_all), color = Pink)
        }
        if (kind == EncoreKind.ARTIST) Text(string(R.string.encore_artists_footnote), color = Muted, fontSize = RiffType.detail)
    }
}

/** Streak, longest session, discovery, completion, most repeated and where the music came from. */
@Composable private fun Habits(summary: EncoreSummary) {
    val habits = summary.habits
    EncoreCard {
        SectionTitle(string(R.string.encore_habits))
        HabitLine(string(R.string.encore_streak), quantity(R.plurals.encore_n_days, habits.streakDays))
        if (habits.longestSessionPlays > 0) HabitLine(string(R.string.encore_longest_session),
            string(R.string.encore_value_line, quantity(R.plurals.encore_n_songs, habits.longestSessionPlays), encoreDuration(habits.longestSessionMs)))
        habits.discovery?.let { HabitLine(string(R.string.encore_discovery), encorePercent(it)) }
        habits.completion?.let { HabitLine(string(R.string.encore_completion), encorePercent(it)) }
        habits.mostRepeatedTitle?.let { HabitLine(string(R.string.encore_most_repeated), string(R.string.encore_repeated_value, it, habits.mostRepeatedPlays)) }
        val sources = habits.sources.entries.sortedByDescending { it.value }
        if (sources.size > 1) HabitLine(string(R.string.encore_sources), sources.joinToString(" · ") { encoreSourceName(it.key) + " " + it.value })
    }
}

@Composable internal fun HabitLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Muted, fontSize = RiffType.body, modifier = Modifier.weight(1f))
        Text(value, color = White, fontSize = RiffType.body, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.4f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

/** The ring around the top song's cover that opens the period's story; coloured until the story has been watched. */
@Composable private fun StoryRing(summary: EncoreSummary, seen: Boolean, onOpen: () -> Unit) {
    val cover = summary.top(EncoreKind.SONG).firstOrNull()
    val label = string(R.string.encore_watch)
    Box(Modifier.size(RiffEncore.ring).clip(CircleShape).clickable { uiHaptic(); onOpen() }.testTag("encore_story_ring")
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(RiffEncore.ring)) {
            val stroke = RiffEncore.ringStroke.toPx()
            drawCircle(if (seen) androidx.compose.ui.graphics.SolidColor(White.copy(alpha = .3f)) else Brush.sweepGradient(listOf(Pink, AccentWarm, Pink)),
                radius = size.minDimension / 2 - stroke / 2, style = Stroke(stroke))
        }
        CatalogArtwork(cover?.artwork, cover?.key ?: "encore", modifier = Modifier.size(RiffEncore.ring - RiffEncore.ringGap * 3).clip(CircleShape))
    }
}

internal val AccentWarm = androidx.compose.ui.graphics.Color(0xFFFFB86B)

/** A row opens what it names: a song plays, an album or an artist opens its page when Riff knows its id. */
private fun openItem(item: EncoreItem, model: ShellModel, provider: YouTubeMusicProvider, playback: PlaybackGateway, onNotice: (String) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope) {
    when (item.kind) {
        EncoreKind.SONG -> item.track()?.let { track -> scope.launch { playRealTrack(provider, playback, track, onNotice) } }
            ?: onNotice(string(R.string.encore_open_on_phone))
        EncoreKind.ALBUM -> Route.collection(item.key.takeUnless { it.startsWith("name:") }, item.title)?.let(model::push)
        EncoreKind.ARTIST -> Route.collection(item.key.takeIf { it.startsWith("id:") }?.removePrefix("id:"), item.title)?.let(model::push)
    }
}

/**
 * Feature 12.6: the Encore card at the top of Library: this week's time and the story ring. As Home's Sunday row
 * ([sunday]) it names the week that is ending and stays away until that week has some listening.
 */
@Composable internal fun EncoreLibraryCard(model: ShellModel, provider: YouTubeMusicProvider, sunday: Boolean = false,
    process: AppContainer = AppContainer.get(LocalContext.current)) {
    val revision by process.history.revision.collectAsStateWithLifecycle()
    val accountKey = remember(provider.authRevision, provider.storageRevision) { provider.storageScopeKey() }
    var week by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(accountKey, revision) {
        val scopes = listOfNotNull(accountKey.takeIf { it != UNVERIFIED_SCOPE_KEY }, DEVICE_LOCAL_SCOPE_KEY)
        week = withContext(process.runtime.dispatchers.io) {
            suspendCatching {
                process.stateReady.await()
                process.encore.quick(scopes, EncorePeriod.containing(EncoreRange.Week, if (sunday) encoreRecapDay(LocalDate.now()) else LocalDate.now(), EncorePeriod.firstDayOfWeek(), null)).first
            }.getOrNull()
        }
    }
    if (sunday && (week ?: 0L) <= 0L) return
    Row(Modifier.fillMaxWidth().riffGlass(GlassRole.Card, RiffRadius.card, backdrop = LocalPageBackdrop.current).clip(RoundedCornerShape(RiffRadius.card))
        .clickable { uiHaptic(); model.push(Route.Encore) }.padding(RiffSpace.block).testTag(if (sunday) "home_encore" else "library_encore"),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(string(if (sunday) R.string.encore_your_week else R.string.encore), color = White, style = RiffText.rowTitle)
            Text(week?.let { string(R.string.encore_this_week_value, encoreDuration(it)) } ?: string(R.string.encore_subtitle), color = Muted, fontSize = RiffType.detail)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Muted)
    }
}
