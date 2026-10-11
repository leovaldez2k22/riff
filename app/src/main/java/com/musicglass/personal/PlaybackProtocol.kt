package com.musicglass.personal

internal const val PLAY_PROVIDER_STREAM = "com.musicglass.personal.PLAY_PROVIDER_STREAM"
internal const val PLAY_LOCAL_AUDIO = "com.musicglass.personal.PLAY_LOCAL_AUDIO"

internal const val PLAY_CATALOG_QUEUE = "com.musicglass.personal.PLAY_CATALOG_QUEUE"
/** Loads the saved queue of the active owner, paused, in place of the current queue. */
internal const val RESTORE_SAVED_QUEUE = "com.musicglass.personal.RESTORE_SAVED_QUEUE"
/** Tries the selected entry again after a failure. Carries the entry it was asked for. */
internal const val RETRY_PLAYBACK = "com.musicglass.personal.RETRY_PLAYBACK"
