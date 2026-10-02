package com.musicglass.personal
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json
class FeaturePolicyTest {
    @Test fun timerHandlesCustomLimitsAndCancellation() {
        assertEquals(601_000L,sleepDeadline(10,1000));assertEquals(0L,sleepDeadline(0,1000))
        assertEquals(0L,sleepDeadline(1441,1000));assertEquals(1L,sleepMinutesRemaining(60_000,59_999));assertEquals(0L,sleepMinutesRemaining(60_000,60_001))
    }
    @Test fun edgeDragScrollHasStableDirectionAndSpeed() {
        assertEquals(0f,queueEdgeScroll(150f,0f,300f,50f),0f)
        assertTrue(queueEdgeScroll(10f,0f,300f,50f)<0);assertTrue(queueEdgeScroll(290f,0f,300f,50f)>0)
        assertEquals(18f,queueEdgeScroll(1000f,0f,300f,50f),0f)
    }
    @Test fun forgottenFavouritesAreImmediatelyAfterQuickPicks() {
        val names=listOf("Forgotten favourites","Trending","Quick picks","Albums for you")
        assertEquals(listOf("Trending","Quick picks","Forgotten favourites","Albums for you"),orderedHomeShelves(names.map {ProviderHomeSection(it,emptyList())}).map {it.title})
    }
    @Test fun failedPlaylistEditsAreNeverReportedAsSaved() {
        assertTrue(playlistEditAccepted("""{"status":"STATUS_SUCCEEDED"}"""))
        assertFalse(playlistEditAccepted("""{"status":"STATUS_FAILED"}"""));assertFalse(playlistEditAccepted("{}"));assertFalse(playlistEditAccepted("""{"error":{"code":403}}"""))
    }
    @Test fun providerMoodEndpointsAreNotReplacedWithSearch() {
        val root=Json.parseToJsonElement("""{"contents":[{"chipCloudChipRenderer":{"text":{"simpleText":"Relax"},"navigationEndpoint":{"browseEndpoint":{"browseId":"FEmusic_home","params":"relax-feed"}}}},{"musicNavigationButtonRenderer":{"buttonText":{"simpleText":"Focus"},"clickCommand":{"browseEndpoint":{"browseId":"FEmusic_moods_and_genres_category","params":"focus-feed"}}}},{"chipCloudChipRenderer":{"text":{"simpleText":"Podcasts"},"navigationEndpoint":{"browseEndpoint":{"browseId":"FEmusic_home","params":"podcasts"}}}}]}""")
        assertEquals(listOf("Relax","Focus"),parseHomeMoods(root).map {it.title});assertEquals("relax-feed",parseHomeMoods(root).first().params)
    }
    @Test fun repeatedPlaylistSongRetainsItsPreciseRemovalToken() {
        fun row(token:String)="""{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"video000001","playlistSetVideoId":"$token"},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"simpleText":"Song"}}}]}}"""
        val root=Json.parseToJsonElement("""{"musicPlaylistShelfRenderer":{"contents":[${row("first")},${row("second")}]}}""")
        val songs=orderedCollectionTracks(root);assertEquals(listOf("first","second"),songs.map {it.setVideoId})
    }
    @Test fun widgetControlsRejectUnexpectedActions() {
        assertTrue(widgetCommandAllowed("toggle"));assertTrue(widgetCommandAllowed("next"));assertFalse(widgetCommandAllowed("delete"))
    }
}
