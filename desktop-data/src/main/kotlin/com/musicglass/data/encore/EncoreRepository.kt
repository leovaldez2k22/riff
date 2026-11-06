package com.musicglass.data.encore

import com.musicglass.data.*

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Feature 12, sections 12.2 and 12.6: what the Encore page shows, read from the daily totals EncoreStore.kt keeps
 * (and, for the habits, from the plays themselves). Every function here reads the database: call off the main thread.
 */

enum class EncoreRange { Day, Week, Month, Year, All }

/** A period of local days, [start] inclusive, [end] exclusive. "All time" starts on the first day history has. */
data class EncorePeriod(val range: EncoreRange, val start: LocalDate, val end: LocalDate) {
    val days: Long get() = ChronoUnit.DAYS.between(start, end)

    fun previous(): EncorePeriod? = when (range) {
        EncoreRange.Day -> EncorePeriod(range, start.minusDays(1), start)
        EncoreRange.Week -> EncorePeriod(range, start.minusWeeks(1), start)
        EncoreRange.Month -> EncorePeriod(range, start.minusMonths(1), start)
        EncoreRange.Year -> EncorePeriod(range, start.minusYears(1), start)
        EncoreRange.All -> null
    }

    /** The next period, or null when it would start after [today]. */
    fun next(today: LocalDate): EncorePeriod? = when (range) {
        EncoreRange.All -> null
        else -> EncorePeriod(range, end, when (range) {
            EncoreRange.Day -> end.plusDays(1); EncoreRange.Week -> end.plusWeeks(1); EncoreRange.Month -> end.plusMonths(1)
            else -> end.plusYears(1)
        }).takeIf { !it.start.isAfter(today) }
    }

    fun contains(day: LocalDate) = !day.isBefore(start) && day.isBefore(end)

    companion object {
        /** The [range] that holds [day]; weeks start on [firstDay] (the locale's, `WeekFields.of(Locale)`). */
        fun containing(range: EncoreRange, day: LocalDate, firstDay: DayOfWeek, historyStart: LocalDate?): EncorePeriod = when (range) {
            EncoreRange.Day -> EncorePeriod(range, day, day.plusDays(1))
            EncoreRange.Week -> day.with(TemporalAdjusters.previousOrSame(firstDay)).let { EncorePeriod(range, it, it.plusWeeks(1)) }
            EncoreRange.Month -> day.withDayOfMonth(1).let { EncorePeriod(range, it, it.plusMonths(1)) }
            EncoreRange.Year -> day.withDayOfYear(1).let { EncorePeriod(range, it, it.plusYears(1)) }
            EncoreRange.All -> EncorePeriod(range, minOf(historyStart ?: day, day), day.plusDays(1))
        }

        fun firstDayOfWeek(locale: Locale = Locale.getDefault()): DayOfWeek = WeekFields.of(locale).firstDayOfWeek
    }
}

/** A song, album, artist or genre in a top list. [movement]: places gained on the previous period, null when new. */
data class EncoreItem(val kind: String, val key: String, val title: String, val subtitle: String, val artwork: String?,
    val plays: Int, val listenedMs: Long, val movement: Int? = null, val wasRanked: Boolean = false) {

}

/** "Your history with this": what [EncoreRepository.history] finds for one song, album or artist. */
data class EncoreHistory(val title: String, val subtitle: String, val artwork: String?, val firstDay: LocalDate, val plays: Int,
    val listenedMs: Long, val bars: List<EncoreBar>, val rankThisYear: Int?)

/** One bar of the listening chart. [opens] is the period a tap opens (a day, a month, a year), if any. */
data class EncoreBar(val start: LocalDate, val hour: Int?, val listenedMs: Long, val opens: EncorePeriod?)

data class EncoreHabits(
    /** The longest run of days in the period with at least [ENCORE_STREAK_MS] each. */
    val streakDays: Int,
    val longestSessionPlays: Int, val longestSessionMs: Long,
    /** The share of time on artists first heard in this period; null when history starts inside it. */
    val discovery: Float?,
    /** Songs finished rather than skipped; null when no play has ended. */
    val completion: Float?,
    val mostRepeatedTitle: String?, val mostRepeatedPlays: Int,
    val sources: Map<String, Int>)

