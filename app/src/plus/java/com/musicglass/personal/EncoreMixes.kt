package com.musicglass.personal

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/*
 * Feature 13 (Riff+, owner Q13, 8 November): mixes made on the phone from Encore. Read only: the daily totals
 * (stats_daily) and, for Time of day, the plays kept in history (history_events). Only songs played from YouTube
 * Music can be queued again, so a phone file never appears. A mix with too few songs is left out; nothing is sent.
 */

internal enum class MixKind { OnRepeat, TimeOfDay, HeatingUp, NewFinds, Artist, Weekday, Season, Genre }

/** One mix: [title] and [detail] for its card, [tracks] in the order they play. */
internal data class EncoreMix(val id: String, val kind: MixKind, val title: String, val detail: String, val tracks: List<ProviderTrack>)

/** The part of the day an hour falls in: night 0–4, morning 5–11, afternoon 12–16, evening 17–23. */
internal enum class DayPart(val hours: IntRange) {
    Night(0..4), Morning(5..11), Afternoon(12..16), Evening(17..23);
    companion object { fun of(hour: Int) = entries.first { hour in it.hours } }
}

/** The three-month season [day] falls in (December–February, March–May, June–August, September–November): its first day. */
internal fun seasonStart(day: LocalDate): LocalDate {
    val m = day.monthValue
    val first = when (m) { 12 -> 12; 1, 2 -> 12; else -> (m - 3) / 3 * 3 + 3 }
    val year = if (m <= 2) day.year - 1 else day.year
    return LocalDate.of(year, first, 1)
}

internal const val MIX_MIN_SONGS = 8
internal const val MIX_MAX_SONGS = 50

/** A song row as Encore keeps it, with what a mix ranks it by. */
private class Scored(val key: String, val title: String, val artist: String, val artwork: String?, val score: Double, val tie: Long)

/**
 * Builds the mixes for one owner's [scopes] on [today] at [hour]. [genres] gives artists' genres (empty while
 * Encore genres are off, which hides genre mixes); [blocked] is Never play. Pure reading; call off the main thread.
 */
