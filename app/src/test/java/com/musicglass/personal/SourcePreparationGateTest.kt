package com.musicglass.personal

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SourcePreparationGateTest {
    @Test fun followingPreparationWaitsAndASkipImmediatelyPromotesIt() = runBlocking {
        val gate = SourcePreparationGate()
        gate.update("one", false)
        val next = async(start = CoroutineStart.UNDISPATCHED) { gate.await("two") }
        assertFalse(next.isCompleted)
        withTimeout(1000) { gate.await("one") }
        gate.update("two", false)
        withTimeout(1000) { next.await() }
    }

    @Test fun playbackAllowsPreloadingAndReleaseCancelsWaiters() = runBlocking {
        val gate = SourcePreparationGate()
        gate.update("one", false)
        val next = async(start = CoroutineStart.UNDISPATCHED) { gate.await("two") }
        gate.update("one", true)
        withTimeout(1000) { next.await() }
        gate.update("one", false)
        val abandoned = launch(start = CoroutineStart.UNDISPATCHED) { gate.await("three") }
        abandoned.cancelAndJoin()
        assertTrue(abandoned.isCancelled)
    }
}
