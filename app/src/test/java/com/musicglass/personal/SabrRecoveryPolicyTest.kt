package com.musicglass.personal

import com.metrolist.innertubex.sabr.SabrFailureKind
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class SabrRecoveryPolicyTest {
    private fun attestation() = SabrPlaybackFailure("SABR playback failed", SabrFailureKind.ATTESTATION_REQUIRED)

    @Test fun firstAttestationFailureRefreshesThatSong() {
        assertEquals("abc123", sabrAttestationRetryId(attestation(), "yt:abc123", live = false, alreadyRefreshed = emptySet()))
    }

    @Test fun secondFailureOnTheSameSongGivesUp() {
        assertNull(sabrAttestationRetryId(attestation(), "yt:abc123", live = false, alreadyRefreshed = setOf("abc123")))
        // A different song still gets its own one refresh.
        assertEquals("other", sabrAttestationRetryId(attestation(), "yt:other", live = false, alreadyRefreshed = setOf("abc123")))
    }

    @Test fun otherSabrKindsAreNotRefreshed() {
        for (kind in listOf(SabrFailureKind.PROTOCOL, SabrFailureKind.RELOAD_PLAYER, null))
            assertNull("$kind", sabrAttestationRetryId(SabrPlaybackFailure("x", kind), "yt:abc123", false, emptySet()))
    }

    @Test fun failureIsFoundInsideMedia3Wrappers() {
        // Media3 wraps data-source errors (PlaybackException > ... > IOException).
        val wrapped = RuntimeException("playback", IOException("source", attestation()))
        assertEquals("abc123", sabrAttestationRetryId(wrapped, "yt:abc123", false, emptySet()))
    }

    @Test fun nonSabrErrorsLiveItemsAndNonYouTubeItemsAreIgnored() {
        assertNull(sabrAttestationRetryId(IOException("HTTP 403"), "yt:abc123", false, emptySet()))
        assertNull(sabrAttestationRetryId(null, "yt:abc123", false, emptySet()))
        assertNull(sabrAttestationRetryId(attestation(), "yt:abc123", live = true, alreadyRefreshed = emptySet()))
        assertNull(sabrAttestationRetryId(attestation(), "local:42", false, emptySet()))
        assertNull(sabrAttestationRetryId(attestation(), "yt:", false, emptySet()))
        assertNull(sabrAttestationRetryId(attestation(), null, false, emptySet()))
    }

    @Test fun cyclicCauseChainTerminates() {
        val a = IOException("a"); val b = IOException("b", a); a.initCause(b)
        assertNull(sabrAttestationRetryId(a, "yt:abc123", false, emptySet()))
    }

    @Test fun faultInjectionIsOffByDefaultAndCountsDown() {
        assertFalse(SabrFaultInjection.consume())
        SabrFaultInjection.failNextAttestations(2)
        assertTrue(SabrFaultInjection.consume()); assertTrue(SabrFaultInjection.consume())
        assertFalse(SabrFaultInjection.consume())
        SabrFaultInjection.failNextAttestations(500)
        repeat(10) { assertTrue(SabrFaultInjection.consume()) }
        assertFalse("capped at 10", SabrFaultInjection.consume())
    }
}
