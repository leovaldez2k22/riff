package com.musicglass.personal

/**
 * Feature 26: a removal that can be taken back. The shell shows the message with an Undo button for a few seconds
 * (one at a time, the newest replacing the last); pressing it runs the undo. Set by RiffApp while it is shown.
 */
internal object UndoNotices {
    @Volatile internal var listener: ((message: String, undo: () -> Unit, expire: () -> Unit) -> Unit)? = null
    /**
     * [expire] runs once when the offer ends without Undo: timed out, dismissed, or replaced by a newer removal, so a
     * second removal commits the first. With no shell to show the notice it runs at once, and this returns false.
     */
    fun show(message: String, expire: () -> Unit = {}, undo: () -> Unit): Boolean {
        val shown = listener ?: run { expire(); return false }
        shown(message, undo, expire); return true
    }
}

/** Removes a queue row and offers to put it back at the same row. A file on the phone is removed without Undo. */
internal fun PlaybackGateway.removeQueueItemWithUndo(index: Int) {
    val entry = state.queue.getOrNull(index)
    removeQueueItem(index)
    if (entry != null && entry.mediaId.startsWith("yt:")) UndoNotices.show(string(R.string.removed_from_queue)) { restoreQueueEntry(entry, index) }
}
