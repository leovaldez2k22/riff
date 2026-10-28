package com.musicglass.personal

/**
 * Feature 26: a removal that can be taken back. The shell shows the message with an Undo button for a few seconds
 * (one at a time, the newest replacing the last); pressing it runs the undo. Set by RiffApp while it is shown.
 */
internal object UndoNotices {
    @Volatile internal var listener: ((message: String, undo: () -> Unit) -> Unit)? = null
    fun show(message: String, undo: () -> Unit) { listener?.invoke(message, undo) }
}

/** Removes a queue row and offers to put it back at the same row. A file on the phone is removed without Undo. */
internal fun PlaybackGateway.removeQueueItemWithUndo(index: Int) {
    val entry = state.queue.getOrNull(index)
    removeQueueItem(index)
    if (entry != null && entry.mediaId.startsWith("yt:")) UndoNotices.show(string(R.string.removed_from_queue)) { restoreQueueEntry(entry, index) }
}
