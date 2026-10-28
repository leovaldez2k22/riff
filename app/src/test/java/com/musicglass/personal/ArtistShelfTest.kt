package com.musicglass.personal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Feature 8: an artist shelf keeps YouTube's "See all", and the shell opens it. */
class ArtistShelfTest {
    private fun shelf(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test fun aSongShelfKeepsItsBottomLink() {
        val songs = shelf("""{"title":{"runs":[{"text":"Top songs"}]},"contents":[],
            "bottomEndpoint":{"clickTrackingParams":"x","browseEndpoint":{"browseId":"VLOLAK5uy_top_songs"}}}""")
        assertEquals(ProviderMoreEndpoint("VLOLAK5uy_top_songs", null), catalogShelfMore(songs))
    }

    @Test fun aCarouselKeepsItsMoreButtonWithParams() {
        val albums = shelf("""{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Albums"}]},
            "moreContentButton":{"buttonRenderer":{"text":{"runs":[{"text":"More"}]},
            "navigationEndpoint":{"browseEndpoint":{"browseId":"UCartist000000000000000","params":"ggMIegYIARoCAQI%3D"}}}}}},"contents":[]}""")
        assertEquals(ProviderMoreEndpoint("UCartist000000000000000", "ggMIegYIARoCAQI%3D"), catalogShelfMore(albums))
    }

    @Test fun aHeaderTitleLinkIsUsedWhenThereIsNoButton() {
        val singles = shelf("""{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Singles",
            "navigationEndpoint":{"browseEndpoint":{"browseId":"MPADUCartist","params":"abc"}}}]}}},"contents":[]}""")
        assertEquals(ProviderMoreEndpoint("MPADUCartist", "abc"), catalogShelfMore(singles))
    }

    @Test fun aShelfWithoutALinkHasNone() {
        assertNull(catalogShelfMore(shelf("""{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Fans also like"}]}}},"contents":[]}""")))
    }

    @Test fun theRouteSurvivesSavingAndRejectsWhatYouTubeWouldNotSend() {
        val route = Route.artistShelf("UCartist000000000000000", "ggMIegYIARoCAQI%3D", "Albums · Artist")!!
        assertEquals(route, Route.decode(Route.encode(route)))
        val noParams = Route.artistShelf("MPADUCartist", null, "Singles")!!
        assertEquals(noParams, Route.decode(Route.encode(noParams)))
        assertNull(Route.artistShelf("bad id with spaces", null, "Albums"))
        assertNull(Route.artistShelf("UCartist", "params with spaces", "Albums"))
    }

    @Test fun aSongListOpensAsACollectionAndAGridAsAShelf() {
        val model = ShellModel()
        assertTrue(model.openArtistShelf(ProviderMoreEndpoint("VLOLAK5uy_top_songs"), "Top songs"))
        assertTrue(model.screen.route is Route.Collection)
        assertTrue(model.openArtistShelf(ProviderMoreEndpoint("UCartist000000000000000", "abc"), "Albums"))
        val shown = model.screen.route as Route.ArtistShelf
        assertEquals("abc", shown.params)
    }

    @Test fun theTitleSaysWhoseAlbumsTheyAre() {
        assertEquals("Albums · Meira Omar", artistShelfTitle("Albums", "Meira Omar"))
        assertEquals("Albums", artistShelfTitle("Albums", ""))
        assertEquals("Songs by Meira Omar", artistShelfTitle("Songs by Meira Omar", "Meira Omar"))
    }
}
