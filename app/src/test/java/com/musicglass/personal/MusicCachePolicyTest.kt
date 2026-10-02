package com.musicglass.personal
import org.junit.Assert.*
import org.junit.Test
class MusicCachePolicyTest {
    @Test fun cachedSignedStreamsExpireWithSafetyMarginAndLiveAlwaysRefreshes() {
        assertTrue(reusableStream(120_000,10_000,false));assertFalse(reusableStream(50_000,10_000,false));assertFalse(reusableStream(120_000,10_000,true))
    }
    @Test fun recent250MembershipCannotBeDisplacedByOlderFavorites() {
        val rows=(0..270).map {SmartCandidate("song$it",it.toLong(),if(it==0) 500 else 1,60_000,120_000,it==0)}
        val chosen=smartCandidates(rows,300);assertEquals(250,chosen.size);assertFalse(chosen.any {it.id=="song0"});assertTrue(chosen.any {it.id=="song270"})
    }
    @Test fun shortPlaysLiveAndEpisodesDoNotTriggerDownloads() {
        val now=1000L;val base=SmartCandidate("good",now,1,35_000,180_000)
        assertEquals(listOf("good"),smartCandidates(listOf(base,base.copy(id="skip",listenedMs=5000),base.copy(id="live",live=true),base.copy(id="podcast",music=false)),now).map {it.id})
    }
    @Test fun storageReserveAndBudgetLimitPreventOversubscription() {
        val gb=1024L*1024*1024;assertTrue(downloadFits(gb,10_000_000,4*gb,10*gb,32*gb))
        assertFalse(downloadFits(4*gb,10_000_000,4*gb,10*gb,32*gb));assertFalse(downloadFits(0,10_000_000,4*gb,gb,32*gb))
    }
    @Test fun mobileNetworksRequireExplicitConsentAndAValidatedConnection() {
        assertFalse(smartDownloadNetworkAllowed(false,false,false,true))
        assertTrue(smartDownloadNetworkAllowed(true,false,false,true))
        assertFalse(smartDownloadNetworkAllowed(true,false,false,false))
    }
    @Test fun wifiOnlyAvoidsMeteredConnectionsButMobileOptInAllowsThem() {
        assertTrue(smartDownloadNetworkAllowed(false,true,true,true))
        assertFalse(smartDownloadNetworkAllowed(false,true,false,true))
        assertTrue(smartDownloadNetworkAllowed(true,true,false,true))
        assertFalse(smartDownloadNetworkAllowed(false,true,true,false))
    }
}
