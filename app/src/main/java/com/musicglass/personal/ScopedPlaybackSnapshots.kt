package com.musicglass.personal

import android.content.Context

internal const val DEVICE_LOCAL_SCOPE_KEY = "device-local"

/** A queue with any provider item stops with its account; a pure on-device queue keeps playing. */
internal fun queueStopsWithAccount(mediaIds: List<String>): Boolean = mediaIds.any { !it.startsWith("local:") }

/**
 * Owner of a queue's saved state: pure on-device queues belong to the device, provider and mixed
 * queues to the account that created them. Null means nothing may be saved (empty or unverified).
 */
internal fun queueOwnerKey(mediaIds: List<String>, accountKey: String): String? = when {
    mediaIds.isEmpty() -> null
    !queueStopsWithAccount(mediaIds) -> DEVICE_LOCAL_SCOPE_KEY
    accountKey == UNVERIFIED_SCOPE_KEY -> null
    else -> accountKey
}

internal interface SnapshotValues {
    fun read(key: String): String?
    /** A null value removes the key. [durable] waits for the write and reports whether it succeeded. */
    fun write(values: Map<String, String?>, durable: Boolean): Boolean
}

internal class PreferenceSnapshotValues(context: Context) : SnapshotValues {
    private val prefs = context.applicationContext.getSharedPreferences("playback_snapshots", Context.MODE_PRIVATE)
    override fun read(key: String): String? = prefs.getString(key, null)
    // A durable write is the account-switch handoff: the queue must be on disk before it is cleared.
    @android.annotation.SuppressLint("ApplySharedPref")
    override fun write(values: Map<String, String?>, durable: Boolean): Boolean {
        val edit = prefs.edit()
        values.forEach { (key, value) -> if (value == null) edit.remove(key) else edit.putString(key, value) }
        return if (durable) edit.commit() else { edit.apply(); true }
    }
}

internal data class StoredSnapshot(val ownerKey: String, val snapshot: PlaybackSnapshot, val updatedAt: Long)

/**
 * One paused queue per owner, so switching accounts never shows or overwrites another owner's
 * queue. Q04 replaces this storage with the playback_snapshots table; the contract stays.
 */
internal class ScopedPlaybackSnapshots(private val values: SnapshotValues, private val wallTime: () -> Long) {
    @Synchronized fun save(ownerKey: String, snapshot: PlaybackSnapshot, durable: Boolean = false): Boolean =
        values.write(mapOf("snapshot_$ownerKey" to encodePlaybackSnapshot(snapshot),
            "updated_$ownerKey" to wallTime().toString()), durable)

    /** Clears this owner only. An empty Guest queue never erases an account's queue. */
    @Synchronized fun clear(ownerKey: String) {
        values.write(mapOf("snapshot_$ownerKey" to null, "updated_$ownerKey" to null), durable = false)
    }

    @Synchronized fun load(ownerKey: String): StoredSnapshot? {
        val snapshot = values.read("snapshot_$ownerKey")?.let(::decodePlaybackSnapshot) ?: return null
        return StoredSnapshot(ownerKey, snapshot, values.read("updated_$ownerKey")?.toLongOrNull() ?: 0L)
    }

    /** The most recently saved valid queue among these owners. */
    fun newest(ownerKeys: List<String>): StoredSnapshot? = ownerKeys.distinct().mapNotNull(::load).maxByOrNull { it.updatedAt }
}
