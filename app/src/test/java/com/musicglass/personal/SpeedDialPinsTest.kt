package com.musicglass.personal

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
class SpeedDialPinsTest {
    private fun song(id:String)=ProviderHomeCard("Song $id","Artist","https://example.com/$id.jpg",videoId=id)
    private fun fresh():SpeedDialPins {
        val context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("speed_dial_pins",Context.MODE_PRIVATE).edit().clear().commit()
        return SpeedDialPins(context)
    }
    @Test fun theClockCountsHoursOnceThereIsAnHour() {
        assertEquals("0:00",playerClock(-5));assertEquals("2:13",playerClock(133_000));assertEquals("59:59",playerClock(3_599_000))
        assertEquals("1:00:00",playerClock(3_600_000));assertEquals("1:43:39",playerClock(6_219_000));assertEquals("12:05:09",playerClock(43_509_000))
    }
    @Test fun pinsSurviveARestartWithWhatOpeningThemNeeds() {
        val pins=fresh()
        val podcast=ProviderHomeCard("Show","Host",null,browseId="MPSPabc",kind=CatalogKind.Podcast)
        val episode=song("episode0001").copy(kind=CatalogKind.Episode,notInterestedToken="token",setVideoId="set")
        assertTrue(pins.pin(episode));assertTrue(pins.pin(podcast));assertTrue(pins.pin(episode))
        val restored=SpeedDialPins(RuntimeEnvironment.getApplication()).cards
        assertEquals(listOf("episode0001","MPSPabc"),restored.map {it.key()})
        assertEquals(CatalogKind.Episode,restored[0].kind);assertNull(restored[0].notInterestedToken);assertNull(restored[0].setVideoId)
        assertEquals(CatalogKind.Podcast,restored[1].kind);assertEquals("Show",restored[1].title)
        pins.unpin("episode0001");assertEquals(listOf("MPSPabc"),SpeedDialPins(RuntimeEnvironment.getApplication()).cards.map {it.key()})
    }
    @Test fun aFullDialRefusesAnotherPinAndCardsWithoutAnIdCannotBePinned() {
        val pins=fresh()
        repeat(SPEED_DIAL_PIN_LIMIT) {assertTrue(pins.pin(song("song%07d".format(it))))}
        assertFalse(pins.pin(song("one_too_many")));assertEquals(SPEED_DIAL_PIN_LIMIT,pins.cards.size)
        assertFalse(ProviderHomeCard("Loose","card",null).pinnable())
    }
    @Test fun pinsLeadTheDialAndTheFeedFillsTheRest() {
        val feed=(1..30).map {song("feed%07d".format(it))}
        val dial=withSpeedDialPins(listOf(song("pinned00001"),feed[4]),feed)
        assertEquals(SPEED_DIAL_SIZE,dial.size);assertEquals(listOf("pinned00001",feed[4].key(),feed[0].key()),dial.take(3).map {it.key()})
        assertEquals(1,dial.count {it.key()==feed[4].key()})
    }
    @Test fun importedPinsGoAfterTheOnesHereAndNothingIsPushedOut() {
        val pins=fresh();pins.pin(song("here0000001"))
        repeat(SPEED_DIAL_PIN_LIMIT-3) {pins.pin(song("here%07d".format(it+2)))}
        val result=pins.addAll(listOf(song("here0000001"),song("new00000001"),song("new00000002"),song("new00000003"),ProviderHomeCard("No","id",null)))
        assertEquals(SpeedDialPins.Added(added=2,present=2,full=1),result)
        assertEquals("here0000001",pins.cards.first().key());assertEquals(listOf("new00000001","new00000002"),pins.cards.takeLast(2).map {it.key()})
        assertEquals(SPEED_DIAL_PIN_LIMIT,SpeedDialPins(RuntimeEnvironment.getApplication()).cards.size)
    }
    @Test fun aDamagedStoreLoadsAsNoPins() {
        assertEquals(emptyList<ProviderHomeCard>(),decodeSpeedDialPins("not json"))
        assertEquals(emptyList<ProviderHomeCard>(),decodeSpeedDialPins("""[{"title":"no id"}]"""))
        assertNull(decodeSpeedDialPins("""[{"videoId":"abc","artwork":"http://plain"}]""").single().artworkUrl)
    }
}
