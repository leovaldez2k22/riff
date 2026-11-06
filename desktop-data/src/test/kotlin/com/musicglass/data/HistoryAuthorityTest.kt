package com.musicglass.data

import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.test.*

/** Real SQLite history contracts; test metadata is never presented as playable application content. */
class HistoryAuthorityTest {
    private fun withStore(test:(SqliteLibraryRepository)->Unit){
        val directory=kotlin.io.path.createTempDirectory("riff-history-authority-").toFile()
        try{SqliteLibraryRepository(directory).use{store->
            store.upsert(TrackRecord(Scopes.LOCAL,"a","Local recording"));store.upsert(TrackRecord(Scopes.GUEST,"a","Remote recording",sourceKind="youtube"));test(store)
        }}finally{directory.deleteRecursively()}
    }
    @Test fun clearRejectsQueuedOldEpochWithoutAffectingAnotherScope()=withStore{store->
        val history=HistoryRepository(store);val start=System.currentTimeMillis()-5000
        val span=ListeningSpan(Scopes.LOCAL,"one","play","a",start,5000)
        val old=history.epoch(Scopes.LOCAL);history.record(span,old)
        history.record(span.copy(scope=Scopes.GUEST),history.epoch(Scopes.GUEST))
        history.clear(Scopes.LOCAL);assertEquals(old+1,history.epoch(Scopes.LOCAL))
        history.record(span.copy(id="queued-before-clear"),old)
        assertEquals(0L,history.summary(Scopes.LOCAL).listenedMs);assertEquals(5000L,history.summary(Scopes.GUEST).listenedMs)
        assertEquals(0L,store.query("SELECT count(*) FROM history_occurrence WHERE scope=?",Scopes.LOCAL){it.getLong(1)}.single())
        history.record(span.copy(id="after-clear",occurrence="new-play"),history.epoch(Scopes.LOCAL))
        assertEquals(5000L,history.summary(Scopes.LOCAL).listenedMs)
    }
    @Test fun offThenOnCannotReacceptAnOlderQueuedWrite()=withStore{store->
        val history=HistoryRepository(store);val old=history.epoch(Scopes.LOCAL)
        store.setPrivacy(Scopes.LOCAL,PrivacyPreferences(saveHistory=false))
        store.setPrivacy(Scopes.LOCAL,PrivacyPreferences(saveHistory=true))
        assertEquals(old+2,history.epoch(Scopes.LOCAL))
        history.record(ListeningSpan(Scopes.LOCAL,"old","play","a",System.currentTimeMillis(),1000),old)
        assertEquals(0L,history.summary(Scopes.LOCAL).listenedMs)
    }
    @Test fun capCountsOccurrencesAndRemovesEverySpanOfTheOldestPlay()=withStore{store->
        val history=HistoryRepository(store,ZoneId.of("UTC"),maximumOccurrences=2);val base=System.currentTimeMillis()-60000
        history.record(ListeningSpan(Scopes.LOCAL,"a1","first","a",base,3000))
        history.record(ListeningSpan(Scopes.LOCAL,"a2","first","a",base+3000,3000))
        history.record(ListeningSpan(Scopes.LOCAL,"a3","first","a",base+6000,3000))
        assertEquals(9000L,history.summary(Scopes.LOCAL).listenedMs)
        history.record(ListeningSpan(Scopes.LOCAL,"b1","second","a",base+15000,5000))
        history.record(ListeningSpan(Scopes.LOCAL,"c1","third","a",base+25000,5000))
        assertEquals(10000L,history.summary(Scopes.LOCAL).listenedMs)
        assertEquals(2L,history.summary(Scopes.LOCAL).plays)
        assertEquals(0L,store.query("SELECT count(*) FROM history_span WHERE occurrence='first'"){it.getLong(1)}.single())
        assertEquals(10000L,store.query("SELECT sum(listened_ms) FROM daily_total WHERE scope=?",Scopes.LOCAL){it.getLong(1)}.single())
    }
    @Test fun heardSpanCrossingMidnightIsSplitExactlyAfterDstChange()=withStore{store->
        val zone=ZoneId.of("America/New_York");val start=Instant.parse("2026-11-02T04:59:58Z").toEpochMilli()
        val history=HistoryRepository(store,zone)
        history.record(ListeningSpan(Scopes.LOCAL,"boundary","play","a",start,5000))
        val days=store.query("SELECT day,listened_ms FROM daily_total WHERE scope=? ORDER BY day",Scopes.LOCAL){it.getString(1) to it.getLong(2)}
        assertEquals(listOf("2026-11-01" to 2000L,"2026-11-02" to 3000L),days)
        assertEquals(5000L,days.sumOf{it.second})
        assertEquals(listOf("2011-12-29" to 1000L,"2011-12-31" to 1000L),historyDayParts(Instant.parse("2011-12-30T09:59:59Z").toEpochMilli(),2000,ZoneId.of("Pacific/Apia")))
    }
    @Test fun alternatingScopesApplyRetentionIndependently()=withStore{store->
        val history=HistoryRepository(store);val old=System.currentTimeMillis()-100L*86400000
        repeat(100){index->
            history.record(ListeningSpan(Scopes.LOCAL,"local-$index","local-play","a",old+index,1))
            history.record(ListeningSpan(Scopes.GUEST,"guest-$index","guest-play","a",old+index,1))
        }
        assertEquals(0L,history.summary(Scopes.LOCAL).listenedMs)
        assertEquals(0L,history.summary(Scopes.GUEST).listenedMs)
    }
    @Test fun clearAndEpochAdvanceRollbackTogether()=withStore{store->
        val history=HistoryRepository(store);val span=ListeningSpan(Scopes.LOCAL,"id","play","a",System.currentTimeMillis(),5000)
        history.record(span);val epoch=history.epoch(Scopes.LOCAL)
        assertFailsWith<IllegalStateException>{store.transaction{history.clear(Scopes.LOCAL);error("injected transaction failure")}}
        assertEquals(epoch,history.epoch(Scopes.LOCAL));assertEquals(5000L,history.summary(Scopes.LOCAL).listenedMs)
    }
    @Test fun matchingOccurrenceIdsInDifferentScopesStillCountAsDifferentPlays()=withStore{store->
        val history=HistoryRepository(store);val span=ListeningSpan(Scopes.LOCAL,"id","same-id","a",System.currentTimeMillis(),5000)
        history.record(span);history.record(span.copy(scope=Scopes.GUEST))
        assertEquals(2L,history.summary(listOf(Scopes.LOCAL,Scopes.GUEST)).plays)
    }
    @Test fun processDeathAtEveryMigrationCheckpointPreservesHistoryAndDuplicatePlaylist(){
        for(checkpoint in LibraryMigrationCheckpoint.entries){
            val directory=kotlin.io.path.createTempDirectory("riff-schema-crash-").toFile()
            try{
                var playlist=""
                SqliteLibraryRepository(directory).use{store->
                    val track=TrackRecord(Scopes.LOCAL,"a","Surviving real SQLite row");store.upsert(track)
                    val lists=PlaylistRepository(store);playlist=lists.create(Scopes.LOCAL,"Preserved duplicates")
                    lists.add(Scopes.LOCAL,playlist,track);lists.add(Scopes.LOCAL,playlist,track)
                    HistoryRepository(store).record(ListeningSpan(Scopes.LOCAL,"id","play","a",System.currentTimeMillis(),5000))
                }
                val url="jdbc:sqlite:${File(directory,"library.sqlite").path}"
                java.sql.DriverManager.getConnection(url).use{db->db.createStatement().use{statement->
                    for(sql in listOf("DROP TABLE encore_artist_genre","DROP INDEX history_occurrence_period","DROP TABLE encore_lifetime","DROP VIEW encore_plays","DROP TABLE encore_daily","DROP TABLE encore_hourly","DROP TABLE encore_rollup_version","ALTER TABLE track DROP COLUMN is_episode","DROP TABLE download_job","DROP TABLE history_occurrence","DROP INDEX history_span_occurrence","DROP TABLE history_epoch","DROP TABLE history_rollup_version","PRAGMA user_version=2"))statement.execute(sql)
                }}
                val javaExecutable=File(System.getProperty("java.home"),"bin/java.exe")
                val child=ProcessBuilder(javaExecutable.path,"-cp",checkNotNull(System.getProperty("riff.data.testClasspath")),HistoryMigrationHaltProbe::class.java.name,directory.path,checkpoint.name)
                    .redirectErrorStream(true).redirectOutput(File(directory,"child.log")).start()
                try{assertTrue(child.waitFor(30,java.util.concurrent.TimeUnit.SECONDS),"Child migration stalled at $checkpoint");assertEquals(73,child.exitValue(),File(directory,"child.log").readText())}
                finally{if(child.isAlive)child.destroyForcibly().waitFor(5,java.util.concurrent.TimeUnit.SECONDS)}
                java.sql.DriverManager.getConnection(url).use{db->db.createStatement().use{statement->
                    statement.executeQuery("PRAGMA user_version").use{assertTrue(it.next());assertEquals(if(checkpoint==LibraryMigrationCheckpoint.AFTER_COMMIT)8 else 2,it.getInt(1),checkpoint.name)}
                }}
                SqliteLibraryRepository(directory).use{store->
                    assertEquals("ok",store.integrity());assertEquals(5000L,HistoryRepository(store).summary(Scopes.LOCAL).listenedMs)
                    assertEquals(2,PlaylistRepository(store).entries(Scopes.LOCAL,playlist).size)
                }
            }finally{directory.deleteRecursively()}
        }
    }
    @Test fun inconsistentLegacyOccurrencesAreRejectedBeforeMigrationCommits(){
        val directory=kotlin.io.path.createTempDirectory("riff-v2-collision-").toFile()
        try{
            SqliteLibraryRepository(directory).use{store->
                store.upsert(TrackRecord(Scopes.LOCAL,"a","First recording"));store.upsert(TrackRecord(Scopes.LOCAL,"b","Second recording"))
                store.execute("INSERT INTO history_span VALUES('local','a','reused','a',1000,1000,0)")
                store.execute("INSERT INTO history_span VALUES('local','b','reused','b',2000,1000,0)")
            }
            val url="jdbc:sqlite:${File(directory,"library.sqlite").path}"
            java.sql.DriverManager.getConnection(url).use{db->db.createStatement().use{statement->
                for(sql in listOf("DROP TABLE encore_artist_genre","DROP INDEX history_occurrence_period","DROP TABLE encore_lifetime","DROP VIEW encore_plays","DROP TABLE encore_daily","DROP TABLE encore_hourly","DROP TABLE encore_rollup_version","ALTER TABLE track DROP COLUMN is_episode","DROP TABLE download_job","DROP TABLE history_occurrence","DROP INDEX history_span_occurrence","DROP TABLE history_epoch","DROP TABLE history_rollup_version","PRAGMA user_version=2"))statement.execute(sql)
            }}
            assertFailsWith<IllegalStateException>{SqliteLibraryRepository(directory).close()}
            java.sql.DriverManager.getConnection(url).use{db->db.createStatement().use{statement->
                statement.executeQuery("PRAGMA user_version").use{assertTrue(it.next());assertEquals(2,it.getInt(1))}
                statement.executeQuery("SELECT count(*) FROM history_span").use{assertTrue(it.next());assertEquals(2,it.getInt(1))}
            }}
        }finally{directory.deleteRecursively()}
    }
    @Test fun versionTwoMigratesRealRowsAndRebuildsDailyReceiptsWithoutDuplication(){
        val directory=kotlin.io.path.createTempDirectory("riff-v2-history-").toFile()
        try{
            SqliteLibraryRepository(directory).use{store->
                store.upsert(TrackRecord(Scopes.LOCAL,"a","Preserved metadata"))
                HistoryRepository(store).record(ListeningSpan(Scopes.LOCAL,"id","play","a",System.currentTimeMillis(),5000))
            }
            java.sql.DriverManager.getConnection("jdbc:sqlite:${File(directory,"library.sqlite").path}").use{db->db.createStatement().use{s->
                for(sql in listOf("DROP TABLE encore_artist_genre","DROP INDEX history_occurrence_period","DROP TABLE encore_lifetime","DROP VIEW encore_plays","DROP TABLE encore_daily","DROP TABLE encore_hourly","DROP TABLE encore_rollup_version","ALTER TABLE track DROP COLUMN is_episode","DROP TABLE download_job","DROP TABLE history_occurrence","DROP INDEX history_span_occurrence","DROP TABLE history_epoch","DROP TABLE history_rollup_version","PRAGMA user_version=2"))s.execute(sql)
            }}
            repeat(2){SqliteLibraryRepository(directory).use{store->
                val history=HistoryRepository(store);assertEquals(5000L,history.summary(Scopes.LOCAL).listenedMs)
                assertEquals(1L,store.query("SELECT count(*) FROM history_occurrence"){it.getLong(1)}.single())
                assertEquals(8L,store.query("PRAGMA user_version"){it.getLong(1)}.single());assertEquals("ok",store.integrity())
            }}
        }finally{directory.deleteRecursively()}
    }
}
