package com.musicglass.personal

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.requireAllowedSabrUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** True while the service itself changes play/pause, so that is not read as the user pressing a button. Main thread. */
internal class InternalChange {
    var active = false
    inline fun <T> run(block: () -> T): T { val was = active; active = true; try { return block() } finally { active = was } }
}

private val PLAYABLE_ID = Regex("[A-Za-z0-9_-]{11}")
private val STREAM_HEADERS = setOf("user-agent", "accept", "accept-language", "referer", "origin")
private fun providerMediaHost(uri: Uri) = uri.scheme == "https" && (uri.host == "googlevideo.com" || uri.host.orEmpty().endsWith(".googlevideo.com"))

/** A stream the app resolved itself and now asks the service to play. */
internal class ProviderStreamRequest(val track: ProviderTrack, val stream: ProviderStream)

/**
 * Reads and checks a request to play a resolved stream. Null when any part of it is not what the app itself would
 * send: a media address outside the provider's media hosts, a malformed identifier, or a SABR selection that was not
 * registered for this video.
 */
internal fun providerStreamRequest(args: Bundle): ProviderStreamRequest? {
    val url = args.getString("url").orEmpty()
    val sabrKey = args.getString("sabrKey")
    val bootstrap = sabrKey?.let(SabrPlaybackRegistry::take)
    if (sabrKey != null && bootstrap == null) return null
    if (bootstrap == null && !providerMediaHost(Uri.parse(url))) return null
    val videoId = args.getString("videoId").orEmpty()
    if (!videoId.matches(PLAYABLE_ID)) return null
    val streamVideoId = args.getString("streamVideoId") ?: videoId
    if (!streamVideoId.matches(PLAYABLE_ID)) return null
    if (bootstrap != null && (bootstrap.videoId != streamVideoId || runCatching { requireAllowedSabrUrl(bootstrap.serverAbrStreamingUrl) }.isFailure)) return null
    val track = ProviderTrack(videoId, args.getString("title").orEmpty().take(180),
        args.getString("artist").orEmpty().take(180), validProviderArtworkUrl(args.getString("artworkUrl")),
        args.getString("artistId"), args.getString("albumId"), args.getString("album").orEmpty().take(180), args.getBoolean("live"))
    val headers = args.getBundle("headers")?.keySet()?.associateWith { key ->
        args.getBundle("headers")?.getString(key).orEmpty()
    }.orEmpty().filterKeys { it.lowercase() in STREAM_HEADERS }
    val videoBootstrap = args.getString("sabrVideoKey")?.let(SabrPlaybackRegistry::take)
    val videoUrl = args.getString("videoUrl")
    if (videoBootstrap != null && (videoBootstrap.videoId != streamVideoId || runCatching { requireAllowedSabrUrl(videoBootstrap.serverAbrStreamingUrl) }.isFailure)) return null
    if (videoUrl != null && videoBootstrap == null && !providerMediaHost(Uri.parse(videoUrl))) return null
    return ProviderStreamRequest(track, ProviderStream(url, headers, args.getString("mimeType"), bootstrap, args.getString("profileId"), videoUrl, args.getString("videoMimeType"), videoBootstrap, sourceVideoId = streamVideoId,
        itag = args.getInt("itag"), bitrate = args.getInt("bitrate"), includesVideo = args.getBoolean("includesVideo")))
}

