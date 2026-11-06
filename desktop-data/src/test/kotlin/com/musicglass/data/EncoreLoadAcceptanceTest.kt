package com.musicglass.data

import com.musicglass.data.encore.*
import java.io.File
import java.time.*
import kotlin.test.*

/** Synthetic relational load only. Never a playback, provider or UI acceptance fixture. */
class EncoreLoadAcceptanceTest {
    @Test fun measureFullPeriodAtTheActualRetainedOccurrenceCap(){
        val directory=kotlin.io.path.createTempDirectory("riff-encore-load-").toFile()
        try{SqliteLibraryRepository(directory).use{store->
            store.setPrivacy(Scopes.LOCAL,PrivacyPreferences(retentionDays=0));HistoryRepository(store,ZoneId.of("UTC"))
            store.transaction{
                repeat(100){i->upsert(TrackRecord(Scopes.LOCAL,"track-$i","Load recording $i","Artist ${i%10}","Album ${i%10}"))}
                execute("""WITH RECURSIVE numbers(n) AS(SELECT 0 UNION ALL SELECT n+1 FROM numbers WHERE n<99999)
                    INSERT INTO history_occurrence SELECT 'local','play-'||n,'track-'||(n%100),1609491600000+(n/100)*86400000+(n%100)*6000,
                    1609491605000+(n/100)*86400000+(n%100)*6000,5000,0 FROM numbers""")
                execute("""INSERT INTO encore_daily SELECT h.scope,date(h.started_ms/1000,'unixepoch'),'song',t.id,t.title,t.artist,NULL,count(*),sum(h.listened_ms)
                    FROM history_occurrence h JOIN track t ON t.scope=h.scope AND t.id=h.track_id GROUP BY h.scope,date(h.started_ms/1000,'unixepoch'),t.id""")
                execute("""INSERT INTO encore_daily SELECT h.scope,date(h.started_ms/1000,'unixepoch'),'artist','name:'||lower(t.artist),t.artist,'',NULL,count(*),sum(h.listened_ms)
                    FROM history_occurrence h JOIN track t ON t.scope=h.scope AND t.id=h.track_id GROUP BY h.scope,date(h.started_ms/1000,'unixepoch'),t.artist""")
                execute("""INSERT INTO encore_daily SELECT h.scope,date(h.started_ms/1000,'unixepoch'),'album',t.album_id,t.album,t.artist,NULL,count(*),sum(h.listened_ms)
                    FROM history_occurrence h JOIN track t ON t.scope=h.scope AND t.id=h.track_id GROUP BY h.scope,date(h.started_ms/1000,'unixepoch'),t.album_id""")
                execute("""INSERT INTO encore_lifetime SELECT scope,kind,key,MAX(title),MAX(subtitle),MAX(artwork),SUM(plays),SUM(listened_ms),MIN(day),MAX(day) FROM encore_daily GROUP BY scope,kind,key""")
                execute("""INSERT INTO encore_hourly SELECT scope,date(started_ms/1000,'unixepoch'),CAST(strftime('%H',started_ms/1000,'unixepoch') AS INTEGER),sum(listened_ms)
                    FROM history_occurrence GROUP BY scope,date(started_ms/1000,'unixepoch'),strftime('%H',started_ms/1000,'unixepoch')""")
            }
            val queryTimes=mutableListOf<String>()
            EncoreRepository(EncoreReadDatabase(store){sql,ms->queryTimes+="$ms ms: $sql"},zone={ZoneId.of("UTC")}).use{repository->
            val period=EncorePeriod.containing(EncoreRange.All,LocalDate.parse("2026-11-03"),DayOfWeek.MONDAY,LocalDate.parse("2021-01-01"))
            val milliseconds=List(3){
                val before=System.nanoTime();val result=repository.summary(listOf(Scopes.LOCAL),period,Instant.parse("2026-11-03T12:00:00Z").toEpochMilli())
                assertEquals(500000000L,result.listenedMs);assertEquals(100000,result.plays);assertEquals(100,result.songs);assertEquals(10,result.artists);assertEquals(10,result.albums)
                (System.nanoTime()-before)/1000000
            }
            val receipt=File(checkNotNull(System.getProperty("riff.encore.benchmark")));receipt.parentFile.mkdirs()
            File(receipt.parentFile,"encore-100k-query-timings.txt").writeText(queryTimes.joinToString("\n"))
            receipt.writeText("""{"fixture":"synthetic relational load only; not playback evidence","occurrences":100000,"uniqueTracks":100,"uniqueArtists":10,"scopes":1,"rollupRows":${store.query("SELECT count(*) FROM encore_daily"){it.getLong(1)}.single()},"readMs":${milliseconds.joinToString(",", "[", "]")},"targetMs":300,"targetMet":${milliseconds.max()<=300}}""")
            assertTrue(milliseconds.max()<=300,"Full Encore read missed the unchanged 300 ms load gate: $milliseconds")
            }
        }}finally{directory.deleteRecursively()}
    }
}
