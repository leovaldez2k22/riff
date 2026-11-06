package com.musicglass.desktop

import kotlin.test.*

class QueueInspectorProjectionTest {
    @Test fun currentOccurrenceIsNotRepeatedAndDuplicateRecordingRemainsUpNext(){
        val track=EmptyDesktopTrack.copy(id="same-recording")
        val before=QueueEntry(song=track);val current=QueueEntry(song=track);val after=QueueEntry(song=track)
        val projection=queueInspectorProjection(listOf(before,current,after),current.queueId)
        assertEquals(1,projection.upNextCount)
        assertEquals(listOf(after.queueId,before.queueId),projection.rows.map{it.entry.queueId})
        assertEquals(listOf(2,0),projection.rows.map{it.queueIndex})
        assertEquals(listOf(false,true),projection.rows.map{it.earlier})
    }
    @Test fun noSelectedOccurrenceKeepsEveryRealQueueEntry(){
        val entries=List(3){QueueEntry(song=EmptyDesktopTrack)}
        assertEquals(entries,queueInspectorProjection(entries,null).rows.map{it.entry})
        assertEquals(0,queueInspectorProjection(entries,entries.last().queueId).upNextCount)
        assertTrue(queueInspectorProjection(emptyList(),null).rows.isEmpty())
    }
}