internal class EncoreMixer(private val db: SQLiteDatabase, private val scopes: List<String>, private val today: LocalDate, private val hour: Int,
    private val zone: ZoneId, private val genres: (List<String>) -> Map<String, List<String>>, private val blocked: (ProviderTrack) -> Boolean,
    private val text: MixText = MixText.Strings) {

    private val scopeIn = scopes.joinToString(",", "scope IN (", ")") { "?" }
    private fun day(daysBack: Long) = today.minusDays(daysBack).toString()

    fun build(): List<EncoreMix> = if (scopes.isEmpty()) emptyList() else try {
        listOfNotNull(onRepeat(), timeOfDay(), heatingUp(), newFinds()) + artistMixes() + listOfNotNull(weekday(), season()) + genreMixes()
    } catch (_: SQLiteException) { emptyList() }

    /** Songs as tracks, best first, blocked and duplicate ones out; null when too few are left. */
    private fun mix(id: String, kind: MixKind, title: String, detail: String, rows: List<Scored>): EncoreMix? {
        val tracks = rows.sortedWith(compareByDescending<Scored> { it.score }.thenByDescending { it.tie }.thenBy { it.key })
            .mapNotNull { r -> EncoreItem(EncoreKind.SONG, r.key, r.title, r.artist, r.artwork, 0, 0).track() }
            .distinctBy { it.videoId }.filterNot(blocked).take(MIX_MAX_SONGS)
        return if (tracks.size < MIX_MIN_SONGS) null else EncoreMix(id, kind, title, detail, tracks)
    }

    /** Songs' plays and listening between two days (from inclusive, to exclusive), with [having] on the totals. */
    private fun songs(from: String, to: String, having: String = "SUM(plays)>=1", extraWhere: String = "", extraArgs: List<String> = emptyList()): List<Scored> =
        db.rawQuery("SELECT key,MAX(title),MAX(subtitle),MAX(artwork),SUM(plays),SUM(listened_ms) FROM stats_daily WHERE $scopeIn AND kind='song' " +
            "AND day>=? AND day<? $extraWhere GROUP BY key HAVING $having", (scopes + from + to + extraArgs).toTypedArray()).use { c ->
            buildList { while (c.moveToNext()) add(Scored(c.getString(0), c.getString(1), c.getString(2), if (c.isNull(3)) null else c.getString(3), c.getInt(4).toDouble(), c.getLong(5))) }
        }

    private val tomorrow get() = today.plusDays(1).toString()

    /** Most played in the last 30 days, played at least twice. */
    private fun onRepeat() = mix("on_repeat", MixKind.OnRepeat, text.onRepeat(), text.onRepeatDetail(), songs(day(29), tomorrow, "SUM(plays)>=2"))

    /** Songs played in this part of the day in the last 90 days, at least twice then, most often first. */
    private fun timeOfDay(): EncoreMix? {
        val part = DayPart.of(hour)
        val from = today.minusDays(89).atStartOfDay(zone).toInstant().toEpochMilli()
        val counts = HashMap<String, Pair<Int, Array<String?>>>()
        db.rawQuery("SELECT media_id,title,artist,artwork,played_at FROM history_events WHERE $scopeIn AND music=1 AND played_at>=? ORDER BY played_at",
            (scopes + from.toString()).toTypedArray()).use { c ->
            while (c.moveToNext()) {
                val at = Instant.ofEpochMilli(c.getLong(4)).atZone(zone).hour
                if (DayPart.of(at) != part) continue
                val key = c.getString(0)
                val seen = counts[key]
                counts[key] = (seen?.first ?: 0) + 1 to arrayOf(c.getString(1), c.getString(2), if (c.isNull(3)) null else c.getString(3))
            }
        }
        val rows = counts.filter { it.value.first >= 2 }.map { (key, v) -> Scored(key, v.second[0].orEmpty(), v.second[1].orEmpty(), v.second[2], v.first.toDouble(), 0) }
        return mix("time_of_day", MixKind.TimeOfDay, text.timeOfDay(part), text.timeOfDayDetail(), rows)
    }

    /** Played at least three times in the last 7 days and at least twice as often as in the four weeks before. */
    private fun heatingUp(): EncoreMix? {
        val recent = songs(day(6), tomorrow, "SUM(plays)>=3").associateBy { it.key }
        if (recent.isEmpty()) return null
        val before = songs(day(34), day(6)).associate { it.key to it.score / 4 }
        val rows = recent.values.mapNotNull { r ->
            val weekly = before[r.key] ?: 0.0
            if (r.score < 2 * weekly + 1) null else Scored(r.key, r.title, r.artist, r.artwork, r.score - weekly, r.tie)
        }
        return mix("heating_up", MixKind.HeatingUp, text.heatingUp(), text.heatingUpDetail(), rows)
    }

    /** First heard in the last 30 days and played at least three times since. */
    private fun newFinds(): EncoreMix? {
        val rows = db.rawQuery("SELECT key,MAX(title),MAX(subtitle),MAX(artwork),SUM(plays),SUM(listened_ms) FROM stats_daily WHERE $scopeIn AND kind='song' " +
            "GROUP BY key HAVING MIN(day)>=? AND SUM(plays)>=3", (scopes + day(29)).toTypedArray()).use { c ->
            buildList { while (c.moveToNext()) add(Scored(c.getString(0), c.getString(1), c.getString(2), if (c.isNull(3)) null else c.getString(3), c.getInt(4).toDouble(), c.getLong(5))) }
        }
        return mix("new_finds", MixKind.NewFinds, text.newFinds(), text.newFindsDetail(), rows)
    }

    /** The artists of a song row, as Encore splits credits, lower-cased. */
    private fun artistsOf(credit: String) = encoreArtists(credit, null).map { it.second.lowercase(Locale.ROOT) }

    /** Every song ever played, for mixes that pick by artist or genre. */
    private val allSongs by lazy { songs("0000-00-00", tomorrow) }

    /** The top three artists by listening in the last 90 days, each with the songs of theirs already played. */
    private fun artistMixes(): List<EncoreMix> {
        val top = db.rawQuery("SELECT MAX(title),SUM(listened_ms) AS ms FROM stats_daily WHERE $scopeIn AND kind='artist' AND day>=? AND day<? " +
            "GROUP BY lower(title) ORDER BY ms DESC,SUM(plays) DESC,lower(title) LIMIT 3", (scopes + day(89) + tomorrow).toTypedArray()).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        return top.mapNotNull { name ->
            val wanted = name.lowercase(Locale.ROOT)
            mix("artist:$wanted", MixKind.Artist, text.artist(name), text.artistDetail(), allSongs.filter { wanted in artistsOf(it.artist) })
        }
    }

    /** What is played on this day of the week, over the last twelve weeks. */
    private fun weekday(): EncoreMix? {
        val dow = today.dayOfWeek
        // SQLite: 0 is Sunday.
        val sqliteDow = (dow.value % 7).toString()
        val rows = songs(day(83), tomorrow, "SUM(plays)>=2", "AND strftime('%w',day)=?", listOf(sqliteDow))
        return mix("weekday:${dow.value}", MixKind.Weekday, text.weekday(dow), text.weekdayDetail(), rows)
    }

    /** The top songs of this season a year ago. */
    private fun season(): EncoreMix? {
        val start = seasonStart(today).minusYears(1); val end = start.plusMonths(3)
        val rows = songs(start.toString(), end.toString(), "SUM(plays)>=2")
        return mix("season:$start", MixKind.Season, text.season(), text.seasonDetail(start, end.minusDays(1)), rows)
    }

    /** The two genres listened to most in the last 90 days (while Encore genres are on), with the songs of their artists. */
    private fun genreMixes(): List<EncoreMix> {
        val artists = db.rawQuery("SELECT MAX(title),SUM(listened_ms) FROM stats_daily WHERE $scopeIn AND kind='artist' AND day>=? AND day<? GROUP BY lower(title)",
            (scopes + day(89) + tomorrow).toTypedArray()).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getLong(1)) } }
        if (artists.isEmpty()) return emptyList()
        val known = genres(artists.map { it.first }).mapKeys { it.key.lowercase(Locale.ROOT) }
        if (known.isEmpty()) return emptyList()
        val byGenre = HashMap<String, Long>()
        artists.forEach { (name, ms) -> known[name.lowercase(Locale.ROOT)]?.forEach { g -> byGenre[g] = (byGenre[g] ?: 0) + ms } }
        return byGenre.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }).take(2).mapNotNull { (genre, _) ->
            mix("genre:${genre.lowercase(Locale.ROOT)}", MixKind.Genre, text.genre(genre), text.genreDetail(),
                allSongs.filter { s -> artistsOf(s.artist).any { genre in known[it].orEmpty() } })
        }
    }
}

