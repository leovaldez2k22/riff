package com.musicglass.personal

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

/** The owner of one play: on-device audio belongs to the device, everything else to the active owner. */
internal fun historyScopeKey(mediaId: String, accountKey: String): String? = when {
    mediaId.startsWith("local:") -> DEVICE_LOCAL_SCOPE_KEY
    accountKey == UNVERIFIED_SCOPE_KEY -> null
    else -> accountKey
}

internal data class StoredSnapshot(val ownerKey: String, val snapshot: PlaybackSnapshot, val updatedAt: Long)
