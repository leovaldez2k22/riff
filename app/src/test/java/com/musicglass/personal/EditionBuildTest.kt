package com.musicglass.personal

import org.junit.Assert.*
import org.junit.Test

class EditionBuildTest {
    @Test fun flavorHasTheExpectedEditionIdentity() {
        assertTrue(BuildConfig.EDITION in setOf("riff", "plus"))
        assertEquals(BuildConfig.FLAVOR == "plus", BuildConfig.PLUS)
        assertEquals(BuildConfig.PLUS, Edition.plus)
        assertEquals(BuildConfig.FLAVOR, BuildConfig.EDITION)
        assertEquals("com.musicglass.personal", BuildConfig.APPLICATION_ID)
    }
}
