package com.musicglass.data

import com.musicglass.data.encore.*
import java.time.*
import kotlin.test.*

/** Statistics fixtures exercise real SQLite only; none can become application playback content. */
class EncoreIntegrationTest {
    private fun withStore(action:(SqliteLibraryRepository,HistoryRepository,EncoreRepository)->Unit){
        val dir=kotlin.io.path.createTempDirectory("riff-encore-").toFile()
        try{SqliteLibraryRepository(dir).use{store->
            store.setPrivacy(Scopes.LOCAL,PrivacyPreferences(retentionDays=0))
            store.upsert(TrackRecord(Scopes.LOCAL,"music","Recording","A & B","Album",artistId="channel-A"))
            store.upsert(TrackRecord(Scopes.LOCAL,"episode","Episode","Speaker",isEpisode=true))
            val history=HistoryRepository(store,ZoneId.of("UTC"))
            EncoreRepository(EncoreReadDatabase(store),zone={ZoneId.of("UTC")}).use{repository->action(store,history,repository)}
        }}finally{dir.deleteRecursively()}
    }
    private fun period(range:EncoreRange,day:String)=EncorePeriod.containing(range,LocalDate.parse(day),DayOfWeek.MONDAY,null)
    private fun read(repository:EncoreRepository,period:EncorePeriod)=repository.summary(listOf(Scopes.LOCAL),period,Instant.parse("2026-11-03T12:00:00Z").toEpochMilli(),DayOfWeek.MONDAY)
    @Test fun realSpansProduceMusicOnlyPeriodsCreditedArtistsAndUnknownCompletion()=withStore{store,history,repository->
        val at=Instant.parse("2026-11-02T23:59:58Z").toEpochMilli()
        history.record(ListeningSpan(Scopes.LOCAL,"a","play","music",at,3000))
        history.record(ListeningSpan(Scopes.LOCAL,"b","play","music",at+3000,3000))
        history.record(ListeningSpan(Scopes.LOCAL,"b","play","music",at+3000,3000))
        history.record(ListeningSpan(Scopes.LOCAL,"episode","talk","episode",at,5000))
        val day=read(repository,period(EncoreRange.Day,"2026-11-02"))
        assertEquals(2000L,day.listenedMs);assertEquals(1,day.plays);assertEquals(2,day.artists);assertEquals(1,day.albums)
        assertEquals(2000L,day.hours[23]);assertNull(day.habits.completion)
        val next=read(repository,period(EncoreRange.Day,"2026-11-03"))
        assertEquals(4000L,next.listenedMs);assertEquals(0,next.plays)
        val week=read(repository,period(EncoreRange.Week,"2026-11-03"))
        assertEquals(6000L,week.listenedMs);assertEquals(1,week.songs);assertEquals(6000L,week.top(EncoreKind.ARTIST).first().listenedMs)
        assertEquals(2,week.top(EncoreKind.ARTIST).size);assertEquals(1,week.habits.longestSessionPlays)
        assertEquals(6000L,week.habits.longestSessionMs)
        assertEquals(6000L,store.query("SELECT sum(listened_ms) FROM encore_hourly"){it.getLong(1)}.single())
    }
    @Test fun rollupAndSpanRollbackAndClearAreAtomic()=withStore{store,history,repository->
        val span=ListeningSpan(Scopes.LOCAL,"one","play","music",Instant.parse("2026-11-02T12:00:00Z").toEpochMilli(),5000)
        assertFailsWith<IllegalStateException>{store.transaction{history.record(span);error("power boundary")}}
        assertEquals(0L,read(repository,period(EncoreRange.Week,"2026-11-03")).listenedMs)
        val old=history.epoch(Scopes.LOCAL);history.record(span);history.clear(Scopes.LOCAL);history.record(span.copy(id="late"),old)
        assertEquals(0L,store.query("SELECT count(*) FROM encore_daily"){it.getLong(1)}.single())
        assertEquals(0L,store.query("SELECT count(*) FROM encore_hourly"){it.getLong(1)}.single())
    }
    @Test fun retainingTotalsPreservesTopsUntilOwnerTurnsItOff()=withStore{store,history,repository->
        store.setPrivacy(Scopes.LOCAL,PrivacyPreferences(retentionDays=30,keepTotals=true))
        history.record(ListeningSpan(Scopes.LOCAL,"one","play","music",Instant.parse("2026-09-01T12:00:00Z").toEpochMilli(),5000))
        history.retain(Scopes.LOCAL,Instant.parse("2026-11-03T12:00:00Z").toEpochMilli())
        assertEquals(0L,store.query("SELECT count(*) FROM history_span"){it.getLong(1)}.single())
        assertEquals(5000L,read(repository,period(EncoreRange.Year,"2026-11-03")).listenedMs)
        store.setPrivacy(Scopes.LOCAL,PrivacyPreferences(retentionDays=30,keepTotals=false));history.retain(Scopes.LOCAL)
        assertEquals(0L,read(repository,period(EncoreRange.Year,"2026-11-03")).listenedMs)
    }
    @Test fun rebuildingProducesIdenticalRowsAndDoesNotDoubleCount()=withStore{store,history,repository->
        repeat(25){i->history.record(ListeningSpan(Scopes.LOCAL,"$i","play-${i/5}","music",Instant.parse("2026-11-02T12:00:00Z").toEpochMilli()+i*1000,1000))}
        val before=read(repository,period(EncoreRange.Month,"2026-11-03"))
        repeat(2){store.transaction{EncoreRollup(store,ZoneId.of("UTC")).rebuild(Scopes.LOCAL)}}
        val after=read(repository,period(EncoreRange.Month,"2026-11-03"))
        assertEquals(before.listenedMs,after.listenedMs);assertEquals(before.plays,after.plays);assertEquals(before.top("song"),after.top("song"))
        assertEquals(5,after.plays);assertEquals(5,after.habits.longestSessionPlays);assertEquals(25000L,after.habits.longestSessionMs)
    }
    @Test fun sameRecordingAcrossLocalAndGuestScopesCombinesItsRealDailyRepeatCount()=withStore{store,history,repository->
        store.setPrivacy(Scopes.GUEST,PrivacyPreferences(retentionDays=0))
        store.upsert(TrackRecord(Scopes.GUEST,"music","Recording","A & B","Album",artistId="channel-A",sourceKind="youtube"))
        val span=ListeningSpan(Scopes.LOCAL,"local","play","music",Instant.parse("2026-11-02T12:00:00Z").toEpochMilli(),5000)
        history.record(span);history.record(span.copy(scope=Scopes.GUEST,id="guest"))
        val value=repository.summary(listOf(Scopes.LOCAL,Scopes.GUEST),period(EncoreRange.Week,"2026-11-03"),System.currentTimeMillis())
        assertEquals(10000L,value.listenedMs);assertEquals(2,value.plays);assertEquals(1,value.songs)
        assertEquals(2,value.habits.mostRepeatedPlays);assertEquals("Recording",value.habits.mostRepeatedTitle)
        assertEquals(2,value.habits.longestSessionPlays)
    }
    @Test fun aBoundedAllTimePeriodCannotReadLaterLifetimeFacts()=withStore{_,history,repository->
        val at=Instant.parse("2026-11-01T12:00:00Z").toEpochMilli()
        history.record(ListeningSpan(Scopes.LOCAL,"first","one","music",at,5000))
        history.record(ListeningSpan(Scopes.LOCAL,"second","two","music",at+86400000,5000))
        val value=read(repository,EncorePeriod(EncoreRange.All,LocalDate.parse("2026-11-01"),LocalDate.parse("2026-11-02")))
        assertEquals(5000L,value.listenedMs);assertEquals(1,value.plays)
    }
    @Test fun sessionBoundaryUsesTheLatestOverlappingEndAndTheExactTwentyMinuteRule()=withStore{_,history,repository->
        val at=Instant.parse("2026-11-02T12:00:00Z").toEpochMilli()
        for((index,offset) in listOf(0L,1000L,1206000L,2412001L).withIndex())history.record(ListeningSpan(Scopes.LOCAL,"$index","$index","music",at+offset,5000))
        val value=read(repository,period(EncoreRange.Day,"2026-11-02"))
        assertEquals(3,value.habits.longestSessionPlays);assertEquals(15000L,value.habits.longestSessionMs)
    }
    @Test fun hourAttributionHandlesBothDstHoursAndDateLineSkip(){
        assertEquals(listOf(HistoryHourPart("2026-11-01",1,2000),HistoryHourPart("2026-11-01",1,3000)),historyHourParts(Instant.parse("2026-11-01T05:59:58Z").toEpochMilli(),5000,ZoneId.of("America/New_York")))
        assertEquals(listOf(HistoryHourPart("2011-12-29",23,1000),HistoryHourPart("2011-12-31",0,1000)),historyHourParts(Instant.parse("2011-12-30T09:59:59Z").toEpochMilli(),2000,ZoneId.of("Pacific/Apia")))
    }
    @Test fun readSnapshotsDoNotPublishAWriteRevision()=withStore{store,_,repository->
        val before=store.revisions.value
        repeat(3){read(repository,period(EncoreRange.Week,"2026-11-03"))}
        assertEquals(before,store.revisions.value)
    }
    @Test fun weeksAndPeriodsFollowLocaleAndCalendarBoundaries(){
        assertEquals(LocalDate.parse("2026-10-26"),period(EncoreRange.Week,"2026-11-01").start)
        assertEquals(LocalDate.parse("2026-10-01"),period(EncoreRange.Month,"2026-11-03").previous()!!.start)
        assertNull(period(EncoreRange.Month,"2026-11-03").next(LocalDate.parse("2026-11-30")))
        assertEquals(2,encoreStreak(mapOf(LocalDate.parse("2026-11-01") to 600000L,LocalDate.parse("2026-11-02") to 600000L)))
    }
}
