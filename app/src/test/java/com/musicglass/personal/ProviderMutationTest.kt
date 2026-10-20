package com.musicglass.personal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Q10: changes sent to the provider. A change counts as made only when the provider says so; one whose
 * outcome is not known is settled by reading, and is never sent a second time on a guess. Every request
 * here is answered by the test: no account is touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
class ProviderMutationTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val owned="VLPLfixtureOwned0000000001"
    private val public="VLPLfixturePublic000000001"
    private val absent="fxsong00009"     // in no page of the owned playlist
    private val present="fxsong00003"    // on its second page
    private var world:ProviderWorld?=null
    private fun world(signedIn:Boolean=true)=ProviderWorld(context,signedIn).also {world=it}
    @After fun close() {world?.close()}
    private fun ok(name:String)=ProviderWorld.Reply(providerFixture(name))
    private fun reply(body:String,status:Int=200)=ProviderWorld.Reply(body,status)
    private fun pages(call:ProviderWorld.Call)=when(call.continuation) {
        null->ok(if(call.browseId==public) "playlist-readonly.json" else "playlist-owned.json")
        "fixture-owned-page-2"->ok("playlist-owned-page-2.json");else->ok("playlist-owned-page-3.json")}
    /** Reads answer with the owned playlist; an edit is answered by [edit]. */
    private fun ProviderWorld.script(read:(ProviderWorld.Call)->ProviderWorld.Reply=::pages,edit:(ProviderWorld.Call)->ProviderWorld.Reply) {
        route={if(it.endpoint=="browse/edit_playlist") edit(it) else if(it.endpoint=="browse") read(it) else error("Unexpected ${it.endpoint}")}
    }
    private fun ProviderWorld.edits()=calls.count {it.endpoint=="browse/edit_playlist"}
    private fun ProviderWorld.reads()=calls.count {it.endpoint=="browse"}

    // ---- playlist edits

    @Test fun anAcknowledgedAddIsSentOnceAndTheCachedPlaylistIsDropped()=runBlocking {
        val w=world();w.script {ok("edit-succeeded.json")}
        w.provider.completeCollection(owned).getOrThrow();assertNotNull(w.provider.cachedCollection(owned))
        val reads=w.reads()
        assertTrue(w.provider.addPlaylistSong(owned,absent).isSuccess)
        assertEquals(1,w.edits());assertEquals("An acknowledged change needs no read",reads,w.reads())
        val sent=w.calls.single {it.endpoint=="browse/edit_playlist"}
        assertEquals("PLfixtureOwned0000000001",sent.field("playlistId"));assertTrue(sent.body["actions"].toString().contains(absent))
        assertNull("What was cached is no longer what the provider has",w.provider.cachedCollection(owned))
    }
    @Test fun aRefusedAddFailsAndMayBeSentAgain()=runBlocking {
        listOf(ok("edit-failed.json"),ok("error-envelope.json"),reply("",403),reply("",404),reply("",429)).forEach {answer->
            world?.close();val w=world();w.script {answer}
            val result=w.provider.addPlaylistSong(owned,absent)
            assertEquals("YouTube Music could not update this playlist. It may be read-only.",result.exceptionOrNull()?.message)
            assertFalse(result.exceptionOrNull() is MutationUnconfirmed)
            assertEquals(1,w.edits());assertEquals("A refusal is not read back",0,w.reads())
            w.script {ok("edit-succeeded.json")}
            assertTrue(w.provider.addPlaylistSong(owned,absent).isSuccess);assertEquals(2,w.edits())
        }
    }
    @Test fun anAddThatNeverLeftFailsForThatReasonAndIsNotReadBack()=runBlocking {
        val w=world();w.script {ProviderWorld.Reply("",200,java.net.UnknownHostException("music.youtube.com"))}
        val result=w.provider.addPlaylistSong(owned,absent)
        assertEquals(OFFLINE_MESSAGE,result.exceptionOrNull()?.message);assertEquals(0,w.reads())
    }
    @Test fun anAddWithNoReadableAnswerIsSettledByReadingThePlaylistAndFoundThere()=runBlocking {
        // 200 with nothing that says "succeeded", a server error, a timeout, a dropped connection: each may have arrived.
        listOf(reply("{}"),reply(""),reply("not json"),reply("",500),reply("",503),reply("",408),
            ProviderWorld.Reply("",200,java.net.SocketTimeoutException("fixture")),ProviderWorld.Reply("",200,IOException("fixture reset"))).forEach {answer->
            world?.close();val w=world();w.script {answer}
            assertTrue("The playlist has it, so it was added",w.provider.addPlaylistSong(owned,present).isSuccess)
            assertEquals("Sent once",1,w.edits());assertEquals("The whole playlist was read",3,w.reads())
        }
    }
    @Test fun anAddWithNoReadableAnswerThatIsNotInThePlaylistFailsAndMayThenBeSentAgain()=runBlocking {
        val w=world();w.script {reply("{}")}
        val result=w.provider.addPlaylistSong(owned,absent)
        assertEquals("YouTube Music did not save this change. Try again.",result.exceptionOrNull()?.message)
        assertFalse(result.exceptionOrNull() is MutationUnconfirmed);assertEquals(1,w.edits())
        w.script {ok("edit-succeeded.json")}
        assertTrue(w.provider.addPlaylistSong(owned,absent).isSuccess);assertEquals(2,w.edits())
    }
    @Test fun whenThePlaylistCannotBeReadTheAddStaysUnconfirmedAndTheNextAttemptReadsBeforeSending()=runBlocking {
        val w=world();w.script(read={reply("",404)}) {reply("",500)}
        val first=w.provider.addPlaylistSong(owned,present)
        assertTrue(first.exceptionOrNull() is MutationUnconfirmed);assertEquals(MUTATION_UNCONFIRMED_MESSAGE,first.exceptionOrNull()?.message)
        assertEquals(1,w.edits())
        // Still unreadable: still unconfirmed, and still nothing sent.
        assertTrue(w.provider.addPlaylistSong(owned,present).exceptionOrNull() is MutationUnconfirmed);assertEquals(1,w.edits())
        // Readable now, and the song is there: the first request did arrive. Sending again would have added it twice.
        w.script {error("Nothing may be sent: the first request arrived")}
        assertTrue(w.provider.addPlaylistSong(owned,present).isSuccess);assertEquals(1,w.edits())
    }
    @Test fun anUnconfirmedAddFoundAbsentOnTheNextAttemptIsSentThen()=runBlocking {
        val w=world();w.script(read={reply("",404)}) {reply("",500)}
        assertTrue(w.provider.addPlaylistSong(owned,absent).exceptionOrNull() is MutationUnconfirmed)
        w.script {ok("edit-succeeded.json")}
        assertTrue(w.provider.addPlaylistSong(owned,absent).isSuccess)
        assertEquals("Read first, then sent once more",2,w.edits())
        val order=w.calls.map {it.endpoint}
        assertTrue("The read comes before the second send",order.lastIndexOf("browse")<order.lastIndexOf("browse/edit_playlist"))
    }
    @Test fun anUnconfirmedAddToOnePlaylistDoesNotHoldUpAnother()=runBlocking {
        val w=world();w.script(read={reply("",404)}) {reply("",500)}
        assertTrue(w.provider.addPlaylistSong(owned,absent).exceptionOrNull() is MutationUnconfirmed)
        w.script {ok("edit-succeeded.json")}
        assertTrue(w.provider.addPlaylistSong("VLPLfixtureOther00000000001",absent).isSuccess);assertEquals(2,w.edits())
    }
    @Test fun aPartlyLoadedPlaylistCannotShowThatAnAddDidNotHappen()=runBlocking {
        val w=world();var n=0
        w.script(read={val from=n*100+1;n++;reply(fixtureCollectionPage(from until from+100,"token-$n"))}) {reply("{}")}
        val result=w.provider.addPlaylistSong(owned,absent)
        assertTrue("Absent from the first 2,000 is not absent",result.exceptionOrNull() is MutationUnconfirmed);assertEquals(1,w.edits())
    }
    @Test fun removeNamesTheEntryAndFollowsTheSameRules()=runBlocking {
        val w=world();w.script {ok("edit-succeeded.json")}
        val entry=w.provider.completeCollection(owned).getOrThrow().albumTracks.first()
        assertTrue(w.provider.removePlaylistSong(owned,entry).isSuccess)
        assertTrue(w.calls.single {it.endpoint=="browse/edit_playlist"}.body["actions"].toString().contains("fixture-set-1"))
        // No readable answer and the entry is still in the playlist: not removed.
        w.script {reply("{}")}
        assertEquals("YouTube Music did not save this change. Try again.",w.provider.removePlaylistSong(owned,entry).exceptionOrNull()?.message)
        // No readable answer and the entry is gone: removed, and not sent again.
        val gone=entry.copy(setVideoId="fixture-set-gone")
        assertTrue(w.provider.removePlaylistSong(owned,gone).isSuccess);assertEquals(3,w.edits())
        w.calls.clear()
        assertTrue("An entry with no identifier cannot be named",w.provider.removePlaylistSong(owned,entry.copy(setVideoId=null)).isFailure);assertEquals(0,w.calls.size)
    }
    @Test fun onlyAnEditablePlaylistAndAWellFormedSongAreEverSent()=runBlocking {
        val w=world();w.route={error("Nothing may be sent: ${it.endpoint}")}
        listOf("VLLM","VLSE","VL","PLfixtureOwned0000000001","MPREb_fixtureAlbum01","VLbad id","VL../x","").forEach {
            assertTrue(it,w.provider.addPlaylistSong(it,absent).isFailure)}
        listOf("","short","fxsong00009x","fx song0009","fxsong0000/").forEach {assertTrue(it,w.provider.addPlaylistSong(owned,it).isFailure)}
        assertEquals(0,w.calls.size)
        w.close();val guest=world(signedIn=false);guest.route={error("Nothing may be sent")}
        assertEquals("Sign in to edit YouTube Music playlists",guest.provider.addPlaylistSong(owned,absent).exceptionOrNull()?.message)
        assertTrue(guest.provider.setSongLike(absent,true).isFailure);assertTrue(guest.provider.saveAlbum("MPREb_fixtureAlbum01").isFailure)
        assertEquals(NotInterestedSent.None,guest.provider.sendNotInterested(absent,"fixture-feedback-song-1").getOrThrow())
        assertEquals(0,guest.calls.size)
    }
    @Test fun anAnswerForAnAccountThatHasSinceChangedIsNotASuccess()=runBlocking {
        val w=world();w.script {w.provider.signOut();ok("edit-succeeded.json")}
        assertTrue(w.provider.addPlaylistSong(owned,absent).isFailure)
    }

    // ---- sort

    @Test fun aSortPreferenceIsSentExactlyAsTheProviderOfferedItAndOnlyForItsOwnPlaylist()=runBlocking {
        val w=world();w.script {ok("edit-succeeded.json")}
        val newest=w.provider.completeCollection(owned).getOrThrow().sortEndpoints.getValue(CollectionSort.Newest)
        w.calls.clear()
        assertTrue("Not sent for another playlist",w.provider.sortedCollection(public,newest).isFailure);assertEquals(0,w.edits())
        val sorted=w.provider.sortedCollection(owned,newest)
        assertEquals(sorted.exceptionOrNull()?.message,true,sorted.isSuccess)
        val sent=w.calls.single {it.endpoint=="browse/edit_playlist"}
        assertEquals("fixture-sort-newest",sent.field("params"));assertEquals("PLfixtureOwned0000000001",sent.field("playlistId"))
        assertEquals("""[{"action":"ACTION_SET_PLAYLIST_VIDEO_ORDER","playlistVideoOrder":1}]""",sent.body["actions"].toString())
        assertEquals(4,sorted.getOrThrow().albumTracks.size)
    }

    // ---- like and save

    @Test fun aLikeNeedsAnAnswerThatIsAnAnswer()=runBlocking {
        val w=world();w.route={if(it.endpoint.startsWith("like/")) ok("like-acknowledged.json") else error("Unexpected ${it.endpoint}")}
        assertTrue(w.provider.setSongLike(absent,true).isSuccess);assertTrue(w.provider.setSongLike(absent,false).isSuccess)
        assertEquals(2,w.calls.size);assertEquals(2,w.endpoints().toSet().size);assertTrue(w.calls.all {it.body.toString().contains(absent)})
        listOf(reply("",403),reply("",401),ok("error-envelope.json")).forEach {answer->
            w.calls.clear();w.route={if(it.endpoint.startsWith("like/")) answer else error("A refusal is not read back")}
            assertEquals("YouTube Music did not accept the like",w.provider.setSongLike(absent,true).exceptionOrNull()?.message);assertEquals(1,w.calls.size)
        }
    }
    @Test fun aLikeWithNoReadableAnswerIsWhateverTheProviderNowSays()=runBlocking {
        val w=world()
        // The watch queue fixture says this song is liked.
        w.route={if(it.endpoint.startsWith("like/")) reply("{}") else ok("watch-next.json")}
        assertTrue("Asked to like, and it is liked",w.provider.setSongLike("fxsong00001",true).isSuccess)
        val unlike=w.provider.setSongLike("fxsong00001",false)
        assertEquals("Asked to unlike, and it is still liked","YouTube Music did not save the like. Try again.",unlike.exceptionOrNull()?.message)
        assertEquals(2,w.calls.count {it.endpoint.startsWith("like/")})
        // Nothing readable either way: not confirmed, and not claimed.
        w.route={if(it.endpoint.startsWith("like/")) reply("",500) else reply("",503)}
        assertTrue(w.provider.setSongLike("fxsong00001",true).exceptionOrNull() is MutationUnconfirmed)
        w.route={if(it.endpoint.startsWith("like/")) reply("{}") else ok("watch-next-unofficial-video.json")}
        assertTrue("A queue that does not say is not a yes",w.provider.setSongLike("fxsong00002",true).exceptionOrNull() is MutationUnconfirmed)
    }
    // ---- not interested (owner, 20 Oct 2026: YouTube Music's own where it has a token, else a dislike)

    @Test fun notInterestedUsesYouTubeMusicsOwnFeedbackWhenTheSongHasIt()=runBlocking {
        val w=world();w.route={when(it.endpoint) {"next"->ok("watch-next.json");"feedback"->reply("{}");else->error("Unexpected ${it.endpoint}")}}
        assertEquals(NotInterestedSent.Feedback,w.provider.sendNotInterested("fxsong00001",null).getOrThrow())
        assertEquals(listOf("next","feedback"),w.endpoints());assertTrue(w.calls.last().body.toString().contains("fixture-feedback-song-1"))
        w.calls.clear()
        assertEquals("A token from Home is used as it is",NotInterestedSent.Feedback,w.provider.sendNotInterested(null,"fixture-home-token").getOrThrow())
        assertEquals(listOf("feedback"),w.endpoints())
    }
    @Test fun aSongWithoutThatFeedbackIsDislikedAndACollectionIsNotSent()=runBlocking {
        val w=world();w.route={when {it.endpoint=="next"->ok("watch-next.json");it.endpoint.startsWith("like/")->ok("like-acknowledged.json");else->error("Unexpected ${it.endpoint}")}}
        assertEquals(NotInterestedSent.Disliked,w.provider.sendNotInterested("fxsong00002",null).getOrThrow())
        assertEquals(listOf("next","like/dislike"),w.endpoints());assertTrue(w.calls.last().body.toString().contains("fxsong00002"))
        assertEquals(false,w.provider.songLikes["fxsong00002"])
        w.calls.clear()
        assertEquals(NotInterestedSent.None,w.provider.sendNotInterested(null,null).getOrThrow());assertEquals(0,w.calls.size)
    }
    @Test fun aDislikeIsRefusedOrSettledByReadingNeverClaimedOnAGuess()=runBlocking {
        val w=world()
        w.route={if(it.endpoint.startsWith("like/")) reply("",403) else ok("watch-next.json")}
        val refused=w.provider.sendNotInterested("fxsong00002",null)
        assertEquals("YouTube Music did not accept the feedback",refused.exceptionOrNull()?.message);assertFalse(refused.exceptionOrNull() is MutationUnconfirmed)
        // No readable answer and the song cannot be read: not confirmed, and not sent again.
        var reads=0
        w.route={if(it.endpoint.startsWith("like/")) reply("",500) else if(reads++==0) ok("watch-next.json") else reply("",503)}
        w.calls.clear()
        assertTrue(w.provider.sendNotInterested("fxsong00002",null).exceptionOrNull() is MutationUnconfirmed)
        assertEquals(1,w.calls.count {it.endpoint.startsWith("like/")})
    }

    @Test fun savingAnAlbumUsesTheAlbumsOwnPlaylistAndDoesNotClaimAnUnconfirmedSave()=runBlocking {
        val w=world();w.route={if(it.endpoint=="browse") ok("album.json") else ok("like-acknowledged.json")}
        assertTrue(w.provider.saveAlbum("MPREb_fixtureAlbum01").isSuccess)
        assertTrue(w.calls.last().body.toString().contains("OLAK5uy_fixtureAlbum000000000001"))
        w.route={if(it.endpoint=="browse") ok("album.json") else reply("{}")}
        val unconfirmed=w.provider.saveAlbum("MPREb_fixtureAlbum01")
        assertTrue(unconfirmed.exceptionOrNull() is MutationUnconfirmed)
        assertEquals("YouTube Music did not confirm saving this. It is safe to try again.",unconfirmed.exceptionOrNull()?.message)
        w.route={if(it.endpoint=="browse") ok("album.json") else reply("",403)}
        assertEquals("Could not save album",w.provider.saveAlbum("MPREb_fixtureAlbum01").exceptionOrNull()?.message)
        w.calls.clear();assertTrue(w.provider.saveAlbum("bad id").isFailure);assertEquals(0,w.calls.size)
    }

    // ---- several playlists at once

    @Test fun aBatchKeepsOneResultForEachPlaylistAndARetrySendsOnlyTheUnresolved()=runBlocking {
        val asked=mutableListOf<String>()
        val answers=mutableMapOf("a" to Result.success(Unit),"b" to Result.failure<Unit>(IllegalStateException("B is read-only")),"c" to Result.failure(MutationUnconfirmed()))
        val first=addToSelectedPlaylists(linkedSetOf("a","b","c")) {asked+=it;answers.getValue(it)}
        assertEquals(setOf("a"),first.completed);assertEquals(setOf("b","c"),first.failures.keys);assertEquals(setOf("c"),first.unconfirmed)
        assertEquals("B is read-only",first.failures["b"]);assertEquals(MUTATION_UNCONFIRMED_MESSAGE,first.failures["c"])
        assertEquals("1 saved; 2 could not be updated. 1 not confirmed by YouTube Music. B is read-only. Retry the remaining selections.",first.summary())
        asked.clear();answers["c"]=Result.success(Unit)
        val second=addToSelectedPlaylists(first.failures.keys) {asked+=it;answers.getValue(it)}
        assertEquals("The playlist that already has the song is not asked again",listOf("b","c"),asked)
        assertEquals(setOf("c"),second.completed);assertEquals(setOf("b"),second.failures.keys);assertTrue(second.unconfirmed.isEmpty())
        assertEquals("1 saved; 1 could not be updated. B is read-only. Retry the remaining selections.",second.summary())
    }
    @Test fun addingASongReadsThePlaylistFirstAndSendsOnlyWhenItIsSafeTo()=runBlocking {
        val w=world();w.script {ok("edit-succeeded.json")}
        fun track(id:String)=ProviderTrack(id,"Fixture","Fixture Artist")
        suspend fun add(id:String,video:String)=addTrackToPlaylist(w.provider,w.provider,id,"Fixture list",track(video))
        assertEquals("Fixture list is read-only",add(public,absent).exceptionOrNull()?.message);assertEquals(0,w.edits())
        assertTrue("Already there: nothing to do",add(owned,present).isSuccess);assertEquals(0,w.edits())
        assertTrue(add(owned,absent).isSuccess);assertEquals(1,w.edits())
        // A playlist too large to load whole: the song may be past what was read.
        w.close();val large=world();var n=0
        large.script(read={val from=n*100+1;n++;if(n==1) ok("playlist-owned.json") else reply(fixtureCollectionPage(from until from+100,"token-$n"))}) {error("Nothing may be sent")}
        val result=addTrackToPlaylist(large.provider,large.provider,owned,"Fixture list",track(absent))
        assertEquals("Fixture list is too large for Riff to check for this song",result.exceptionOrNull()?.message);assertEquals(0,large.edits())
    }
}
