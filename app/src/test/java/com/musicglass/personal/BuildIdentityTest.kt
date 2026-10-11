package com.musicglass.personal

import org.junit.Assert.*
import org.junit.Test

class BuildIdentityTest {
    @Test fun dirtyContentAndVariantDistinguishBuildsFromTheSameCommit() {
        val first = BuildIdentity("a".repeat(40), "dirty", "b".repeat(64), "debug", 22)
        assertNotEquals(first.shortId, first.copy(sourceDigest = "c".repeat(64)).shortId)
        assertNotEquals(first.shortId, first.copy(variant = "release").shortId)
        assertTrue(first.diagnosticLine.contains("revision=" + "a".repeat(40)))
        assertTrue(first.diagnosticLine.contains("source=" + "b".repeat(64)))
    }

    @Test fun unavailableGitNeverClaimsClean() {
        val local = BuildIdentity("local-unknown", "local-unknown", "c".repeat(64), "debug", 22)
        assertTrue(local.shortId.startsWith("local-unknown"))
        assertFalse(local.diagnosticLine.contains("state=clean"))
    }

    @Test fun generatedIdentityContainsRealContentDigestAndCandidateVersion() {
        assertTrue(BuildIdentity.current().sourceDigest.matches(Regex("[a-f0-9]{64}")))
        assertTrue(BuildIdentity.current().sourceState in setOf("clean", "dirty", "local-unknown"))
    }
}
