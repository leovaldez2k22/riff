package com.musicglass.personal

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The last Home feed shown, with when it was fetched. */
internal data class FeedSnapshot(val sections: List<ProviderHomeSection>, val savedAt: Long)

internal const val FEED_SNAPSHOT_VERSION = 1
internal const val FEED_SNAPSHOT_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

// Snapshots hold titles, IDs and public artwork links only. Anything that looks like a signed stream
// URL or a credential is dropped rather than written to disk.
private val UNSAFE_TEXT = Regex("googlevideo\\.com|[?&](sig|signature|pot|n)=|SAPISID|__Secure-", RegexOption.IGNORE_CASE)
private fun safe(value: String?): String? = value?.takeUnless { UNSAFE_TEXT.containsMatchIn(it) }

internal fun encodeFeedSnapshot(sections: List<ProviderHomeSection>, savedAt: Long): String = buildJsonObject {
    put("version", FEED_SNAPSHOT_VERSION)
    put("savedAt", savedAt)
    put("sections", buildJsonArray {
        for (section in sections) add(buildJsonObject {
            put("title", section.title)
            put("cards", buildJsonArray {
                for (c in section.cards) add(buildJsonObject {
                    put("title", c.title); put("subtitle", c.subtitle)
                    safe(c.artworkUrl)?.takeIf { it.startsWith("https://") }?.let { put("art", it) }
                    c.videoId?.let { put("videoId", it) }; c.browseId?.let { put("browseId", it) }
                    put("kind", c.kind.name)
                    c.artistId?.let { put("artistId", it) }; c.albumId?.let { put("albumId", it) }
                    if (c.album.isNotEmpty()) put("album", c.album)
                    if (c.musicVideo) put("musicVideo", true); if (c.officialMusicVideo) put("officialMusicVideo", true)
                    c.setVideoId?.let { put("setVideoId", it) }; c.addedAt?.let { put("addedAt", it) }
                })
            })
        })
    })
}.toString()

/** Null for anything unreadable, from another version, or with no usable cards. */
internal fun decodeFeedSnapshot(text: String): FeedSnapshot? = runCatching {
    val root = Json.parseToJsonElement(text).jsonObject
    if (root["version"]?.jsonPrimitive?.int != FEED_SNAPSHOT_VERSION) return null
    fun JsonObject.str(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    val sections = root["sections"]!!.jsonArray.map { s ->
        val section = s.jsonObject
        ProviderHomeSection(section.str("title").orEmpty(), (section["cards"] as? JsonArray).orEmpty().mapNotNull { e ->
            val c = e.jsonObject
            val kind = CatalogKind.entries.firstOrNull { it.name == c.str("kind") } ?: return@mapNotNull null
            ProviderHomeCard(title = c.str("title").orEmpty(), subtitle = c.str("subtitle").orEmpty(),
                artworkUrl = safe(c.str("art")), videoId = c.str("videoId"), browseId = c.str("browseId"), kind = kind,
                artistId = c.str("artistId"), albumId = c.str("albumId"), album = c.str("album").orEmpty(),
                musicVideo = (c["musicVideo"] as? JsonPrimitive)?.booleanOrNull == true,
                officialMusicVideo = (c["officialMusicVideo"] as? JsonPrimitive)?.booleanOrNull == true,
                setVideoId = c.str("setVideoId"), addedAt = (c["addedAt"] as? JsonPrimitive)?.longOrNull)
        })
    }.filter { it.cards.isNotEmpty() }
    FeedSnapshot(sections, root["savedAt"]!!.jsonPrimitive.long).takeIf { sections.isNotEmpty() }
}.getOrNull()

/**
 * The last successful Home feed per account, so a cold start shows real shelves at once while the
 * fresh feed loads (W3). One small file per account and feed key under noBackupFilesDir; not backed
 * up, deleted on sign-out. All methods do disk I/O: call off the main thread.
 */
internal class FeedSnapshotStore(private val root: File, private val clock: () -> Long = System::currentTimeMillis) {
    companion object {
        const val MAX_FILE_CHARS = 512 * 1024
        const val MAX_TOTAL_BYTES = 2L * 1024 * 1024
    }

    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun scopeDir(scope: String) = File(root, hash(scope).take(32))
    private fun file(scope: String, key: String) = File(scopeDir(scope), hash(key).take(16) + ".json")

    @Synchronized fun save(scope: String, key: String, sections: List<ProviderHomeSection>) {
        if (sections.none { it.cards.isNotEmpty() }) return
        val text = encodeFeedSnapshot(sections, clock())
        if (text.length > MAX_FILE_CHARS) return
        val target = file(scope, key)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, target.name + ".tmp")
        temporary.writeText(text)
        if (!temporary.renameTo(target)) { target.delete(); if (!temporary.renameTo(target)) temporary.delete() }
        trim()
    }

    /** Null when missing, unreadable (then deleted) or older than a week. */
    @Synchronized fun load(scope: String, key: String): FeedSnapshot? {
        val target = file(scope, key)
        if (!target.isFile || target.length() > MAX_FILE_CHARS * 4L) return null
        val snapshot = runCatching { decodeFeedSnapshot(target.readText()) }.getOrNull()
        if (snapshot == null) { target.delete(); return null }
        return snapshot.takeIf { clock() - it.savedAt in 0..FEED_SNAPSHOT_MAX_AGE_MS }
    }

    @Synchronized fun deleteScope(scope: String) { scopeDir(scope).deleteRecursively() }

    /** Keeps the whole folder under [MAX_TOTAL_BYTES], oldest files first. */
    private fun trim() {
        val files = root.walkTopDown().filter { it.isFile }.sortedBy { it.lastModified() }.toMutableList()
        var total = files.sumOf { it.length() }
        while (total > MAX_TOTAL_BYTES && files.isNotEmpty()) { val oldest = files.removeAt(0); total -= oldest.length(); oldest.delete() }
    }
}
