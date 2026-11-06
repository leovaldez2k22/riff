package com.musicglass.personal

import org.junit.Assert.assertEquals
import org.junit.Test

/** Dragging a song up to the top of a scrolled queue. */
class QueueDragTargetTest {
    @Test fun inTheMiddleTheSongGoesToTheNearestRow() = assertEquals(12, queueDragTarget(12, 15, 9, 40))
    @Test fun whileTheListCanScrollUpItStopsAtTheSecondRow() {
        assertEquals(10, queueDragTarget(9, 15, 9, 40))
        assertEquals(10, queueDragTarget(9, 15, 9, 0))
    }
    @Test fun atTheVeryTopItTakesTheFirstRow() = assertEquals(0, queueDragTarget(0, 3, 0, 0))
    @Test fun aPartlyScrolledFirstRowWaitsForTheTop() = assertEquals(1, queueDragTarget(0, 3, 0, 60))
    @Test fun aSongAlreadyAtTheTopMovesFreely() = assertEquals(10, queueDragTarget(10, 9, 9, 40))
    @Test fun draggingDownIsUnchanged() = assertEquals(20, queueDragTarget(20, 15, 9, 40))
    @Test fun theDraggedRowStaysWholeInsideTheList() {
        assertEquals(0f, queueDraggedTop(-72f, 0, 1500, 149), 0f)
        assertEquals(1351f, queueDraggedTop(1400f, 0, 1500, 149), 0f)
        assertEquals(600f, queueDraggedTop(600f, 0, 1500, 149), 0f)
    }
}
