package com.musicglass.personal

import org.junit.Assert.*
import org.junit.Test

/** Q18-017: whether the device can reach the internet, with and without a VPN in front. */
class NetworkConditionsTest {
    private val vpn = NetworkLink(vpn = true, wifi = false, unmetered = true, validated = true)
    private val wifi = NetworkLink(vpn = false, wifi = true, unmetered = true, validated = true)
    private val mobile = NetworkLink(vpn = false, wifi = false, unmetered = false, validated = true)

    @Test fun withoutAVpnTheActiveNetworkDecides() {
        assertEquals(NetworkConditions(true, true, true), networkConditionsOf(wifi, emptyList()))
        assertEquals(NetworkConditions(false, false, true), networkConditionsOf(mobile, emptyList()))
        assertEquals(NetworkConditions(true, true, false), networkConditionsOf(wifi.copy(validated = false), emptyList()))
        assertEquals(NetworkConditions(false, false, false), networkConditionsOf(null, emptyList()))
    }

    @Test fun aValidatedVpnWithNothingUnderItIsOffline() {
        // The phone in Q18B1: NordVPN active and validated, Wi-Fi and mobile data off.
        assertFalse(networkConditionsOf(vpn, listOf(vpn)).validated)
        assertFalse(networkConditionsOf(vpn, emptyList()).validated)
        assertFalse("A network that is not validated does not carry the VPN", networkConditionsOf(vpn, listOf(vpn, wifi.copy(validated = false))).validated)
    }

    @Test fun aVpnOverAValidatedNetworkIsOnlineAndTakesWifiFromIt() {
        assertEquals(NetworkConditions(true, true, true), networkConditionsOf(vpn, listOf(vpn, wifi)))
        assertEquals(NetworkConditions(false, false, true), networkConditionsOf(vpn, listOf(vpn, mobile)))
        assertEquals("Wi-Fi is preferred when both are up", NetworkConditions(true, true, true), networkConditionsOf(vpn, listOf(mobile, vpn, wifi)))
        assertFalse("A VPN that is itself not validated stays offline", networkConditionsOf(vpn.copy(validated = false), listOf(wifi)).validated)
    }
}
