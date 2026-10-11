package com.musicglass.personal

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking

/**
 * Phone checks borrow the listener's own saved queue and put it back. Since Q04 that queue is stored
 * per owner in the state database, not in the shared `last_playback` preference. Call from the
 * instrumentation thread.
 */
internal fun phoneSavedQueue(context: Context): PlaybackSnapshot? {
    val process = AppContainer.get(context)
    runBlocking { process.stateReady.await() }
    return process.snapshots.newest(listOf(activeOwnerKey(context), DEVICE_LOCAL_SCOPE_KEY))?.snapshot
}

/** Stores [snapshot] for the owner it belongs to. False when it could not be stored; the caller keeps its backup. */
internal fun storePhoneSavedQueue(context: Context, snapshot: PlaybackSnapshot): Boolean {
    val process = AppContainer.get(context)
    runBlocking { process.stateReady.await() }
    val owner = queueOwnerKey(snapshot.queue.map { it.mediaId }, activeOwnerKey(context)) ?: return false
    return process.snapshots.save(process.snapshots.ticket(owner), snapshot) == StoreOutcome.Done
}

private fun activeOwnerKey(context: Context): String {
    var key = UNVERIFIED_SCOPE_KEY
    InstrumentationRegistry.getInstrumentation().runOnMainSync { key = YouTubeMusicProvider.shared(context).storageScopeKey() }
    return key
}
