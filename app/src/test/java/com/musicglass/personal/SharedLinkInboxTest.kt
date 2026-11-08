package com.musicglass.personal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The owner's bug (8 Nov): a song link opened Riff but sometimes stayed on Home, because emptying the inbox cancelled it. */
class SharedLinkInboxTest {
    /** Lets every coroutine on this one thread run until it waits. */
    private suspend fun settle() = repeat(20) { yield() }

    @Test fun aLinkStillResolvingIsNotCancelledWhenTheInboxEmpties() = runBlocking {
        val inbox = MutableStateFlow<String?>("song")
        val resolved = CompletableDeferred<Unit>()
        val played = mutableListOf<String>()
        val drain = launch { drainInbox(inbox) { link -> resolved.await(); played += link } }
        settle()
        assertNull("taken from the inbox at once", inbox.value)
        resolved.complete(Unit) // the song resolves after the inbox was emptied
        settle()
        assertEquals(listOf("song"), played)
        drain.cancel()
    }

    @Test fun aNewerLinkReplacesOneStillResolvingAndTheSameLinkCanComeAgain() = runBlocking {
        val inbox = MutableStateFlow<String?>(null)
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        val played = mutableListOf<String>()
        val drain = launch { drainInbox(inbox) { link -> gates.getOrPut(link) { CompletableDeferred() }.await(); played += link } }
        inbox.value = "first"; settle()
        inbox.value = "second"; settle()
        gates.getValue("first").complete(Unit); gates.getValue("second").complete(Unit); settle()
        assertEquals(listOf("second"), played)
        inbox.value = "second"; settle()
        assertEquals(listOf("second", "second"), played)
        drain.cancel()
    }
}
