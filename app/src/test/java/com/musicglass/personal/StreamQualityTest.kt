package com.musicglass.personal

import com.metrolist.innertubex.extraction.AudioQuality
import org.junit.Assert.*
import org.junit.Test

/** Q18-089 and Q18-090: which client a download asks first, and when the library is told the account has Premium. */
class StreamQualityTest {
    @Test fun aDownloadAsksWebRemixFirstUnlessItIsExcluded() {
        assertEquals("WEB_REMIX", downloadClientOverride(download = true, video = false, live = false, excluded = emptySet()))
        assertNull(downloadClientOverride(download = false, video = false, live = false, excluded = emptySet()))
        assertNull(downloadClientOverride(download = true, video = true, live = false, excluded = emptySet()))
        assertNull("a recovery that ruled it out is kept", downloadClientOverride(download = true, video = false, live = false, excluded = setOf("WEB_REMIX")))
    }

    @Test fun premiumIsPassedOnlyWhenConfirmedAndOnlyForHighQualityAudio() {
        assertTrue(streamHints(video = false, live = false, download = false, quality = AudioQuality.HIGH, premium = true).premium)
        assertTrue(streamHints(video = false, live = false, download = true, quality = AudioQuality.HIGH, premium = true).premium)
        assertFalse(streamHints(video = false, live = false, download = false, quality = AudioQuality.HIGH, premium = false).premium)
        assertFalse(streamHints(video = false, live = false, download = false, quality = AudioQuality.LOW, premium = true).premium)
        assertFalse(streamHints(video = true, live = false, download = false, quality = AudioQuality.HIGH, premium = true).premium)
        val download = streamHints(video = false, live = false, download = true, quality = AudioQuality.HIGH, premium = true)
        assertTrue("the transport limits stay", download.allowSabr && download.allowBoundedRange && !download.allowHls)
    }

    /** Stands for an exception R8 renamed in a release build. */
    private class ja5(cause: Throwable? = null) : java.io.IOException("secret message", cause)

    @Test fun aRenamedFailureIsLoggedByTheNearestKeptName() {
        // Q18-089: "type=ja5" said nothing; the nearest superclass with a real name stands in, and causes follow.
        assertEquals("IOException", readableFailureName(ja5()))
        assertEquals("IllegalStateException", readableFailureName(IllegalStateException("x")))
        assertEquals("none", readableFailureCauses(ja5()))
        assertEquals("IOException>SocketTimeoutException", readableFailureCauses(RuntimeException(ja5(java.net.SocketTimeoutException("t")))))
        val logged = DiagnosticEvent.parse("download_preferred_client_failed type=${readableFailureName(ja5())} causes=${readableFailureCauses(RuntimeException(ja5()))}").text
        assertEquals("download_preferred_client_failed type=IOException causes=IOException", logged)
    }

    @Test fun onlyPremiumOnlyFormatsConfirmPremium() {
        assertTrue(premiumOnlyFormat(774)); assertTrue(premiumOnlyFormat(141))
        assertFalse(premiumOnlyFormat(251)); assertFalse(premiumOnlyFormat(140)); assertFalse(premiumOnlyFormat(null))
    }
}
