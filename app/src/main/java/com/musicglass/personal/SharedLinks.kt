package com.musicglass.personal

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Feature 13: YouTube Music and YouTube links opened in Riff, by tapping one (once the owner allows it in Android's
 * settings) or with Share → Riff. Only these forms are read, and only their ids, each checked against what YouTube
 * sends; anything else is ignored. Links come from outside the app, so nothing else from them is used.
 */
internal sealed interface SharedTarget {
    /** A song (or a video, played as audio like any song in Riff), from [startMs] in. */
    data class Song(val videoId: String, val startMs: Long = 0) : SharedTarget
    /** A playlist ("VL…"), album ("MPRE…") or artist ("UC…"), opened as a collection. */
    data class Collection(val browseId: String) : SharedTarget
}

private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
private val LIST_ID = Regex("[A-Za-z0-9_-]{2,200}")
private val URL_IN_TEXT = Regex("https?://[^\\s\"'<>]+")
private val YOUTUBE_HOSTS = setOf("music.youtube.com", "www.youtube.com", "m.youtube.com", "youtube.com", "youtu.be")

/** The first YouTube Music or YouTube link in [text] (a share often puts a title before it), or null. */
internal fun parseYouTubeLink(text: String?): SharedTarget? {
    val raw = text?.let { URL_IN_TEXT.find(it)?.value } ?: return null
    val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
    val host = uri.host?.lowercase() ?: return null
    if (host !in YOUTUBE_HOSTS) return null
    val path = uri.pathSegments.orEmpty()
    val start = startOf(uri.getQueryParameter("t"))
    if (host == "youtu.be") return path.firstOrNull()?.takeIf(VIDEO_ID::matches)?.let { SharedTarget.Song(it, start) }
    return when {
        path.firstOrNull() == "watch" -> uri.getQueryParameter("v")?.takeIf(VIDEO_ID::matches)?.let { SharedTarget.Song(it, start) }
        path.firstOrNull() == "shorts" -> path.getOrNull(1)?.takeIf(VIDEO_ID::matches)?.let { SharedTarget.Song(it, start) }
        path.firstOrNull() == "playlist" -> uri.getQueryParameter("list")?.takeIf(LIST_ID::matches)?.let { SharedTarget.Collection("VL$it") }
        path.firstOrNull() == "browse" -> path.getOrNull(1)?.takeIf { LIST_ID.matches(it) && (it.startsWith("MPRE") || it.startsWith("VL") || it.startsWith("UC")) }
            ?.let { SharedTarget.Collection(it) }
        path.firstOrNull() == "channel" -> path.getOrNull(1)?.takeIf { LIST_ID.matches(it) && it.startsWith("UC") }?.let { SharedTarget.Collection(it) }
        else -> null
    }
}

/** "90", "90s", "1m30s", "1h2m3s" → milliseconds; anything else → 0. */
internal fun startOf(value: String?): Long {
    if (value.isNullOrBlank()) return 0
    value.toLongOrNull()?.let { return (it * 1000).coerceIn(0, 24 * 3_600_000L) }
    val match = Regex("^(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?$").matchEntire(value) ?: return 0
    val (h, m, s) = match.destructured
    return ((h.toLongOrNull() ?: 0) * 3600 + (m.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0)).times(1000).coerceIn(0, 24 * 3_600_000L)
}

/** What a VIEW or SEND intent asks Riff to open, or null when it isn't a YouTube link. */
internal fun sharedTargetOf(intent: Intent?): SharedTarget? = when (intent?.action) {
    Intent.ACTION_VIEW -> parseYouTubeLink(intent.dataString)
    Intent.ACTION_SEND -> parseYouTubeLink(intent.getStringExtra(Intent.EXTRA_TEXT))
    else -> null
}

/** Links waiting for the shell; MainActivity puts them here and RiffApp takes them. */
internal object SharedLinkInbox {
    val pending = MutableStateFlow<SharedTarget?>(null)
    /** Feature 27: an app shortcut or the Quick Settings tile asked for this. */
    val shortcut = MutableStateFlow<AppShortcut?>(null)
}

/** What MainActivity reads from any intent it is started or brought forward with. */
internal fun deliverIntent(intent: Intent?) {
    sharedTargetOf(intent)?.let { SharedLinkInbox.pending.value = it }
    appShortcutOf(intent)?.let { SharedLinkInbox.shortcut.value = it }
}
