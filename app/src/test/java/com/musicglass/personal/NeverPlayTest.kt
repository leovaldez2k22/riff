package com.musicglass.personal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Feature 5 (owner, Q5): the never-play list, matched by stable ids and kept apart per account. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NeverPlayTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val song = NeverPlayEntry("Ab-_12345cd", false, "A song")
    private val artist = NeverPlayEntry("UCabcdefghijklmnopqrstuv", true, "An artist")

    @Test fun onlyStableIdsAreKeptAndAMalformedListIsEmpty() {
        val kept = decodeNeverPlay(encodeNeverPlay(listOf(song, artist, NeverPlayEntry("short", false), NeverPlayEntry("notachannel", true), song)))
        assertEquals(listOf(song, artist), kept)
        assertTrue(decodeNeverPlay("{not json").isEmpty())
        assertTrue(decodeNeverPlay(null).isEmpty())
    }

    @Test fun aSongMatchesByIdAndAnArtistByChannelNeverByName() {
        val set = NeverPlaySet(listOf(song, artist))
        assertTrue(set.blocks(song.key, null))
        assertTrue(set.blocks("zzzzzzzzzzz", artist.key))
        // A namesake without the channel id is not taken for the artist.
        assertFalse(set.blocks("zzzzzzzzzzz", null))
        assertFalse(set.blocks(null, "UCsomeoneelse000000000"))
        assertTrue(NeverPlaySet(emptyList()).isEmpty)
    }

    @Test fun playbackPassesOverBlockedEntriesAndStopsWhenNoneAreLeft() {
        val blocked = setOf(1, 2, 4)
        assertEquals(3, neverPlayNext(1, 6, blocked::contains))
        assertEquals(5, neverPlayNext(4, 6, blocked::contains))
        assertEquals(-1, neverPlayNext(5, 6, blocked::contains))
        assertEquals("Everything left is blocked", -1, neverPlayNext(0, 10, { it > 0 }))
        assertEquals("Only so many are passed over", -1, neverPlayNext(0, 500, { it <= NEVER_PLAY_SKIP_LIMIT }))
    }

    @Test fun eachAccountHasItsOwnList() {
        val store = NeverPlayStore.get(context)
        store.add("owner-a", song); store.add("owner-a", NeverPlayEntry("bad", false))
        assertTrue(store.contains("owner-a", song.key, false))
        assertFalse(store.contains("owner-b", song.key, false))
        assertEquals(1, store.entries("owner-a").size)
        store.remove("owner-a", song.key, false)
        assertTrue(store.entries("owner-a").isEmpty())
    }

    @Test fun blockingTheSongPlayingSkipsItAndTheLastOnePauses() {
        val scope = OfflineMusicStore.get(context).currentScope
        val store = NeverPlayStore.get(context)
        val two = listOf(QueueEntry("yt:${song.key}", "A song", "An artist"), QueueEntry("yt:zzzzzzzzzzz", "B", "C"))
        val playing = RecordingGateway(PlaybackUiState(mediaId = "yt:${song.key}", artistId = artist.key, queue = two))
        val notices = mutableListOf<String>()
        toggleNeverPlay(context, playing.gateway, song, notices::add)
        assertEquals(listOf("next()"), playing.asked)
        assertTrue(store.contains(scope, song.key, false))
        // Taking it off again plays nothing and skips nothing.
        toggleNeverPlay(context, playing.gateway, song, notices::add)
        assertEquals(listOf("next()"), playing.asked)
        assertFalse(store.contains(scope, song.key, false))

        val alone = RecordingGateway(PlaybackUiState(mediaId = "yt:zzzzzzzzzzz", artistId = artist.key, queue = two.take(1)))
        toggleNeverPlay(context, alone.gateway, artist, notices::add)
        assertEquals(listOf("pause()"), alone.asked)
        assertEquals(3, notices.size)
        store.remove(scope, artist.key, true)
    }

    @Test fun thePlayerMenuOffersTheSongAndAKnownArtistOnly() {
        assertEquals(listOf(song.key, artist.key), nowPlayingNeverPlay(context,
            PlaybackUiState(mediaId = "yt:${song.key}", artistId = artist.key)).map { it.key })
        assertEquals(listOf(song.key), nowPlayingNeverPlay(context, PlaybackUiState(mediaId = "yt:${song.key}")).map { it.key })
        assertTrue(nowPlayingNeverPlay(context, PlaybackUiState(mediaId = "local:42")).isEmpty())
        assertTrue(nowPlayingNeverPlay(context, PlaybackUiState(mediaId = "yt:${song.key}", live = true)).isEmpty())
        assertEquals("Never play this song", neverPlayLabel(context, song))
    }
}
