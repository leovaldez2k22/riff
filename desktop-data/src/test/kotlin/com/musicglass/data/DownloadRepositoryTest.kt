package com.musicglass.data
import java.io.File
import kotlin.test.*
class DownloadRepositoryTest {
    private fun withStore(body:(SqliteLibraryRepository)->Unit){val dir=kotlin.io.path.createTempDirectory("riff-download-db-").toFile();try{SqliteLibraryRepository(dir).use(body)}finally{dir.deleteRecursively()}}
    @Test fun lateCompletionCannotUndoPauseOrRemoval()=withStore{store->
        val repo=DownloadRepository(store);val track=TrackRecord(Scopes.GUEST,"youtube:abcdefghijk","Recorded music",sourceKind="youtube")
        repo.request(track);val queued=checkNotNull(repo.get(track.scope,track.id))
        assertTrue(repo.change(queued.copy(state=DownloadState.Transferring),setOf(DownloadState.Queued)))
        assertTrue(repo.change(queued.copy(state=DownloadState.Paused),setOf(DownloadState.Transferring)))
        val receipt=queued.copy(state=DownloadState.Complete,bytes=10,total=10,sha256="a".repeat(64))
        assertFalse(repo.change(receipt,setOf(DownloadState.Transferring,DownloadState.Verifying)))
        assertEquals(DownloadState.Paused,repo.get(track.scope,track.id)?.state)
        assertTrue(repo.change(queued.copy(state=DownloadState.Removing),setOf(DownloadState.Paused)))
        assertFalse(repo.change(receipt,setOf(DownloadState.Verifying)))
        repo.forget(track.scope,track.id);assertNull(repo.get(track.scope,track.id));assertNotNull(store.get(track.scope,track.id))
    }
    @Test fun completionNeedsAReceiptAndPrivateScopesAreNotFabricated()=withStore{store->
        val repo=DownloadRepository(store);val track=TrackRecord(Scopes.GUEST,"youtube:abcdefghijk","Recorded music",sourceKind="youtube");repo.request(track)
        val row=checkNotNull(repo.get(track.scope,track.id))
        assertFailsWith<IllegalArgumentException>{repo.change(row.copy(state=DownloadState.Complete),setOf(DownloadState.Queued))}
        assertFailsWith<IllegalArgumentException>{repo.request(track.copy(scope="account_missing"))}
        assertEquals(1,repo.list().size)
    }
    @Test fun schemaThreeMigratesWithoutChangingExistingHistory() {
        val dir=kotlin.io.path.createTempDirectory("riff-download-v3-").toFile()
        try{
            SqliteLibraryRepository(dir).use{it.upsert(TrackRecord(Scopes.LOCAL,"real","Recording"));HistoryRepository(it).record(ListeningSpan(Scopes.LOCAL,"span","occurrence","real",System.currentTimeMillis()-1000,1000))}
            java.sql.DriverManager.getConnection("jdbc:sqlite:${File(dir,"library.sqlite").path}").use{db->db.createStatement().use{it.execute("DROP TABLE encore_artist_genre");it.execute("DROP INDEX history_occurrence_period");it.execute("DROP TABLE encore_lifetime");it.execute("DROP VIEW encore_plays");it.execute("DROP TABLE encore_daily");it.execute("DROP TABLE encore_hourly");it.execute("DROP TABLE encore_rollup_version");it.execute("ALTER TABLE track DROP COLUMN is_episode");it.execute("DROP TABLE download_job");it.execute("PRAGMA user_version=3")}}
            SqliteLibraryRepository(dir).use{store->assertEquals(8L,store.query("PRAGMA user_version"){it.getLong(1)}.single());assertEquals(1000L,HistoryRepository(store).summary(Scopes.LOCAL).listenedMs);assertTrue(DownloadRepository(store).list().isEmpty());assertEquals("ok",store.integrity())}
        }finally{dir.deleteRecursively()}
    }
    @Test fun knownEpisodeIdentitySurvivesAnUnclassifiedMetadataRefresh()=withStore{store->
        val episode=TrackRecord(Scopes.GUEST,"youtube:abcdefghijk","Actual provider episode",sourceKind="youtube",isEpisode=true)
        store.upsert(episode);assertTrue(store.get(episode.scope,episode.id)!!.isEpisode)
        store.upsert(episode.copy(title="Refreshed metadata",isEpisode=false));assertTrue(store.get(episode.scope,episode.id)!!.isEpisode)
        assertEquals("Refreshed metadata",store.get(episode.scope,episode.id)!!.title)
    }
}
