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
 * Q18-104: a playlist the account can edit can be renamed and deleted, through YouTube Music's own edit and delete
 * calls. A delete is never sent twice on a guess. Every request is answered by the test: no account is touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
class PlaylistManageTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private var world:ProviderWorld?=null
    @After fun close() {world?.close()}
    private fun world(signedIn:Boolean=true,reply:(ProviderWorld.Call)->ProviderWorld.Reply)=
        ProviderWorld(context,signedIn).also {world?.close();world=it;it.route=reply}
    private val id="VLPLfixtureOwn00000000001"

    @Test fun aRenameSendsTheNewNameThroughTheEditCall()=runBlocking {
        val w=world {ProviderWorld.Reply("""{"responseContext":{},"status":"STATUS_SUCCEEDED"}""")}
        assertTrue(w.provider.renamePlaylist(id,"  Road trip  ").isSuccess)
        val call=w.calls.single()
        assertEquals("browse/edit_playlist",call.endpoint)
        assertEquals("PLfixtureOwn00000000001",call.field("playlistId"))
        val actions=call.body["actions"].toString()
        assertTrue(actions,actions.contains("ACTION_SET_PLAYLIST_NAME") && actions.contains("\"Road trip\""))
    }

    @Test fun aRenameNeedsANameAnEditablePlaylistAndSigningIn()=runBlocking {
        val w=world {error("Nothing may be sent")}
        assertEquals("Name the playlist.",w.provider.renamePlaylist(id,"   ").exceptionOrNull()?.message)
        assertEquals("Name the playlist.",w.provider.renamePlaylist(id,"x".repeat(PLAYLIST_TITLE_LIMIT+1)).exceptionOrNull()?.message)
        assertTrue("Liked songs is not renamed",w.provider.renamePlaylist("VLLM","Mine").isFailure)
        assertEquals(0,w.calls.size)
        val out=world(signedIn=false) {error("Nothing may be sent")}
        assertTrue(out.provider.renamePlaylist(id,"Road trip").isFailure)
        assertEquals(0,out.calls.size)
    }

    @Test fun aRejectedRenameSaysSo()=runBlocking {
        val w=world {ProviderWorld.Reply("""{"responseContext":{},"status":"STATUS_FAILED"}""")}
        val result=w.provider.renamePlaylist(id,"Road trip")
        assertTrue(result.isFailure);assertFalse(result.exceptionOrNull() is MutationUnconfirmed)
    }

    @Test fun aDeleteGoesThroughTheDeleteCallOnce()=runBlocking {
        val w=world {ProviderWorld.Reply("""{"responseContext":{}}""")}
        assertTrue(w.provider.deletePlaylist(id).isSuccess)
        val call=w.calls.single()
        assertEquals("playlist/delete",call.endpoint)
        assertEquals("PLfixtureOwn00000000001",call.field("playlistId"))
    }

    @Test fun anErrorInsideA200DeletesNothing()=runBlocking {
        val w=world {ProviderWorld.Reply("""{"error":{"code":403,"status":"PERMISSION_DENIED"}}""")}
        val result=w.provider.deletePlaylist(id)
        assertTrue(result.isFailure);assertFalse(result.exceptionOrNull() is MutationUnconfirmed)
        assertEquals(1,w.calls.size)
    }

    @Test fun anUnansweredDeleteIsNotSentAgainAndSaysToLookFirst()=runBlocking {
        listOf(ProviderWorld.Reply(""),ProviderWorld.Reply("{}"),ProviderWorld.Reply("",503)).forEach {answer->
            val w=world {answer}
            val result=w.provider.deletePlaylist(id)
            assertTrue(result.exceptionOrNull() is MutationUnconfirmed)
            assertEquals("YouTube Music did not confirm the delete. Look in your library before trying again.",result.exceptionOrNull()?.message)
            assertEquals("Sent once",listOf("playlist/delete"),w.calls.map {it.endpoint})
        }
    }

    @Test fun likedSongsAndOtherListsAreNeverDeleted()=runBlocking {
        val w=world {error("Nothing may be sent")}
        listOf("VLLM","VLSE","PLnotABrowseId0000001","MPREb_album000000001","VL").forEach {
            assertTrue(it,w.provider.deletePlaylist(it).isFailure)
        }
        assertEquals(0,w.calls.size)
        val out=world(signedIn=false) {error("Nothing may be sent")}
        assertTrue(out.provider.deletePlaylist(id).isFailure)
        assertEquals(0,out.calls.size)
    }
}
