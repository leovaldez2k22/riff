package com.musicglass.personal

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import io.ktor.client.HttpClient
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    @Volatile private var selectedForDiagnostics: String? = null
    private val preparationGate = SourcePreparationGate()
    fun selected(entry: String?) { selectedForDiagnostics = entry; preparationGate.update(entry, false) }
    fun isResolving(entry: String) = entry in resolving
    /** The last entries looked up, numbered in the order first seen, so the log can tell a repeat from a new one. */
    private val lookups = LinkedHashMap<String, Int>()
    private var lookupCount = 0
    /** One pool for every stream request, so a seek or the next song reuses the open connection (Q18-057). */
    private val streamHttp by lazy { OkHttpStreamDataSource.client() }
    private fun lookupNumber(entry: String): Int = synchronized(lookups) {
        lookups.getOrPut(entry) { ++lookupCount }.also { if (lookups.size > 64) lookups.remove(lookups.keys.first()) }
    }

    /** The entry last looked up ahead, and that lookup. One try per entry: a failure is not repeated on every event. Main thread. */
    private var aheadEntry: String? = null
    private var ahead: Job? = null

    /**
     * Q18-059: once the selected song plays, the next entry's stream is looked up and kept in the provider's cache,
     * so a skip to it starts without waiting for the lookup (on the phone about 1.5 s goes to the cipher alone).
     * The player itself looks the next entry up only near the end of the song. Called after every player event; main thread.
     */
    fun lookUpNext(player: Player) {
        preparationGate.update(player.currentMediaItem?.entryId(), player.isPlaying)
        val next = if (player.hasNextMediaItem()) player.getMediaItemAt(player.nextMediaItemIndex) else null
        if (next == null || !lookAheadWanted(player.isPlaying, player.currentMediaItem?.mediaId, next.localConfiguration?.uri?.scheme,
                next.mediaId, next.mediaMetadata.extras?.getBoolean("live") == true)) {
            ahead?.cancel(); ahead = null; aheadEntry = null
            return
        }
        val entry = next.entryId().ifEmpty { next.mediaId }
        if (entry == aheadEntry) return
        aheadEntry = entry; ahead?.cancel()
        val videoId = next.mediaId.removePrefix("yt:")
        ahead = scope.launch {
            if (withContext(Dispatchers.IO) { offline.audio(offline.currentScope, videoId) } != null) return@launch
            val start = android.os.SystemClock.elapsedRealtime()
            provider.resolve(videoId).fold(
                onSuccess = { events.append("stream_ahead_ready ms=${android.os.SystemClock.elapsedRealtime() - start}") },
                onFailure = { events.append("stream_ahead_failed type=${it::class.simpleName}") })
        }
    }

    /**
     * The app came back to the screen, or restored a queue while on it, with a song waiting: look up its address now, so that
     * Play finds it ready. Nothing is looked up for a song already on the phone or starting from the disk cache. Main thread.
     */
    fun readySelected(player: Player) {
        val item = player.currentMediaItem ?: return
        if (!returnLookupWanted(player.isPlaying, player.playWhenReady, item.localConfiguration?.uri?.scheme, item.mediaId,
                item.mediaMetadata.extras?.getBoolean("live") == true)) return
        val videoId = item.mediaId.removePrefix("yt:")
        val scopeNow = offline.currentScope
        val quality = provider.streamQualityName()
        scope.launch {
            val held = withContext(Dispatchers.IO) { offline.audio(scopeNow, videoId) != null || MusicDiskCache.playable(context, scopeNow, videoId, quality) != null }
            if (held) return@launch
            val start = android.os.SystemClock.elapsedRealtime()
            provider.resolve(videoId).onSuccess { events.append("stream_ready_on_return ms=${android.os.SystemClock.elapsedRealtime() - start}") }
        }
    }

    override fun createMediaSource(item: MediaItem): MediaSource {
        if (item.localConfiguration?.uri?.scheme == "catalog") {
            return DeferredProviderSource(item) {
                // Only the selected entry and the one the player preloads reach this point; the rest of the queue is not looked up.
                val entry = item.entryId()
                preparationGate.await(entry)
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
                    val fromDisk = !track.isLive && item.mediaMetadata.extras?.getBoolean("video") != true
                    val quality = provider.streamQualityName()
                    val cached = if (fromDisk) withContext(Dispatchers.IO) { MusicDiskCache.playable(context, offline.currentScope, track.videoId, quality) } else null
                    if (cached != null) {
                        events.append("stream_from_cache part=${cached.second.word}")
                        val standIn = cachedStreamStandIn(cached.first, track.videoId)
                        queue.remember(item, standIn)
                        return@DeferredProviderSource createMediaSource(queue.providerItem(track, standIn, queue.keepEntry(item)))
                    }
                    val start = android.os.SystemClock.elapsedRealtime()
                    val stream = StartupDiagnostics.await(StartupDiagnostics.Stage.Resolve, if (entry == selectedForDiagnostics) StartupDiagnostics.Kind.Source else StartupDiagnostics.Kind.Preload) { withinLookupDeadline(if (track.isLive) 60_000L else 30_000L) { provider.resolve(track.videoId, track.isLive, video = track.isLive) }.getOrThrow() }
                    events.append("selected_stream_ready ms=${android.os.SystemClock.elapsedRealtime() - start}")
                    queue.remember(item, stream)
                    audio.learnLoudness(item, stream.loudnessDb)
                    val ready = queue.providerItem(track, stream, queue.keepEntry(item))
                    if (fromDisk) cachedStreamEntry(stream, ready.localConfiguration?.customCacheKey)?.let { kept ->
                        val scopeNow = offline.currentScope
                        scope.launch(Dispatchers.IO) { MusicDiskCache.index(context).put(scopeNow, track.videoId, quality, kept) }
                    }
                    createMediaSource(ready)
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
            ProviderMediaDataSource(OkHttpStreamDataSource(streamHttp, headers),
                sabrHttp, findBootstrap = { key -> queue.sabrSelection(key) }, onSabrResponse = { response ->
                    if (response.requestNumber == 0 || response.failureCategory != null)
                        events.append("sabr_response http=${response.httpStatus ?: 0} failure=${response.failureCategory ?: "none"} init=${response.initializationReceived} media=${response.selectedMediaBytes}")
                })
        }
        val stream = queue.streamFor(item)
        val directAudio = isDirectAudio(stream, item.localConfiguration?.uri?.scheme, item.mediaMetadata.extras?.getBoolean("live") == true)
        val upstream = if (directAudio) {
            val owner = provider.session.value
            val pinned = checkNotNull(stream)
            val addressed = streamUrlFactory(DataSource.Factory { OkHttpStreamDataSource(streamHttp, emptyMap()) }, pinned,
                System::currentTimeMillis) {
                if (provider.session.value != owner) throw java.io.IOException("Playback owner changed")
                val fresh = provider.resolve(pinned.sourceVideoId ?: item.mediaId.removePrefix("yt:")).getOrThrow()
                if (provider.session.value != owner) throw java.io.IOException("Playback owner changed")
                fresh
            }
            DataSource.Factory { ChunkedStreamDataSource(addressed.createDataSource(), pinned.contentLength) }
        } else source
        val default = DefaultDataSource.Factory(context, upstream)
        val dataSource = if (item.localConfiguration?.uri?.scheme in setOf("https", "sabr") && item.mediaMetadata.extras?.getBoolean("live") != true && item.mediaMetadata.extras?.getBoolean("video") != true)
            MusicDiskCache.audioFactory(context, default)
            else default
        val factory = DefaultMediaSourceFactory(dataSource)
        // Only a direct address can be replaced. A live playlist or segment may 404 for a moment and keeps Media3's retries.
        if (directAudio) factory.setLoadErrorHandlingPolicy(StreamLoadErrorPolicy())
        return factory.createMediaSource(item)
    }
}

/**
 * Q18-059: the next entry is looked up ahead only while a song plays, and only when it would otherwise be looked up
 * on arrival: an entry from the catalog (a download or a resolved stream needs no lookup), not live (its address
 * does not keep), and not the song already playing.
 */
/** A song waiting to be played that a lookup could get ready. One that is playing, or about to, has its own lookup. */
internal fun returnLookupWanted(playing: Boolean, playWhenReady: Boolean, scheme: String?, mediaId: String, live: Boolean): Boolean =
    !playing && !playWhenReady && scheme == "catalog" && !live && mediaId.startsWith("yt:")

internal fun lookAheadWanted(playing: Boolean, currentMediaId: String?, nextScheme: String?, nextMediaId: String, nextLive: Boolean): Boolean =
    playing && nextScheme == "catalog" && !nextLive && nextMediaId.startsWith("yt:") && nextMediaId != currentMediaId

