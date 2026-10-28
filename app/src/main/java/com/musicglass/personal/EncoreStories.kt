package com.musicglass.personal

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Feature 12, section 12.5: Encore's stories, full screen, one per period. Segmented bars at the top, about 6 s a slide;
 * tap the right third for the next slide and the left third for the previous; hold to pause; swipe down to close and
 * sideways for the period before or after. Under reduced motion slides don't advance by themselves. Each slide can be
 * shared as a 1080 × 1920 picture.
 */
private const val SLIDE_MS = 6_000L

internal enum class EncoreSlide { Intro, Time, TopSong, TopFive, TopArtist, TopAlbums, Genres, GoldenHour, Habits, Milestone, Summary }

/** The slides a period has: each is left out when it has nothing to show. */
internal fun encoreSlides(summary: EncoreSummary): List<EncoreSlide> = buildList {
    add(EncoreSlide.Intro); add(EncoreSlide.Time)
    if (summary.top(EncoreKind.SONG).isNotEmpty()) add(EncoreSlide.TopSong)
    if (summary.top(EncoreKind.SONG).size >= 3) add(EncoreSlide.TopFive)
    if (summary.top(EncoreKind.ARTIST).isNotEmpty()) add(EncoreSlide.TopArtist)
    if (summary.top(EncoreKind.ALBUM).size >= 2) add(EncoreSlide.TopAlbums)
    if (summary.top(ENCORE_GENRE_KIND).isNotEmpty()) add(EncoreSlide.Genres)
    if (summary.goldenHour != null) add(EncoreSlide.GoldenHour)
    add(EncoreSlide.Habits)
    if (summary.milestones.isNotEmpty()) add(EncoreSlide.Milestone)
    add(EncoreSlide.Summary)
}

/** Whether a period's story has been watched, for the ring's colour. */
internal fun encoreStorySeen(context: Context, period: EncorePeriod) = context.applicationContext
    .getSharedPreferences("music_collections", Context.MODE_PRIVATE).getBoolean("encore_seen_${period.range}_${period.start}", false)

private fun markStorySeen(context: Context, period: EncorePeriod) {
    context.applicationContext.getSharedPreferences("music_collections", Context.MODE_PRIVATE).edit()
        .putBoolean("encore_seen_${period.range}_${period.start}", true).apply()
}

