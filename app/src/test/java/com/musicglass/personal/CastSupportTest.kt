package com.musicglass.personal

import org.junit.Assert.assertEquals
import org.junit.Test

/** Feature 19: what a speaker can be given, and finding the speaker's song in the phone's queue. */
class CastSupportTest {
    private fun stream(url: String = "https://rr1.googlevideo.com/videoplayback?id=1", mime: String? = "audio/webm; codecs=\"opus\"", bounded: Boolean = false) =
        ProviderStream(url, mapOf("User-Agent" to "x"), mime, boundedRange = bounded)

    @Test fun aDirectStreamGoesToTheSpeakerWithItsPlainType() =
        assertEquals(CastSource.Direct("https://rr1.googlevideo.com/videoplayback?id=1", "audio/webm"), castSource("yt:abc", stream()))

    @Test fun aStreamWithoutATypeIsSentAsMp4() =
        assertEquals("audio/mp4", (castSource("yt:abc", stream(mime = null)) as CastSource.Direct).mimeType)

    @Test fun whatASpeakerCannotFetchIsRefused() {
        assertEquals(CastSource.Refused("on_device"), castSource("local:42", stream()))
        assertEquals(CastSource.Refused("no_stream"), castSource("yt:abc", null))
        assertEquals(CastSource.Refused("hls"), castSource("yt:abc", stream(mime = "application/x-mpegURL")))
        assertEquals(CastSource.Refused("bounded_range"), castSource("yt:abc", stream(bounded = true)))
        assertEquals(CastSource.Refused("not_https"), castSource("yt:abc", stream(url = "sabr://key")))
    }

    @Test fun theSpeakersSongIsFoundNearestThePhonesPlace() {
        val queue = listOf("a", "b", "c", "b", "d")
        assertEquals(3, nearestEntry(queue, "b", 4))
        assertEquals(1, nearestEntry(queue, "b", 0))
        assertEquals(-1, nearestEntry(queue, "z", 0))
        assertEquals(-1, nearestEntry(queue, "", 0))
    }
}
