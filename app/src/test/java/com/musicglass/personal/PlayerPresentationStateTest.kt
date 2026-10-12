package com.musicglass.personal

import org.junit.Assert.*
import org.junit.Test

/** Q11: the drags that open and close the player, apart from the screen that draws them. */
class PlayerPresentationStateTest {
    private val motion = MotionPolicy(true, 0f)
    private val cues = mutableListOf<HapticCue>()
    private fun state() = PlayerPresentationState(haptic = { cues += it })
    private val area = 1000 // the drag range is 55 percent of this

    @Test fun aDragPastHalfWayFromTheMiniBarOpensThePlayerOnce() {
        val player = state(); var opened = 0
        assertFalse(player.draggingMini)
        player.dragMini(-200f, 0, area); assertTrue(player.draggingMini); assertFalse(player.draggingFull)
        player.dragMini(-200f, 400, area)
        player.releaseMini(sheetOpen = false, motion, area) { opened++ }
        assertEquals(1, opened); assertFalse(player.draggingMini); assertEquals(listOf(HapticCue.DragEnd), cues)
    }
    @Test fun aShortSlowDragFromTheMiniBarLeavesItClosedWithNoCue() {
        val player = state(); var opened = 0
        player.dragMini(-60f, 0, area); player.dragMini(-20f, 900, area)
        player.releaseMini(false, motion, area) { opened++ }
        assertEquals(0, opened); assertEquals(emptyList<HapticCue>(), cues)
    }
    @Test fun aQuickFlickUpOpensItHoweverShort() {
        val player = state(); var opened = 0
        player.dragMini(-10f, 0, area); player.dragMini(-60f, 40, area)
        player.releaseMini(false, motion, area) { opened++ }
        assertEquals(1, opened)
    }
    @Test fun releasingTheFullPlayerLowClosesItAndAFlingUpKeepsItOpen() {
        val player = state(); var closed = 0
        // The controller is not running in this test, so its position reads closed; a drag starts from there.
        player.dragFull(120f, 0, area); assertTrue(player.draggingFull)
        player.releaseFull(0f, sheetOpen = true, motion, area) { closed++ }
        assertEquals(1, closed); assertFalse(player.draggingFull); assertEquals(listOf(HapticCue.DragEnd), cues)
        player.dragFull(120f, 0, area)
        player.releaseFull(2000f, sheetOpen = true, motion, area) { closed++ }
        assertEquals("A fling upward wins over where the drag ended", 1, closed); assertEquals(1, cues.size)
    }
    @Test fun goingToTheBackgroundEndsAnyDrag() {
        val player = state()
        player.dragMini(-300f, 0, area); player.dragFull(10f, 0, area)
        player.stopped(open = false)
        assertFalse(player.draggingMini); assertFalse(player.draggingFull)
        // The next drag starts from the player's position again, not from where the abandoned one was.
        var opened = 0; player.dragMini(-10f, 0, area); player.releaseMini(false, motion, area) { opened++ }
        assertEquals(0, opened)
    }
    @Test fun aZeroHeightAreaCannotDivideByZero() {
        val player = state(); player.dragMini(-1f, 0, 0); player.releaseMini(false, motion, 0) {}
        assertFalse(player.draggingMini)
    }
}
