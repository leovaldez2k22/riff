package com.musicglass.desktop

internal data class QueueInspectorRow(val entry:QueueEntry,val queueIndex:Int,val earlier:Boolean)
internal data class QueueInspectorProjection(val rows:List<QueueInspectorRow>,val upNextCount:Int)
/** Identity is the queue occurrence, never a track ID: duplicate recordings may bracket the current item. */
internal fun queueInspectorProjection(entries:List<QueueEntry>,currentEntryId:String?):QueueInspectorProjection {
    val current=entries.indexOfFirst{it.queueId==currentEntryId}
    val upNext=entries.withIndex().filter{it.index>current}.map{QueueInspectorRow(it.value,it.index,false)}
    val earlier=entries.withIndex().filter{it.index<current}.map{QueueInspectorRow(it.value,it.index,true)}
    return QueueInspectorProjection(upNext+earlier,upNext.size)
}
