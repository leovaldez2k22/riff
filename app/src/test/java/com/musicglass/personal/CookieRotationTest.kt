package com.musicglass.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieRotationTest {
    @Test fun keepsOnlyRotatingSessionCookies() {
        val headers = listOf(
            "__Secure-1PSIDTS=sidts-new; Domain=.youtube.com; Path=/; Expires=Sun, 03-Oct-2027 12:00:00 GMT; Secure; HttpOnly",
            "SIDCC=cc-new; expires=Sun, 03-Oct-2027 12:00:00 GMT; path=/; domain=.youtube.com",
            "SAPISID=never-from-a-response; Domain=.youtube.com",
            "YSC=visitor; Domain=.youtube.com",
            "VISITOR_INFO1_LIVE=x; Max-Age=15552000")
        val kept = rotatedSessionCookies(headers)
        assertEquals(listOf("__Secure-1PSIDTS", "SIDCC"), kept.map { it.first })
        assertTrue(kept.first().second.startsWith("__Secure-1PSIDTS=sidts-new; Domain=.youtube.com"))
    }

    @Test fun neverWritesADeletion() {
        val headers = listOf(
            "__Secure-3PSIDTS=; Domain=.youtube.com",
            "__Secure-1PSIDCC=gone; Max-Age=0",
            "__Secure-3PSIDCC=gone; Expires=Thu, 01-Jan-1970 00:00:01 GMT",
            "SIDCC=bad; max-age=-1")
        assertTrue(rotatedSessionCookies(headers).isEmpty())
    }

    @Test fun onlyYouTubeHosts() {
        assertTrue(isYouTubeHost("music.youtube.com")); assertTrue(isYouTubeHost("youtube.com"))
        assertFalse(isYouTubeHost("youtube.com.evil.example")); assertFalse(isYouTubeHost("notyoutube.com"))
        assertFalse(isYouTubeHost("accounts.google.com"))
    }
}