/**
 * What is in the queue and why: the entries the user chose, the recommendations added after them, and the streams
 * resolved for entries so far. Builds the queue's items and applies the commands that replace or extend it. Owned by
 * the playback service; everything here runs on the main thread except the stream maps, which loader threads read.
 * It does not decide retries (RecoveryCoordinator) or store anything (PlaybackPersistence).
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class QueueCoordinator(private val context: Context, private val scope: CoroutineScope, private val provider: YouTubeMusicProvider,
    private val sessions: SessionCoordinator, private val offline: OfflineMusicStore, private val events: PlaybackEventLog,
    private val order: PlaybackCommandOrder, private val audio: AudioTransitionCoordinator, private val persistence: () -> PlaybackPersistence,
    private val selfChange: InternalChange, private val player: () -> Player?, private val onReset: () -> Unit) {
    /** The item this class put in place of a failed one. Its arrival is not a new play. */
    var replacement: MediaItem? = null
    /** Entries added by recommendations. Only these may be passed over when they cannot be played. */
    private val autoEntries = hashSetOf<String>()
    private val resolvedStreams = ConcurrentHashMap<MediaItem, ProviderStream>()
    private var radioJob: Job? = null
    /** Changes whenever the queue is replaced; work started for an earlier queue must not touch this one. */
    var generation = 0
        private set
    var radioSeed: String? = null
        private set
    private var radioContinuation: String? = null
    /** YouTube Music gave its last page of recommendations for this queue (Q18-096). */
    private var radioEnded = false
    private val candidates = ArrayDeque<ProviderTrack>()
    private val queuedIds = linkedSetOf<String>()
    private val streamHeaders = ConcurrentHashMap<String, Map<String, String>>()
    private val sabrSelections = ConcurrentHashMap<String, SabrBootstrap>()
    /** Queue entries whose audio was last opened from a download rather than from the provider. */
    private val downloadServed = ConcurrentHashMap.newKeySet<String>()

    fun isAutomatic(entry: String) = entry in autoEntries
    fun streamFor(item: MediaItem): ProviderStream? = resolvedStreams[item]
    fun remember(item: MediaItem, stream: ProviderStream) { resolvedStreams[item] = stream }
    fun headersFor(uri: String): Map<String, String> = streamHeaders[uri].orEmpty()
    fun sabrSelection(key: String): SabrBootstrap? = sabrSelections[key]
    fun servedFromDownload(entry: String, served: Boolean) { if (served) downloadServed.add(entry) else downloadServed.remove(entry) }
    fun isServedFromDownload(entry: String) = entry in downloadServed

    fun newEntryId() = UUID.randomUUID().toString()
    /** A replacement for an entry already queued keeps that entry's identifier. */
    fun keepEntry(item: MediaItem?) = item?.entryId()?.takeIf { it.isNotEmpty() } ?: newEntryId()

    fun trackFromItem(item: MediaItem): ProviderTrack {
        val m = item.mediaMetadata; val e = m.extras
        return ProviderTrack(item.mediaId.removePrefix("yt:"), m.title?.toString().orEmpty(), m.artist?.toString().orEmpty(),
            m.artworkUri?.toString(), e?.getString("artistId"), e?.getString("albumId"), m.albumTitle?.toString().orEmpty(), e?.getBoolean("live") == true, e?.getBoolean("music", true) != false)
    }
    /** An entry whose stream is looked up only when the player reaches it. */
    fun catalogItem(track: ProviderTrack, entryId: String = newEntryId()): MediaItem = MediaItem.Builder().setMediaId("yt:${track.videoId}")
        .setUri("catalog://${track.videoId}").setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist)
            .setAlbumTitle(track.album).setArtworkUri(offline.indexedArtwork(offline.currentScope, track.videoId)?.let(Uri::fromFile) ?: validProviderArtworkUrl(track.artworkUrl)?.let(Uri::parse))
            .setExtras(Bundle().apply { putString(ENTRY_ID, entryId); putString("artistId", track.artistId); putString("albumId", track.albumId); putBoolean("live", track.isLive); putBoolean("video", track.isLive); putBoolean("music", track.isMusic) }).build()).build()
    /** An entry with its stream. The stream's address and headers stay in this process and are never saved. */
    fun providerItem(listedTrack: ProviderTrack, stream: ProviderStream, entryId: String = newEntryId()): MediaItem {
        val track = resolvedPlaybackTrack(listedTrack, stream)
        val uri = stream.sabrBootstrap?.let { bootstrap ->
            val key = UUID.randomUUID().toString(); sabrSelections[key] = bootstrap; Uri.parse("sabr://$key")
        } ?: Uri.parse(stream.url)
        streamHeaders[uri.toString()] = stream.headers.filterKeys { it.lowercase() in STREAM_HEADERS }
        val videoUri = stream.sabrVideoBootstrap?.let { val key = UUID.randomUUID().toString(); sabrSelections[key] = it; Uri.parse("sabrvideo://$key") }
            ?: stream.videoUrl?.let(Uri::parse)
        videoUri?.let { streamHeaders[it.toString()] = stream.headers.filterKeys { k -> k.lowercase() in STREAM_HEADERS } }
        val extra = Bundle().apply { putString(ENTRY_ID, entryId); putString("videoUri", videoUri?.toString()); putBoolean("video", videoUri != null || track.isLive || stream.includesVideo); putString("artistId", track.artistId); putString("albumId", track.albumId); putBoolean("live", track.isLive); putString("profileId", stream.profileId); putString("sourceVideoId", stream.sourceVideoId ?: track.videoId); putBoolean("music", track.isMusic) }
        val hls = stream.mimeType?.contains("mpegurl", true) == true
        // HLS forbids MediaItem custom cache keys; live/video also bypass the audio cache.
        val cacheKey = if (!hls && !track.isLive && !stream.includesVideo && videoUri == null)
            audioStreamCacheKey(offline.currentScope, stream.sourceVideoId ?: track.videoId, stream.itag,
                stream.mimeType, stream.bitrate, stream.sabrBootstrap != null) else null
        return MediaItem.Builder().setMediaId("yt:${track.videoId}").setUri(uri).setCustomCacheKey(cacheKey)
            .apply { if (hls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album)
                .setArtworkUri(validProviderArtworkUrl(track.artworkUrl)?.let(Uri::parse)).setExtras(extra).build()).build()
            .also { resolvedStreams[it] = stream; audio.learnLoudness(it, stream.loudnessDb) }
    }
    /** The outgoing song's copy for a crossfade: its resolved stream when there is one. */
    fun tailItem(item: MediaItem): MediaItem = resolvedStreams[item]?.let { providerItem(trackFromItem(item), it, keepEntry(item)) } ?: item

    /** Ends recommendations and forgets what was resolved for the queue. Retries in progress end with it. */
    fun reset() {
        replacement = null; resolvedStreams.clear(); downloadServed.clear()
        onReset(); autoEntries.clear()
        generation++; radioJob?.cancel(); radioJob = null; radioSeed = null; radioContinuation = null; radioEnded = false; candidates.clear()
        player()?.setPlaylistMetadata(MediaMetadata.EMPTY)
    }
    /** [reset], and the addresses handed to the player for the old queue stop being answerable. */
    fun resetForNewQueue(keepQueuedIds: Boolean = false) {
        reset(); sabrSelections.clear(); streamHeaders.clear()
        if (!keepQueuedIds) queuedIds.clear()
    }

    /** Loads an owner's saved queue paused. Never starts playback. */
    fun restore(player: Player, stored: StoredSnapshot) {
        val saved = stored.snapshot
        val items = saved.queue.map { q -> if (q.mediaId.startsWith("yt:")) catalogItem(ProviderTrack(q.mediaId.removePrefix("yt:"),
            q.title, q.artist, q.artworkUrl, q.artistId, q.albumId, q.album, q.live, q.music)) else MediaItem.Builder().setMediaId(q.mediaId).setUri(q.localUri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(q.title).setArtist(q.artist).setAlbumTitle(q.album).setExtras(Bundle().apply { putString(ENTRY_ID, newEntryId()) }).build()).build() }
        // The selected song carries its saved length: nothing is prepared before Play, so the player has none (Q18-019).
        val shown = items.mapIndexed { i, item -> if (i != saved.index || saved.durationMs <= 0) item
            else item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setDurationMs(saved.durationMs).build()).build() }
        player.setMediaItems(shown, saved.index, saved.positionMs)
        player.shuffleModeEnabled = saved.shuffle; player.repeatMode = saved.repeat; player.playWhenReady = false
        radioSeed = saved.radioSeed; queuedIds.addAll(saved.queue.filter { it.mediaId.startsWith("yt:") }.map { it.mediaId.removePrefix("yt:") })
        player.setPlaylistMetadata(MediaMetadata.Builder().setSubtitle(if (radioSeed == null) "Album order" else string(R.string.restored_queue_paused)).build())
        events.append("session_restored count=${items.size} index=${saved.index}")
        persistence().adopt(stored.ownerKey)
    }
    /** The user asked for the saved queue of the owner they are in. It replaces the player's queue, paused. */
    fun replaceWithSaved(player: Player, stored: StoredSnapshot) {
        audio.cancelBlend(); player.stop(); resetForNewQueue()
        restore(player, stored)
    }

    /** A queue of catalog entries from the app: a whole collection, one entry to add, or a replacement for the selected one. */
    fun playCatalogQueue(player: Player, args: Bundle, saved: PlaybackSnapshot?, ticket: PlaybackCommandOrder.Ticket, stale: () -> Boolean): SessionResult {
        if (stale() || order.superseded(ticket)) return SessionResult(SessionError.INFO_CANCELLED)
        if (saved == null || saved.queue.any { !it.mediaId.startsWith("yt:") }) return SessionResult(SessionError.ERROR_BAD_VALUE)
        val tracks = saved.queue.map { q -> ProviderTrack(q.mediaId.removePrefix("yt:"), q.title, q.artist, q.artworkUrl, q.artistId, q.albumId, q.album, q.live, q.music) }
        if (tracks.any { !it.videoId.matches(PLAYABLE_ID) }) return SessionResult(SessionError.ERROR_BAD_VALUE)
        if (args.containsKey("enqueueNext") && tracks.size == 1 && player.mediaItemCount > 0) {
            val track = tracks[0]; val existing = indexOfRecording(player, track)
            // Feature 26: Undo puts a removed song back at the row it left.
            val insertAt = args.getInt("insertAt", -1)
            if (existing != null) { if (args.getBoolean("enqueueNext") && existing != player.currentMediaItemIndex) player.moveMediaItem(existing, afterCurrent(player, existing)) }
            else { queuedIds.add(track.videoId); player.addMediaItem(when {
                insertAt >= 0 -> insertAt.coerceAtMost(player.mediaItemCount)
                args.getBoolean("enqueueNext") -> player.currentMediaItemIndex + 1
                else -> player.mediaItemCount }, catalogItem(track)) }
            return SessionResult(SessionResult.RESULT_SUCCESS)
        }
        if (args.containsKey("replacePosition") && tracks.size == 1 && player.mediaItemCount > 0) {
            // Meant for the entry that was selected when it was asked for; the user has since moved on.
            args.getString("expectedEntry")?.let { if (it != player.currentMediaItem?.entryId()) return SessionResult(SessionError.INFO_CANCELLED) }
            val index = player.currentMediaItemIndex; val wants = player.playWhenReady
            player.replaceMediaItem(index, catalogItem(tracks[0], keepEntry(player.currentMediaItem))); player.seekTo(index, args.getLong("replacePosition")); player.prepare(); player.playWhenReady = wants
            return SessionResult(SessionResult.RESULT_SUCCESS)
        }
        player.stop(); resetForNewQueue()
        player.shuffleModeEnabled = saved.shuffle; player.repeatMode = Player.REPEAT_MODE_OFF
        queuedIds.addAll(tracks.map { it.videoId })
        player.setMediaItems(tracks.map { catalogItem(it) }, saved.index, 0)
        if (args.getBoolean("automatic") && !tracks[0].isLive) radioSeed = tracks[0].videoId
        player.setPlaylistMetadata(MediaMetadata.Builder().setTitle(saved.collectionTitle).setSubtitle(if (saved.collectionTitle.isBlank()) "Album order" else "Collection order").build())
        // A pause made after this was asked for wins: the queue loads, silent.
        if (order.autoplay(ticket, args.getBoolean("autoplay", true))) { player.prepare(); player.play() } else player.playWhenReady = false
        persistence().save(player); events.append("album_queue_selected count=${tracks.size}")
        return SessionResult(SessionResult.RESULT_SUCCESS)
    }

    /** One on-device file replaces the queue. The caller has checked that the file may be read. */
    fun playLocal(player: Player, item: MediaItem, ticket: PlaybackCommandOrder.Ticket): SessionResult {
        resetForNewQueue(keepQueuedIds = true)
        player.setMediaItem(item)
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.prepare()
        if (order.autoplay(ticket, true)) player.play() else player.playWhenReady = false
        events.append("local_audio_selected")
        return SessionResult(SessionResult.RESULT_SUCCESS)
    }

    /** A resolved stream from the app: replaces the selected entry, joins the queue, or starts a new queue with recommendations. */
    fun playProviderStream(player: Player, args: Bundle, request: ProviderStreamRequest, ticket: PlaybackCommandOrder.Ticket, stale: () -> Boolean): SessionResult {
        val track = request.track; val stream = request.stream; val videoId = track.videoId
        if (stale()) return SessionResult(SessionError.INFO_CANCELLED)
        if (args.getBoolean("replaceCurrent") && player.mediaItemCount > 0) {
            args.getString("expectedEntry")?.let { if (it != player.currentMediaItem?.entryId()) return SessionResult(SessionError.INFO_CANCELLED) }
            val index = player.currentMediaItemIndex; val wants = player.playWhenReady
            player.replaceMediaItem(index, providerItem(track, stream, keepEntry(player.currentMediaItem))); player.seekTo(index, args.getLong("position").coerceAtLeast(0))
            player.prepare(); player.playWhenReady = wants
        } else if (args.getBoolean("enqueue") && player.mediaItemCount > 0) {
            val existing = indexOfRecording(player, track)
            if (existing != null) {
                if (args.getBoolean("playNext") && existing != player.currentMediaItemIndex) player.moveMediaItem(existing, afterCurrent(player, existing))
                return SessionResult(SessionResult.RESULT_SUCCESS)
            }
            val item = providerItem(track, stream)
            val index = if (args.getBoolean("playNext")) player.currentMediaItemIndex + 1 else player.mediaItemCount
            queuedIds.add(videoId); player.addMediaItem(index, item)
        } else {
            resetForNewQueue()
            queuedIds.add(videoId)
            player.setMediaItem(providerItem(track, stream), args.getLong("position", 0L).coerceAtLeast(0))
            player.repeatMode = Player.REPEAT_MODE_OFF; player.prepare()
            if (order.autoplay(ticket, true)) player.play() else player.playWhenReady = false
            events.append("provider_stream_selected")
            if (!track.isLive) { radioSeed = videoId; replenish(player) }
        }
        return SessionResult(SessionResult.RESULT_SUCCESS)
    }

    /** Where the same video or the same recording already is in the queue. */
    private fun indexOfRecording(player: Player, track: ProviderTrack) = (0 until player.mediaItemCount).firstOrNull {
        val item = player.getMediaItemAt(it); val m = item.mediaMetadata
        sameRecording(item.mediaId, m.title.toString(), m.artist.toString(), track)
    }
    /** The index that puts the entry now at [existing] directly after the selected one. */
    private fun afterCurrent(player: Player, existing: Int) =
        (player.currentMediaItemIndex + 1 - if (existing < player.currentMediaItemIndex) 1 else 0).coerceAtMost(player.mediaItemCount - 1)

    private fun status(player: Player, loading: Boolean, message: String) {
        player.setPlaylistMetadata(MediaMetadata.Builder().setSubtitle(message)
            .setExtras(Bundle().apply { putBoolean("queueLoading", loading) }).build())
    }
    /**
     * Adds recommendations when a queue that was started from one song is close to its end, or at once when the open
     * queue was scrolled to its last song ([onDemand], Q18-096).
     */
    fun replenish(player: Player, onDemand: Boolean = false) {
        val seed = radioSeed ?: return
        if (!radioShouldExtend(onDemand, player.playbackState == Player.STATE_READY, player.isPlaying,
                player.mediaItemCount - player.currentMediaItemIndex, player.mediaItemCount, radioJob?.isActive == true)) return
        if (radioEnded && candidates.isEmpty()) { if (onDemand) status(player, false, string(R.string.no_more_playable_recommendations_right_now)); return }
        val started = generation; val owner = sessions.capture()
        radioJob = scope.launch {
            status(player, true, string(R.string.finding_more_music_on_youtube_music))
            try {
                if (candidates.isEmpty()) {
                    val (tracks, token) = provider.recommendations(seed, radioContinuation).getOrThrow()
                    if (started != generation || !sessions.accepts(owner)) return@launch
                    radioContinuation = token; radioEnded = token == null
                    val known = (0 until player.mediaItemCount).map { val m = player.getMediaItemAt(it).mediaMetadata; queueRecordingKey(m.title.toString(), m.artist.toString()) }.toSet()
                    val hidden = NotInterestedStore.get(context).keys
                    candidates.addAll(queueCandidates(tracks, queuedIds + hidden, known))
                }
                var added = 0; var attempted = 0
                while (candidates.isNotEmpty() && added < 6 && attempted < 12) {
                    val track = candidates.removeFirst(); attempted++
                    if (track.videoId in queuedIds) continue
                    if (started != generation || !isActive || !sessions.accepts(owner)) return@launch
                    if (track.videoId in queuedIds || (0 until player.mediaItemCount).any {
                        val m = player.getMediaItemAt(it).mediaMetadata
                        queueRecordingKey(m.title.toString(), m.artist.toString()) == queueRecordingKey(track.title, track.artist)
                    }) continue
                    if (!queuedIds.add(track.videoId)) continue
                    player.addMediaItem(catalogItem(track).also { autoEntries.add(it.entryId()) }); added++
                    if (added == 1) status(player, true, string(R.string.adding_youtube_music_recommendations))
                }
                status(player, false, if (added > 0) string(R.string.music_from_youtube_music) else string(R.string.no_more_playable_recommendations_right_now))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (started == generation) status(player, false, string(R.string.couldn_t_load_recommendations_your_current_s)) }
        }
    }

    /** One recovery attempt: loads the entry again, keeping queue order, position and whether the user wants playback. */
    suspend fun reload(entry: String, step: RecoveryStep.Attempt, fault: (MediaItem, Throwable) -> PlaybackFault): AttemptOutcome {
        val player = player() ?: return AttemptOutcome.Obsolete
        val item = player.currentMediaItem?.takeIf { it.entryId() == entry } ?: return AttemptOutcome.Obsolete
        val extras = item.mediaMetadata.extras
        val scheme = item.localConfiguration?.uri?.scheme
        val track = trackFromItem(item)
        val previous = resolvedStreams[item]
        val sourceId = if (track.isLive) track.videoId else extras?.getString("sourceVideoId") ?: previous?.sourceVideoId ?: track.videoId
        if (item.mediaId.startsWith("yt:") && step.action != RecoveryAction.Prepare) provider.invalidateStream(sourceId)
        // An entry that was never resolved is looked up by its own source, which also prefers a download.
        if (item.mediaId.startsWith("yt:") && scheme == "file" && step.action != RecoveryAction.Prepare) {
            // A download that could not be read: look the entry up again, which uses the files only when they are complete.
            val fresh = catalogItem(track, keepEntry(item))
            replacement = fresh
            return if (selfChange.run { replaceFailedStream(player, item, fresh) }) AttemptOutcome.Applied else AttemptOutcome.Obsolete
        }
        if (step.action == RecoveryAction.Prepare || !item.mediaId.startsWith("yt:") || scheme == "catalog") {
            selfChange.run { player.prepare() }
            return AttemptOutcome.Applied
        }
        val started = generation
        val video = track.isLive || previous?.includesVideo == true || extras?.getBoolean("video") == true
        return try {
            if (step.action == RecoveryAction.RefreshAttestation) provider.invalidateAttestation()
            val stream = provider.resolve(sourceId, track.isLive, step.excluded, video = video).getOrThrow()
            if (started != generation || player.currentMediaItem !== item) return AttemptOutcome.Obsolete
            val fresh = providerItem(track, stream, keepEntry(item))
            replacement = fresh
            if (selfChange.run { replaceFailedStream(player, item, fresh, if (track.isLive) 0L else null) }) AttemptOutcome.Applied else AttemptOutcome.Obsolete
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { AttemptOutcome.Failed(fault(item, e)) }
    }
    /** An automatic entry that cannot be played is passed over; one the user chose never is. */
    fun skipUnavailable(entry: String): Boolean {
        val player = player() ?: return false
        if (entry !in autoEntries || player.currentMediaItem?.entryId() != entry || !player.hasNextMediaItem()) return false
        selfChange.run { player.seekToNextMediaItem(); player.prepare() }
        return true
    }
}