/** The words on the mix cards; tests use plain ones so they need no resources. */
internal interface MixText {
    fun onRepeat(): String; fun onRepeatDetail(): String
    fun timeOfDay(part: DayPart): String; fun timeOfDayDetail(): String
    fun heatingUp(): String; fun heatingUpDetail(): String
    fun newFinds(): String; fun newFindsDetail(): String
    fun artist(name: String): String; fun artistDetail(): String
    fun weekday(day: DayOfWeek): String; fun weekdayDetail(): String
    fun season(): String; fun seasonDetail(from: LocalDate, to: LocalDate): String
    fun genre(name: String): String; fun genreDetail(): String

    object Strings : MixText {
        override fun onRepeat() = string(R.string.mix_on_repeat); override fun onRepeatDetail() = string(R.string.mix_on_repeat_detail)
        override fun timeOfDay(part: DayPart) = string(when (part) {
            DayPart.Night -> R.string.mix_nights; DayPart.Morning -> R.string.mix_mornings
            DayPart.Afternoon -> R.string.mix_afternoons; DayPart.Evening -> R.string.mix_evenings })
        override fun timeOfDayDetail() = string(R.string.mix_time_of_day_detail)
        override fun heatingUp() = string(R.string.mix_heating_up); override fun heatingUpDetail() = string(R.string.mix_heating_up_detail)
        override fun newFinds() = string(R.string.mix_new_finds); override fun newFindsDetail() = string(R.string.mix_new_finds_detail)
        override fun artist(name: String) = string(R.string.mix_artist, name); override fun artistDetail() = string(R.string.mix_artist_detail)
        override fun weekday(day: DayOfWeek) = string(R.string.mix_weekday, day.getDisplayName(TextStyle.FULL, Locale.getDefault()))
        override fun weekdayDetail() = string(R.string.mix_weekday_detail)
        override fun season() = string(R.string.mix_season)
        override fun seasonDetail(from: LocalDate, to: LocalDate): String {
            val month = { d: LocalDate -> d.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
            return string(R.string.mix_season_detail, month(from), month(to), to.year.toString())
        }
        override fun genre(name: String) = string(R.string.mix_genre, name); override fun genreDetail() = string(R.string.mix_genre_detail)
    }
}
