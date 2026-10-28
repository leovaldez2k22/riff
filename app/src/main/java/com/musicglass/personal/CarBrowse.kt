package com.musicglass.personal

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Feature 14: who may browse Riff's library. Android Auto and the system's own media controls, when the system installed them. */
private val CAR_BROWSERS = setOf("com.google.android.projection.gearhead", "com.android.systemui", "com.google.android.googlequicksearchbox")

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun mayBrowse(session: MediaSession, controller: MediaSession.ControllerInfo) =
    session.isAutoCompanionController(controller) || session.isAutomotiveController(controller) ||
        (controller.packageName in CAR_BROWSERS && controller.isTrusted)

/** The [CarSource] the playback service gives the car: the same repositories the phone's screens read. */
internal class ServiceCarSource(context: Context, private val online: () -> Boolean) : CarSource {
    private val process = AppContainer.get(context)
    private val provider = process.provider
    private val offline = OfflineMusicStore.get(context)
    override val signedIn get() = provider.signedIn
    override fun online() = online.invoke()
    override suspend fun home() = provider.homeSections().getOrNull()
    override suspend fun library(kind: String) = provider.libraryCatalog(kind).getOrNull()?.cards
    override suspend fun liked() = provider.likedSongsPage().getOrNull()?.tracks
    override suspend fun collection(browseId: String) = provider.browseCatalog(browseId).getOrNull()?.let { page ->
        (page.albumTracks + page.cards + page.shelves.flatMap { it.cards }).mapNotNull { it.track() }.distinctBy { it.videoId }.take(MAX_SNAPSHOT_ITEMS)
    }
    override suspend fun downloadedSongs() = withContext(Dispatchers.IO) { offline.rows().filter { it.status == DownloadState.Complete }.map { it.track } }
    override suspend fun recent(limit: Int) = withContext(Dispatchers.IO) {
        val scope = historyScopeKey("yt:", offline.currentScope) ?: return@withContext emptyList()
        process.history.page(scope, limit = limit * 2).entries.mapNotNull { it.track() }.distinctBy { it.videoId }.take(limit)
    }
    override suspend fun search(query: String) = provider.search(query).getOrNull()
}

/** Media3's library calls answered from [library], on [scope] (the service's main-thread scope). */
internal class CarBrowse(private val scope: CoroutineScope, private val library: CarLibrary) {
    private fun <T> future(block: suspend () -> T): ListenableFuture<T> = SettableFuture.create<T>().also { answer ->
        scope.launch {
            try { answer.set(block()) } catch (e: CancellationException) { answer.cancel(false); throw e } catch (e: Exception) { answer.setException(e) }
        }
    }

    fun root(): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(LibraryResult.ofItem(library.root(), LibraryParams.Builder().setExtras(carRootExtras()).build()))

    fun children(parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
        library.children(parentId, page, pageSize)?.let { LibraryResult.ofItemList(it, params) } ?: LibraryResult.ofError(SessionError.ERROR_IO)
    }

    fun item(mediaId: String): ListenableFuture<LibraryResult<MediaItem>> = future {
        library.item(mediaId)?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
    }

    fun search(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> = future {
        session.notifySearchResultChanged(browser, query, library.search(query), params)
        LibraryResult.ofVoid()
    }

    fun searchResult(query: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
        LibraryResult.ofItemList(library.searchResult(query, page, pageSize), params)
    }

    /** What the car chose, as Riff's own queue; [start] makes the player's items. */
    fun play(items: List<MediaItem>, startIndex: Int, start: (List<ProviderTrack>) -> List<MediaItem>): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
        val queue = library.queueFor(items, startIndex) ?: throw UnsupportedOperationException("not in Riff's library")
        MediaSession.MediaItemsWithStartPosition(start(queue.tracks), queue.index, C.TIME_UNSET)
    }

    /** "Add to queue" in the car: the chosen songs alone. */
    fun add(items: List<MediaItem>, make: (ProviderTrack) -> MediaItem): ListenableFuture<List<MediaItem>> = future {
        items.mapNotNull { item -> library.queueFor(listOf(item), 0)?.let { it.tracks[it.index] } }.map(make)
            .ifEmpty { throw UnsupportedOperationException("not in Riff's library") }
    }
}