@Composable internal fun EncoreStories(scopes: List<String>, period: EncorePeriod, process: AppContainer, onClose: () -> Unit, onPeriod: (EncorePeriod) -> Unit) {
    val context = LocalContext.current
    val motion = LocalMotionPolicy.current
    val scope = rememberCoroutineScope()
    var summary by remember(period) { mutableStateOf<EncoreSummary?>(null) }
    LaunchedEffect(period, scopes) {
        summary = withContext(process.runtime.dispatchers.io) { suspendCatching { process.encore.summary(scopes, period, System.currentTimeMillis()) }.getOrNull() }
        markStorySeen(context, period)
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val current = summary
        Box(Modifier.fillMaxSize().background(Color.Black).testTag("encore_stories")) {
            if (current == null) return@Box
            val slides = remember(current) { encoreSlides(current) }
            var index by remember(current) { mutableIntStateOf(0) }
            var held by remember { mutableStateOf(false) }
            var capturing by remember { mutableStateOf(false) }
            val progress = remember(current, index) { Animatable(0f) }
            val today = remember { java.time.LocalDate.now() }
            LaunchedEffect(current, index, held) {
                if (motion.reduced || held) return@LaunchedEffect
                val left = ((1f - progress.value) * SLIDE_MS).toLong()
                // motion: music-timed. The story's clock, not a transition: it runs at real time whatever the pace.
                progress.animateTo(1f, androidx.compose.animation.core.tween(left.toInt(), easing = androidx.compose.animation.core.LinearEasing))
                if (index < slides.lastIndex) index++ else onClose()
            }
            val layer = rememberGraphicsLayer()
            fun share() {
                scope.launch {
                    held = true; capturing = true
                    withFrameNanos { }; withFrameNanos { }
                    val picture = runCatching { encoreCard(layer.toImageBitmap().asAndroidBitmap()) }.getOrNull()
                    capturing = false; held = false
                    if (picture == null || !shareEncoreCard(context, picture)) uiHaptic(HapticCue.Reject)
                }
            }
            Box(Modifier.fillMaxSize().drawWithContent { if (capturing) layer.record { this@drawWithContent.drawContent() }; drawContent() }
                .background(storyGradient(slides[index]))) {
                AnimatedContent(index, transitionSpec = { fadeIn(GlassMotion.timed(GlassMotion.selectionMs, motion)) togetherWith fadeOut(GlassMotion.exit(GlassMotion.selectionMs, motion)) },
                    label = "encore slide") { shown ->
                    Box(Modifier.fillMaxSize().padding(WindowInsets.safeDrawing.asPaddingValues()).padding(RiffSpace.screenEdge), contentAlignment = Alignment.Center) {
                        Slide(slides[shown], current, today, motion.reduced)
                    }
                }
            }
            // Taps, holds and swipes, over the slide and under the bars and buttons.
            Box(Modifier.fillMaxSize().pointerInput(current) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val start = down.position; val startTime = down.uptimeMillis
                    held = true
                    val up = waitForUpOrCancellation()
                    held = false
                    val end = up?.position ?: return@awaitEachGesture
                    val dx = end.x - start.x; val dy = end.y - start.y
                    when {
                        dy > size.height / 6f && abs(dy) > abs(dx) -> onClose()
                        abs(dx) > size.width / 4f -> (if (dx < 0) period.next(today) else period.previous())?.let { uiHaptic(); onPeriod(it) }
                        up.uptimeMillis - startTime > 400 -> Unit
                        start.x < size.width / 3f -> if (index > 0) { index--; uiHaptic(HapticCue.Selection) }
                        else -> if (index < slides.lastIndex) { index++; uiHaptic(HapticCue.Selection) } else onClose()
                    }
                }
            })
            // A dialog window may get no insets at all; the bars then still clear a status bar.
            val top = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding().coerceAtLeast(RiffSpace.touch)
            Column(Modifier.fillMaxWidth().padding(top = top).padding(horizontal = RiffSpace.item)) {
                Row(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite; contentDescription = string(R.string.encore_slide_n, index + 1, slides.size) },
                    horizontalArrangement = Arrangement.spacedBy(RiffEncore.storyBarGap)) {
                    slides.indices.forEach { i ->
                        val fill = when { i < index -> 1f; i > index -> 0f; motion.reduced -> 1f; else -> progress.value }
                        Canvas(Modifier.weight(1f).height(RiffEncore.storyBar)) {
                            drawRoundRect(White.copy(alpha = .3f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
                            drawRoundRect(White, size = size.copy(width = size.width * fill), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(encorePeriodLabel(period, today), color = White, fontSize = RiffType.body, modifier = Modifier.weight(1f))
                    IconButton(::share, modifier = Modifier.testTag("encore_story_share")) { Icon(Icons.Rounded.Share, string(R.string.encore_share_slide), tint = White) }
                    IconButton(onClose, modifier = Modifier.testTag("encore_story_close")) { Icon(Icons.Rounded.Close, string(R.string.close), tint = White) }
                }
            }
        }
    }
}

/** A slow gradient per slide, Riff's pink into the night. */
private fun storyGradient(slide: EncoreSlide): Brush {
    val tints = listOf(Color(0xFF3B1530), Color(0xFF1B2440), Color(0xFF2A1A3E), Color(0xFF103A35), Color(0xFF3A2414))
    val tint = tints[slide.ordinal % tints.size]
    return Brush.verticalGradient(listOf(tint, Color(0xFF07070B)))
}

@Composable private fun Slide(slide: EncoreSlide, summary: EncoreSummary, today: java.time.LocalDate, still: Boolean) {
    val motion = LocalMotionPolicy.current
    val rise = remember(slide) { Animatable(if (still) 0f else 1f) }
    LaunchedEffect(slide) { if (!still) rise.animateTo(0f, GlassMotion.timed(GlassMotion.artworkMs, motion)) }
    val lift = Modifier.offset { IntOffset(0, (rise.value * 80).roundToInt()) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(RiffSpace.block)) {
        when (slide) {
            EncoreSlide.Intro -> {
                Text(encoreStoryTitle(summary.period.range), color = White, fontSize = RiffEncore.storyTitle, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text(encorePeriodLabel(summary.period, today), color = Muted, fontSize = RiffEncore.storyBody)
            }
            EncoreSlide.Time -> {
                Text(string(R.string.encore_story_time), color = Muted, fontSize = RiffEncore.storyBody)
                Text(encoreDuration(summary.listenedMs), color = White, fontSize = RiffEncore.storyNumber, fontWeight = FontWeight.Bold, modifier = lift)
                summary.previousListenedMs?.let { before ->
                    val change = summary.listenedMs - before
                    if (abs(change) >= 60_000L) Text(string(if (change > 0) R.string.encore_story_more else R.string.encore_story_less, encoreDuration(change),
                        encorePreviousName(summary.period.range)), color = White, fontSize = RiffEncore.storyBody, textAlign = TextAlign.Center)
                }
            }
            EncoreSlide.TopSong -> summary.top(EncoreKind.SONG).first().let { song ->
                Text(string(R.string.encore_story_top_song), color = Muted, fontSize = RiffEncore.storyBody)
                CatalogArtwork(song.artwork, song.key, modifier = lift.size(RiffEncore.storyArt).rotate(rise.value * -8f).clip(RoundedCornerShape(RiffRadius.hero)))
                Text(song.title, color = White, fontSize = RiffEncore.storyTitle, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(string(R.string.encore_value_line, quantity(R.plurals.encore_n_plays, song.plays), encoreDuration(song.listenedMs)), color = Muted, fontSize = RiffEncore.storyBody)
            }
            EncoreSlide.TopFive -> {
                Text(string(R.string.encore_story_top5), color = White, fontSize = RiffEncore.storyTitle, fontWeight = FontWeight.Bold)
                summary.top(EncoreKind.SONG).take(5).forEachIndexed { i, song -> StoryRow(i + 1, song, Modifier.offset { IntOffset(((rise.value * (i + 1) * 60)).roundToInt(), 0) }) }
            }
            EncoreSlide.TopArtist -> summary.top(EncoreKind.ARTIST).first().let { artist ->
                Text(string(R.string.encore_story_top_artist), color = Muted, fontSize = RiffEncore.storyBody)
                val cover = summary.top(EncoreKind.SONG).firstOrNull { it.subtitle.contains(artist.title) }
                CatalogArtwork(cover?.artwork, artist.key, modifier = lift.size(RiffEncore.storyArt).clip(CircleShape))
                Text(artist.title, color = White, fontSize = RiffEncore.storyTitle, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                if (summary.listenedMs > 0) Text(string(R.string.encore_story_share_of, encorePercent(artist.listenedMs.toFloat() / summary.listenedMs)),
                    color = Muted, fontSize = RiffEncore.storyBody)
            }
            EncoreSlide.TopAlbums -> {
                Text(string(R.string.encore_story_albums), color = White, fontSize = RiffEncore.storyTitle, fontWeight = FontWeight.Bold)
                Box(Modifier.fillMaxWidth().height(RiffEncore.storyArt), contentAlignment = Alignment.Center) {
                    summary.top(EncoreKind.ALBUM).take(5).reversed().forEachIndexed { i, album ->
                        val turn = (i - 2) * 9f * (1f - rise.value)
                        CatalogArtwork(album.artwork, album.key, modifier = Modifier.size(RiffEncore.storyArt * .62f).rotate(turn)
                            .offset { IntOffset(((i - 2) * 46 * (1f - rise.value)).roundToInt(), 0) }.clip(RoundedCornerShape(RiffRadius.artwork)))
                    }
                }
                summary.top(EncoreKind.ALBUM).take(3).forEachIndexed { i, album -> Text("${i + 1}. ${album.title}", color = White, fontSize = RiffEncore.storyBody, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            EncoreSlide.Genres -> {
                Text(string(R.string.encore_story_genres), color = White, fontSize = RiffEncore.storyTitle, fontWeight = FontWeight.Bold)
                EncoreDonut(summary.top(ENCORE_GENRE_KIND).take(6), null) { }
            }
            EncoreSlide.GoldenHour -> {
                EncoreClock(summary.hours)
                Text(string(R.string.encore_story_golden, encoreHourLabel(summary.goldenHour ?: 0)), color = White, fontSize = RiffEncore.storyBody, textAlign = TextAlign.Center)
            }
            EncoreSlide.Habits -> {
                Text(string(R.string.encore_story_habits), color = White, fontSize = RiffEncore.storyTitle, fontWeight = FontWeight.Bold)
                Text(string(R.string.encore_streak) + ": " + quantity(R.plurals.encore_n_days, summary.habits.streakDays), color = White, fontSize = RiffEncore.storyBody)
                summary.habits.discovery?.let { Text(string(R.string.encore_discovery) + ": " + encorePercent(it), color = White, fontSize = RiffEncore.storyBody) }
                summary.personality?.let {
                    Text(encorePersonalityName(it), color = Pink, fontSize = RiffEncore.storyTitle, fontWeight = FontWeight.Bold, modifier = lift)
                    Text(encorePersonalityRule(it), color = Muted, fontSize = RiffEncore.storyBody, textAlign = TextAlign.Center)
                }
            }
            EncoreSlide.Milestone -> {
                Text(string(R.string.encore_story_milestone), color = Muted, fontSize = RiffEncore.storyBody)
                summary.milestones.forEach { Text(encoreMilestoneName(it), color = White, fontSize = RiffEncore.storyTitle, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = lift) }
            }
            EncoreSlide.Summary -> SummaryCard(summary, today)
        }
    }
}

@Composable private fun StoryRow(rank: Int, item: EncoreItem, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(rank.toString(), color = Pink, fontSize = RiffEncore.storyBody, fontWeight = FontWeight.Bold)
        Spacer(Modifier.size(RiffSpace.rowGap))
        CatalogArtwork(item.artwork, item.key, modifier = Modifier.size(RiffEncore.storyRowArt).clip(RoundedCornerShape(RiffRadius.artwork)))
        Column(Modifier.weight(1f).padding(start = RiffSpace.rowGap)) {
            Text(item.title, color = White, fontSize = RiffEncore.storyBody, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.subtitle, color = Muted, fontSize = RiffType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The shareable card: time, top song, top artist, top genre, personality, and Riff's two waves in the corner. */
@Composable private fun SummaryCard(summary: EncoreSummary, today: java.time.LocalDate) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(RiffRadius.hero)).background(White.copy(alpha = .08f)).padding(RiffSpace.block * 1.5f),
        verticalArrangement = Arrangement.spacedBy(RiffSpace.block)) {
        Text(encoreStoryTitle(summary.period.range), color = White, fontSize = RiffEncore.storyBody, fontWeight = FontWeight.Bold)
        Text(encorePeriodLabel(summary.period, today), color = Muted, fontSize = RiffType.body)
        SummaryLine(string(R.string.encore_listening_time), encoreDuration(summary.listenedMs))
        summary.top(EncoreKind.SONG).firstOrNull()?.let { SummaryLine(string(R.string.encore_story_top_song), it.title) }
        summary.top(EncoreKind.ARTIST).firstOrNull()?.let { SummaryLine(string(R.string.encore_story_top_artist), it.title) }
        summary.top(ENCORE_GENRE_KIND).firstOrNull()?.let { SummaryLine(string(R.string.encore_top_genres), it.title) }
        summary.personality?.let { SummaryLine(string(R.string.encore_personality), encorePersonalityName(it)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Canvas(Modifier.size(RiffEncore.storyShare)) {
                val stroke = size.width / 10f
                for (row in 0..1) {
                    val path = androidx.compose.ui.graphics.Path()
                    val y = size.height * (.38f + row * .28f)
                    path.moveTo(0f, y); path.cubicTo(size.width * .3f, y - size.height * .25f, size.width * .6f, y + size.height * .25f, size.width, y)
                    drawPath(path, Pink, style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                }
            }
        }
    }
}

@Composable private fun SummaryLine(label: String, value: String) {
    Column {
        Text(label, color = Muted, fontSize = RiffType.body)
        Text(value, color = White, fontSize = RiffEncore.storyBody, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** The slide as a 1080 × 1920 picture: scaled to fill it and centred, so a taller screen loses only empty sky. */
internal fun encoreCard(slide: Bitmap, width: Int = 1080, height: Int = 1920): Bitmap {
    val card = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(card)
    canvas.drawColor(android.graphics.Color.BLACK)
    val scale = maxOf(width.toFloat() / slide.width, height.toFloat() / slide.height)
    val w = (slide.width * scale).roundToInt(); val h = (slide.height * scale).roundToInt()
    val software = if (slide.config == Bitmap.Config.HARDWARE) slide.copy(Bitmap.Config.ARGB_8888, false) else slide
    canvas.drawBitmap(software, null, android.graphics.Rect((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2),
        android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
    return card
}

/** Through the same file provider and intent as lyric cards. */
internal fun shareEncoreCard(context: Context, card: Bitmap): Boolean = runCatching {
    context.startActivity(Intent.createChooser(lyricCardShareIntent(context, card), string(R.string.encore_share_title))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}.isSuccess
