package com.musicglass.personal

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Feature 24: the suggestion response's two sections, and the top result shown only for an artist or album. */
class SearchSuggestionsTest {
    private val response = """
        {"contents":[
          {"searchSuggestionsSectionRenderer":{"contents":[
            {"searchSuggestionRenderer":{"suggestion":{"runs":[{"text":"beat"},{"text":"les","bold":true}]},
              "navigationEndpoint":{"searchEndpoint":{"query":"beatles"}}}},
            {"historySuggestionRenderer":{"suggestion":{"runs":[{"text":"beatles abbey road"}]},
              "navigationEndpoint":{"searchEndpoint":{"query":"beatles abbey road"}}}},
            {"searchSuggestionRenderer":{"suggestion":{"runs":[{"text":"beat"}]},
              "navigationEndpoint":{"searchEndpoint":{"query":"beat"}}}}
          ]}},
          {"searchSuggestionsSectionRenderer":{"contents":[
            {"musicResponsiveListItemRenderer":{
              "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"The Beatles"}]}}},
                             {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Artist"}]}}}],
              "navigationEndpoint":{"browseEndpoint":{"browseId":"UCbeatles01","browseEndpointContextSupportedConfigs":
                {"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}}}
          ]}}
        ]}""".trimIndent()

    @Test fun textSuggestionsComeFirstWithoutWhatWasTyped() {
        val parsed = parseSearchSuggestions(Json.parseToJsonElement(response), "beat")
        assertEquals(listOf("beatles", "beatles abbey road"), parsed.queries)
    }

    @Test fun readyResultsAreRead() {
        val parsed = parseSearchSuggestions(Json.parseToJsonElement(response), "beat")
        assertEquals(1, parsed.results.size)
        assertEquals("The Beatles", parsed.results[0].title)
        assertEquals(CatalogKind.Artist, parsed.results[0].kind)
    }

    @Test fun anEmptyResponseSuggestsNothing() {
        assert(parseSearchSuggestions(Json.parseToJsonElement("{}"), "x").isEmpty)
    }

    @Test fun theTopResultIsAnArtistOrAlbumInAllOnly() {
        val artist = ProviderHomeCard("The Beatles", "Artist", null, browseId = "UCbeatles01", kind = CatalogKind.Artist)
        val song = ProviderHomeCard("Help!", "The Beatles", null, videoId = "abcdefghijk", kind = CatalogKind.Song)
        assertEquals(artist, searchTopResult(listOf(artist, song), "All"))
        assertNull(searchTopResult(listOf(artist, song), "Artists"))
        assertNull(searchTopResult(listOf(song, artist), "All"))
    }
}
