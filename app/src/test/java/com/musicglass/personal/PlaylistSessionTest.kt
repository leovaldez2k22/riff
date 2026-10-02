package com.musicglass.personal
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
class PlaylistSessionTest {
    @Test fun sourceCancellationDoesNotCancelPicker()=runBlocking {
        val result=sessionSafeMutation<Unit> {currentCoroutineContext().cancel(CancellationException("InnerTube session changed"));yield()}
        assertTrue(result.isFailure);assertTrue(isActive)
        assertEquals(1,sessionSafeMutation {1}.getOrThrow())
    }
    @Test fun leavingPickerStillCancelsRequest()=runBlocking {
        val entered=CompletableDeferred<Unit>();val stopped=CompletableDeferred<Unit>()
        val picker=launch {sessionSafeMutation<Unit> {entered.complete(Unit);try {awaitCancellation()} finally {stopped.complete(Unit)}}}
        entered.await();picker.cancelAndJoin();stopped.await();assertTrue(picker.isCancelled)
    }
}