enum class EncorePersonality { NightOwl, EarlyBird, Loyal, Explorer }

enum class EncoreMilestone { Hours10, Hours100, Hours1000, Play1000, Streak30, Artists100 }

enum class EncoreSort { Minutes, Plays }

data class EncoreSummary(
    val period: EncorePeriod,
    val listenedMs: Long,
    /** Some plays came from before Encore recorded listening time; their time is estimated. */
    val estimated: Boolean,
    val plays: Int, val songs: Int, val artists: Int, val albums: Int,
    val previousListenedMs: Long?,
    val bars: List<EncoreBar>,
    val hours: LongArray,
    /** 7 × 24, rows from the week's first day; only for a week or longer. */
    val weekHours: List<LongArray>?,
    /** By sort, then kind (song, album, artist, genre): up to [ENCORE_TOP_LIMIT] each. */
    val tops: Map<EncoreSort, Map<String, List<EncoreItem>>>,
    val habits: EncoreHabits,
    val calendar: Map<LocalDate, Long>,
    val goldenHour: Int?,
    val personality: EncorePersonality?,
    val milestones: List<EncoreMilestone>,
    val historyStart: LocalDate?) {
    val empty get() = plays == 0 && listenedMs == 0L
    fun top(kind: String, sort: EncoreSort = EncoreSort.Minutes) = tops[sort]?.get(kind).orEmpty()
}

const val ENCORE_TOP_LIMIT = 100
const val ENCORE_STREAK_MS = 10 * 60_000L
const val ENCORE_SESSION_GAP_MS = 20 * 60_000L
/** Plays recorded before listening time was kept count as this much, or their length if shorter. */
const val ENCORE_ESTIMATE_MS = 3 * 60_000L
const val ENCORE_GENRE_KIND = "genre"

/** "Night owl" and the rest, from the hours and habits; null when nothing stands out. Rules shown under the label. */
fun encorePersonality(hours: LongArray, discovery: Float?, topArtistShare: Float?): EncorePersonality? {
    val total = hours.sum().takeIf { it > 0 } ?: return null
    val night = (listOf(22, 23, 0, 1, 2, 3).sumOf { hours[it] }).toFloat() / total
    val morning = (5..8).sumOf { hours[it] }.toFloat() / total
    return when {
        night > .40f -> EncorePersonality.NightOwl
        morning > .30f -> EncorePersonality.EarlyBird
        (topArtistShare ?: 0f) > .25f -> EncorePersonality.Loyal
        (discovery ?: 0f) > .30f -> EncorePersonality.Explorer
        else -> null
    }
}

/** The longest run of consecutive days at or over [floor]. */
fun encoreStreak(daily: Map<LocalDate, Long>, floor: Long = ENCORE_STREAK_MS): Int {
    var best = 0; var run = 0; var last: LocalDate? = null
    for (day in daily.filterValues { it >= floor }.keys.sorted()) {
        run = if (last != null && last.plusDays(1) == day) run + 1 else 1
        best = maxOf(best, run); last = day
    }
    return best
}

/** A play as the habits read it. */
data class EncorePlay(val playedAt: Long, val mediaId: String, val title: String, val listenedMs: Long, val durationMs: Long?,
    val ended: Int, val source: String?)

/** Plays without a gap of more than [ENCORE_SESSION_GAP_MS] between one's end and the next's start: (plays, time). */
fun encoreLongestSession(plays: List<EncorePlay>): Pair<Int, Long> {
    var best = 0 to 0L; var count = 0; var time = 0L; var lastEnd = Long.MIN_VALUE
    for (play in plays.sortedBy { it.playedAt }) {
        val heard = play.listenedMs.takeIf { it > 0 } ?: minOf(play.durationMs ?: ENCORE_ESTIMATE_MS, ENCORE_ESTIMATE_MS)
        if (count == 0 || play.playedAt - lastEnd > ENCORE_SESSION_GAP_MS) { count = 0; time = 0 }
        count++; time += heard; lastEnd = maxOf(lastEnd, play.playedAt + heard)
        if (count > best.first || (count == best.first && time > best.second)) best = count to time
    }
    return best
}

