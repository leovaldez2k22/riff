package com.musicglass.personal

import android.app.job.JobScheduler
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Feature 12 extras: the weekly goal, Home's Sunday row and the weekly recap's timing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EncoreWeeklyTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneId.of("Europe/London")
    // 1 November 2026 is a Sunday.
    private val sunday = LocalDate.of(2026, 11, 1)
    private fun at(day: LocalDate, hour: Int, minute: Int = 0) = day.atTime(hour, minute).atZone(zone)

    @After fun off() { setEncoreRecapOn(context, false); setEncoreGoalHours(context, 0) }

    @Test fun theGoalRingFillsWithTheWeekAndStopsWhenFull() {
        assertEquals(0f, encoreGoalProgress(3_600_000, 0))
        assertEquals(.5f, encoreGoalProgress(3_600_000, 2), 1e-6f)
        assertEquals(1f, encoreGoalProgress(30 * 3_600_000L, 5))
        assertEquals(0f, encoreGoalProgress(0, 5))
    }

    @Test fun onlyTheOfferedGoalsAreKept() {
        setEncoreGoalHours(context, 5)
        assertEquals(5, encoreGoalHours(context))
        setEncoreGoalHours(context, 7)
        assertEquals("a goal not offered is off", 0, encoreGoalHours(context))
    }

    @Test fun aSundayRecapNamesTheWeekThatIsEnding() {
        assertEquals("a week from Monday: Sunday is its last day", sunday, encoreRecapDay(sunday, DayOfWeek.MONDAY))
        assertEquals("a week from Sunday: today began a new week", sunday.minusDays(1), encoreRecapDay(sunday, DayOfWeek.SUNDAY))
        assertEquals(sunday.minusDays(3), encoreRecapDay(sunday.minusDays(3), DayOfWeek.SUNDAY))
        assertTrue(encoreRecapToday(sunday))
        assertFalse(encoreRecapToday(sunday.plusDays(1)))
    }

    @Test fun theRecapIsDueOnSundayEvening() {
        val due = at(sunday, 18)
        assertEquals("from midweek", due, nextEncoreRecap(at(sunday.minusDays(3), 9), sentThisWeek = false))
        assertEquals("on Sunday morning", due, nextEncoreRecap(at(sunday, 9), sentThisWeek = false))
        assertEquals("a little late is still this week's", due, nextEncoreRecap(at(sunday, 20), sentThisWeek = false))
        assertEquals("too late waits a week", due.plusWeeks(1), nextEncoreRecap(at(sunday, 21, 30), sentThisWeek = false))
        assertEquals("once sent, the next week", due.plusWeeks(1), nextEncoreRecap(at(sunday, 18, 5), sentThisWeek = true))
        assertEquals("Monday waits for Sunday", due.plusWeeks(1), nextEncoreRecap(at(sunday.plusDays(1), 9), sentThisWeek = false))
    }

    @Test fun theSwitchSetsAndClearsTheOneJob() {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        assertTrue(scheduler.allPendingJobs.none { it.service.className == EncoreRecapJob::class.java.name })
        setEncoreRecapOn(context, true)
        val job = scheduler.allPendingJobs.single { it.service.className == EncoreRecapJob::class.java.name }
        assertTrue(job.isPersisted)
        setEncoreRecapOn(context, false)
        assertNull(scheduler.allPendingJobs.firstOrNull { it.service.className == EncoreRecapJob::class.java.name })
    }

    @Test fun theScheduledLatencyReachesSundayEvening() {
        setEncoreRecapOn(context, true)
        val now = ZonedDateTime.of(sunday.minusDays(1).atTime(18, 0), zone)
        EncoreRecap.schedule(context, now)
        val job = context.getSystemService(JobScheduler::class.java).allPendingJobs.single { it.service.className == EncoreRecapJob::class.java.name }
        assertEquals(24 * 3_600_000L, job.minLatencyMillis)
    }
}
