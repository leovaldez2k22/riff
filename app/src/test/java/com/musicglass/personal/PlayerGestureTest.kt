package com.musicglass.personal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

class PlayerGestureTest {
    @Test fun aFastReversalOverridesDistance() {
        assertFalse(playerReleaseOpens(.90f, -900f))
        assertTrue(playerReleaseOpens(.10f, 900f))
    }

    @Test fun aSlowReleaseSettlesToTheNearestEnd() {
        assertFalse(playerReleaseOpens(.49f, 0f))
        assertTrue(playerReleaseOpens(.51f, 0f))
    }

    @Test fun releasingBeforeTheActorReadsTheLastDragStillUsesTheFinalPosition() = runBlocking {
        val controller = PlayerMorphController()
        controller.dragTo(.2f)
        controller.dragTo(.65f)
        controller.settle(true, MotionPolicy(true, 0f), direct = true,
            fromDrag = .65f, releaseVelocity = 2f)
        val actor = launch { controller.run() }
        try {
            withTimeout(1_000) { while (controller.position.value != 1f) yield() }
            assertEquals(1f, controller.position.value)
        } finally {
            actor.cancelAndJoin()
        }
    }

    @Test fun outOfRangeDragCannotEscapeThePlayerEndpoints() = runBlocking {
        val controller = PlayerMorphController()
        val actor = launch { controller.run() }
        try {
            controller.dragTo(3f)
            withTimeout(1_000) { while (controller.position.value != 1f) yield() }
            controller.dragTo(-2f)
            withTimeout(1_000) { while (controller.position.value != 0f) yield() }
            assertEquals(0f, controller.position.value)
        } finally {
            actor.cancelAndJoin()
        }
    }

    @Test fun aNewDragInterruptsThePreviousTarget() = runBlocking {
        val controller = PlayerMorphController()
        val actor = launch { controller.run() }
        try {
            controller.settle(true, MotionPolicy(true, 0f))
            withTimeout(1_000) { while (controller.position.value != 1f) yield() }
            controller.dragTo(.3f)
            withTimeout(1_000) { while (controller.position.value != .3f) yield() }
            controller.settle(false, MotionPolicy(true, 0f), direct = true,
                fromDrag = .3f)
            withTimeout(1_000) { while (controller.position.value != 0f) yield() }
            assertEquals(0f, controller.position.value)
        } finally {
            actor.cancelAndJoin()
        }
    }
}
