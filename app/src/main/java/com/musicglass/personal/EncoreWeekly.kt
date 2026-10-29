package com.musicglass.personal

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/*
 * Feature 12 extras: the weekly goal. Hours a week the owner aims for, or off; a ring on the headline card of any week
 * shows how far that week got. Kept on the phone with Encore's other settings.
 */

/**
 * The day whose week a Sunday recap names: today, unless the locale's week starts on Sunday, when today begins a new
 * week and the recap is of the week that just ended.
 */
internal fun encoreRecapDay(today: java.time.LocalDate, firstDay: java.time.DayOfWeek = EncorePeriod.firstDayOfWeek()): java.time.LocalDate =
    if (today.dayOfWeek == firstDay) today.minusDays(1) else today

/** Home's Encore row and the weekly recap are on Sundays. */
internal fun encoreRecapToday(today: java.time.LocalDate) = today.dayOfWeek == java.time.DayOfWeek.SUNDAY

/** The goals offered, in hours a week; 0 is off. */
internal val ENCORE_GOAL_HOURS = listOf(0, 2, 5, 10, 20)
private const val GOAL_KEY = "encore_goal_hours"

private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("music_collections", Context.MODE_PRIVATE)
internal fun encoreGoalHours(context: Context) = prefs(context).getInt(GOAL_KEY, 0).takeIf { it in ENCORE_GOAL_HOURS } ?: 0
internal fun setEncoreGoalHours(context: Context, hours: Int) { prefs(context).edit().putInt(GOAL_KEY, hours.takeIf { it in ENCORE_GOAL_HOURS } ?: 0).apply() }

/** How much of a [hours] goal [listenedMs] reaches, 0 to 1; 0 with no goal. */
internal fun encoreGoalProgress(listenedMs: Long, hours: Int): Float =
    if (hours <= 0) 0f else (listenedMs / (hours * 3_600_000f)).coerceIn(0f, 1f)

/** The ring and its line on a week's headline: "3 h 10 m of your 5 h goal", or "Goal reached". */
@Composable internal fun EncoreGoalLine(listenedMs: Long, hours: Int) {
    val progress = encoreGoalProgress(listenedMs, hours)
    val line = if (progress >= 1f) string(R.string.encore_goal_reached, hours) else string(R.string.encore_goal_line, encoreDuration(listenedMs), hours)
    Row(Modifier.semantics(mergeDescendants = true) { contentDescription = line }.testTag("encore_goal"), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(RiffSpace.touch)) {
            val stroke = RiffSpace.tight.toPx()
            val inset = stroke / 2
            val box = Size(size.width - stroke, size.height - stroke)
            drawArc(White.copy(alpha = .12f), 0f, 360f, false, Offset(inset, inset), box, style = Stroke(stroke))
            if (progress > 0f) drawArc(Pink, -90f, 360f * progress, false, Offset(inset, inset), box, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text(line, color = if (progress >= 1f) Pink else White, fontSize = RiffType.body, modifier = Modifier.padding(start = RiffSpace.rowGap))
    }
}

/** The goal's setting on the Encore page: Off, or hours a week. */
@Composable internal fun EncoreGoalSetting(hours: Int, onChange: (Int) -> Unit) {
    EncoreCard {
        Text(string(R.string.encore_goal), color = White, style = RiffText.body)
        Text(string(R.string.encore_goal_detail), color = Muted, fontSize = RiffType.detail)
        GlassSegmented(ENCORE_GOAL_HOURS.map { if (it == 0) string(R.string.encore_goal_off) else string(R.string.encore_goal_hours, it) },
            ENCORE_GOAL_HOURS.indexOf(hours).coerceAtLeast(0), { onChange(ENCORE_GOAL_HOURS[it]) }, "encore_goal_setting")
    }
}

/** The weekly recap's switch. Turning it on asks for notifications first where Android needs that. */
@Composable internal fun EncoreRecapSetting() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(encoreRecapOn(context)) }
    fun set(value: Boolean) { on = value; setEncoreRecapOn(context, value) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) set(true) }
    EncoreCard {
        Row(Modifier.fillMaxWidth().clickable {
            uiHaptic()
            when {
                on -> set(false)
                Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                    ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                else -> set(true)
            }
        }.testTag("encore_recap_toggle"), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(string(R.string.encore_recap), color = White, style = RiffText.body)
                Text(string(R.string.encore_recap_detail), color = Muted, fontSize = RiffType.detail)
            }
            Spacer(Modifier.width(RiffSpace.item))
            GlassSwitch(on)
        }
    }
}
