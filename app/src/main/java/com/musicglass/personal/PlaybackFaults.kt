package com.musicglass.personal

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.EOFException
import java.io.File

/**
 * Sorts a playback failure into its class for the recovery policy, and marks a download whose files caused one.
 * Only the class, the status and the profile leave this class: never an address, a header or a file path.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class PlaybackFaults(private val scope: CoroutineScope, private val runtime: RuntimeDependencies, private val provider: YouTubeMusicProvider,
    private val offline: OfflineMusicStore, private val events: PlaybackEventLog, private val queue: QueueCoordinator) {
    private fun source(item: MediaItem) = when {
        !item.mediaId.startsWith("yt:") -> FaultSource.Device
        // A failure of a download is a failure of its files: asking the provider again would only open the same files.
        item.localConfiguration?.uri?.scheme == "file" || queue.isServedFromDownload(item.entryId()) -> FaultSource.Download
        item.mediaMetadata.extras?.getBoolean("live") == true -> FaultSource.Live
        else -> FaultSource.Provider
    }
    /** Sorts a failure for [item] into its class. */
    fun of(item: MediaItem, errorCodeName: String, error: Throwable): PlaybackFault {
        val causes = generateSequence(error as Throwable?) { it.cause }.take(16).toList()
        val http = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        val retryAfter = http?.headerFields?.entries?.firstOrNull { it.key.equals("Retry-After", true) }?.value?.firstOrNull()?.trim()?.toLongOrNull()?.times(1000)
        if (source(item) == FaultSource.Download) noteDownloadFault(item, errorCodeName, causes.any { it is EOFException })
        return classifyFault(errorCodeName, causes, source(item), runtime.networkConditions().validated,
            provider.sessionState == SessionState.Expired, http?.responseCode, retryAfter,
            queue.streamFor(item)?.profileId ?: item.mediaMetadata.extras?.getString("profileId"))
    }
    /**
     * A download failed to play. If its files are gone, do not match their marker, or could not be read
     * ([downloadUnreadable]), the download is marked as needing a new download, so the next attempt does not open the
     * same file again.
     */
    private fun noteDownloadFault(item: MediaItem, errorCodeName: String, endedEarly: Boolean) {
        val named = item.localConfiguration?.uri?.takeIf { it.scheme == "file" }?.path?.let { File(it).parentFile }
        val id = item.mediaId.removePrefix("yt:"); val owner = offline.currentScope
        scope.launch(runtime.dispatchers.io) {
            val dir = runCatching { offline.directory(owner, id) }.getOrNull() ?: return@launch
            // Only this account's own download folder; a file from anywhere else is not a download.
            if (named != null && !runCatching { named.canonicalFile == dir.canonicalFile }.getOrDefault(false)) return@launch
            val problem = BundleValidator.problem(dir)
            if (problem == null && !downloadUnreadable(errorCodeName, endedEarly)) return@launch
            events.append("download_invalid reason=${problem?.wire ?: "unreadable"}"); offline.markDamaged(owner, id)
        }
    }
}

/**
 * A download's own file failed in a way that only a damaged file explains: it ended before its index said
 * ([endedEarly]), its container could not be read, it is gone, or a read went past its end. The last is how a seek
 * into a file cut short fails (31 Oct): playing through to the cut ends early instead.
 */
internal fun downloadUnreadable(errorCodeName: String, endedEarly: Boolean): Boolean =
    endedEarly || listOf("PARSING_CONTAINER", "FILE_NOT_FOUND", "READ_POSITION_OUT_OF_RANGE").any(errorCodeName::contains)
