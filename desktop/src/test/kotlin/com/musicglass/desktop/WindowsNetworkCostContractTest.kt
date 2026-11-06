package com.musicglass.desktop
import kotlin.test.*
class WindowsNetworkCostContractTest {
    @Test fun onlyAnExplicitlyUnrestrictedUnflaggedConnectionAllowsOptionalLookups(){
        assertTrue(WindowsNetworkCost.Result(1).unmetered)
        for(flags in listOf(null,0,2,4,0x10001,0x20001,0x40001,0x80001,-1))assertFalse(WindowsNetworkCost.Result(flags).unmetered,"$flags")
    }
}
