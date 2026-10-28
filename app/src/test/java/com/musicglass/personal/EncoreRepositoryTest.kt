package com.musicglass.personal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Call
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.TimeZone
import java.util.UUID

/** Feature 12, sections 12.2 and 12.4: what the Encore page reads from the daily totals, and the genre lookup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EncoreRepositoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val name = "encore-read-${UUID.randomUUID()}.db"
    private lateinit var db: LibraryStateDb
    private lateinit var history: HistoryRepository
    private var now = 0L
    private val zone = TimeZone.getDefault()

    @Before fun open() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        db = LibraryStateDb(context, name)
        history = HistoryRepository(db, ScopeEpochs(db).also { it.warm() }, { now })
    }
    @After fun close() { db.close(); context.deleteDatabase(name); TimeZone.setDefault(zone) }

    private fun at(day: String, hour: Int) = LocalDate.parse(day).atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private var serial = 0
    /** One play of [song] by [artist] at [day] [hour]:00, heard for [minutes]. */
    private fun play(day: String, hour: Int, song: String, artist: String, minutes: Long, ended: Int = EncoreEnded.FINISHED) {
        now = at(day, hour)
        val entry = HistoryEntry("yt:$song", song, artist, null, now, true, "e-%06d".format(serial++), 240_000L,
            album = "$song album", albumId = "MPRE$song", source = EncoreSource.STREAM)
        history.record(entry, history.ticket("owner"))
        history.addListened("owner", entry.eventId, minutes * 60_000L, now)
        history.markEnded("owner", entry.eventId, ended)
    }
    private val repo get() = EncoreRepository(db, { ZoneOffset.UTC })
    private fun week(start: String) = EncorePeriod(EncoreRange.Week, LocalDate.parse(start), LocalDate.parse(start).plusWeeks(1))

    @Test fun periodsFollowTheCalendarAndTheLocalesFirstDay() {
        val wednesday = LocalDate.parse("2026-10-28")
        assertEquals(LocalDate.parse("2026-10-26"), EncorePeriod.containing(EncoreRange.Week, wednesday, DayOfWeek.MONDAY, null).start)
        assertEquals(LocalDate.parse("2026-10-25"), EncorePeriod.containing(EncoreRange.Week, wednesday, DayOfWeek.SUNDAY, null).start)
        val month = EncorePeriod.containing(EncoreRange.Month, wednesday, DayOfWeek.MONDAY, null)
        assertEquals(LocalDate.parse("2026-09-01"), month.previous()!!.start)
        assertNull("no period after today", month.next(wednesday))
        assertEquals(LocalDate.parse("2026-01-01"), EncorePeriod.containing(EncoreRange.Year, wednesday, DayOfWeek.MONDAY, null).start)
        assertEquals(LocalDate.parse("2026-07-27"), EncorePeriod.containing(EncoreRange.All, wednesday, DayOfWeek.MONDAY, LocalDate.parse("2026-07-27")).start)
    }

    @Test fun aWeekAddsUpWithTopsBarsAndTheChangeOnTheWeekBefore() {
        play("2026-10-19", 20, "AAAAAAAAAA1", "Meira Omar", 10)
        play("2026-10-26", 22, "AAAAAAAAAA1", "Meira Omar", 30)
        play("2026-10-26", 23, "AAAAAAAAAA1", "Meira Omar", 20)
        play("2026-10-27", 9, "BBBBBBBBBB2", "Arijit Singh", 15, EncoreEnded.SKIPPED)
        val summary = repo.summary(listOf("owner"), week("2026-10-26"), at("2026-10-30", 12), DayOfWeek.MONDAY)
        assertEquals(65 * 60_000L, summary.listenedMs)
        assertEquals(3, summary.plays); assertEquals(2, summary.songs); assertEquals(2, summary.artists)
        assertEquals(10 * 60_000L, summary.previousListenedMs)
        assertEquals("AAAAAAAAAA1", summary.top(EncoreKind.SONG).first().title)
        assertEquals("by plays, too", 2, summary.top(EncoreKind.SONG, EncoreSort.Plays).first().plays)
        assertEquals(7, summary.bars.size)
        assertEquals(50 * 60_000L, summary.bars[0].listenedMs)
        assertEquals(EncoreRange.Day, summary.bars[0].opens?.range)
        assertEquals(22, summary.goldenHour)
        assertEquals("two of three finished", 2f / 3, summary.habits.completion!!, .001f)
        assertEquals("both Monday plays repeat one song", 2, summary.habits.mostRepeatedPlays)
        assertEquals(30 * 60_000L, summary.weekHours!![0][22])
    }

    @Test fun rankMovementComparesWithThePreviousList() {
        val before = listOf(EncoreItem("song", "a", "A", "", null, 1, 1), EncoreItem("song", "b", "B", "", null, 1, 1))
        val now = listOf(EncoreItem("song", "b", "B", "", null, 1, 1), EncoreItem("song", "c", "C", "", null, 1, 1), EncoreItem("song", "a", "A", "", null, 1, 1))
        val ranked = encoreRanked(now, before)
        assertEquals(1, ranked[0].movement)
        assertTrue("new", !ranked[1].wasRanked)
        assertEquals(-2, ranked[2].movement)
    }

    @Test fun streaksSessionsAndPersonality() {
        val daily = mapOf(LocalDate.parse("2026-10-01") to 20 * 60_000L, LocalDate.parse("2026-10-02") to 11 * 60_000L,
            LocalDate.parse("2026-10-03") to 5 * 60_000L, LocalDate.parse("2026-10-04") to 30 * 60_000L)
        assertEquals("a day under 10 minutes breaks it", 2, encoreStreak(daily))
        val plays = listOf(EncorePlay(0, "a", "A", 180_000, null, 1, null), EncorePlay(200_000, "b", "B", 180_000, null, 1, null),
            EncorePlay(200_000 + 3_600_000, "c", "C", 180_000, null, 1, null))
        assertEquals(2 to 360_000L, encoreLongestSession(plays))
        val night = LongArray(24).also { it[23] = 50; it[12] = 50 }
        assertEquals(EncorePersonality.NightOwl, encorePersonality(night, null, null))
        assertEquals(EncorePersonality.Loyal, encorePersonality(LongArray(24).also { it[12] = 1 }, .1f, .3f))
        assertNull(encorePersonality(LongArray(24), null, null))
    }

    @Test fun oldPlaysWithoutTimeAreEstimatedAndSaySo() {
        now = at("2026-10-26", 8)
        history.record(HistoryEntry("yt:CCCCCCCCCC3", "Old", "Someone", null, now, true, "e-old", 120_000L), history.ticket("owner"))
        val summary = repo.summary(listOf("owner"), week("2026-10-26"), at("2026-10-30", 12), DayOfWeek.MONDAY)
        assertEquals(120_000L, summary.listenedMs)
        assertTrue(summary.estimated)
    }

    @Test fun milestonesAreThoseCrossedInThePeriod() {
        val before = Lifetime(9 * 3_600_000L, 999, 99)
        val after = Lifetime(11 * 3_600_000L, 1001, 100)
        assertEquals(listOf(EncoreMilestone.Hours10, EncoreMilestone.Play1000, EncoreMilestone.Artists100), encoreMilestones(before, after, 3))
    }

    @Test fun genresKeepOnlyRealOnesFromTheBestMatch() {
        val answer = Json.parseToJsonElement("""{"artists":[{"name":"Queen","score":100,"tags":[{"name":"rock","count":12},
            {"name":"seen live","count":30},{"name":"hard rock","count":4},{"name":"glam rock","count":2}]}]}""")
        assertEquals(listOf("Rock"), musicBrainzGenres(answer, "queen"))
        assertEquals(emptyList<String>(), musicBrainzGenres(Json.parseToJsonElement("""{"artists":[{"name":"Other","score":40}]}"""), "Queen"))
    }

    @Test fun genresSendNothingWhenOffOrMetered() {
        var calls = 0
        val http = Call.Factory { calls++; error("no request expected") }
        var on = false; var wifi = true
        val genres = EncoreGenres(db, http, { now }, enabled = { on }, unmetered = { wifi }, pause = { })
        assertEquals(0, runBlocking { genres.refresh(listOf("Queen")) })
        on = true; wifi = false
        assertEquals(0, runBlocking { genres.refresh(listOf("Queen")) })
        assertEquals(0, calls)
        assertEquals(emptyMap<String, List<String>>(), genres.cached(listOf("Queen")))
    }
}
