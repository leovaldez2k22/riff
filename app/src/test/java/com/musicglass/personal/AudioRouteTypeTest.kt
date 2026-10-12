package com.musicglass.personal

import android.media.AudioDeviceInfo
import android.media.MediaRoute2Info
import org.junit.Assert.assertEquals
import org.junit.Test

/** Q14: opening the full player ended the app on Android 12 and 13, where a route cannot be asked for its type. */
class AudioRouteTypeTest {
    private val neverAsked: () -> Int = { throw NoSuchMethodError("MediaRoute2Info.getType") }

    @Test fun aPhoneOlderThanApi34IsNeverAskedForTheRouteType() {
        for (sdk in 31..33) assertEquals(-1, routeDeviceType(sdk, hasRoute = true, routeType = neverAsked))
    }

    @Test fun noSelectedRouteIsThePhoneItselfOnEveryVersion() {
        for (sdk in listOf(31, 33, 34, 36)) assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, routeDeviceType(sdk, hasRoute = false, routeType = neverAsked))
    }

    @Test fun fromApi34TheRouteTypeBecomesTheAudioDeviceType() {
        val expected = mapOf(
            MediaRoute2Info.TYPE_BUILTIN_SPEAKER to AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            MediaRoute2Info.TYPE_WIRED_HEADSET to AudioDeviceInfo.TYPE_WIRED_HEADSET,
            MediaRoute2Info.TYPE_WIRED_HEADPHONES to AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            MediaRoute2Info.TYPE_BLUETOOTH_A2DP to AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            MediaRoute2Info.TYPE_BLE_HEADSET to AudioDeviceInfo.TYPE_BLE_HEADSET,
            MediaRoute2Info.TYPE_UNKNOWN to -1,
        )
        for ((route, device) in expected) assertEquals(device, routeDeviceType(34, hasRoute = true) { route })
    }

    @Test fun anUnknownTypeLeavesTheNameToDecideTheIcon() {
        assertEquals(OutputKind.Earphones, outputKind(routeDeviceType(31, true, neverAsked), "Fixture Buds"))
        assertEquals(OutputKind.Speaker, outputKind(routeDeviceType(31, true, neverAsked), "Phone"))
    }
}
