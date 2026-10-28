package com.musicglass.personal

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Feature 13: only YouTube Music and YouTube links, only their checked ids. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SharedLinksTest {
    @Test fun songsFromEveryFormOfLink() {
        assertEquals(SharedTarget.Song("dQw4w9WgXcQ"), parseYouTubeLink("https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RDAMVM"))
        assertEquals(SharedTarget.Song("dQw4w9WgXcQ"), parseYouTubeLink("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals(SharedTarget.Song("dQw4w9WgXcQ"), parseYouTubeLink("https://youtu.be/dQw4w9WgXcQ?si=tracking"))
        assertEquals(SharedTarget.Song("dQw4w9WgXcQ"), parseYouTubeLink("https://youtube.com/shorts/dQw4w9WgXcQ"))
    }

    @Test fun aStartTimeIsKept() {
        assertEquals(SharedTarget.Song("dQw4w9WgXcQ", 90_000), parseYouTubeLink("https://youtu.be/dQw4w9WgXcQ?t=90"))
        assertEquals(SharedTarget.Song("dQw4w9WgXcQ", 90_000), parseYouTubeLink("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=1m30s"))
        assertEquals(0L, startOf("nonsense"))
    }

    @Test fun collectionsOpenAsCollections() {
        assertEquals(SharedTarget.Collection("VLPLabc123"), parseYouTubeLink("https://music.youtube.com/playlist?list=PLabc123"))
        assertEquals(SharedTarget.Collection("MPREb_album01"), parseYouTubeLink("https://music.youtube.com/browse/MPREb_album01"))
        assertEquals(SharedTarget.Collection("UCartist01"), parseYouTubeLink("https://music.youtube.com/channel/UCartist01"))
    }

    @Test fun aSharedTitleBeforeTheLinkIsSkipped() {
        assertEquals(SharedTarget.Song("dQw4w9WgXcQ"), parseYouTubeLink("Listen to this! https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test fun anythingElseIsIgnored() {
        assertNull(parseYouTubeLink("https://example.com/watch?v=dQw4w9WgXcQ"))
        assertNull(parseYouTubeLink("https://music.youtube.com/watch?v=short"))
        assertNull(parseYouTubeLink("https://music.youtube.com/browse/FEmusic_home"))
        assertNull(parseYouTubeLink("no link here"))
        assertNull(parseYouTubeLink(null))
    }

    @Test fun viewAndShareIntentsAreRead() {
        assertEquals(SharedTarget.Song("dQw4w9WgXcQ"), sharedTargetOf(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://youtu.be/dQw4w9WgXcQ"))))
        assertEquals(SharedTarget.Song("dQw4w9WgXcQ"), sharedTargetOf(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "https://youtu.be/dQw4w9WgXcQ")))
        assertNull(sharedTargetOf(Intent(Intent.ACTION_MAIN)))
    }
}