/** The milestones crossed between the lifetime totals before the period and at its end. */
fun encoreMilestones(before: Lifetime, after: Lifetime, streak: Int): List<EncoreMilestone> = buildList {
    fun crossed(b: Long, a: Long, mark: Long) = b < mark && a >= mark
    val hour = 3_600_000L
    if (crossed(before.listenedMs, after.listenedMs, 10 * hour)) add(EncoreMilestone.Hours10)
    if (crossed(before.listenedMs, after.listenedMs, 100 * hour)) add(EncoreMilestone.Hours100)
    if (crossed(before.listenedMs, after.listenedMs, 1000 * hour)) add(EncoreMilestone.Hours1000)
    if (crossed(before.plays.toLong(), after.plays.toLong(), 1000)) add(EncoreMilestone.Play1000)
    if (crossed(before.artists.toLong(), after.artists.toLong(), 100)) add(EncoreMilestone.Artists100)
    if (streak >= 30) add(EncoreMilestone.Streak30)
}

data class Lifetime(val listenedMs: Long, val plays: Int, val artists: Int)

/** Movement in rank against the previous period's list: places gained (negative when it fell), or null when new. */
fun encoreRanked(current: List<EncoreItem>, previous: List<EncoreItem>): List<EncoreItem> {
    val before = previous.withIndex().associate { it.value.key to it.index }
    return current.mapIndexed { index, item -> before[item.key]?.let { item.copy(movement = it - index, wasRanked = true) } ?: item.copy(movement = null) }
}

/**
 * Reads Encore for one owner's plays and their phone's own files ([scopes]). [genres] gives an artist's genres when
 * the optional lookup (12.4) is on; null hides genres.
 */
class EncoreRepository(private val db: EncoreReadDatabase, private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val genres: ((List<String>) -> Map<String, List<String>>)? = null):AutoCloseable {
    override fun close()=db.close()

    private fun scopeIn(scopes: List<String>):String {
        require(scopes.size in 1..3&&scopes.distinct().size==scopes.size);scopes.forEach(Scopes::validate)
        return scopes.joinToString(",", "scope IN (", ")") { "?" }
    }

    /** The first day any of [scopes] has a total or a play: where "All time" starts. */
    fun historyStart(scopes: List<String>): LocalDate? {
        val d = db.readableDatabase
        val daily = d.read("SELECT MIN(day) FROM encore_daily WHERE ${scopeIn(scopes)}", scopes.toTypedArray()) { c -> if (c.moveToFirst() && !c.isNull(0)) LocalDate.parse(c.getString(0)) else null }
        return daily
    }

    /** Plays and listened time by year, oldest first, from the song totals: Encore's part of an Export file. */
    fun years(scopes: List<String>): List<Triple<Int, Int, Long>> =
        db.readableDatabase.read("SELECT substr(day,1,4) AS y,SUM(plays),SUM(listened_ms) FROM encore_daily WHERE ${scopeIn(scopes)} AND kind='song' " +
            "GROUP BY y ORDER BY y", scopes.toTypedArray()) { c ->
            buildList { while (c.moveToNext()) c.getString(0).toIntOrNull()?.let { add(Triple(it, c.getInt(1), c.getLong(2))) } }
        }

    private fun dayArgs(scopes: List<String>, period: EncorePeriod) = (scopes + period.start.toString() + period.end.toString()).toTypedArray()

    /** Listening per day from the song totals (each play counts once there). */
    fun daily(scopes: List<String>, period: EncorePeriod): Map<LocalDate, Long> =
        db.readableDatabase.read("SELECT day,SUM(listened_ms) FROM encore_daily WHERE ${scopeIn(scopes)} AND kind='song' AND day>=? AND day<? GROUP BY day",
            dayArgs(scopes, period)) { c -> buildMap { while (c.moveToNext()) put(LocalDate.parse(c.getString(0)), c.getLong(1)) } }

    private fun hourly(scopes: List<String>, period: EncorePeriod): List<Triple<LocalDate, Int, Long>> =
        db.readableDatabase.read("SELECT day,hour,SUM(listened_ms) FROM encore_hourly WHERE ${scopeIn(scopes)} AND day>=? AND day<? GROUP BY day,hour",
            dayArgs(scopes, period)) { c -> buildList { while (c.moveToNext()) add(Triple(LocalDate.parse(c.getString(0)), c.getInt(1), c.getLong(2))) } }

