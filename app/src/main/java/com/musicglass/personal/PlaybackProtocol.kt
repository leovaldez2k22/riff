package com.musicglass.personal

internal const val PLAY_PROVIDER_STREAM = "com.musicglass.personal.PLAY_PROVIDER_STREAM"
internal const val PLAY_LOCAL_AUDIO = "com.musicglass.personal.PLAY_LOCAL_AUDIO"

internal const val PLAY_CATALOG_QUEUE = "com.musicglass.personal.PLAY_CATALOG_QUEUE"
/** Loads the saved queue of the active owner, paused, in place of the current queue. */
internal const val RESTORE_SAVED_QUEUE = "com.musicglass.personal.RESTORE_SAVED_QUEUE"
/** Q18-096: the open queue was scrolled to its last song; more recommendations are wanted now. */
internal const val LOAD_MORE_QUEUE = "com.musicglass.personal.LOAD_MORE_QUEUE"
internal const val RESHUFFLE_QUEUE = "com.musicglass.personal.RESHUFFLE_QUEUE"
/**
 * Feature 18 (Riff+): the songs after the one playing in a new order, only while the queue is still the one the order was
 * made from ("expected" and "order", entry ids). Without "order" it answers with the entries the user put to play next
 * ("pinned"), which keep their place.
 */
internal const val REORDER_QUEUE = "com.musicglass.personal.REORDER_QUEUE"
/** Tries the selected entry again after a failure. Carries the entry it was asked for. */
internal const val RETRY_PLAYBACK = "com.musicglass.personal.RETRY_PLAYBACK"
