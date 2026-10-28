package com.musicglass.personal

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Feature 12, section 12.2: Encore's charts, each drawn on one canvas. The selected bar or cell is Riff's pink and the
 * rest white at 30 %. Each value is also an accessibility node of its own, so TalkBack reads the numbers.
 */
private val Rest = White.copy(alpha = .30f)
private val Faint = White.copy(alpha = .08f)

/** Bars growing from zero when the period changes (at once under reduced motion). */
@Composable private fun rememberGrowth(key: Any): Float {
    val motion = LocalMotionPolicy.current
    val growth = remember(key) { Animatable(if (motion.reduced) 1f else 0f) }
    LaunchedEffect(key) { if (!motion.reduced) growth.animateTo(1f, GlassMotion.timed(GlassMotion.ambientMs, motion)) }
    return growth.value
}

/**
 * The listening chart. Dragging moves the highlight from bar to bar with a light tick; tapping a bar opens its day,
 * month or year ([onOpen]).
 */
@Composable internal fun EncoreBarChart(bars: List<EncoreBar>, range: EncoreRange, onOpen: (EncorePeriod) -> Unit) {
    if (bars.isEmpty()) return
    var chosen by remember(bars) { mutableStateOf<Int?>(null) }
    val growth = rememberGrowth(bars)
    val top = (bars.maxOf { it.listenedMs }).coerceAtLeast(1L).toFloat()
    val latestOpen by rememberUpdatedState(onOpen)
    val shown = chosen?.let { bars.getOrNull(it) } ?: bars.maxByOrNull { it.listenedMs }
    Column(Modifier.fillMaxWidth()) {
        Text(shown?.let { encoreBarDetail(it, range) }.orEmpty(), color = if (chosen != null) White else Muted, fontSize = RiffType.body,
            modifier = Modifier.testTag("encore_chart_detail"))
        Box(Modifier.fillMaxWidth().height(RiffEncore.chart)) {
            Canvas(Modifier.fillMaxSize().testTag("encore_chart")
                .pointerInput(bars) {
                    fun index(x: Float) = (x / size.width * bars.size).toInt().coerceIn(0, bars.lastIndex)
                    detectHorizontalDragGestures(onDragStart = { chosen = index(it.x); uiHaptic(HapticCue.Selection) }) { change, _ ->
                        val next = index(change.position.x)
                        if (next != chosen) { chosen = next; uiHaptic(HapticCue.Selection) }
                    }
                }
                .pointerInput(bars) {
                    detectTapGestures { offset ->
                        val bar = bars[(offset.x / size.width * bars.size).toInt().coerceIn(0, bars.lastIndex)]
                        bar.opens?.let { uiHaptic(); latestOpen(it) }
                    }
                }) {
                val gap = RiffEncore.barGap.toPx(); val slot = size.width / bars.size
                val corner = CornerRadius(RiffEncore.barCorner.toPx().coerceAtMost(slot / 2))
                bars.forEachIndexed { i, bar ->
                    val height = (bar.listenedMs / top * size.height * growth).coerceAtLeast(if (bar.listenedMs > 0) 2f else 0f)
                    drawRoundRect(Faint, Offset(i * slot + gap / 2, 0f), Size(slot - gap, size.height), corner)
                    if (height > 0f) drawRoundRect(if (i == chosen || (chosen == null && bar == shown)) Pink else Rest,
                        Offset(i * slot + gap / 2, size.height - height), Size(slot - gap, height), corner)
                }
            }
            // One node per bar for TalkBack, laid over the canvas; a double tap opens the bar as a tap does.
            Row(Modifier.fillMaxSize()) {
                bars.forEach { bar ->
                    val words = encoreBarDetail(bar, range)
                    Box(Modifier.weight(1f).fillMaxHeight().semantics(mergeDescendants = true) { contentDescription = words }
                        .then(bar.opens?.let { opens -> Modifier.clickable(role = Role.Button) { uiHaptic(); onOpen(opens) } } ?: Modifier))
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            val every = when { bars.size > 24 -> 7; bars.size > 12 -> 3; else -> 1 }
            bars.forEachIndexed { i, bar ->
                Text(if (i % every == 0) encoreBarLabel(bar, range) else "", color = Muted, fontSize = RiffEncore.cellLabel, maxLines = 1,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** The 24-hour radial clock: each hour's segment as long as the time in it. Tap a segment for its hour. */
@Composable internal fun EncoreClock(hours: LongArray) {
    val top = hours.maxOrNull()?.coerceAtLeast(1L)?.toFloat() ?: 1f
    var chosen by remember(hours) { mutableStateOf<Int?>(null) }
    val golden = hours.withIndex().filter { it.value > 0 }.maxByOrNull { it.value }?.index
    val growth = rememberGrowth(hours.toList())
    val shown = chosen ?: golden
    val summary = golden?.let { string(R.string.encore_golden_hour, encoreHourLabel(it)) }.orEmpty()
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(RiffEncore.clock).testTag("encore_clock").semantics { contentDescription = summary }
            .pointerInput(hours) {
                detectTapGestures { offset ->
                    val angle = (atan2(offset.y - size.height / 2f, offset.x - size.width / 2f) + PI / 2 + 2 * PI) % (2 * PI)
                    chosen = ((angle / (2 * PI)) * 24).toInt().coerceIn(0, 23); uiHaptic(HapticCue.Selection)
                }
            }) {
            val outer = size.minDimension / 2; val inner = outer * RiffEncore.clockHole; val sweep = 360f / 24
            for (hour in 0 until 24) {
                val length = (outer - inner) * (hours[hour] / top) * growth
                val start = hour * sweep - 90f + .8f
                drawArc(Faint, start, sweep - 1.6f, false, Offset(center.x - outer + (outer - inner) / 2, center.y - outer + (outer - inner) / 2),
                    Size(2 * outer - (outer - inner), 2 * outer - (outer - inner)), style = Stroke(outer - inner))
                if (length > 0f) {
                    val r = inner + length / 2
                    drawArc(if (hour == shown) Pink else Rest, start, sweep - 1.6f, false, Offset(center.x - r, center.y - r), Size(2 * r, 2 * r),
                        style = Stroke(length))
                }
            }
            for (mark in listOf(0, 6, 12, 18)) {
                val a = mark / 24.0 * 2 * PI - PI / 2
                drawCircle(Muted, 2f, Offset(center.x + (inner * .82f * cos(a)).toFloat(), center.y + (inner * .82f * sin(a)).toFloat()))
            }
        }
        if (shown != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(encoreHourLabel(shown), color = White, style = RiffText.rowTitle)
            Text(encoreDuration(hours[shown]), color = Muted, fontSize = RiffType.detail)
        }
    }
}

/** 7 rows (from the week's first day) × 24 hours. Tap a cell for "Friday at 22:00 · 3 h 40 m in total". */
@Composable internal fun EncoreWeekHeat(rows: List<LongArray>, firstDay: DayOfWeek) {
    val top = rows.maxOf { it.maxOrNull() ?: 0L }.coerceAtLeast(1L).toFloat()
    var chosen by remember(rows) { mutableStateOf<Pair<Int, Int>?>(null) }
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val days = List(7) { firstDay.plus(it.toLong()) }
    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cell = maxWidth / 24
            Canvas(Modifier.fillMaxWidth().height(cell * 7).testTag("encore_heatmap").pointerInput(rows) {
                detectTapGestures { offset ->
                    val column = (offset.x / size.width * 24).toInt().coerceIn(0, 23); val row = (offset.y / size.height * 7).toInt().coerceIn(0, 6)
                    chosen = row to column; uiHaptic(HapticCue.Selection)
                }
            }) {
                val side = size.width / 24; val gap = RiffEncore.cellGap.toPx(); val corner = CornerRadius(RiffEncore.cellCorner.toPx())
                rows.forEachIndexed { r, hours -> hours.forEachIndexed { h, ms ->
                    val color = if (chosen == r to h) Pink else if (ms > 0) White.copy(alpha = .10f + .70f * (ms / top)) else Faint
                    drawRoundRect(color, Offset(h * side + gap / 2, r * side + gap / 2), Size(side - gap, side - gap), corner)
                } }
            }
            Column(Modifier.fillMaxWidth()) {
                days.forEachIndexed { r, day ->
                    val busiest = rows[r].withIndex().maxByOrNull { it.value }?.takeIf { it.value > 0 }
                    val words = busiest?.let { string(R.string.encore_heat_cell, day.getDisplayName(TextStyle.FULL, locale), encoreHourLabel(it.index), encoreDuration(it.value)) }
                        ?: day.getDisplayName(TextStyle.FULL, locale)
                    Box(Modifier.fillMaxWidth().height(cell).semantics { contentDescription = words })
                }
            }
        }
        val detail = chosen?.let { (r, h) -> string(R.string.encore_heat_cell, days[r].getDisplayName(TextStyle.FULL, locale), encoreHourLabel(h), encoreDuration(rows[r][h])) }
        Text(detail ?: string(R.string.encore_heat_hint), color = if (detail != null) White else Muted, fontSize = RiffType.detail)
    }
}

/** One square a day, shaded by time listened; weeks run across, scrolling when there are many. Tap a day to open it. */
@Composable internal fun EncoreCalendar(daily: Map<LocalDate, Long>, period: EncorePeriod, firstDay: DayOfWeek, onOpen: (EncorePeriod) -> Unit) {
    val start = period.start.minusDays(Math.floorMod(period.start.dayOfWeek.value - firstDay.value, 7).toLong())
    val weeks = (ChronoUnit.DAYS.between(start, period.end) + 6) / 7
    val top = daily.values.maxOrNull()?.coerceAtLeast(1L)?.toFloat() ?: 1f
    val latestOpen by rememberUpdatedState(onOpen)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cell: Dp = if (weeks <= 6) maxWidth / 7 else RiffEncore.rankArt / 3
        val across = period.range == EncoreRange.Month
        val width = if (across) maxWidth else cell * weeks.toInt()
        val height = if (across) cell * weeks.toInt() else cell * 7
        Box(Modifier.fillMaxWidth().then(if (across) Modifier else Modifier.horizontalScroll(rememberScrollState(Int.MAX_VALUE)))) {
            Canvas(Modifier.width(width).height(height).testTag("encore_calendar").pointerInput(daily) {
                detectTapGestures { offset ->
                    val side = cell.toPx()
                    val (week, weekday) = if (across) (offset.y / side).toInt() to (offset.x / side).toInt() else (offset.x / side).toInt() to (offset.y / side).toInt()
                    val day = start.plusDays(week * 7L + weekday.coerceIn(0, 6))
                    if (period.contains(day)) { uiHaptic(); latestOpen(EncorePeriod(EncoreRange.Day, day, day.plusDays(1))) }
                }
            }.semantics { contentDescription = string(R.string.encore_calendar_summary, daily.count { it.value > 0 }) }) {
                val side = cell.toPx(); val gap = RiffEncore.cellGap.toPx(); val corner = CornerRadius(RiffEncore.cellCorner.toPx())
                var day = start; var index = 0
                while (day.isBefore(period.end)) {
                    val week = index / 7; val weekday = index % 7
                    if (period.contains(day)) {
                        val ms = daily[day] ?: 0L
                        val x = (if (across) weekday else week) * side; val y = (if (across) week else weekday) * side
                        drawRoundRect(if (ms > 0) Pink.copy(alpha = .18f + .82f * (ms / top)) else Faint, Offset(x + gap / 2, y + gap / 2),
                            Size(side - gap, side - gap), corner)
                    }
                    day = day.plusDays(1); index++
                }
            }
        }
    }
}

/** Genres as a donut drawing itself; tap a slice (or its row below) to choose it. */
@Composable internal fun EncoreDonut(genres: List<EncoreItem>, chosen: String?, onChoose: (String) -> Unit) {
    val total = genres.sumOf { it.listenedMs }.coerceAtLeast(1L).toFloat()
    val growth = rememberGrowth(genres)
    val shades = listOf(Pink, Color(0xFFFFB86B), Color(0xFF7DD3FC), Color(0xFFA78BFA), Color(0xFF6EE7B7), Color(0xFFFDE68A))
    val latest by rememberUpdatedState(onChoose)
    val stroke = with(LocalDensity.current) { RiffEncore.donutStroke.toPx() }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(RiffEncore.donut).testTag("encore_donut").pointerInput(genres) {
            detectTapGestures { offset ->
                val dx = offset.x - size.width / 2f; val dy = offset.y - size.height / 2f
                if (hypot(dx, dy) < size.width / 2f - stroke * 1.5f) return@detectTapGestures
                val angle = ((Math.toDegrees(atan2(dy, dx).toDouble()) + 90 + 360) % 360).toFloat()
                var at = 0f
                for (genre in genres) { val sweep = genre.listenedMs / total * 360f; if (angle < at + sweep) { uiHaptic(HapticCue.Selection); latest(genre.key); break }; at += sweep }
            }
        }) {
            var at = -90f
            genres.forEachIndexed { i, genre ->
                val sweep = genre.listenedMs / total * 360f * growth
                val color = shades[i % shades.size].copy(alpha = if (chosen == null || chosen == genre.key) 1f else .35f)
                drawArc(color, at, (sweep - 1.5f).coerceAtLeast(.5f), false, Offset(stroke / 2, stroke / 2),
                    Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
                at += sweep
            }
        }
        val shown = genres.firstOrNull { it.key == chosen } ?: genres.firstOrNull()
        if (shown != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(shown.title, color = White, style = RiffText.rowTitle)
            Text(encorePercent(shown.listenedMs / total), color = Muted, fontSize = RiffType.detail)
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(RiffSpace.tight)) {
        genres.take(6).forEachIndexed { i, genre ->
            Row(Modifier.fillMaxWidth().clickable { uiHaptic(HapticCue.Selection); onChoose(genre.key) }, verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(RiffSpace.item)) { drawCircle(shades[i % shades.size]) }
                Text(genre.title, color = if (chosen == genre.key) White else Muted, fontSize = RiffType.body,
                    modifier = Modifier.weight(1f).semantics { contentDescription = genre.title + ", " + encorePercent(genre.listenedMs / total) })
                Text(encorePercent(genre.listenedMs / total), color = Muted, fontSize = RiffType.detail)
            }
        }
    }
}
