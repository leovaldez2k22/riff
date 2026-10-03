package com.musicglass.personal

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderErrorMessageTest {
    @Test fun offlineIsSaidPlainly() {
        assertEquals(OFFLINE_MESSAGE, providerErrorMessage(UnknownHostException("Unable to resolve host \"music.youtube.com\": No address associated with hostname")))
        assertEquals(OFFLINE_MESSAGE, providerErrorMessage(IOException("wrapped", java.net.ConnectException("refused"))))
    }

    @Test fun timeoutsAreNotCalledOffline() {
        assertEquals(TIMEOUT_MESSAGE, providerErrorMessage(SocketTimeoutException("timeout")))
    }

    @Test fun otherMessagesKeepTheirTextUnlessTechnical() {
        assertEquals("Playlist not found", providerErrorMessage(IllegalStateException("Playlist not found")))
        assertEquals("YouTube Music could not load this content", providerErrorMessage(IOException("HTTP 500 from https://music.youtube.com")))
        assertEquals("YouTube Music could not load this content", providerErrorMessage(IllegalStateException(null as String?)))
    }
}
