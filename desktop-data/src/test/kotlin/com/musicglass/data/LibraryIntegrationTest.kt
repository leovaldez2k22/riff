package com.musicglass.data

import java.io.File
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

class LibraryIntegrationTest {
    @Test fun tornJournalTailIsPreservedAndQuarantined()=withStore{store->
        val input=File(store.directory,"torn.legacy").apply{writeText("SCHEMA|2\nLIB|default|C:\\music\\real.flac|Title|Artist||1||1")}
        val original=input.readBytes();val audit=LegacyDesktopStoreReader(store).importFile(input)
        assertEquals(0L,audit.imported);assertEquals(1L,audit.quarantined);assertContentEquals(original,File(audit.backupPath).readBytes());assertEquals(0L,store.count("local"))
    }
    @Test fun incompleteFtsSetupRebuildsPersistedRows(){
        val directory=kotlin.io.path.createTempDirectory("riff-fts-recovery-").toFile()
        try{
            SqliteLibraryRepository(directory).use{it.upsert(TrackRecord("local","kept","Recoverable recording"))}
            java.sql.DriverManager.getConnection("jdbc:sqlite:${File(directory,"library.sqlite").absolutePath}").use{db->db.createStatement().use{it.execute("DROP TRIGGER track_ai");it.execute("INSERT INTO track_fts(track_fts) VALUES('delete-all')")}}
            SqliteLibraryRepository(directory).use{store->assertEquals("kept",store.search("local","Recoverable").single().id);assertEquals("ok",store.integrity())}
        }finally{directory.deleteRecursively()}
    }
    private fun withStore(block:(SqliteLibraryRepository)->Unit){val root=kotlin.io.path.createTempDirectory("riff-data-test").toFile();try{SqliteLibraryRepository(root).use(block)}finally{root.deleteRecursively()}}
    @Test fun scopesUnicodeFtsAndReactiveCommitAreRealSqlite()=withStore{store->
        store.upsert(TrackRecord("local","a","Café العربية 日本語","Björk","Debut",sourcePath="C:/music/a.flac"))
        store.upsert(TrackRecord("account_a","same","Private only"))
        assertEquals("a",store.search("local","cafe").single().id)
        assertEquals("a",store.search("local","العربية").single().id)
        assertEquals("a",store.search("local","日本語").single().id)
        assertTrue(store.search("guest","Private").isEmpty())
        assertEquals(1L,store.count("local"));assertEquals("ok",store.integrity())
        runBlocking {assertEquals("a",store.observe("local").first().single().id)}
        assertEquals("SQLite format 3\u0000",File(store.directory,"library.sqlite").inputStream().use{String(it.readNBytes(16))})
    }
    @Test fun playlistDuplicatesSurviveReorderingAndHistoryUsesPresentedSpans()=withStore{store->
        val tracks=TrackRecord("local","a","actual metadata",durationMs=120000)
        store.upsert(tracks);val playlists=PlaylistRepository(store);val id=playlists.create("local","Daily")
        val first=playlists.add("local",id,tracks);val second=playlists.add("local",id,tracks)
        assertNotEquals(first,second);playlists.reorder("local",id,listOf(second,first))
        assertEquals(listOf(second,first),playlists.entries("local",id).map{it.entryId})
        val history=HistoryRepository(store);val span=ListeningSpan("local","receipt","occurrence","a",System.currentTimeMillis(),5000)
        history.record(span);history.record(span)
        assertEquals(5000L,history.summary("local").listenedMs)
        store.setPrivacy("local",PrivacyPreferences(saveHistory=false));history.record(span.copy(id="rejected"))
        assertEquals(5000L,history.summary("local").listenedMs)
        history.clear("local");assertEquals(0L,history.summary("local").listenedMs)
    }
    @Test fun legacyImportIsIdempotentAndQuarantinesAmbiguityWithImmutableBackup()=withStore{store->
        val media=File(store.directory,"actual-file.flac").apply{writeBytes(byteArrayOf(1,2,3))}
        val source=File(store.directory,"legacy.txt").apply{writeText("SCHEMA|2\nLIB|default|${media.path}|Title|Artist|Album|1234||1\nLIB|default|track-1|Invented|Artist||180000||1\nLIB|default|${media.path}|bad|delimiter|too|many|parts|0|extra\n")}
        val original=source.readBytes();val reader=LegacyDesktopStoreReader(store);val audit=reader.importFile(source)
        assertEquals(1L,audit.imported);assertEquals(2L,audit.quarantined)
        assertEquals(audit,reader.importFile(source));assertEquals(1L,store.count("local"))
        assertContentEquals(original,File(audit.backupPath).readBytes());assertContentEquals(original,source.readBytes())
        assertTrue(File(audit.reportPath).readText().contains("Quarantined: 2"))
    }
    @Test fun interruptedImportRollsBackAndRetryUsesPreservedOriginal()=withStore{store->
        val media=File(store.directory,"actual.flac").apply{writeBytes(byteArrayOf(1))}
        val source=File(store.directory,"old").apply{writeText("SCHEMA|2\nLIB|default|${media.path}|Title|Artist||1||1\n")}
        val reader=LegacyDesktopStoreReader(store)
        assertFailsWith<IllegalStateException>{reader.importFile(source){if(it=="before_commit")error("Injected process boundary")}}
        assertEquals(0L,store.count("local"))
        assertEquals(1L,reader.importFile(source).imported)
    }
    @Test fun fullText100kBenchmarkHasSeparateColdAndWarmObservations()=withStore{store->
        store.upsertAll((0 until 100000).asSequence().map{TrackRecord("local","track-$it","Recorded title $it","Artist ${it%100}","Album ${it%1000}")})
        assertEquals(100000L,store.count("local"))
        val coldStart=System.nanoTime();assertTrue(store.search("local","Recorded title 98765").isNotEmpty());val cold=(System.nanoTime()-coldStart)/1e6
        val samples=List(25){val start=System.nanoTime();store.search("local","Recorded title 98765");(System.nanoTime()-start)/1e6}.sorted()
        val report=System.getProperty("riff.data.benchmark")?.let(::File)
        report?.apply{parentFile.mkdirs();writeText("{\"rows\":100000,\"firstQueryMs\":$cold,\"warmMedianMs\":${samples[12]},\"warmP95Ms\":${samples[23]},\"coldDefinition\":\"first connection query after bulk import; OS cache not flushed\"}")}
        assertTrue(samples[23]<10,"Actual SQLite FTS warm p95 exceeded 10ms: ${samples[23]}")
    }
    @Test fun actualProcessTerminationAtEveryImportPublishBoundary(){
        for(boundary in listOf("backed_up","row:1","row:2","before_commit","committed","report_published"))withStore{store->
            val source=File(store.directory,"input.legacy").apply{writeText("SCHEMA|2\nLIB|default|C:\\music\\recorded.flac|Recorded|Artist||12000||1\n")}
            val original=source.readBytes()
            val argumentFile=File(store.directory,"child.args")
            val classpath=checkNotNull(System.getProperty("riff.data.testClasspath"))
            fun quoted(value:String)="\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\""
            argumentFile.writeText(listOf("-cp",quoted(classpath),"com.musicglass.data.ImportCrashChild",quoted(store.directory.path),quoted(source.path),quoted(boundary)).joinToString("\n"))
            val javaBinary=File(System.getProperty("java.home"),"bin/java.exe")
            val child=ProcessBuilder(javaBinary.path,"@${argumentFile.path}").redirectErrorStream(true).redirectOutput(File(store.directory,"child.log")).start()
            assertTrue(child.waitFor(20,java.util.concurrent.TimeUnit.SECONDS),"Import child timed out at $boundary")
            assertEquals(86,child.exitValue(),File(store.directory,"child.log").readText())
            val result=LegacyDesktopStoreReader(store).importFile(source)
            assertEquals(1L,result.imported);assertEquals("ok",store.integrity());assertEquals(1L,store.count("local"))
            assertContentEquals(original,source.readBytes());assertContentEquals(original,File(result.backupPath).readBytes())
        }
    }
    @Test fun sqliteVersionOneMigratesWithoutLosingTracks(){
        val directory=kotlin.io.path.createTempDirectory("riff-migrate-").toFile()
        try{
            SqliteLibraryRepository(directory).use{it.upsert(TrackRecord("local","kept","Recorded metadata"))}
            java.sql.DriverManager.getConnection("jdbc:sqlite:${File(directory,"library.sqlite").path}").use{connection->connection.createStatement().use{it.execute("DROP TABLE scan_issue");it.execute("DROP TABLE encore_artist_genre");it.execute("DROP INDEX history_occurrence_period");it.execute("DROP TABLE encore_lifetime");it.execute("DROP VIEW encore_plays");it.execute("DROP TABLE encore_daily");it.execute("DROP TABLE encore_hourly");it.execute("DROP TABLE encore_rollup_version");it.execute("ALTER TABLE track DROP COLUMN is_episode");it.execute("DROP TABLE download_job");it.execute("DROP TABLE history_occurrence");it.execute("DROP INDEX history_span_occurrence");it.execute("DROP TABLE history_epoch");it.execute("DROP TABLE history_rollup_version");it.execute("PRAGMA user_version=1")}}
            SqliteLibraryRepository(directory).use{store->assertEquals("Recorded metadata",store.get("local","kept")!!.title);store.scanIssue("root","a.flac","Rejected actual probe");assertEquals(1,store.scanIssues().size);assertEquals("ok",store.integrity())}
        }finally{directory.deleteRecursively()}
    }
    @Test fun previewSnapshotSourcesAreQuarantinedInsteadOfBecomingLibraryRows()=withStore{store->
        val audit=LegacyDesktopStoreReader(store).importVerifiedSnapshot("{snapshot-test}",listOf(TrackRecord("local","track-1","Preview"),TrackRecord("guest","riff://stream/fixture","Preview",sourceKind="youtube")))
        assertEquals(0L,audit.imported);assertEquals(2L,audit.quarantined);assertEquals(0L,store.count("local"))
    }
    @Test fun sqliteFullRollsBackWithoutAlternateStore()=withStore{store->
        store.upsert(TrackRecord("local","kept","Kept"))
        val pages=store.query("PRAGMA page_count"){it.getLong(1)}.single()
        store.query("PRAGMA max_page_count=${pages+8}"){it.getLong(1)}
        assertFails{store.upsertAll((0..100).asSequence().map{TrackRecord("local","large-$it","a".repeat(16000))})}
        assertEquals(1L,store.count("local"));assertEquals("ok",store.integrity())
    }
    @Test fun unwritableDatabaseLocationFailsExplicitly(){
        val directory=kotlin.io.path.createTempDirectory("riff-denied-").toFile()
        try{File(directory,"library.sqlite").mkdirs();assertFails{SqliteLibraryRepository(directory)};assertFalse(File(directory,"library-state.db").exists())}finally{directory.deleteRecursively()}
    }
    @Test fun futureSchemaIsEntirelyQuarantined()=withStore{store->
        val source=File(store.directory,"future").apply{writeText("SCHEMA|900\nLIB|default|C:\\music\\a.flac|Title|||1||1\n")}
        val result=LegacyDesktopStoreReader(store).importFile(source);assertEquals(0L,result.imported);assertEquals(2L,result.quarantined);assertEquals(0L,store.count("local"))
    }

}

object ImportCrashChild {
    @JvmStatic fun main(args:Array<String>){SqliteLibraryRepository(File(args[0])).use{store->LegacyDesktopStoreReader(store).importFile(File(args[1])){point->if(point==args[2])Runtime.getRuntime().halt(86)}};error("Crash boundary was never reached")}
}
