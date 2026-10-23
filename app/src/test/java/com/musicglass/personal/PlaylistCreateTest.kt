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

/**
 * Q18-081: Riff makes a playlist through YouTube Music's own create call, private, and adds the song to it.
 * A create is made only when the answer names the new playlist, and is never sent twice on a guess.
 * Every request is answered by the test: no account is touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
class PlaylistCreateTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private var world:ProviderWorld?=null
    @After fun close() {world?.close()}
    private fun world(signedIn:Boolean=true,reply:(ProviderWorld.Call)->ProviderWorld.Reply)=
        ProviderWorld(context,signedIn).also {world=it;it.route=reply}
    private val song=ProviderTrack("fxsong00001","Fixture song","Fixture artist")

    @Test fun aNewPlaylistIsPrivateOnTheAccountAndHoldsTheSong()=runBlocking {
        val w=world {call->when(call.endpoint) {
            "playlist/create"->ProviderWorld.Reply("""{"responseContext":{},"playlistId":"PLfixtureNew00000000001"}""")
            "browse/edit_playlist"->ProviderWorld.Reply("""{"responseContext":{},"status":"STATUS_SUCCEEDED"}""")
            else->error("Unexpected ${call.endpoint}")
        }}
        assertEquals("VLPLfixtureNew00000000001",newPlaylistWith(w.provider,"  Road trip ",song).getOrThrow())
        val create=w.calls.single {it.endpoint=="playlist/create"}
        assertEquals("Road trip",create.field("title"))
        assertEquals("PRIVATE",create.field("privacyStatus"))
        val add=w.calls.single {it.endpoint=="browse/edit_playlist"}
        assertEquals("PLfixtureNew00000000001",add.field("playlistId"))
        assertTrue(add.body["actions"].toString().contains("fxsong00001"))
        assertEquals("A new playlist is not read before the song goes in",0,w.calls.count {it.endpoint=="browse"})
    }

    @Test fun anErrorInsideA200MakesNothing()=runBlocking {
        val w=world {ProviderWorld.Reply("""{"error":{"code":400,"status":"INVALID_ARGUMENT"}}""")}
        val result=w.provider.createPlaylist("Road trip")
        assertTrue(result.isFailure);assertFalse(result.exceptionOrNull() is MutationUnconfirmed)
        assertEquals(1,w.calls.size)
    }

    @Test fun anUnansweredCreateIsNotSentAgainAndSaysToLookFirst()=runBlocking {
        listOf(ProviderWorld.Reply(""),ProviderWorld.Reply("{}"),ProviderWorld.Reply("""{"responseContext":{}}"""),ProviderWorld.Reply("",503)).forEach {answer->
            world?.close()
            val w=world {answer}
            val result=newPlaylistWith(w.provider,"Road trip",song)
            assertTrue(result.exceptionOrNull() is MutationUnconfirmed)
            assertEquals("YouTube Music did not confirm the new playlist. Look in your library before trying again.",result.exceptionOrNull()?.message)
            assertEquals("Sent once, and no song added to a playlist that may not exist",listOf("playlist/create"),w.calls.map {it.endpoint})
        }
    }

    @Test fun aNameIsNeededAndSigningInFirst()=runBlocking {
        val w=world {error("Nothing may be sent")}
        assertEquals("Name the playlist.",w.provider.createPlaylist("   ").exceptionOrNull()?.message)
        assertEquals("Name the playlist.",w.provider.createPlaylist("x".repeat(PLAYLIST_TITLE_LIMIT+1)).exceptionOrNull()?.message)
        world?.close()
        val out=world(signedIn=false) {error("Nothing may be sent")}
        assertTrue(out.provider.createPlaylist("Road trip").isFailure)
        assertEquals(0,w.calls.size+out.calls.size)
    }

    @Test fun whenTheSongCannotBeAddedTheMessageSaysThePlaylistWasMade()=runBlocking {
        val w=world {call->when(call.endpoint) {
            "playlist/create"->ProviderWorld.Reply("""{"responseContext":{},"playlistId":"PLfixtureNew00000000001"}""")
            else->ProviderWorld.Reply("""{"responseContext":{},"status":"STATUS_FAILED"}""")
        }}
        val message=newPlaylistWith(w.provider,"Road trip",song).exceptionOrNull()?.message.orEmpty()
        assertTrue(message,message.startsWith("Road trip was made, but the song was not added"))
    }

    @Test fun theCreateAnswerIsReadStrictly() {
        assertEquals(MutationOutcome.Acknowledged,createPlaylistOutcome(200,"""{"playlistId":"PLabc"}"""))
        assertEquals(MutationOutcome.Unknown,createPlaylistOutcome(200,"""{"playlistId":"bad id!"}"""))
        assertEquals(MutationOutcome.Rejected,createPlaylistOutcome(403,""))
        assertEquals(MutationOutcome.Unknown,createPlaylistOutcome(500,""))
        assertNull(createdPlaylistId("not json"))
    }
}
