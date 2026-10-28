package com.musicglass.personal

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Feature 12: Encore's words for times, periods and labels, in the phone's language and calendar. */

/** "14 h 32 m", or "32 m" under an hour. */
internal fun encoreDuration(ms: Long): String {
    val minutes = (abs(ms) / 60_000L).toInt()
    return if (minutes >= 60) string(R.string.encore_duration_hm, minutes / 60, minutes % 60) else string(R.string.encore_duration_m, minutes)
}

internal fun encorePercent(share: Float): String = string(R.string.encore_percent, (share * 100).roundToInt())

/** An hour of the day as the phone writes times: "22:00" or "10 PM". */
internal fun encoreHourLabel(hour: Int, locale: Locale = Locale.getDefault()): String =
    LocalTime.of(hour, 0).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))

internal fun encoreRangeLabel(range: EncoreRange): String = string(when (range) {
    EncoreRange.Day -> R.string.encore_day; EncoreRange.Week -> R.string.encore_week; EncoreRange.Month -> R.string.encore_month
    EncoreRange.Year -> R.string.encore_year; EncoreRange.All -> R.string.encore_all_time
})

/** The stepper's words: "Today", "This week", "October 2026", "Since 27 July 2026". */
internal fun encorePeriodLabel(period: EncorePeriod, today: LocalDate, locale: Locale = Locale.getDefault()): String {
    val medium = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    val thisWeek = EncorePeriod.containing(EncoreRange.Week, today, EncorePeriod.firstDayOfWeek(locale), null)
    return when (period.range) {
        EncoreRange.Day -> when (period.start) {
            today -> string(R.string.encore_today); today.minusDays(1) -> string(R.string.encore_yesterday)
            else -> period.start.format(DateTimeFormatter.ofPattern("EEE d MMM", locale))
        }
        EncoreRange.Week -> when (period.start) {
            thisWeek.start -> string(R.string.encore_this_week); thisWeek.start.minusWeeks(1) -> string(R.string.encore_last_week)
            else -> string(R.string.encore_range_of_days, period.start.format(DateTimeFormatter.ofPattern("d MMM", locale)),
                period.end.minusDays(1).format(DateTimeFormatter.ofPattern("d MMM", locale)))
        }
        EncoreRange.Month -> period.start.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))
        EncoreRange.Year -> period.start.year.toString()
        EncoreRange.All -> string(R.string.encore_since_x, period.start.format(medium))
    }
}

/** "the week before", for the headline's change. */
internal fun encorePreviousName(range: EncoreRange): String = string(when (range) {
    EncoreRange.Day -> R.string.encore_prev_day; EncoreRange.Week -> R.string.encore_prev_week
    EncoreRange.Month -> R.string.encore_prev_month; else -> R.string.encore_prev_year
})

/** A bar's label: an hour, a weekday, a day of the month, a month or a year. */
internal fun encoreBarLabel(bar: EncoreBar, range: EncoreRange, locale: Locale = Locale.getDefault()): String = when {
    bar.hour != null -> encoreHourLabel(bar.hour, locale)
    range == EncoreRange.Week -> bar.start.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
    range == EncoreRange.Month -> bar.start.dayOfMonth.toString()
    range == EncoreRange.Year || bar.opens?.range == EncoreRange.Month -> bar.start.month.getDisplayName(TextStyle.SHORT, locale)
    else -> bar.start.year.toString()
}

/** The highlighted bar's line: "Tue 21 Oct · 2 h 05 m". */
internal fun encoreBarDetail(bar: EncoreBar, range: EncoreRange, locale: Locale = Locale.getDefault()): String {
    val day = when {
        bar.hour != null -> encoreHourLabel(bar.hour, locale)
        bar.opens?.range == EncoreRange.Day -> bar.start.format(DateTimeFormatter.ofPattern("EEE d MMM", locale))
        bar.opens?.range == EncoreRange.Month -> bar.start.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))
        else -> encoreBarLabel(bar, range, locale)
    }
    return string(R.string.encore_value_line, day, encoreDuration(bar.listenedMs))
}

internal fun encorePersonalityName(personality: EncorePersonality): String = string(when (personality) {
    EncorePersonality.NightOwl -> R.string.encore_night_owl; EncorePersonality.EarlyBird -> R.string.encore_early_bird
    EncorePersonality.Loyal -> R.string.encore_loyal; EncorePersonality.Explorer -> R.string.encore_explorer
})

internal fun encorePersonalityRule(personality: EncorePersonality): String = string(when (personality) {
    EncorePersonality.NightOwl -> R.string.encore_night_owl_rule; EncorePersonality.EarlyBird -> R.string.encore_early_bird_rule
    EncorePersonality.Loyal -> R.string.encore_loyal_rule; EncorePersonality.Explorer -> R.string.encore_explorer_rule
})

internal fun encoreMilestoneName(milestone: EncoreMilestone): String = string(when (milestone) {
    EncoreMilestone.Hours10 -> R.string.encore_milestone_10h; EncoreMilestone.Hours100 -> R.string.encore_milestone_100h
    EncoreMilestone.Hours1000 -> R.string.encore_milestone_1000h; EncoreMilestone.Play1000 -> R.string.encore_milestone_1000_plays
    EncoreMilestone.Streak30 -> R.string.encore_milestone_streak; EncoreMilestone.Artists100 -> R.string.encore_milestone_artists
})

internal fun encoreSourceName(source: String): String = string(when (source) {
    EncoreSource.DOWNLOAD -> R.string.encore_source_download; EncoreSource.DEVICE -> R.string.encore_source_device
    else -> R.string.encore_source_stream
})

/** The rank mark beside a top-list row: "▲3", "▼1", "NEW", or nothing when it held its place. */
internal fun encoreMovement(item: EncoreItem, compared: Boolean): String? = when {
    !compared -> null
    !item.wasRanked -> string(R.string.encore_new)
    (item.movement ?: 0) > 0 -> "▲" + item.movement
    (item.movement ?: 0) < 0 -> "▼" + abs(item.movement ?: 0)
    else -> null
}

/** "Your week in Encore". */
internal fun encoreStoryTitle(range: EncoreRange): String = string(when (range) {
    EncoreRange.Day -> R.string.encore_story_day; EncoreRange.Week -> R.string.encore_story_week
    EncoreRange.Month -> R.string.encore_story_month; EncoreRange.Year -> R.string.encore_story_year
    EncoreRange.All -> R.string.encore_story_all
})
