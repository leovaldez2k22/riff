package com.musicglass.personal
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
class Rc15PolicyTest {
    private val track=ProviderTrack("video000001","Numb","Linkin Park")
    private fun renderer(id:String,title:String,type:String)="""{"playlistPanelVideoRenderer":{"videoId":"$id","title":{"simpleText":"$title"},"navigationEndpoint":{"watchEndpoint":{"videoId":"$id","watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{"musicVideoType":"$type"}}}}}}"""
    private fun wrapped(id:String="video000001",title:String="Linkin Park - Numb (Official Music Video)",type:String="MUSIC_VIDEO_TYPE_OMV")=Json.parseToJsonElement("""{"playlistPanelRenderer":{"contents":[{"playlistPanelVideoWrapperRenderer":{"primaryRenderer":${renderer(id,"Numb","MUSIC_VIDEO_TYPE_ATV")},"counterpart":[{"counterpartRenderer":${renderer("video000002",title,type)}}]}},${renderer("video000003","Other song","MUSIC_VIDEO_TYPE_OMV")}]}}""")
    @Test fun switcherUsesOnlyRequestedSongsOfficialCounterpart() {
        assertEquals("video000002",musicVideoPair(wrapped(),track))
        assertNull(musicVideoPair(wrapped(id="video000004"),track))
        assertNull(musicVideoPair(wrapped(title="Numb (Official Lyrics Video)"),track))
        assertNull(musicVideoPair(wrapped(type="MUSIC_VIDEO_TYPE_UGC"),track))
        assertNull(musicVideoPair(wrapped(title="A completely different song"),track))
    }
    @Test fun primaryOfficialVideoCanUseItself() {
        assertEquals(track.videoId,musicVideoPair(Json.parseToJsonElement(renderer(track.videoId,"Numb","MUSIC_VIDEO_TYPE_OMV")),track))
    }
    @Test fun songVideoCounterpartIsNotInsertedIntoAudioQueue() {
        assertEquals(listOf("video000001","video000003"),parseQueueTracks(wrapped()).map {it.videoId})
    }
    @Test fun pairingRequestPreservesAccountContextAndDoesNotAlterContinuation() {
        val raw="""{"videoId":"video000001","playlistId":"RDAMVMvideo000001","continuation":null,"context":{"client":{"hl":"en"},"user":{"lockedSafetyMode":false}}}"""
        val source=Json.parseToJsonElement(raw).jsonObject;val output=Json.parseToJsonElement(musicPairingRequest(raw)).jsonObject
        assertEquals(source["context"],output["context"]);assertEquals(source["playlistId"],output["playlistId"])
        assertEquals("MUSIC_VIDEO_TYPE_ATV",catalogValue(output["watchEndpointMusicSupportedConfigs"],"musicVideoType"))
        val continuation="""{"videoId":"video000001","continuation":"next"}""";assertEquals(continuation,musicPairingRequest(continuation))
        assertEquals("invalid",musicPairingRequest("invalid"))
    }
    @Test fun dailyPhotoChoiceNeverReusesPreviousAndIsStableForThatDay() {
        val a=photoChoiceOrder(listOf("1","2","2","3"),"1","Relax","2026-10-02")
        assertEquals(setOf("2","3"),a.toSet());assertEquals(a,photoChoiceOrder(listOf("1","2","2","3"),"1","Relax","2026-10-02"))
        assertTrue(photoChoiceOrder(listOf("1"),"1","Relax","2026-10-02").isEmpty())
        assertEquals("2026-10-02",photoDay(LocalDate.of(2026,10,2)))
        assertTrue(moodPhotoQuery("Workout").contains("running"));assertTrue(moodPhotoQuery("Focus").contains("library"))
    }
    @Test fun selectedOutputTypeAndProductNameChooseDistinctIcons() {
        assertEquals(OutputKind.Phone,outputKind(2,"Phone"));assertEquals(OutputKind.Earphones,outputKind(8,"Galaxy Buds Pro"))
        assertEquals(OutputKind.Headphones,outputKind(8,"WH-1000XM5"));assertEquals(OutputKind.Speaker,outputKind(8,"JBL Flip 6"))
        assertEquals(OutputKind.Speaker,outputKind(24,"Living room"));assertEquals(OutputKind.Headphones,outputKind(4,"Wired headphones"))
    }
    @Test fun radioRowsContainOnlyUniqueLiveVideos() {
        fun card(id:String,kind:CatalogKind)=ProviderHomeCard(title=id,subtitle="Channel",artworkUrl=null,videoId=id,kind=kind)
        val rows=uniqueRadioShelves(listOf(ProviderHomeSection("A",listOf(card("one",CatalogKind.Live),card("old",CatalogKind.Song))),ProviderHomeSection("B",listOf(card("one",CatalogKind.Live),card("two",CatalogKind.Live)))))
        assertEquals(listOf("one","two"),rows.flatMap {it.cards}.map {it.videoId});assertTrue(radioChannels.size>=40);assertTrue(radioCategories.size>=12)
        assertEquals(radioChannels.size,radioChannels.map {it.handle.lowercase()}.toSet().size)
        assertEquals("radio",radioFeedKey("Featured"));assertTrue(channelsForRadio("Jazz & café").all {it.category=="Jazz & café"})
    }
    // Phone checks 29 Oct (bug 8): the lens widens under the finger but never stands out of the bar.
    @Test fun navigationLensWidensWithinTheBarAndRespectsReducedMotion() {
        val lens=navigationLens(100f,80f,58f,1f,0f,1f,false)
        assertTrue(lens.centerY-lens.radiusY>=0f);assertTrue(lens.centerY+lens.radiusY<=58f);assertTrue(lens.radiusX>40);assertTrue(lens.gain>0)
        val reduced=navigationLens(100f,80f,58f,1f,80f,1f,true)
        assertEquals(0f,reduced.gain,0f);assertEquals(40f,reduced.radiusX,0f);assertEquals(29f,reduced.radiusY,0f)
        assertEquals(0f,navigationLens(100f,80f,58f,1f,0f,0f,false).gain,0f)
    }
    @Test fun countdownRoundsUpAndNeverShowsNegativeTime() {
        assertEquals("1:00",sleepCountdown(60_001,1));assertEquals("0:01",sleepCountdown(60_001,60_000));assertEquals("0:00",sleepCountdown(60_001,60_002))
        assertEquals(0f,sleepFadeGain(-5),0f);assertEquals(1f,sleepFadeGain(16000),0f);assertEquals(.5f,sleepFadeGain(7500),0f)
    }
    @Test fun photoSearchRejectsNonPhotographsAndUntrustedSources() {
        fun row(id:String,license:String="by-sa",url:String="https://live.staticflickr.com/1/photo.jpg",title:String="Mountain lake")="""{"id":"$id","title":"$title","url":"$url","creator":"Photographer","license":"$license","license_version":"4.0","license_url":"https://creativecommons.org/licenses/by-sa/4.0/","foreign_landing_url":"https://www.flickr.com/photos/1/2","width":1024,"height":768}"""
        val id="f9812900-83f8-49a6-9d86-f2185f64e142"
        val rows=Json.parseToJsonElement("""{"results":[${row(id)},${row(id,license="by-nc")},${row(id,title="Lake painting")},${row(id,url="https://staticflickr.com.evil.example/image.jpg")},${row("../../invalid")},${row(id,url="http://live.staticflickr.com/photo.jpg")}]}""").jsonObject
        val photos=parseOpenversePhotos(rows);assertEquals(1,photos.size);assertEquals("CC BY-SA 4.0",photos.single().license);assertEquals("Photographer",photos.single().author)
    }

    @Test fun glassCategoryLabelsAndBoundsFollowTheirTab() {
        assertTrue(categoryKeyVisible("radio:All","Radio"));assertFalse(categoryKeyVisible("radio:All","Home"))
        assertTrue(categoryKeyVisible("Relax","Home"));assertFalse(categoryKeyVisible("Relax","Radio"))
        assertTrue(categoryKeyVisible("search:Songs","Search"));assertFalse(categoryKeyVisible("search:Songs","Radio"))
        assertFalse(categoryKeyVisible("radio:All","Search"));assertFalse(categoryKeyVisible("All","Library"))
    }

}
