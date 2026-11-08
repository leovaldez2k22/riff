package com.musicglass.personal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
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

/** Feature 13 (Riff+, Q13): mixes from Encore's own tables. Every song and artist here is invented. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EncoreMixesTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val name = "encore-mix-${UUID.randomUUID()}.db"
    private lateinit var db: LibraryStateDb
    private lateinit var history: HistoryRepository
    private var now = 0L
    private val zone = TimeZone.getDefault()
    private var serial = 0

    @Before fun open() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        db = LibraryStateDb(context, name)
        history = HistoryRepository(db, ScopeEpochs(db).also { it.warm() }, { now })
    }
    @After fun close() { db.close(); context.deleteDatabase(name); TimeZone.setDefault(zone) }

    private fun id(n: Int) = "song%07d".format(n).take(11)
    private fun play(day: LocalDate, hour: Int, song: Int, artist: String, minutes: Long = 3) {
        now = day.atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val entry = HistoryEntry("yt:${id(song)}", "Song $song", artist, null, now, true, "e-%06d".format(serial++), 240_000L, source = EncoreSource.STREAM)
        history.record(entry, history.ticket("owner"))
        history.addListened("owner", entry.eventId, minutes * 60_000L, now)
    }

    private object Plain : MixText {
        override fun onRepeat() = "On repeat"; override fun onRepeatDetail() = ""
        override fun timeOfDay(part: DayPart) = "Your ${part.name}"; override fun timeOfDayDetail() = ""
        override fun heatingUp() = "Heating up"; override fun heatingUpDetail() = ""
        override fun newFinds() = "New finds"; override fun newFindsDetail() = ""
        override fun artist(name: String) = "$name mix"; override fun artistDetail() = ""
        override fun weekday(day: DayOfWeek) = "Your $day"; override fun weekdayDetail() = ""
        override fun season() = "Season throwback"; override fun seasonDetail(from: LocalDate, to: LocalDate) = "$from..$to"
        override fun genre(name: String) = "Your $name"; override fun genreDetail() = ""
    }

    private val today = LocalDate.parse("2026-11-06") // a Friday
    private fun mixes(hour: Int = 9, genres: Map<String, List<String>> = emptyMap(), blocked: Set<String> = emptySet()) =
        EncoreMixer(db.readableDatabase, listOf("owner", DEVICE_LOCAL_SCOPE_KEY), today, hour, ZoneOffset.UTC, { genres }, { it.videoId in blocked }, Plain).build()

    @Test fun noHistoryMakesNoMixesAndAFewPlaysAreNotEnough() {
        assertEquals(emptyList<EncoreMix>(), mixes())
        (1..5).forEach { s -> repeat(3) { play(today.minusDays(1), 9, s, "Band") } }
        assertTrue("Five songs are fewer than a mix needs", mixes().none { it.kind == MixKind.OnRepeat })
    }

    @Test fun onRepeatRanksByPlaysAndLeavesOutBlockedSongs() {
        (1..12).forEach { s -> repeat(2 + s) { play(today.minusDays(2), 20, s, "Band $s") } }
        val mix = mixes(blocked = setOf(id(12))).single { it.kind == MixKind.OnRepeat }
        assertEquals(id(11), mix.tracks.first().videoId)
        assertTrue(mix.tracks.none { it.videoId == id(12) }); assertEquals(11, mix.tracks.size)
        assertEquals("Deterministic", mix, mixes(blocked = setOf(id(12))).single { it.kind == MixKind.OnRepeat })
    }

    @Test fun timeOfDayTakesOnlyPlaysInThisPartOfTheDay() {
        (1..9).forEach { s -> repeat(2) { play(today.minusDays(3), 7, s, "Morning band") } }
        (20..29).forEach { s -> repeat(2) { play(today.minusDays(3), 22, s, "Night band") } }
        val morning = mixes(hour = 8).single { it.kind == MixKind.TimeOfDay }
        assertEquals("Your Morning", morning.title); assertTrue(morning.tracks.all { it.artist == "Morning band" })
        assertEquals("Your Evening", mixes(hour = 21).single { it.kind == MixKind.TimeOfDay }.title)
        assertNull(mixes(hour = 14).firstOrNull { it.kind == MixKind.TimeOfDay })
    }

    @Test fun heatingUpAndNewFindsFollowTheirRules() {
        // Old favourites played a lot before and the same this week: not heating up.
        (1..9).forEach { s -> (8..34 step 2).forEach { d -> play(today.minusDays(d.toLong()), 12, s, "Old") }; repeat(3) { play(today.minusDays(1), 12, s, "Old") } }
        // New songs, first heard this week, played three times: heating up and new finds.
        (40..48).forEach { s -> repeat(3) { play(today.minusDays(2), 12, s, "New") } }
        val all = mixes()
        assertTrue(all.single { it.kind == MixKind.HeatingUp }.tracks.all { it.artist == "New" })
        assertTrue(all.single { it.kind == MixKind.NewFinds }.tracks.all { it.artist == "New" })
    }

    @Test fun artistWeekdaySeasonAndGenreMixes() {
        // Two credits per song: "Lead & Guest" counts for both, as Encore does.
        (1..10).forEach { s -> repeat(2) { play(today.minusDays(7), 18, s, "Lead & Guest", 10) } }
        (11..20).forEach { s -> repeat(2) { play(today.minusDays(14), 18, s, "Other", 1) } }
        // Last autumn (September to November 2025).
        (30..39).forEach { s -> repeat(2) { play(LocalDate.parse("2025-10-10"), 12, s, "Autumn") } }
        val all = mixes(genres = mapOf("Lead" to listOf("jazz"), "Guest" to listOf("jazz"), "Other" to listOf("rock")))
        val artists = all.filter { it.kind == MixKind.Artist }.map { it.title }
        assertEquals("Ties go by name", listOf("Guest mix", "Lead mix", "Other mix"), artists)
        val friday = all.single { it.kind == MixKind.Weekday }
        assertEquals("Your FRIDAY", friday.title); assertEquals(20, friday.tracks.size)
        val season = all.single { it.kind == MixKind.Season }
        assertEquals("2025-09-01..2025-11-30", season.detail); assertTrue(season.tracks.all { it.artist == "Autumn" })
        assertEquals(listOf("Your jazz", "Your rock"), all.filter { it.kind == MixKind.Genre }.map { it.title })
        assertTrue("Genres off: no genre mixes", mixes().none { it.kind == MixKind.Genre })
    }

    @Test fun seasonsAndDayParts() {
        assertEquals(LocalDate.parse("2025-12-01"), seasonStart(LocalDate.parse("2026-02-28")))
        assertEquals(LocalDate.parse("2026-12-01"), seasonStart(LocalDate.parse("2026-12-31")))
        assertEquals(LocalDate.parse("2026-03-01"), seasonStart(LocalDate.parse("2026-05-31")))
        assertEquals(LocalDate.parse("2026-09-01"), seasonStart(LocalDate.parse("2026-11-08")))
        assertEquals(DayPart.Night, DayPart.of(4)); assertEquals(DayPart.Morning, DayPart.of(5)); assertEquals(DayPart.Evening, DayPart.of(23))
    }

    @Test fun theCacheIsKeyedByOwnerDayPartAndHistory() {
        val a = EncoreMixCache.cacheKey(listOf("owner"), today, DayPart.Morning, today.minusDays(3), false)
        EncoreMixCache.put(a, emptyList()); assertNotNull(EncoreMixCache.get(a))
        assertNull(EncoreMixCache.get(EncoreMixCache.cacheKey(listOf("owner"), today, DayPart.Morning, null, false)))
        assertNull(EncoreMixCache.get(EncoreMixCache.cacheKey(listOf("other"), today, DayPart.Morning, today.minusDays(3), false)))
        assertNull(EncoreMixCache.get(EncoreMixCache.cacheKey(listOf("owner"), today.plusDays(1), DayPart.Morning, today.minusDays(3), false)))
        EncoreMixCache.clear()
    }
}
