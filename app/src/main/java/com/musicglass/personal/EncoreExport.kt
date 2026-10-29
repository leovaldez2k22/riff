package com.musicglass.personal

import org.json.JSONObject

/*
 * Feature 12 extras: Encore in Export. Riff's Export file carries each owner's totals by year and all-time top 25s, so a
 * file shows what Encore said; import rebuilds Encore from the plays and only passes these lines over. The Encore page
 * also saves the period it shows as a CSV of its top lists, for a spreadsheet.
 */

/** How many of each all-time top list go into an Export file. */
internal const val ENCORE_EXPORT_TOP = 25
internal val ENCORE_EXPORT_KINDS = listOf(EncoreKind.SONG, EncoreKind.ARTIST, EncoreKind.ALBUM)

/** One year's totals as an Export record line. */
internal fun encoreYearLine(year: Int, plays: Int, listenedMs: Long): String =
    JSONObject().put("t", "encore").put("year", year).put("plays", plays).put("ms", listenedMs).toString()

/** One row of an all-time top list as an Export record line. */
internal fun encoreTopLine(kind: String, rank: Int, item: EncoreItem): String =
    JSONObject().put("t", "encore_top").put("kind", kind).put("rank", rank).put("title", item.title.take(180))
        .put("subtitle", item.subtitle.take(180)).put("plays", item.plays).put("ms", item.listenedMs).toString()

/** A CSV field: quoted, and a leading = + - @ made plain text so a spreadsheet does not run it. */
private fun csvField(value: String): String {
    val safe = if (value.firstOrNull() in setOf('=', '+', '-', '@', '\t', '\r')) "'$value" else value
    return "\"" + safe.replace("\"", "\"\"") + "\""
}

/** A period's top lists by minutes ([top] gives one kind's), as CSV: list, rank, title, subtitle, plays, minutes. */
internal fun encoreCsv(top: (String) -> List<EncoreItem>): String = buildString {
    append("list,rank,title,subtitle,plays,minutes\r\n")
    for (kind in ENCORE_EXPORT_KINDS + "genre") top(kind).forEachIndexed { index, item ->
        append(listOf(kind, (index + 1).toString(), csvField(item.title), csvField(item.subtitle), item.plays.toString(),
            (item.listenedMs / 60_000).toString()).joinToString(","))
        append("\r\n")
    }
}

/** A file name for the period: "riff-encore-week-2026-10-26.csv". */
internal fun encoreCsvName(period: EncorePeriod) = "riff-encore-${period.range.name.lowercase()}-${period.start}.csv"
