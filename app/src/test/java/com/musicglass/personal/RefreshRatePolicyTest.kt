package com.musicglass.personal

import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshRatePolicyTest {
    @Test fun usesThePanelsNominal120HzRate() {
        assertEquals(120.00001f, preferredUiRefreshRate(listOf(60f, 90f, 120.00001f)), .001f)
    }
    @Test fun fallsBackToTheFastestSupportedRateBelow120() {
        assertEquals(90f, preferredUiRefreshRate(listOf(60f, 90f)), 0f)
        assertEquals(60f, preferredUiRefreshRate(listOf(60f)), 0f)
    }
    @Test fun avoidsAnUnnecessary144HzRequestWhen120IsSupported() {
        assertEquals(120f, preferredUiRefreshRate(listOf(60f, 120f, 144f)), 0f)
        assertEquals(144f, preferredUiRefreshRate(listOf(144f)), 0f)
    }
    @Test fun unavailableRatesLeaveSelectionToTheSystem() {
        assertEquals(0f, preferredUiRefreshRate(emptyList()), 0f)
        assertEquals(0f, preferredUiRefreshRate(listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f)), 0f)
    }
}
