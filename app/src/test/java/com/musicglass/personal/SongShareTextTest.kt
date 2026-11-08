package com.musicglass.personal

import org.junit.Assert.*
import org.junit.Test

/** Feature 8 (owner, Q8): one formatter for both song menus; song.link built locally, never asked. */
class SongShareTextTest {
    @Test fun theOrdinaryLinkIsUnchanged() {
        assertEquals("Song · Artist\nhttps://music.youtube.com/watch?v=9bZkp7q19f0", songShareText("Song", "Artist", "9bZkp7q19f0"))
    }

    @Test fun theUniversalLinkWrapsTheCanonicalSongAddress() {
        assertEquals("Song · Artist\nhttps://song.link/https://music.youtube.com/watch?v=Ab-_12345cd",
            songShareText("Song", "Artist", "Ab-_12345cd", universal = true))
    }

    @Test fun anythingButAPublicSongIdHasNothingToShare() {
        for (id in listOf("", "local:42", "short", "9bZkp7q19f0&t=1", "9bZkp7q19f0/x", "../../etc"))
            for (universal in listOf(false, true)) assertNull(id, songShareText("Song", "Artist", id, universal))
    }
}
