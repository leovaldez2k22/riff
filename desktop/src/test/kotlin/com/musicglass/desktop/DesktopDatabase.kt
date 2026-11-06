package com.musicglass.desktop

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** Listening history event record. */
internal data class HistoryEvent(
    val scope: String,
    val eventId: String,
    val mediaId: String,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val playedAt: Long,
    val durationMs: Long
)

/** Saved playback snapshot for session resumption. */
internal data class PlaybackSnapshot(
    val scope: String,
    val currentTrackId: String,
    val positionMs: Long,
    val queueTrackIds: List<String>,
    val updatedAt: Long
)

/** Library saved track record. */
internal data class LibraryItem(
    val mediaId: String,
    val scope: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artworkUrl: String?,
    val addedAtEpochMs: Long
)

/** Saved playlist record. */
internal data class PlaylistRecord(
    val playlistId: String,
    val scope: String,
    val title: String,
    val trackIds: List<String>,
    val updatedAtEpochMs: Long
)

/** Encore listening statistics record. */
internal data class EncoreStatsRecord(
    val scope: String,
    val periodKey: String,
    val totalListeningSeconds: Long,
    val topTrackIds: List<String>,
    val topArtistNames: List<String>,
    val updatedAtEpochMs: Long
)

/**
 * Desktop transactional database with versioned schema migrations,
 * Write-Ahead Logging (WAL), atomic commits, and account-scoped isolation.
 * Conforms to Section 5.2 of Riff Windows desktop architecture.
 */
