package com.musicglass.personal

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class NotInterestedTest {
    private fun menu(icon: String, text: String, token: String?) = """
        {"menuRenderer":{"items":[
          {"menuNavigationItemRenderer":{"text":{"runs":[{"text":"Go to album"}]},"navigationEndpoint":{"browseId":"MPREx"}}},
          {"menuServiceItemRenderer":{"text":{"runs":[{"text":"$text"}]},"icon":{"iconType":"$icon"},
            "serviceEndpoint":{${token?.let { "\"feedbackEndpoint\":{\"feedbackToken\":\"$it\"}" } ?: "\"queueAddEndpoint\":{}"}}}}
        ]}}"""

    @Test fun findsTokenByIconOrEnglishLabel() {
        assertEquals("tok1", notInterestedToken(Json.parseToJsonElement(menu("NOT_INTERESTED", "Nicht interessiert", "tok1"))))
        assertEquals("tok2", notInterestedToken(Json.parseToJsonElement(menu("OTHER", "Not interested", "tok2"))))
    }

    @Test fun ignoresOtherServiceItems() {
        assertNull(notInterestedToken(Json.parseToJsonElement(menu("ADD_TO_REMOTE_QUEUE", "Add to queue", null))))
        assertNull(notInterestedToken(Json.parseToJsonElement(menu("REMOVE", "Remove from history", "history"))))
        assertNull(notInterestedToken(null))
    }

    @Test fun parsedCardsCarryTheirToken() {
        val root = Json.parseToJsonElement("""{"contents":[{"musicTwoRowItemRenderer":{
            "title":{"runs":[{"text":"Song"}]},"subtitle":{"runs":[{"text":"Artist"}]},
            "navigationEndpoint":{"watchEndpoint":{"videoId":"abcdefghijk"}},
            "menu":${menu("NOT_INTERESTED", "Not interested", "card-token")}}}]}""")
        assertEquals("card-token", parseCatalogCards(root).single().notInterestedToken)
    }

    @Test fun watchNextTokenBelongsToTheRequestedSong() {
        fun row(id: String, token: String) = """{"playlistPanelVideoRenderer":{"videoId":"$id","menu":${menu("NOT_INTERESTED", "Not interested", token)}}}"""
        val root = Json.parseToJsonElement("""{"contents":[${row("otherSong00", "wrong")},${row("abcdefghijk", "right")}]}""")
        assertEquals("right", notInterestedTokenFor(root, "abcdefghijk"))
        assertNull(notInterestedTokenFor(root, "missingSong"))
    }

    private fun keys(vararg k: String) = k.map { NotInterestedEntry(it) }
    @Test fun listKeepsNewestAndDropsDuplicates() {
        assertEquals(listOf("b", "a"), addNotInterested(keys("a", "b"), NotInterestedEntry("a")).map { it.key })
        assertEquals(listOf("c", "d"), addNotInterested(keys("a", "b", "c"), NotInterestedEntry("d"), limit = 2).map { it.key })
    }

    @Test fun storedEntriesRoundTripAndKeepLegacyKeys() {
        val entry = NotInterestedEntry("abcdefghijk", "Song", "Artist", "https://i.ytimg.com/x.jpg", true, 42L)
        val album = NotInterestedEntry("MPREalbum", "Album", "", null, false, 43L)
        assertEquals(listOf(entry, album), decodeNotInterested(encodeNotInterested(listOf(entry, album)), null))
        val migrated = decodeNotInterested(encodeNotInterested(listOf(entry)), "abcdefghijk\nzyxwvutsrqp\nVLplaylist")
        assertEquals(listOf("zyxwvutsrqp", "VLplaylist", "abcdefghijk"), migrated.map { it.key })
        assertEquals(listOf(true, false, true), migrated.map { it.song })
        assertTrue(decodeNotInterested("not json", null).isEmpty())
    }

    @Test fun boostMapsToMillibels() {
        assertEquals(0, boostMillibels(1f))
        assertEquals(0, boostMillibels(Float.NaN))
        assertEquals(602, boostMillibels(2f))
        assertEquals(954, boostMillibels(3f))
        assertEquals(954, boostMillibels(5f))
    }

    @Test fun dimStaysClearForAQuickTap() {
        assertEquals(0f, peekDimAlpha(.25f), 0f)
        assertEquals(.5f, peekDimAlpha(1f), 1e-6f)
    }

    @Test fun peekMenuPrefersBelowThenAboveTheCard() {
        val window = IntSize(1000, 2000); val panel = IntSize(500, 600)
        assertEquals(IntOffset(100, 530), peekMenuPosition(IntRect(100, 300, 400, 500), window, panel, 30, 50, 50))
        assertEquals(IntOffset(470, 770), peekMenuPosition(IntRect(900, 1400, 1000, 1600), window, panel, 30, 50, 50))
    }
}
