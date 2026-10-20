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

/** Undo of "Not interested" takes back a dislike Riff sent, only on the account it was sent for (owner, 20 Oct 2026). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
class NotInterestedUndoTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private var world:ProviderWorld?=null
    @After fun close() {world?.close()}
    private fun store()=NotInterestedStore.get(context).also {s->s.entries.forEach {s.remove(it.key)}}

    @Test fun theDislikeIsTakenBackOnTheAccountThatMadeIt()=runBlocking {
        val w=ProviderWorld(context).also {world=it};w.route={if(it.endpoint.startsWith("like/")) ProviderWorld.Reply(providerFixture("like-acknowledged.json")) else error("Unexpected ${it.endpoint}")}
        val store=store();val scope=w.provider.accountScopeKey()!!
        store.add(NotInterestedEntry("fxsong00002","Song"));store.markDisliked("fxsong00002",scope)
        assertEquals(scope,store.entries.single().dislikedFor)
        val notices=mutableListOf<String>()
        undoNotInterested(context,w.provider,store.entries.single(),notices::add)
        assertTrue(store.entries.isEmpty());assertEquals(listOf("like/removelike"),w.endpoints())
        assertEquals(listOf("Riff and YouTube Music can suggest this again"),notices)
    }
    @Test fun anotherAccountSignedInNowIsNeverTouched()=runBlocking {
        val w=ProviderWorld(context).also {world=it};w.route={error("Nothing may be sent: ${it.endpoint}")}
        val store=store()
        store.add(NotInterestedEntry("fxsong00002","Song",dislikedFor="s2_"+"f".repeat(64)))
        val notices=mutableListOf<String>()
        undoNotInterested(context,w.provider,store.entries.single(),notices::add)
        assertTrue(store.entries.isEmpty());assertEquals(0,w.calls.size)
        assertEquals(listOf("Riff can suggest this again. Its dislike stays on the account that made it"),notices)
    }
    @Test fun anEntryUndoneBeforeTheDislikeLandsIsNotBroughtBackAndTheMarkSurvivesARestart() {
        val store=store()
        store.markDisliked("fxsong00003","s2_"+"a".repeat(64));assertTrue(store.entries.isEmpty())
        store.add(NotInterestedEntry("fxsong00004","Song"));store.markDisliked("fxsong00004","s2_"+"a".repeat(64))
        assertEquals("s2_"+"a".repeat(64),decodeNotInterested(encodeNotInterested(store.entries),null).single().dislikedFor)
    }
    @Test fun eachOutcomeSaysWhatHappened() {
        assertEquals("Got it. Disliked on YouTube Music too, so it stays out of your mixes",notInterestedNotice(Result.success(NotInterestedSent.Disliked),true))
        assertEquals("Got it. YouTube Music will suggest this less",notInterestedNotice(Result.success(NotInterestedSent.Feedback),true))
        assertEquals("Hidden in Riff. YouTube Music could not be told this time",notInterestedNotice(Result.failure(MutationUnconfirmed()),true))
        assertEquals("Got it. Riff won't suggest this song",notInterestedNotice(Result.success(NotInterestedSent.None),true))
        assertEquals("Got it. Riff will hide this",notInterestedNotice(Result.success(NotInterestedSent.None),false))
    }
}