    /** The top [limit] of one kind, by listening time or by plays. */
    fun top(scopes: List<String>, period: EncorePeriod, kind: String, sort: EncoreSort, limit: Int = ENCORE_TOP_LIMIT): List<EncoreItem> {
        require(kind in setOf("song","album","artist")&&limit in 1..100)
        val order = if (sort == EncoreSort.Plays) "plays DESC,ms DESC" else "ms DESC,plays DESC"
        return db.readableDatabase.read("SELECT key,MAX(title),MAX(subtitle),MAX(artwork),SUM(plays) AS plays,SUM(listened_ms) AS ms FROM encore_daily " +
            "WHERE ${scopeIn(scopes)} AND kind=? AND day>=? AND day<? GROUP BY key HAVING plays>0 OR ms>0 ORDER BY $order,key LIMIT $limit",
            (scopes + kind + period.start.toString() + period.end.toString()).toTypedArray()) { c ->
            buildList { while (c.moveToNext()) add(EncoreItem(kind, c.getString(0), c.getString(1), c.getString(2), if (c.isNull(3)) null else c.getString(3), c.getInt(4), c.getLong(5))) }
        }
    }

    private fun counts(scopes: List<String>, period: EncorePeriod): Map<String, Int> =
        db.readableDatabase.read("SELECT kind,COUNT(DISTINCT key) FROM encore_daily WHERE ${scopeIn(scopes)} AND day>=? AND day<? AND (plays>0 OR listened_ms>0) GROUP BY kind",
            dayArgs(scopes, period)) { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) } }

    private fun totals(scopes: List<String>, period: EncorePeriod): Pair<Int, Long> =
        db.readableDatabase.read("SELECT SUM(plays),SUM(listened_ms) FROM encore_daily WHERE ${scopeIn(scopes)} AND kind='song' AND day>=? AND day<?",
            dayArgs(scopes, period)) { c -> if (c.moveToFirst()) c.getInt(0) to c.getLong(1) else 0 to 0L }

    private fun range(period: EncorePeriod): Pair<Long, Long> =
        period.start.atStartOfDay(zone()).toInstant().toEpochMilli() to period.end.atStartOfDay(zone()).toInstant().toEpochMilli()

    /** The plays themselves, for the habits and for estimating plays kept from before listening time was recorded. */
    fun plays(scopes: List<String>, period: EncorePeriod): List<EncorePlay> {
        val (from, to) = range(period)
        return db.readableDatabase.read("SELECT played_at,media_id,title,listened_ms,duration_ms,ended,source FROM encore_plays " +
            "WHERE ${scopeIn(scopes)} AND music=1 AND listened_ms>=5000 AND played_at>=? AND played_at<? ORDER BY played_at", (scopes + from.toString() + to.toString()).toTypedArray()) { c ->
            buildList { while (c.moveToNext()) add(EncorePlay(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), if (c.isNull(4)) null else c.getLong(4), c.getInt(5), c.getString(6))) }
        }
    }

    /** Each artist's first day in all of history: what "discovery" compares against. */
    private fun firstHeard(scopes: List<String>): Map<String, LocalDate> =
        db.readableDatabase.read("SELECT key,MIN(first_day) FROM encore_lifetime WHERE ${scopeIn(scopes)} AND kind='artist' GROUP BY key", scopes.toTypedArray()) { c -> buildMap { while (c.moveToNext()) put(c.getString(0), LocalDate.parse(c.getString(1))) } }

    private fun lifetime(scopes: List<String>, before: LocalDate): Lifetime {
        val period = EncorePeriod(EncoreRange.All, LocalDate.of(1970, 1, 1), before)
        val (plays, ms) = totals(scopes, period)
        return Lifetime(ms, plays, counts(scopes, period)[EncoreKind.ARTIST] ?: 0)
    }

    // Windows totals use actual presented spans only. Unknown legacy listening time is never invented.
    private data class RetainedHabits(val session:Pair<Int,Long>,val completion:Float?,val sources:Map<String,Int>)
    /** Stream retained occurrence facts once; no list of 100,000 plays or per-day lists is materialized. */
    private fun retainedHabits(scopes:List<String>,period:EncorePeriod):RetainedHabits {
        val (from,to)=range(period)
        val args=(scopes+from.toString()+to.toString()).toTypedArray()
        val session=db.read("SELECT played_at,listened_ms FROM encore_plays WHERE ${scopeIn(scopes)} AND listened_ms>=5000 AND played_at>=? AND played_at<? ORDER BY played_at",args){cursor->
            var count=0;var time=0L;var lastEnd=0L;var best=0 to 0L
            while(cursor.moveToNext()){
                val at=cursor.getLong(0);val heard=cursor.getLong(1)
                if(count==0||at-lastEnd>ENCORE_SESSION_GAP_MS){count=0;time=0}
                count++;time+=heard;lastEnd=maxOf(lastEnd,at+heard)
                if(count>best.first||(count==best.first&&time>best.second))best=count to time
            };best
        }
        // Aggregate numeric occurrence facts before joining metadata; do not project a source string per play.
        return db.read("""SELECT SUM(p.finished),SUM(p.finished),SUM(CASE WHEN t.source_kind='local' THEN p.plays ELSE 0 END)
            FROM (SELECT scope,track_id,COUNT(*) AS plays,SUM(completed) AS finished FROM history_occurrence
                WHERE ${scopeIn(scopes)} AND listened_ms>=5000 AND started_ms>=? AND started_ms<? GROUP BY scope,track_id) p
            JOIN track t ON t.scope=p.scope AND t.id=p.track_id WHERE t.is_episode=0""".trimIndent(),args){cursor->
            check(cursor.moveToFirst());val ended=cursor.getInt(1);val device=cursor.getInt(2)
            RetainedHabits(session,if(ended==0)null else cursor.getInt(0).toFloat()/ended,if(device>0)mapOf("device" to device)else emptyMap())
        }
    }

    private fun allTop(scopes:List<String>,kind:String,sort:EncoreSort):List<EncoreItem>{
        val order=if(sort==EncoreSort.Minutes)"ms DESC,plays DESC"else"plays DESC,ms DESC"
        return db.read("SELECT key,MAX(title),MAX(subtitle),MAX(artwork),SUM(plays) AS plays,SUM(listened_ms) AS ms FROM encore_lifetime WHERE ${scopeIn(scopes)} AND kind=? GROUP BY key HAVING plays>0 OR ms>0 ORDER BY $order,key LIMIT 100",(scopes+kind).toTypedArray()){cursor->
            buildList{while(cursor.moveToNext())add(EncoreItem(kind,cursor.getString(0),cursor.getString(1),cursor.getString(2),if(cursor.isNull(3))null else cursor.getString(3),cursor.getInt(4),cursor.getLong(5)))}
        }
    }
    fun summary(scopes: List<String>, period: EncorePeriod, now: Long, firstDay: DayOfWeek = EncorePeriod.firstDayOfWeek()): EncoreSummary = db.snapshot {
        val historyStart=historyStart(scopes)
        val all=period.range==EncoreRange.All&&historyStart==period.start&&db.read("SELECT MAX(last_day) FROM encore_lifetime WHERE ${scopeIn(scopes)}",scopes.toTypedArray()){it.moveToFirst()&&(!it.isNull(0)&&LocalDate.parse(it.getString(0)).isBefore(period.end))}
        val hourly=hourly(scopes,period)
        val daily=hourly.groupBy{it.first}.mapValues{(_,parts)->parts.sumOf{it.third}}
        val hours=LongArray(24).also{values->hourly.forEach{(_,hour,ms)->values[hour]+=ms}}
        val (plays,recorded)=if(all)db.read("SELECT SUM(plays),SUM(listened_ms) FROM encore_lifetime WHERE ${scopeIn(scopes)} AND kind='song'",scopes.toTypedArray()){if(it.moveToFirst())it.getInt(0) to it.getLong(1)else 0 to 0L}else totals(scopes,period)
        val counts=if(all)db.read("SELECT kind,COUNT(DISTINCT key) FROM encore_lifetime WHERE ${scopeIn(scopes)} AND (plays>0 OR listened_ms>0) GROUP BY kind",scopes.toTypedArray()){cursor->buildMap{while(cursor.moveToNext())put(cursor.getString(0),cursor.getInt(1))}}else counts(scopes,period)
        val previous=period.previous()?.takeIf{historyStart!=null&&it.end.isAfter(historyStart)}
        val previousMs=previous?.let{totals(scopes,it).second}
        val kinds=listOf(EncoreKind.SONG,EncoreKind.ALBUM,EncoreKind.ARTIST)
        val tops=EncoreSort.entries.associateWith{sort->
            val lists=kinds.associateWith{kind->
                val current=if(all)allTop(scopes,kind,sort)else top(scopes,period,kind,sort)
                if(previous==null)current else encoreRanked(current,top(scopes,previous,kind,sort))
            }
            genreTop(lists[EncoreKind.ARTIST].orEmpty(),sort)?.let{lists+(ENCORE_GENRE_KIND to it)}?:lists
        }
        val artists=tops[EncoreSort.Minutes]?.get(EncoreKind.ARTIST).orEmpty()
        val discovery=if(historyStart==null||!historyStart.isBefore(period.start))null else {
            val first=firstHeard(scopes);val artistTime=artists.sumOf{it.listenedMs}
            if(artistTime==0L)null else artists.filter{first[it.key]?.let(period::contains)==true}.sumOf{it.listenedMs}.toFloat()/artistTime
        }
        val retained=retainedHabits(scopes,period)
        val heardScopes=db.read("SELECT DISTINCT scope FROM encore_lifetime WHERE ${scopeIn(scopes)} AND kind='song'",scopes.toTypedArray()){cursor->buildList{while(cursor.moveToNext())add(cursor.getString(0))}}
        val repeated=if(heardScopes.size==1)db.read("SELECT title,plays FROM encore_daily INDEXED BY encore_repeat WHERE scope=? AND kind='song' AND day>=? AND day<? AND plays>1 ORDER BY plays DESC,key LIMIT 1",(heardScopes+period.start.toString()+period.end.toString()).toTypedArray()){if(it.moveToFirst())it.getString(0) to it.getInt(1)else null}
            else db.read("SELECT MAX(title),SUM(plays) AS n FROM encore_daily INDEXED BY encore_repeat WHERE ${scopeIn(scopes)} AND kind='song' AND day>=? AND day<? GROUP BY day,key HAVING n>1 ORDER BY n DESC,key LIMIT 1",dayArgs(scopes,period)){if(it.moveToFirst())it.getString(0) to it.getInt(1)else null}
        val streak=encoreStreak(daily)
        val habits=EncoreHabits(streak,retained.session.first,retained.session.second,discovery,retained.completion,repeated?.first,repeated?.second?:0,retained.sources)
        val weekHours=if(period.range==EncoreRange.Day)null else List(7){LongArray(24)}.also{rows->hourly.forEach{(day,hour,ms)->rows[Math.floorMod(day.dayOfWeek.value-firstDay.value,7)][hour]+=ms}}
        val topArtistShare=artists.firstOrNull()?.listenedMs?.let{if(recorded>0)it.toFloat()/recorded else null}
        val before=if(all)Lifetime(0,0,0)else lifetime(scopes,period.start)
        val after=if(all)Lifetime(recorded,plays,counts[EncoreKind.ARTIST]?:0)else lifetime(scopes,period.end)
        EncoreSummary(period,recorded,false,plays,counts[EncoreKind.SONG]?:0,counts[EncoreKind.ARTIST]?:0,counts[EncoreKind.ALBUM]?:0,previousMs,
            bars(period,daily,hourly),hours,weekHours,tops,habits,if(period.range==EncoreRange.Day||period.range==EncoreRange.Week)emptyMap()else daily,
            hours.withIndex().filter{it.value>0}.maxByOrNull{it.value}?.index,encorePersonality(hours,discovery,topArtistShare),encoreMilestones(before,after,streak),historyStart)
    }

    /** Genres from the artists' time, each artist's split across its top two genres; null when genres are off or unknown. */
    private fun genreTop(artists: List<EncoreItem>, sort: EncoreSort): List<EncoreItem>? {
        val lookup = genres ?: return null
        val found = lookup(artists.map { it.title })
        if (found.isEmpty()) return null
        val totals = HashMap<String, Pair<Double, Double>>()
        artists.forEach { artist ->
            val names = found[artist.title].orEmpty().take(2).takeIf { it.isNotEmpty() } ?: return@forEach
            names.forEach { genre ->
                val (ms, plays) = totals[genre] ?: (0.0 to 0.0)
                totals[genre] = (ms + artist.listenedMs.toDouble() / names.size) to (plays + artist.plays.toDouble() / names.size)
            }
        }
        return totals.map { (genre, value) -> EncoreItem(ENCORE_GENRE_KIND, genre, genre, "", null, value.second.toInt(), value.first.toLong()) }
            .sortedWith(if (sort == EncoreSort.Plays) compareByDescending<EncoreItem> { it.plays }.thenByDescending { it.listenedMs }
                else compareByDescending<EncoreItem> { it.listenedMs }.thenByDescending { it.plays })
            .take(ENCORE_TOP_LIMIT).takeIf { it.isNotEmpty() }
    }

    private fun bars(period: EncorePeriod, daily: Map<LocalDate, Long>, hourly: List<Triple<LocalDate, Int, Long>>): List<EncoreBar> = when (period.range) {
        EncoreRange.Day -> (0 until 24).map { hour -> EncoreBar(period.start, hour, hourly.filter { it.second == hour }.sumOf { it.third }, null) }
        EncoreRange.Week, EncoreRange.Month -> (0 until period.days).map { period.start.plusDays(it) }
            .map { day -> EncoreBar(day, null, daily[day] ?: 0, EncorePeriod(EncoreRange.Day, day, day.plusDays(1))) }
        EncoreRange.Year -> (0 until 12).map { period.start.plusMonths(it.toLong()) }
            .map { month -> EncoreBar(month, null, daily.filterKeys { it.year == month.year && it.month == month.month }.values.sum(),
                EncorePeriod(EncoreRange.Month, month, month.plusMonths(1))) }
        EncoreRange.All -> if (ChronoUnit.MONTHS.between(period.start, period.end) < 24)
            generateSequence(period.start.withDayOfMonth(1)) { it.plusMonths(1) }.takeWhile { it.isBefore(period.end) }
                .map { month -> EncoreBar(month, null, daily.filterKeys { it.year == month.year && it.month == month.month }.values.sum(),
                    EncorePeriod(EncoreRange.Month, month, month.plusMonths(1))) }.toList()
        else (period.start.year..period.end.minusDays(1).year).map { year -> LocalDate.of(year, 1, 1) }
            .map { year -> EncoreBar(year, null, daily.filterKeys { it.year == year.year }.values.sum(), EncorePeriod(EncoreRange.Year, year, year.plusYears(1))) }
    }

    /** What was played a year ago today, most played first (12.2 extras, "On this day"). */
    fun onThisDay(scopes: List<String>, today: LocalDate, limit: Int = 5): List<EncoreItem> {
        val day = today.minusYears(1)
        return top(scopes, EncorePeriod(EncoreRange.Day, day, day.plusDays(1)), EncoreKind.SONG, EncoreSort.Plays, limit)
    }

    /** The top songs of all time not played in [quietDays] days (12.2 extras, "Forgotten favourites"). */
    fun forgotten(scopes: List<String>, today: LocalDate, quietDays: Long = 60, limit: Int = 50): List<EncoreItem> {
        val since = today.minusDays(quietDays).toString()
        return db.readableDatabase.read("SELECT key,MAX(title),MAX(subtitle),MAX(artwork),SUM(plays) AS plays,SUM(listened_ms) AS ms FROM encore_daily " +
            "WHERE ${scopeIn(scopes)} AND kind='song' GROUP BY key HAVING MAX(day)<? ORDER BY ms DESC,plays DESC LIMIT $limit",
            (scopes + since).toTypedArray()) { c ->
            buildList { while (c.moveToNext()) add(EncoreItem(EncoreKind.SONG, c.getString(0), c.getString(1), c.getString(2), if (c.isNull(3)) null else c.getString(3), c.getInt(4), c.getLong(5))) }
        }
    }

    /**
     * "Your history with this" (12.2 extras): when [key] was first heard, its plays and time in all, its listening by
     * month (by year past two years), and its rank among its kind this year. Null when it was never played.
     */
    fun history(scopes: List<String>, kind: String, key: String, today: LocalDate): EncoreHistory? = db.snapshot {
        val args = (scopes + kind + key).toTypedArray()
        val where = "${scopeIn(scopes)} AND kind=? AND key=?"
        val d = db.readableDatabase
        val head = d.read("SELECT MIN(day),MAX(title),MAX(subtitle),MAX(artwork),SUM(plays),SUM(listened_ms) FROM encore_daily WHERE $where", args) { c ->
            if (!c.moveToFirst() || c.isNull(0)) null
            else EncoreHistory(c.getString(1), c.getString(2), if (c.isNull(3)) null else c.getString(3), LocalDate.parse(c.getString(0)), c.getInt(4), c.getLong(5), emptyList(), null)
        } ?: return@snapshot null
        val months = d.read("SELECT substr(day,1,7),SUM(listened_ms) FROM encore_daily WHERE $where GROUP BY 1", args) { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1)) } }
        val firstMonth = head.firstDay.withDayOfMonth(1); val lastMonth = maxOf(today, head.firstDay).withDayOfMonth(1)
        val bars = if (java.time.temporal.ChronoUnit.MONTHS.between(firstMonth, lastMonth) < 24)
            generateSequence(firstMonth) { it.plusMonths(1) }.takeWhile { !it.isAfter(lastMonth) }
                .map { m -> EncoreBar(m, null, months[m.toString().take(7)] ?: 0L, EncorePeriod(EncoreRange.Month, m, m.plusMonths(1))) }.toList()
        else (firstMonth.year..lastMonth.year).map { year -> LocalDate.of(year, 1, 1).let { y ->
            EncoreBar(y, null, months.filterKeys { it.startsWith("$year-") }.values.sum(), EncorePeriod(EncoreRange.Year, y, y.plusYears(1))) } }
        val year = today.withDayOfYear(1).toString(); val nextYear = today.withDayOfYear(1).plusYears(1).toString()
        val mine = d.read("SELECT SUM(listened_ms) FROM encore_daily WHERE $where AND day>=? AND day<?", (scopes + kind + key + year + nextYear).toTypedArray()) { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L }
        val rank = if (mine <= 0) null else d.read("SELECT COUNT(*) FROM (SELECT key FROM encore_daily WHERE ${scopeIn(scopes)} AND kind=? AND day>=? AND day<? " +
            "GROUP BY key HAVING SUM(listened_ms)>CAST(? AS INTEGER))", (scopes + kind + year + nextYear + mine.toString()).toTypedArray()) { c -> if (c.moveToFirst()) c.getInt(0) + 1 else null }
        head.copy(bars = bars, rankThisYear = rank)
    }

    /** Today's listening and top song, for the Library card. */
    fun quick(scopes: List<String>, period: EncorePeriod): Pair<Long, EncoreItem?> =
        totals(scopes, period).second to top(scopes, period, EncoreKind.SONG, EncoreSort.Minutes, 1).firstOrNull()

    fun genreArtists(scopes:List<String>):List<EncoreGenreArtist> = db.read("SELECT MAX(title),GROUP_CONCAT(DISTINCT scope) FROM encore_lifetime WHERE ${scopeIn(scopes)} AND kind='artist' GROUP BY key ORDER BY SUM(listened_ms) DESC,key LIMIT 200",scopes.toTypedArray()){cursor->
        buildList{while(cursor.moveToNext())add(EncoreGenreArtist(cursor.getString(0),cursor.getString(1).split(',')))}
    }
    fun database(): EncoreReadDatabase = db.readableDatabase
}

object EncoreKind { const val SONG="song"; const val ALBUM="album"; const val ARTIST="artist" }
object EncoreEnded { const val PLAYING=0; const val FINISHED=1; const val SKIPPED=2; const val STOPPED=3 }

data class EncoreGenreArtist(val name:String,val scopes:List<String>)
