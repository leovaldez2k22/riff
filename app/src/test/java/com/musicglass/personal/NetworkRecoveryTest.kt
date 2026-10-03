package com.musicglass.personal

import java.io.IOException
import java.net.UnknownHostException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkRecoveryTest {
    @Test fun networkFailuresAreRecognised() {
        // What the soak hit: SABR wraps the cause in its message.
        assertTrue(NetworkRecovery.isNetworkFailure("ERROR_CODE_IO_UNSPECIFIED",
            listOf(IOException("Source error"), IOException("SABR playback failed (UnknownHostException)"))))
        assertTrue(NetworkRecovery.isNetworkFailure("ERROR_CODE_IO_UNSPECIFIED", listOf(UnknownHostException("music.youtube.com"))))
        assertTrue(NetworkRecovery.isNetworkFailure("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", emptyList()))
    }

    @Test fun otherFailuresAreNot() {
        assertFalse(NetworkRecovery.isNetworkFailure("ERROR_CODE_IO_BAD_HTTP_STATUS", listOf(IOException("HTTP 403"))))
        assertFalse(NetworkRecovery.isNetworkFailure("ERROR_CODE_DECODING_FAILED", listOf(IllegalStateException("codec"))))
    }

    @Test fun retriesOnlyWhileWantedAndLimited() {
        assertTrue(NetworkRecovery.shouldRetry(networkFailure = true, playWhenReady = true, hasError = true, attempts = 0))
        assertFalse(NetworkRecovery.shouldRetry(true, playWhenReady = false, hasError = true, attempts = 0)) // user paused
        assertFalse(NetworkRecovery.shouldRetry(true, true, hasError = false, attempts = 0)) // already recovered
        assertFalse(NetworkRecovery.shouldRetry(true, true, true, attempts = NetworkRecovery.MAX_ATTEMPTS))
        assertFalse(NetworkRecovery.shouldRetry(networkFailure = false, playWhenReady = true, hasError = true, attempts = 0))
    }
}
