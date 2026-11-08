package com.musicglass.personal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Feature 12 (owner, Q12): files are read, rows matched and kept on the phone; the copy to YouTube Music is tried
 * only against fakes here. Every title, artist and id is invented.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlaylistImportTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun aSpotifyStyleCsvKeepsOrderRepeatsAndQuotedCommas() {
        val csv = "\uFEFF\"Track URI\",\"Track Name\",\"Artist Name(s)\",\"Album Name\",\"Duration (ms)\"\r\n" +
            "spotify:track:1,\"Hello, Again\",\"Fixture Band, Guest\",Album One,201000\r\n" +
            "spotify:track:2,\"Say \"\"Yes\"\"\",Fixture Band,Album Two,180500\r\n" +
            "spotify:track:1,\"Hello, Again\",\"Fixture Band, Guest\",Album One,201000\r\n" +
            ",,,,\r\n" + "spotify:track:3,,,Nothing,1\r\n"
        val list = readImportFile("Road trip.csv", csv.toByteArray()).single()
        assertEquals("Road trip", list.name)
        assertEquals(listOf("Hello, Again", "Say \"Yes\"", "Hello, Again"), list.rows.map { it.title })
        assertEquals(listOf(1, 2, 3), list.rows.map { it.position })
        assertEquals("Fixture Band, Guest", list.rows[0].artist)
        assertEquals(201_000L, list.rows[0].durationMs)
        assertEquals("A row with no title and no link", 1, list.unreadable)
    }

    @Test fun youtubeLinksAreTakenAsTheyAreAndNothingElseIsALink() {
        assertEquals("Ab-_12345cd", importVideoId("https://music.youtube.com/watch?v=Ab-_12345cd&list=RDAMVM"))
        assertEquals("Ab-_12345cd", importVideoId("https://youtu.be/Ab-_12345cd?si=x"))
        assertEquals("Ab-_12345cd", importVideoId("https://www.youtube.com/shorts/Ab-_12345cd"))
        assertNull(importVideoId("https://example.com/watch?v=Ab-_12345cd"))
        assertNull(importVideoId("file:///sdcard/Music/song.mp3"))
        assertNull("A title can be eleven letters", importVideoId("Bulletproof"))
        assertEquals("Ab-_12345cd", importVideoId("Ab-_12345cd", bare = true))
        val csv = "title,artist,link\nBulletproof,Fixture,https://youtu.be/Ab-_12345cd\nParadise123,Fixture,\n"
        assertEquals(listOf("Ab-_12345cd", null), readImportFile("x.csv", csv.toByteArray()).single().rows.map { it.videoId })
    }

    @Test fun m3uNamesComeFromExtinfOrTheFileNameAndPathsAreNeverOpened() {
        val m3u = "#EXTM3U\n#PLAYLIST:Evening\n#EXTINF:215,Fixture Band - First Light\n/storage/emulated/0/Music/a.mp3\n" +
            "C:\\Music\\Other Band - Second Song.flac\n#EXTINF:-1,Radio\nhttps://youtu.be/Ab-_12345cd\n"
        val list = readImportFile("whatever.m3u8", m3u.toByteArray()).single()
        assertEquals("Evening", list.name)
        assertEquals(listOf("First Light", "Second Song", "Radio"), list.rows.map { it.title })
        assertEquals(listOf("Fixture Band", "Other Band", ""), list.rows.map { it.artist })
        assertEquals(215_000L, list.rows[0].durationMs)
        assertEquals("Ab-_12345cd", list.rows[2].videoId)
    }

    @Test fun spotifyAccountDataHoldsSeveralLists() {
        val json = """{"playlists":[{"name":"Gym","items":[{"track":{"trackName":"Lift","artistName":"Fixture","albumName":"A"}},{"episode":{}}]},
            {"name":"Empty","items":[]}]}"""
        val lists = readImportFile("Playlist1.json", json.toByteArray())
        assertEquals(listOf("Gym"), lists.map { it.name })
        assertEquals(1, lists[0].unreadable)
        assertTrue(readImportFile("big.csv", ByteArray(IMPORT_MAX_BYTES + 1) { 'a'.code.toByte() }).isEmpty())
    }

    private fun candidate(id: String, title: String, artist: String, ms: Long? = null) = ImportCandidate(ProviderTrack(id, title, artist), ms)

    @Test fun aClearMatchIsTakenACloseCallIsAskedAndAVersionIsNotTheSong() {
        val row = ImportRow(1, "First Light (feat. Guest)", "Fixture Band", durationMs = 215_000)
        val clear = classifyImport(row, listOf(candidate("song0000001", "First Light", "Fixture Band", 214_000), candidate("song0000002", "Other", "Someone")))
        assertEquals(ImportStatus.Matched, clear.status); assertEquals("song0000001", clear.chosen?.videoId)
        val twins = classifyImport(row, listOf(candidate("song0000001", "First Light", "Fixture Band"), candidate("song0000003", "First Light", "Fixture Band")))
        assertEquals(ImportStatus.Ambiguous, twins.status); assertNull(twins.chosen); assertEquals(2, twins.options.size)
        val live = classifyImport(ImportRow(1, "First Light", "Fixture Band"), listOf(candidate("song0000004", "First Light (Live)", "Fixture Band")))
        assertNotEquals(ImportStatus.Matched, live.status)
        assertEquals(ImportStatus.Missing, classifyImport(row, listOf(candidate("song0000005", "Unrelated", "Nobody"))).status)
        assertEquals(ImportStatus.Missing, classifyImport(row, emptyList()).status)
    }

    @Test fun repeatsAreSearchedOnceLinksNotAtAllAndAFailedSearchCanBeRetried() = runBlocking {
        val asked = mutableListOf<String>()
        val rows = listOf(ImportRow(1, "First Light", "Fixture Band"), ImportRow(2, "Linked", videoId = "Ab-_12345cd"),
            ImportRow(3, "First Light", "Fixture Band"), ImportRow(4, "Broken", "Fixture Band"))
        val found = matchImport(rows, { q -> asked += q
            if (q.startsWith("broken")) Result.failure(java.io.IOException("offline")) else Result.success(listOf(candidate("song0000001", "First Light", "Fixture Band"))) }, pauseMs = 0)
        assertEquals(listOf("first light Fixture Band", "broken Fixture Band"), asked)
        assertEquals(listOf("song0000001", "Ab-_12345cd", "song0000001", null), found.map { it.chosen?.videoId })
        assertTrue(found[3].failed)
    }

    @Test fun phonePlaylistsBelongToOneAccountAndKeepRepeatsApart() {
        val store = PhonePlaylistStore.get(context)
        val songs = listOf(ProviderTrack("song0000001", "A", "X"), ProviderTrack("song0000002", "B", "X"), ProviderTrack("song0000001", "A", "X"))
        val made = store.create("owner-a", "Mix", songs)!!
        assertEquals(3, made.songs.map { it.occurrence }.distinct().size)
        assertEquals(listOf("Mix"), store.lists("owner-a").map { it.name })
        assertTrue(store.lists("owner-b").isEmpty())
        assertNull(store.create(UNVERIFIED_SCOPE_KEY, "No", songs))
        assertEquals(made, decodePhonePlaylists(encodePhonePlaylists(listOf(made))).single())
        assertTrue(decodePhonePlaylists("[{\"broken\":1}]").isEmpty())
        store.delete("owner-a", made.id); assertTrue(store.lists("owner-a").isEmpty())
    }

    @Test fun aCopyMakesOnePlaylistAndCarriesOnWhereItStopped() = runBlocking {
        val store = PhonePlaylistStore.get(context)
        val songs = listOf(ProviderTrack("song0000001", "A", "X"), ProviderTrack("song0000002", "B", "X"), ProviderTrack("song0000001", "A", "X"))
        val list = store.create("owner-c", "Mix", songs)!!
        val account = mutableListOf<String>(); var made = 0; var failAt = 2
        val target = PhoneCopyTarget({ made++; Result.success("PLfixture") }, { Result.success(account.toList()) },
            // The second song reaches the account, but its answer is lost.
            { _, video -> account += video; if (account.size == failAt) Result.failure(java.io.IOException("offline")) else Result.success(Unit) })
        val first = copyPhonePlaylist(store, "owner-c", list, target, { true })
        assertEquals(1, first.added); assertNotNull(first.error)
        val stored = store.lists("owner-c").single()
        assertEquals("PLfixture", stored.remoteId); assertEquals(1, stored.copied.size)
        // Carrying on reads the account first, so the second song is not sent twice; the repeat of the first still is.
        failAt = -1
        val second = copyPhonePlaylist(store, "owner-c", stored, target, { true })
        assertNull(second.error); assertEquals(1, second.added); assertEquals(1, made)
        assertEquals(listOf("song0000001", "song0000002", "song0000001"), account)
        // An account switch stops it before anything is sent.
        val other = store.create("owner-c", "Two", songs)!!
        assertNotNull(copyPhonePlaylist(store, "owner-c", other, target, { false }).error); assertEquals(1, made)
        store.lists("owner-c").forEach { store.delete("owner-c", it.id) }
    }
}
