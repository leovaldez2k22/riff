package com.musicglass.personal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Q10: the provider class itself, on the pinned library, answering to scripted responses. These show what
 * Riff does with each kind of answer. They say nothing about what YouTube Music answers today.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
class ProviderContractTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val owned="VLPLfixtureOwned0000000001"
    private var world:ProviderWorld?=null
    private fun world(signedIn:Boolean=true)=ProviderWorld(context,signedIn).also {world=it}
    @After fun close() {world?.close()}
    private fun ok(name:String)=ProviderWorld.Reply(providerFixture(name))
    private fun ProviderWorld.ownedPlaylist() {route={when(it.continuation) {
        null->ok("playlist-owned.json");"fixture-owned-page-2"->ok("playlist-owned-page-2.json");"fixture-owned-page-3"->ok("playlist-owned-page-3.json")
        else->error("Unexpected page ${it.continuation}")}}}

    // ---- collections

    @Test fun aCollectionIsReadToItsLastPageAndThenServedFromMemory()=runBlocking {
        val w=world();w.ownedPlaylist()
        val page=w.provider.completeCollection(owned).getOrThrow()
        assertEquals(listOf("fxsong00001","fxsong00002","fxsong00003","fxsong00004"),page.albumTracks.map {it.videoId})
        assertTrue(page.editable);assertNull(page.truncation);assertNull(page.next)
        assertEquals(listOf("browse","browse","browse"),w.endpoints())
        assertEquals(owned,w.calls[0].browseId);assertEquals(listOf(null,"fixture-owned-page-2","fixture-owned-page-3"),w.calls.map {it.continuation})
        assertNull("Nothing partial is left behind",w.provider.partialCollection(owned))
        assertSame(page,w.provider.completeCollection(owned).getOrThrow());assertEquals(3,w.calls.size)
    }
    @Test fun aPageTokenSentTwiceEndsTheListAndTheListSaysItMayBeIncomplete()=runBlocking {
        val w=world()
        // Page three answers with page two again, which names page three again.
        w.route={if(it.continuation==null) ok("playlist-owned.json") else ok("playlist-owned-page-2.json")}
        val page=w.provider.completeCollection(owned).getOrThrow()
        assertEquals(CollectionTruncation.RepeatedPage,page.truncation)
        assertEquals("The repeated token is not followed a second time",3,w.calls.size)
        assertEquals(listOf("fxsong00001","fxsong00002","fxsong00003","fxsong00003"),page.albumTracks.map {it.videoId})
    }
    @Test fun pagesThatGoRoundUnderNewTokensEndTheListToo()=runBlocking {
        val w=world();var n=0
        w.route={ProviderWorld.Reply(fixtureCollectionPage(1..3,"fresh-token-${++n}"))}
        val page=w.provider.completeCollection(owned).getOrThrow()
        assertEquals(CollectionTruncation.RepeatedPage,page.truncation)
        assertEquals("Two pages with nothing new are enough",3,w.calls.size)
    }
    @Test fun aCollectionThatAddsOneSongAPageForEverStopsAtThePageLimit()=runBlocking {
        val w=world();var n=0
        w.route={n++;ProviderWorld.Reply(fixtureCollectionPage(listOf(n),"token-$n"))}
        val page=w.provider.completeCollection(owned).getOrThrow()
        assertEquals(COLLECTION_PAGE_LIMIT,w.calls.size);assertEquals(COLLECTION_PAGE_LIMIT,page.albumTracks.size)
        assertEquals(CollectionTruncation.RepeatedPage,page.truncation)
    }
    @Test fun aCollectionOverTheLimitIsCutAtTheLimitAndSaysSo()=runBlocking {
        val w=world();var n=0
        w.route={val from=n*100+1;n++;ProviderWorld.Reply(fixtureCollectionPage(from until from+100,"token-$n"))}
        val page=w.provider.completeCollection(owned).getOrThrow()
        assertEquals(COLLECTION_TRACK_LIMIT,page.albumTracks.size);assertEquals(CollectionTruncation.Limit,page.truncation)
        assertEquals("Nothing is requested past the limit",20,w.calls.size)
        assertEquals(fixtureVideoId(1),page.albumTracks.first().videoId);assertEquals(fixtureVideoId(2000),page.albumTracks.last().videoId)
        assertEquals(COLLECTION_TRACK_LIMIT,page.albumTracks.map {it.setVideoId}.toSet().size)
        assertTrue(page.truncation!!.label.contains("2,000"))
    }
    @Test fun aCollectionOfExactlyTheLimitIsWhole()=runBlocking {
        val w=world();var n=0
        w.route={val from=n*100+1;n++;ProviderWorld.Reply(fixtureCollectionPage(from until from+100,if(n<20) "token-$n" else null))}
        val page=w.provider.completeCollection(owned).getOrThrow()
        assertEquals(COLLECTION_TRACK_LIMIT,page.albumTracks.size);assertNull(page.truncation)
    }
    @Test fun aLastPageThatRunsPastTheLimitIsCutAndLabelled()=runBlocking {
        val w=world();var n=0
        w.route={n++;if(n==1) ProviderWorld.Reply(fixtureCollectionPage(1..1990,"token-1")) else ProviderWorld.Reply(fixtureCollectionPage(1991..2050))}
        val page=w.provider.completeCollection(owned).getOrThrow()
        assertEquals(COLLECTION_TRACK_LIMIT,page.albumTracks.size);assertEquals(CollectionTruncation.Limit,page.truncation)
    }
    @Test fun anEmptyCollectionIsAnEmptyPage()=runBlocking {
        val w=world();w.route={ProviderWorld.Reply(fixtureCollectionPage(emptyList()))}
        val page=w.provider.completeCollection(owned).getOrThrow()
        assertEquals(emptyList<ProviderHomeCard>(),page.albumTracks);assertNull(page.truncation);assertEquals(1,w.calls.size)
    }

    // ---- failures

    @Test fun anErrorInsideA200FailsTheRequestAndIsNotCachedAsAnEmptyPage()=runBlocking {
        val w=world();w.route={ok("error-envelope.json")}
        val first=w.provider.completeCollection(owned)
        assertEquals("YouTube Music refused this request.",first.exceptionOrNull()?.message)
        assertEquals("A refusal is not asked for again",1,w.calls.size)
        assertNull(w.provider.cachedCollection(owned))
        w.ownedPlaylist()
        assertEquals(4,w.provider.completeCollection(owned).getOrThrow().albumTracks.size)
    }
    @Test fun aRefusalOrAMissingCollectionIsAskedForOnceAndABusyProviderThreeTimes()=runBlocking {
        fun attempts(status:Int):Pair<Int,String?> {
            world?.close();val w=world();w.route={ProviderWorld.Reply("",status)}
            val message=runBlocking {w.provider.completeCollection(owned)}.exceptionOrNull()?.message
            // An unauthenticated answer also sets off a session check; only requests for the collection are counted.
            return w.calls.count {it.endpoint=="browse"} to message
        }
        assertEquals(1 to "YouTube Music refused this request.",attempts(403))
        assertEquals(1 to "This is not available on YouTube Music.",attempts(404))
        assertEquals(3 to "YouTube Music is limiting requests. Try again shortly.",attempts(429))
        assertEquals(3 to "YouTube Music is having trouble. Try again later.",attempts(500))
        assertEquals(3 to "YouTube Music is having trouble. Try again later.",attempts(503))
        assertEquals(1,attempts(400).first)
        val (calls,message)=attempts(401)
        assertEquals(1,calls);assertNotNull(message);assertFalse("No status line is shown",message!!.contains("401"))
    }
    @Test fun aReadThatFailsOnceIsReadAgainAndAReadIsSafeToRepeat()=runBlocking {
        val w=world();var n=0
        w.route={n++;when {n==1->ProviderWorld.Reply("",503);n==2->ProviderWorld.Reply("",200,IOException("fixture reset"))
            it.continuation==null->ok("playlist-owned.json");it.continuation=="fixture-owned-page-2"->ok("playlist-owned-page-2.json");else->ok("playlist-owned-page-3.json")}}
        assertEquals(4,w.provider.completeCollection(owned).getOrThrow().albumTracks.size);assertEquals(5,w.calls.size)
    }
    @Test fun aConnectionFailureIsReportedAsOfflineWithNoAddress()=runBlocking {
        val w=world();w.route={ProviderWorld.Reply("",200,java.net.UnknownHostException("music.youtube.com"))}
        val message=w.provider.browseCatalog("MPREb_fixtureAlbum01").exceptionOrNull()?.message
        assertEquals(OFFLINE_MESSAGE,message)
    }
    @Test fun anAnswerThatArrivesAfterTheAccountChangedIsDropped()=runBlocking {
        val w=world()
        w.route={w.provider.signOut();ok("playlist-owned.json")}
        val result=w.provider.browseCatalog(owned)
        assertTrue("The first account's page is not handed to the next",result.isFailure)
        assertNull(w.provider.cachedCollection(owned));assertNull(w.provider.partialCollection(owned))
    }

    // ---- search, Home, account, watch queue

    @Test fun eachSearchKindSendsItsFilterAndKeepsOnlyThatKind()=runBlocking {
        val w=world()
        val expected=mapOf("Videos" to "search-videos.json","Albums" to "search-albums.json","Artists" to "search-artists.json",
            "Playlists" to "search-playlists.json","Podcasts" to "search-podcasts.json","Songs" to "search-songs.json")
        val sent=mutableMapOf<String,String?>()
        expected.forEach {(filter,fixture)->
            w.calls.clear();w.route={ok(fixture)}
            val page=w.provider.discover("  fixture query  ",filter).getOrThrow()
            assertTrue("$filter found something",page.cards.isNotEmpty())
            assertEquals("$filter: one request",listOf("search"),w.endpoints());assertEquals("fixture query",w.calls[0].field("query"))
            sent[filter]=w.calls[0].field("params")
        }
        assertTrue("Every kind sends a filter",sent.values.all {!it.isNullOrBlank()});assertEquals("Each filter is its own",6,sent.values.toSet().size)
        w.calls.clear();w.route={ok("search-mixed.json")}
        val all=w.provider.discover("fixture query").getOrThrow()
        assertNull("No filter is sent for All",w.calls.single().field("params"));assertEquals(3,all.cards.size)
    }
    @Test fun aSearchWithNoResultsIsEmptyAndAnOverlongQueryIsCut()=runBlocking {
        val w=world();w.route={ok("search-empty.json")}
        assertEquals(emptyList<ProviderHomeCard>(),w.provider.discover("x".repeat(400)).getOrThrow().cards)
        assertEquals(100,w.calls.single().field("query")!!.length)
    }
    @Test fun aSearchErrorInsideA200IsAFailure()=runBlocking {
        val w=world();w.route={ok("error-envelope.json")}
        assertEquals("YouTube Music refused this request.",w.provider.discover("fixture").exceptionOrNull()?.message)
        w.route={ProviderWorld.Reply("",429)}
        assertEquals("YouTube Music is limiting requests. Try again shortly.",w.provider.discover("fixture two").exceptionOrNull()?.message)
    }
    @Test fun theNextSearchPageIsAskedForByItsToken()=runBlocking {
        val w=world();w.route={if(it.continuation==null) ok("search-songs.json") else ok("search-songs-page-2.json")}
        val first=w.provider.discover("fixture","Songs").getOrThrow()
        val more=w.provider.discover("fixture",first.next,"Songs").getOrThrow()
        assertEquals("fixture-search-songs-2",w.calls.last().continuation);assertEquals(listOf("fxsong00003"),more.cards.map {it.videoId});assertNull(more.next)
    }
    @Test fun theAccountMenuGivesTheProfileAndASignedOutSessionAsksNothing()=runBlocking {
        val w=world();w.route={if(it.endpoint=="account/account_menu") ok("account-menu.json") else error("Unexpected ${it.endpoint}")}
        assertEquals("Fixture Listener",w.provider.profile().getOrThrow()?.name)
        w.close();val guest=world(signedIn=false)
        assertNull(guest.provider.profile().getOrThrow());assertEquals(emptyList<String>(),guest.endpoints())
        assertFalse(guest.provider.capabilities.like.available);assertEquals(ProviderCapabilities.SIGN_IN_LIKE,guest.provider.capabilities.like.reason)
        assertTrue(guest.provider.capabilities.browse.available)
    }
    @Test fun aSongIsPairedWithItsOfficialVideoAndNotWithAFanUpload()=runBlocking {
        val w=world();w.route={ok("watch-next.json")}
        val track=ProviderTrack("fxsong00001","Fixture Song 1","Fixture Artist")
        assertEquals("fxvideo0001",w.provider.videoFor(track).getOrThrow());assertEquals(listOf("next"),w.endpoints())
        w.route={ok("watch-next-unofficial-video.json")}
        assertNull(w.provider.videoFor(track.copy(videoId="fxsong00002")).getOrThrow())
    }
    @Test fun upNextNeverHandsBackTheTokenItWasAskedWith()=runBlocking {
        val w=world();w.route={ok("watch-next.json")}
        val (tracks,next)=w.provider.recommendations("fxsong00001").getOrThrow()
        assertEquals(listOf("fxsong00001","fxsong00002","fxsong00003"),tracks.map {it.videoId});assertEquals("fixture-radio-2",next)
        val (_,again)=w.provider.recommendations("fxsong00001","fixture-radio-2").getOrThrow()
        assertNull("The same token again means there is no further page",again)
    }
    @Test fun likeStateIsReadFromTheWatchQueueAndIsNotGuessed()=runBlocking {
        val w=world();w.route={ok("watch-next.json")}
        assertEquals(true,w.provider.loadLike("fxsong00001").getOrThrow())
        w.route={ok("watch-next-unofficial-video.json")}
        assertNull(w.provider.loadLike("fxsong00002").getOrThrow())
    }
    @Test fun lyricsAreFoundThroughTheWatchQueue()=runBlocking {
        val w=world();w.route={if(it.endpoint=="next") ok("watch-next.json") else ok("lyrics-timed.json")}
        val lyrics=w.provider.lyricsFor("fxsong00001",10_000)
        assertNotNull(lyrics);assertTrue(lyrics!!.timed);assertEquals("First fixture line",lyrics.lines[0].text)
        assertNull("A malformed id asks nothing",w.provider.lyricsFor("not an id",10_000))
    }

    // ---- cached pages

    @Test fun aLoadInFlightWhenItsPageIsInvalidatedCannotPutItsResultBack()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val cache=PageMemoryCache(scope,scheduled=false)
            val release=CompletableDeferred<String>()
            val stale=async(Dispatchers.Unconfined) {runCatching {cache.get("1:collection:a") {release.await()}}}
            cache.invalidate("1:collection:a")
            release.complete("before the edit")
            assertTrue(stale.await().exceptionOrNull() is CancellationException)
            assertNull("The stale answer is not stored",cache.peek<String>("1:collection:a"))
            assertEquals("after the edit",cache.get("1:collection:a") {"after the edit"})
            // The account generation is part of every key: a page for one generation is never read by the next.
            assertNull(cache.peek<String>("2:collection:a"))
        } finally {scope.cancel()}
    }
    @Test fun pagesAreKeyedByTheAccountGenerationAndSigningOutEmptiesThem()=runBlocking {
        val w=world();w.ownedPlaylist()
        val before=w.provider.feedKey("collection:$owned")
        w.provider.completeCollection(owned).getOrThrow();assertNotNull(w.provider.cachedCollection(owned))
        w.provider.signOut()
        assertNotEquals(before,w.provider.feedKey("collection:$owned"));assertNull(w.provider.cachedCollection(owned))
    }
}