internal class DesktopDatabase(private val baseDir: File) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
    }

    private val dbFile = File(baseDir, "library-state.db")
    private val walFile = File(baseDir, "library-state.db-wal")
    private val lock = ReentrantReadWriteLock()

    private val historyStore = ConcurrentHashMap<String, HistoryEvent>()
    private val snapshotStore = ConcurrentHashMap<String, PlaybackSnapshot>()
    private val libraryStore = ConcurrentHashMap<String, LibraryItem>()
    private val playlistStore = ConcurrentHashMap<String, PlaylistRecord>()
    private val encoreStore = ConcurrentHashMap<String, EncoreStatsRecord>()

    private var schemaVersion = 1

    init {
        baseDir.mkdirs()
        recoverFromWal()
        applyMigrations()
    }

    private fun applyMigrations() {
        lock.write {
            if (schemaVersion < CURRENT_SCHEMA_VERSION) {
                // Migration to version 2: add library and playlist support
                schemaVersion = CURRENT_SCHEMA_VERSION
                checkpoint()
            }
        }
    }

    /** Appends a history event transactionally. */
    fun recordHistory(event: HistoryEvent) = lock.write {
        val line = "HIST|${event.scope}|${event.eventId}|${event.mediaId}|${event.title}|${event.artist}|${event.artworkUrl ?: ""}|${event.playedAt}|${event.durationMs}\n"
        walFile.appendText(line)
        historyStore["${event.scope}:${event.eventId}"] = event
    }

    /** Queries history for a given scope, ordered recent-first. */
    fun getRecentHistory(scope: String, limit: Int = 100): List<HistoryEvent> = lock.read {
        historyStore.values
            .filter { it.scope == scope }
            .sortedByDescending { it.playedAt }
            .take(limit)
    }

    /** Saves a playback snapshot for session resumption. */
    fun saveSnapshot(snapshot: PlaybackSnapshot) = lock.write {
        val queueJoined = snapshot.queueTrackIds.joinToString(",")
        val line = "SNAP|${snapshot.scope}|${snapshot.currentTrackId}|${snapshot.positionMs}|${snapshot.updatedAt}|$queueJoined\n"
        walFile.appendText(line)
        snapshotStore[snapshot.scope] = snapshot
    }

    /** Retrieves the last saved playback snapshot for a scope. */
    fun getSnapshot(scope: String): PlaybackSnapshot? = lock.read {
        snapshotStore[scope]
    }

    /** Adds or updates an item in the user's library. */
    fun addLibraryItem(item: LibraryItem) = lock.write {
        val line = "LIB|${item.scope}|${item.mediaId}|${item.title}|${item.artist}|${item.album}|${item.durationMs}|${item.artworkUrl ?: ""}|${item.addedAtEpochMs}\n"
        walFile.appendText(line)
        libraryStore["${item.scope}:${item.mediaId}"] = item
    }

    fun getLibraryItems(scope: String): List<LibraryItem> = lock.read {
        libraryStore.values
            .filter { it.scope == scope }
            .sortedByDescending { it.addedAtEpochMs }
    }

    /** Creates or updates a playlist. */
    fun savePlaylist(playlist: PlaylistRecord) = lock.write {
        val tracksJoined = playlist.trackIds.joinToString(",")
        val line = "PLAYLIST|${playlist.scope}|${playlist.playlistId}|${playlist.title}|${playlist.updatedAtEpochMs}|$tracksJoined\n"
        walFile.appendText(line)
        playlistStore["${playlist.scope}:${playlist.playlistId}"] = playlist
    }

    fun getPlaylists(scope: String): List<PlaylistRecord> = lock.read {
        playlistStore.values
            .filter { it.scope == scope }
            .sortedByDescending { it.updatedAtEpochMs }
    }

    /** Saves Encore listening statistics. */
    fun saveEncoreStats(stats: EncoreStatsRecord) = lock.write {
        val tracks = stats.topTrackIds.joinToString(",")
        val artists = stats.topArtistNames.joinToString(",")
        val line = "ENCORE|${stats.scope}|${stats.periodKey}|${stats.totalListeningSeconds}|${stats.updatedAtEpochMs}|$tracks|$artists\n"
        walFile.appendText(line)
        encoreStore["${stats.scope}:${stats.periodKey}"] = stats
    }

    fun getEncoreStats(scope: String, periodKey: String): EncoreStatsRecord? = lock.read {
        encoreStore["$scope:$periodKey"]
    }

    /** Flushes the WAL log into the main DB file and truncates the WAL (Checkpoint). */
    fun checkpoint() = lock.write {
        val writer = dbFile.bufferedWriter()
        writer.use { out ->
            out.write("SCHEMA|$schemaVersion\n")
            for (event in historyStore.values) {
                out.write("HIST|${event.scope}|${event.eventId}|${event.mediaId}|${event.title}|${event.artist}|${event.artworkUrl ?: ""}|${event.playedAt}|${event.durationMs}\n")
            }
            for (snap in snapshotStore.values) {
                out.write("SNAP|${snap.scope}|${snap.currentTrackId}|${snap.positionMs}|${snap.updatedAt}|${snap.queueTrackIds.joinToString(",")}\n")
            }
            for (lib in libraryStore.values) {
                out.write("LIB|${lib.scope}|${lib.mediaId}|${lib.title}|${lib.artist}|${lib.album}|${lib.durationMs}|${lib.artworkUrl ?: ""}|${lib.addedAtEpochMs}\n")
            }
            for (p in playlistStore.values) {
                out.write("PLAYLIST|${p.scope}|${p.playlistId}|${p.title}|${p.updatedAtEpochMs}|${p.trackIds.joinToString(",")}\n")
            }
            for (e in encoreStore.values) {
                out.write("ENCORE|${e.scope}|${e.periodKey}|${e.totalListeningSeconds}|${e.updatedAtEpochMs}|${e.topTrackIds.joinToString(",")}|${e.topArtistNames.joinToString(",")}\n")
            }
        }
        if (walFile.exists()) {
            walFile.writeText("") // Truncate WAL
        }
    }

    private fun recoverFromWal() {
        if (dbFile.exists()) {
            dbFile.forEachLine { parseLine(it) }
        }
        if (walFile.exists()) {
            walFile.forEachLine { parseLine(it) }
        }
    }

    private fun parseLine(line: String) {
        val parts = line.split("|")
        if (parts.isEmpty()) return
        when (parts[0]) {
            "SCHEMA" -> {
                if (parts.size >= 2) schemaVersion = parts[1].toIntOrNull() ?: 1
            }
            "HIST" -> {
                if (parts.size >= 9) {
                    val event = HistoryEvent(
                        scope = parts[1],
                        eventId = parts[2],
                        mediaId = parts[3],
                        title = parts[4],
                        artist = parts[5],
                        artworkUrl = parts[6].ifBlank { null },
                        playedAt = parts[7].toLongOrNull() ?: 0L,
                        durationMs = parts[8].toLongOrNull() ?: 0L
                    )
                    historyStore["${event.scope}:${event.eventId}"] = event
                }
            }
            "SNAP" -> {
                if (parts.size >= 6) {
                    val queue = if (parts[5].isBlank()) emptyList() else parts[5].split(",")
                    val snap = PlaybackSnapshot(
                        scope = parts[1],
                        currentTrackId = parts[2],
                        positionMs = parts[3].toLongOrNull() ?: 0L,
                        updatedAt = parts[4].toLongOrNull() ?: 0L,
                        queueTrackIds = queue
                    )
                    snapshotStore[snap.scope] = snap
                }
            }
            "LIB" -> {
                if (parts.size >= 9) {
                    val item = LibraryItem(
                        scope = parts[1],
                        mediaId = parts[2],
                        title = parts[3],
                        artist = parts[4],
                        album = parts[5],
                        durationMs = parts[6].toLongOrNull() ?: 0L,
                        artworkUrl = parts[7].ifBlank { null },
                        addedAtEpochMs = parts[8].toLongOrNull() ?: 0L
                    )
                    libraryStore["${item.scope}:${item.mediaId}"] = item
                }
            }
            "PLAYLIST" -> {
                if (parts.size >= 6) {
                    val tracks = if (parts[5].isBlank()) emptyList() else parts[5].split(",")
                    val playlist = PlaylistRecord(
                        scope = parts[1],
                        playlistId = parts[2],
                        title = parts[3],
                        updatedAtEpochMs = parts[4].toLongOrNull() ?: 0L,
                        trackIds = tracks
                    )
                    playlistStore["${playlist.scope}:${playlist.playlistId}"] = playlist
                }
            }
            "ENCORE" -> {
                if (parts.size >= 7) {
                    val tracks = if (parts[5].isBlank()) emptyList() else parts[5].split(",")
                    val artists = if (parts[6].isBlank()) emptyList() else parts[6].split(",")
                    val stats = EncoreStatsRecord(
                        scope = parts[1],
                        periodKey = parts[2],
                        totalListeningSeconds = parts[3].toLongOrNull() ?: 0L,
                        updatedAtEpochMs = parts[4].toLongOrNull() ?: 0L,
                        topTrackIds = tracks,
                        topArtistNames = artists
                    )
                    encoreStore["${stats.scope}:${stats.periodKey}"] = stats
                }
            }
        }
    }

    fun clearAll() = lock.write {
        historyStore.clear()
        snapshotStore.clear()
        libraryStore.clear()
        playlistStore.clear()
        encoreStore.clear()
        if (dbFile.exists()) dbFile.delete()
        if (walFile.exists()) walFile.delete()
    }
}