/** One recording in the queue: the same video, or the same song by the same main artist from another upload. */
internal fun sameRecording(mediaId: String, title: String, artist: String, track: ProviderTrack) =
    mediaId == "yt:${track.videoId}" || queueRecordingKey(title, artist) == queueRecordingKey(track.title, track.artist)

internal enum class EnqueueOutcome { Added, Moved, AlreadyQueued }
/**
 * What Add to queue or Play next does to [queue] with [current] selected, by the rule the service follows: a
 * recording already queued is never added twice; Play next moves it after the selected one (Q18-018).
 */
internal fun enqueueOutcome(queue: List<QueueEntry>, current: Int, track: ProviderTrack, next: Boolean): EnqueueOutcome {
    val existing = queue.indexOfFirst { sameRecording(it.mediaId, it.title, it.artist, track) }
    return when {
        existing < 0 -> EnqueueOutcome.Added
        next && existing != current -> EnqueueOutcome.Moved
        else -> EnqueueOutcome.AlreadyQueued
    }
}
/** The notice for [outcome]: it says what happened, and nothing when nothing did. */
internal fun enqueueNotice(outcome: EnqueueOutcome, next: Boolean) = when (outcome) {
    EnqueueOutcome.AlreadyQueued -> string(R.string.already_in_the_queue)
    else -> if (next) string(R.string.playing_next) else string(R.string.added_to_queue)
}

/** Recommendations stop at this many entries in the queue when they are added on their own. */
internal const val RADIO_QUEUE_AUTO_LIMIT = 80
/** And at this many when the listener scrolls for more (Q18-096). */
internal const val RADIO_QUEUE_LIMIT = 200

/**
 * Whether to fetch more recommendations now. On their own: while playing, within four entries of the end. Asked for
 * by scrolling to the last song: whenever nothing is being fetched already, up to [RADIO_QUEUE_LIMIT].
 */
internal fun radioShouldExtend(onDemand: Boolean, ready: Boolean, playing: Boolean, remaining: Int, size: Int, busy: Boolean): Boolean =
    !busy && if (onDemand) size < RADIO_QUEUE_LIMIT else ready && playing && remaining <= 4 && size < RADIO_QUEUE_AUTO_LIMIT
