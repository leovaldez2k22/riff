package com.musicglass.personal

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns a queue entry into something the player can read: a download, an on-device file, a resolved stream, or a
 * catalog entry whose stream is looked up when the player reaches it. Used by the main player and by the crossfade's
 * second player. Owned by the playback service; [createMediaSource] is called on the playback thread.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class PlaybackSources(private val context: Context, private val scope: CoroutineScope, private val provider: YouTubeMusicProvider,
    private val offline: OfflineMusicStore, private val events: PlaybackEventLog, private val audio: AudioTransitionCoordinator,
    private val queue: QueueCoordinator, private val sabrHttp: HttpClient, private val resolvingChanged: () -> Unit,
    private val defaults: DefaultMediaSourceFactory = DefaultMediaSourceFactory(context)) : MediaSource.Factory by defaults {
    /** Entries whose stream is being looked up now. Written from the playback thread. */
    private val resolving = ConcurrentHashMap.newKeySet<String>()
    fun isResolving(entry: String) = entry in resolving
    /** The last entries looked up, numbered in the order first seen, so the log can tell a repeat from a new one. */
    private val lookups = LinkedHashMap<String, Int>()
    private var lookupCount = 0
    private fun lookupNumber(entry: String): Int = synchronized(lookups) {
        lookups.getOrPut(entry) { ++lookupCount }.also { if (lookups.size > 64) lookups.remove(lookups.keys.first()) }
    }

    override fun createMediaSource(item: MediaItem): MediaSource {
        if (item.localConfiguration?.uri?.scheme == "catalog") {
            return DeferredProviderSource(item) {
                // Only the selected entry and the one the player preloads reach this point; the rest of the queue is not looked up.
                val entry = item.entryId()
                resolving.add(entry); scope.launch { resolvingChanged() }
                // Which entry is not kept, only whether it is one seen before: a small number, new for each new entry.
                events.append("stream_resolve_start lookup=${lookupNumber(entry)}")
                try {
                    val track = queue.trackFromItem(item)
                    var local = offline.audio(offline.currentScope, track.videoId)
                    // The marker vouches for the length; the first bytes must also be a container a download is written in.
                    // A download that fails this is never handed to the decoder: it is marked for downloading again and the song streams.
                    if (local != null) BundleValidator.problem(local.parentFile!!)?.let { problem ->
                        events.append("download_invalid reason=${problem.wire}"); offline.markDamaged(offline.currentScope, track.videoId); local = null
                    }
                    if (local != null && !track.isLive) {
                        queue.servedFromDownload(entry, true)
                        audio.learnLoudness(item, BundleLoudness.read(local.parentFile))
                        val art = offline.artwork(offline.currentScope, track.videoId)?.let(Uri::fromFile)
                        val resolved = item.buildUpon().setUri(Uri.fromFile(local)).setCustomCacheKey(null).setMediaMetadata(item.mediaMetadata.buildUpon().apply { if (art != null) setArtworkUri(art) }.build()).build()
                        return@DeferredProviderSource createMediaSource(resolved)
                    }
                    queue.servedFromDownload(entry, false)
                    val start = android.os.SystemClock.elapsedRealtime()
                    val stream = withTimeout(if (track.isLive) 60_000 else 30_000) { provider.resolve(track.videoId, track.isLive, video = track.isLive).getOrThrow() }
                    events.append("selected_stream_ready ms=${android.os.SystemClock.elapsedRealtime() - start}")
                    queue.remember(item, stream)
                    audio.learnLoudness(item, stream.loudnessDb)
                    createMediaSource(queue.providerItem(track, stream, queue.keepEntry(item)))
                } finally { resolving.remove(entry); scope.launch { resolvingChanged() } }
            }
        }
        item.mediaMetadata.extras?.getString("videoUri")?.let { videoUri ->
            val extra = Bundle(item.mediaMetadata.extras).apply { remove("videoUri") }
            val sound = item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extra).build()).build()
            val video = MediaItem.Builder().setMediaId("${item.mediaId}:video").setUri(videoUri).build()
            return MergingMediaSource(true, createMediaSource(sound), createMediaSource(video))
        }
        if (item.localConfiguration?.uri?.scheme == "file") {
            // A download: its value lies next to its audio. A file of any other kind has none.
            if (item.mediaId.startsWith("yt:")) audio.learnLoudness(item, BundleLoudness.read(item.localConfiguration?.uri?.path?.let { File(it).parentFile }))
            return offlineMediaSource(context, item)
        }
        val headers = queue.headersFor(item.localConfiguration?.uri.toString())
        val source = DataSource.Factory {
            ProviderMediaDataSource(DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers).createDataSource(),
                sabrHttp, findBootstrap = { key -> queue.sabrSelection(key) }, onSabrResponse = { response ->
                    if (response.requestNumber == 0 || response.failureCategory != null)
                        events.append("sabr_response http=${response.httpStatus ?: 0} failure=${response.failureCategory ?: "none"} init=${response.initializationReceived} media=${response.selectedMediaBytes}")
                })
        }
        val default = DefaultDataSource.Factory(context, source)
        val dataSource = if (item.localConfiguration?.uri?.scheme in setOf("https", "sabr") && item.mediaMetadata.extras?.getBoolean("live") != true && item.mediaMetadata.extras?.getBoolean("video") != true)
            MusicDiskCache.audioFactory(context, default)
            else default
        return DefaultMediaSourceFactory(dataSource).createMediaSource(item)
    }
}
