package com.musicglass.desktop

import kotlinx.serialization.json.*
import java.util.concurrent.Executor
import kotlin.test.*

class SearchAndLinkContractTest {
    @Test fun linksAcceptOnlyKnownHostsPathsAndValidatedIds(){
        val id="dQw4w9WgXcQ"
        for(url in listOf("https://music.youtube.com/watch?v=$id","https://www.youtube.com/watch?v=$id","https://youtu.be/$id","https://youtube.com/shorts/$id"))assertEquals(DesktopSharedTarget.Song(id),parseYouTubeLink("Listen: $url"))
        assertEquals(DesktopSharedTarget.Song(id,90000),parseYouTubeLink("https://youtu.be/$id?t=1m30s"))
        assertEquals(DesktopSharedTarget.Collection("VLPL123"),parseYouTubeLink("https://music.youtube.com/playlist?list=PL123"))
        assertEquals(DesktopSharedTarget.Collection("MPREb_123"),parseYouTubeLink("https://music.youtube.com/browse/MPREb_123"))
        assertEquals(DesktopSharedTarget.Collection("UC123"),parseYouTubeLink("https://youtube.com/channel/UC123"))
        for(url in listOf("https://youtube.com.evil/watch?v=$id","https://evil@youtube.com/watch?v=$id","https://youtu.be/short","https://youtube.com/watch?v=$id&v=aaaaaaaaaaa","file:///C:/music.mp3","https://youtube.com:9999/watch?v=$id","https://youtube.com/browse/../../secret"))assertNull(parseYouTubeLink(url),url)
    }
    @Test fun timestampParsingCannotOverflowOrExceedOneDay(){
        assertEquals(3723000L,linkStartMs("1h2m3s"));assertEquals(90000L,linkStartMs("90"))
        assertEquals(86400000L,linkStartMs("99999h"));assertEquals(0L,linkStartMs("99999999999999999999999999999999999999"));assertEquals(0L,linkStartMs("-90"));assertEquals(0L,linkStartMs("bad"))
    }
    @Test fun suggestionsRetainActualQueriesBoundDeduplicateAndExcludeTypedText(){
        val nodes=(0..12).map{index->buildJsonObject{put("searchSuggestionRenderer",buildJsonObject{put("navigationEndpoint",buildJsonObject{put("searchEndpoint",buildJsonObject{put("query",if(index==0)"daft"else"daft $index")})})})}}
        val result=parseSearchSuggestions(JsonArray(nodes+nodes),"daft")
        assertEquals((1..6).map{"daft $it"},result.queries);assertTrue(result.results.isEmpty())
    }
    @Test fun pathologicalSuggestionsDoNotWalkUnboundedTrees(){
        var node:JsonElement=JsonPrimitive("leaf");repeat(40){node=buildJsonObject{put("child",node)}}
        assertFailsWith<IllegalArgumentException>{parseSearchSuggestions(node,"query")}
    }
    @Test fun fullSearchDoesNotTurnAnArtistsPlayButtonIntoASong(){
        val root=Json.parseToJsonElement("""{"contents":[
          {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Artist identity"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"UCactualArtist"}},"playNavigationEndpoint":{"watchEndpoint":{"videoId":"aaaaaaaaaaa"}}}},
          {"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Actual recording","navigationEndpoint":{"watchEndpoint":{"videoId":"bbbbbbbbbbb"}}}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Actual artist","navigationEndpoint":{"browseEndpoint":{"browseId":"UCactualArtist"}}}]}}}],"fixedColumns":[{"musicResponsiveListItemFixedColumnRenderer":{"text":{"runs":[{"text":"3:12"}]}}}]}},
          {"lockupViewModel":{"contentId":"ccccccccccc","metadata":{"lockupMetadataViewModel":{"title":{"content":"Modern video recording"}}}}}
        ]}""")
        val cards=parseCatalogCards(root)
        assertEquals(CatalogKind.Artist,cards.first().kind);assertNull(cards.first().videoId)
        val songs=playableSearchTracks(cards)
        assertEquals(listOf("youtube:bbbbbbbbbbb","youtube:ccccccccccc"),songs.map{it.id})
        assertEquals("Actual artist",songs.first().artist)
        assertEquals("Actual recording",songs.first().title)
        assertEquals(192000L,songs.first().durationMs)
        assertEquals("UCactualArtist",songs.first().artistId)
    }
    @Test fun topResultRequiresAnActualDedicatedProviderShelf(){
        val row="""{"title":{"runs":[{"text":"Exact album"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"MPREactualAlbum"}}}"""
        val ordinary=parseDesktopSearchResult(Json.parseToJsonElement("""{"musicTwoRowItemRenderer":$row}"""))
        assertNull(ordinary.topResult)
        val top=parseDesktopSearchResult(Json.parseToJsonElement("""{"musicCardShelfRenderer":$row}"""))
        assertEquals("MPREactualAlbum",top.topResult?.browseId);assertTrue(top.tracks.isEmpty())
    }
    @Test fun submittedQueriesAndDisabledSuggestionsRestoreFromRealSqlite(){
        val directory=kotlin.io.path.createTempDirectory("riff-search-state-").toFile()
        fun host()=DesktopHost(audioBridge=testAudioBridge(),directory=directory,executor=Executor{it.run()},audioExecutor=Executor{it.run()},initialState=HostSnapshot(searchSuggestionsEnabled=false),enableDataLayer=false,searchProvider={emptyList()})
        try{
            host().use{h->h.dispatch(DesktopCommand.SetSearchSuggestions(false));for(i in 0..24)h.dispatch(DesktopCommand.SubmitSearch("query $i"));assertEquals(20,h.snapshot.recentSearches[h.accounts.token.value.scope]!!.size)}
            host().use{h->assertFalse(h.snapshot.searchSuggestionsEnabled);val recent=h.snapshot.recentSearches[h.accounts.token.value.scope]!!;assertEquals("query 24",recent.first());assertEquals(20,recent.size)}
        }finally{directory.deleteRecursively()}
    }
}
