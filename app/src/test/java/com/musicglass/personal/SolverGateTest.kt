package com.musicglass.personal

import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Q18-098: a song's lookup in the solver goes ahead of preloads that are not yet inside the library. */
class SolverGateTest {
    private val order = Collections.synchronizedList(mutableListOf<String>())

    @Test(timeout = 10_000) fun aPreloadWaitingForItsTurnStepsAsideForALookup() {
        val gate = SolverGate()
        val firstInside = CountDownLatch(1); val releaseFirst = CountDownLatch(1)
        val lookupArrived = CountDownLatch(1); val held = mutableListOf<Long>()
        val first = thread { gate.background { order += "first in"; firstInside.countDown(); releaseFirst.await(); order += "first out" } }
        firstInside.await()
        // The second preload queues behind the first; then the song is tapped.
        val second = thread { gate.background({ synchronized(held) { held += it } }) { order += "second" } }
        Thread.sleep(100)
        val lookup = thread { gate.lookup { lookupArrived.countDown(); order += "lookup start"; Thread.sleep(200); order += "lookup end" } }
        lookupArrived.await()
        releaseFirst.countDown()
        listOf(first, second, lookup).forEach { it.join() }
        assertEquals(listOf("first in", "lookup start", "first out", "lookup end", "second"), order)
        assertTrue("the wait for the lookup is reported", synchronized(held) { held.single() } > 0)
    }

    @Test(timeout = 10_000) fun preloadsGoInOneAtATime() {
        val gate = SolverGate(); val inside = java.util.concurrent.atomic.AtomicInteger(); var most = 0
        (1..4).map { thread { gate.background { val n = inside.incrementAndGet(); synchronized(this) { most = maxOf(most, n) }; Thread.sleep(50); inside.decrementAndGet() } } }.forEach { it.join() }
        assertEquals(1, most)
    }

    @Test(timeout = 10_000) fun aLookupIsNeverHeldByThePreloads() {
        val gate = SolverGate(); val release = CountDownLatch(1); val inside = CountDownLatch(1)
        val preload = thread { gate.background { inside.countDown(); release.await() } }
        inside.await()
        val done = CountDownLatch(1)
        thread { gate.lookup { done.countDown() } }
        assertTrue("the lookup ran while a preload was inside", done.await(2, TimeUnit.SECONDS))
        release.countDown(); preload.join()
    }

    @Test(timeout = 10_000) fun aPreloadHeldPastItsLimitGoesInAnyway() {
        val gate = SolverGate(maxHoldMs = 200); val release = CountDownLatch(1); val lookupInside = CountDownLatch(1)
        val lookup = thread { gate.lookup { lookupInside.countDown(); release.await() } }
        lookupInside.await()
        val ran = CountDownLatch(1)
        thread { gate.background { ran.countDown() } }
        assertTrue("a later song still gets its player", ran.await(3, TimeUnit.SECONDS))
        release.countDown(); lookup.join()
    }
}
