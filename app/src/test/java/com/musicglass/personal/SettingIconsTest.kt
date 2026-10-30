package com.musicglass.personal

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BlurOff
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.DataSaverOff
import androidx.compose.material.icons.rounded.DataSaverOn
import androidx.compose.material.icons.rounded.Equalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** On and off icons (owner, 30 Oct): a switch's off icon comes from its on icon; the equalizer's switch keeps its one. */
class SettingIconsTest {
    @Test fun aSwitchShowsItsOffIconWhileOff() {
        assertEquals(Icons.Rounded.DataSaverOff, offIconFor(Icons.Rounded.DataSaverOn))
        assertEquals(Icons.Rounded.BlurOff, offIconFor(Icons.Rounded.BlurOn))
    }

    @Test fun theEqualizerSwitchIsLeftAsItLooks() {
        assertNull(offIconFor(Icons.Rounded.Equalizer))
    }
}
