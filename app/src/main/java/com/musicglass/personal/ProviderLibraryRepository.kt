package com.musicglass.personal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.innertubex.models.YouTubeClient
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.get
import io.ktor.client.request.header
import androidx.compose.runtime.mutableStateMapOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What belongs to the signed-in account at the provider: its library, its likes and its playlists, and every
 * request that changes them. A change counts as made only when the provider acknowledged it for the account
 * that was signed in when it was sent; an outcome that is not known is settled by reading, never by sending again.
 */
internal class ProviderLibraryRepository(private val session: ProviderSessionManager, private val catalog: ProviderCatalogRepository) {
    private val runtimeScope get() = session.scope
    private val playbackEvents get() = session.playbackEvents
    private val innerTube get() = session.innerTube
    private val http get() = session.http
    private val json get() = session.json
    private val signedIn get() = session.signedIn
    private val authRevision get() = session.authRevision
    private suspend fun <T> providerResult(block: suspend () -> T): Result<T> = session.providerResult(block)
    private val collectionCache get() = catalog.collectionCache
    private fun feedKey(key: String) = catalog.feedKey(key)
    private suspend fun requestCatalog(browseId: String, token: String? = null) = catalog.requestCatalog(browseId, token)
    private suspend fun completeCollectionFresh(id: String, sort: CollectionSortEndpoint? = null) = catalog.completeCollectionFresh(id, sort)
    private fun libraryArtwork(page: CatalogPage) = catalog.libraryArtwork(page)
    var libraryScanRevision by mutableIntStateOf(0)
        private set
    var libraryPlaylistSnapshot by mutableStateOf<List<ProviderHomeCard>>(emptyList())
        private set
    fun cachedLibrary(kind:String):CatalogPage?=collectionCache.peek(feedKey("library:$libraryRevision:$kind"))
    val songLikes = mutableStateMapOf<String,Boolean>()
    var libraryRevision by mutableIntStateOf(0)
        private set
    private val likeGate = Mutex()
    fun discardPageMemory() {libraryPlaylistSnapshot=emptyList();libraryScanRevision=0}
    init { session.onReset { songLikes.clear();libraryPlaylistSnapshot=emptyList();libraryScanRevision=0 } }

    private val collectionSortGate=Mutex()
    suspend fun sortedCollection(id:String,endpoint:CollectionSortEndpoint):Result<CatalogPage> = providerResult {
        collectionSortGate.withLock {
            val revision=authRevision
            val key=feedKey("collection:$id")
            endpoint.playlistEdit?.let {edit->
                check(signedIn) {"Sign in to sort this playlist"}
                require(edit.validFor(id)) {"This playlist sort command is unavailable"}
                val session=innerTube.sessionSnapshot()
                check(revision==authRevision) {"Account changed while sorting"}
                // Do not retry this write: a timeout can mean the preference was already applied.
                collectionCache.invalidate(key)
                applyPlaylistSort(http,json,session,edit)
                check(revision==authRevision) {"Account changed while sorting"}
            }
            val page=completeCollectionFresh(id,endpoint.takeIf {it.playlistEdit==null}).getOrThrow()
            check(revision==authRevision) {"Account changed while sorting"}
            check(page.albumTracks.isNotEmpty() || page.cards.any {it.track()!=null}) {"YouTube Music did not return a sorted track list"}
            if(endpoint.playlistEdit!=null) collectionCache.put(key,page)
            page
        }
    }
    suspend fun libraryCatalog(kind:String)=libraryCatalog(kind,null)
    suspend fun libraryCatalog(kind:String,token:String?):Result<CatalogPage> = providerResult {
        if(token!=null) libraryCatalogFresh(kind,token).getOrThrow()
        else collectionCache.get(feedKey("library:$libraryRevision:$kind")) {libraryArtwork(libraryCatalogFresh(kind).getOrThrow())}
    }
    private suspend fun libraryCatalogFresh(kind: String, token: String? = null): Result<CatalogPage> = providerResult {
        check(signedIn) { "Sign in to view your YouTube Music library" }
        requestCatalog(when(kind) { "Playlists" -> "FEmusic_liked_playlists"; "Albums" -> "FEmusic_liked_albums"
            "Artists" -> "FEmusic_library_corpus_track_artists"; "Podcasts" -> "FEmusic_library_non_music_audio_list"; "All" -> "FEmusic_library_landing"; else -> "FEmusic_liked_videos" },token)
    }
    suspend fun scanLibraryPlaylists():Result<List<ProviderHomeCard>> = providerResult {
        val revision=authRevision
        var page=libraryCatalogFresh("Playlists").getOrThrow()
        val cards=page.cards.toMutableList();val seen=mutableSetOf<String>()
        while(page.next!=null && seen.add(page.next!!) && cards.size<2000) {page=libraryCatalogFresh("Playlists",page.next).getOrThrow();cards.addAll(page.cards)}
        check(revision==authRevision) {"Account changed while scanning library"}
        val latest=libraryArtwork(CatalogPage(cards)).cards.filter {it.kind==CatalogKind.Playlist}.distinctBy {it.key()}
        val old=cachedLibrary("Playlists")?.cards.orEmpty()
        old.filter {c->latest.none {it.key()==c.key()}}.forEach {collectionCache.invalidate(feedKey("collection:${it.browseId}"))}
        collectionCache.put(feedKey("library:$libraryRevision:Playlists"),CatalogPage(latest))
        libraryPlaylistSnapshot=latest;libraryScanRevision++
        cachedLibrary("All")?.let {collectionCache.put(feedKey("library:$libraryRevision:All"),it.copy(cards=it.cards.filterNot {c->c.kind==CatalogKind.Playlist}+latest))}
        latest
    }
    fun refreshLibraryIndex() {runtimeScope.launch {if(signedIn) scanLibraryPlaylists()}}
    suspend fun editablePlaylists():Result<List<ProviderHomeCard>> = providerResult {
        val list=scanLibraryPlaylists().getOrThrow()
        list.filter {it.browseId!=null && it.browseId!="VLLM" && it.browseId!="VLSE"}
    }
    /** Edits sent for this session whose outcome is not known. Each is settled by reading the playlist before anything is sent again. */
    private val unsettledEdits=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** A request cancelled from inside (the library refreshing its session) may still have arrived. */
    private suspend fun sentOutcome(onFault:(Exception)->Unit={},send:suspend ()->MutationOutcome):MutationOutcome =
        try {send()}
        catch(e:CancellationException) {currentCoroutineContext().ensureActive();MutationOutcome.Unknown}
        catch(e:Exception) {onFault(e);transportOutcome(e)}
    /**
     * One playlist edit. [applied] reads a freshly loaded playlist and says whether the edit is in it.
     * Acknowledged: done. Rejected: failed, and sending again is safe. Unknown: the playlist is read; the
     * edit is never sent a second time on a guess, since adding twice would add twice.
     */
    private suspend fun playlistMutation(id:String,edit:String,applied:(CatalogPage)->Boolean,request:suspend ()->io.ktor.client.statement.HttpResponse):Result<Unit> = sessionSafeMutation {
        check(signedIn) {"Sign in to edit YouTube Music playlists"}
        require(id.startsWith("VL") && id.length>2 && id!="VLLM" && id!="VLSE" && id.matches(Regex("[A-Za-z0-9_-]{3,200}"))) {"Choose an editable playlist"}
        val revision=authRevision;val key="$revision:$id:$edit";val cacheKey=feedKey("collection:$id")
        suspend fun readBack():Boolean {
            val page=try {completeCollectionFresh(id).getOrThrow()} catch(e:CancellationException) {currentCoroutineContext().ensureActive();throw MutationUnconfirmed()} catch(e:Exception) {throw MutationUnconfirmed()}
            check(revision==authRevision) {"Account changed while updating playlist"}
            // A list that is not the whole playlist cannot show that an entry is absent.
            if(page.truncation!=null && !applied(page)) throw MutationUnconfirmed()
            return applied(page)
        }
        fun done() {unsettledEdits.remove(key);collectionCache.invalidate(cacheKey);libraryRevision++}
        if(key in unsettledEdits) {
            collectionCache.invalidate(cacheKey)
            if(readBack()) {playbackEvents.append("provider_edit_settled applied=true");done();return@sessionSafeMutation}
            playbackEvents.append("provider_edit_settled applied=false");unsettledEdits.remove(key)
        }
        var fault:Exception?=null
        val outcome=sentOutcome({fault=it}) {val response=request();playlistEditOutcome(response.status.value,response.bodyAsText())}
        // Whatever was answered, what is cached may no longer be what the provider has.
        collectionCache.invalidate(cacheKey)
        check(revision==authRevision) {"Account changed while updating playlist"}
        when(outcome) {
            MutationOutcome.Acknowledged -> done()
            // A request that never left failed for the reason it never left, not because of the playlist.
            MutationOutcome.Rejected -> error(fault?.let(::providerErrorMessage)?:"YouTube Music could not update this playlist. It may be read-only.")
            MutationOutcome.Unknown -> {
                unsettledEdits.add(key);playbackEvents.append("provider_edit_unknown")
                if(readBack()) {playbackEvents.append("provider_edit_settled applied=true");done()}
                else {unsettledEdits.remove(key);playbackEvents.append("provider_edit_settled applied=false");error("YouTube Music did not save this change. Try again.")}
            }
        }
    }
    suspend fun addPlaylistSong(id:String,videoId:String):Result<Unit> {
        if(!videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) return Result.failure(IllegalStateException("This song cannot be added to a playlist"))
        return playlistMutation(id,"add:$videoId",{page->page.albumTracks.any {it.videoId==videoId}}) {innerTube.addToPlaylist(YouTubeClient.WEB_REMIX,id,videoId)}
    }
    suspend fun removePlaylistSong(id:String,card:ProviderHomeCard):Result<Unit> {
        val setId=card.setVideoId;val videoId=card.videoId
        if(setId==null || videoId==null) return Result.failure(IllegalStateException("YouTube Music did not supply this playlist entry's ID"))
        return playlistMutation(id,"remove:$setId",{page->page.albumTracks.none {it.setVideoId==setId}}) {innerTube.removePlaylistSong(YouTubeClient.WEB_REMIX,id,setId,videoId)}
    }
    suspend fun saveAlbum(browseId:String):Result<Unit> = providerResult {
        check(signedIn) {"Sign in to save albums on YouTube Music"}
        require(browseId.matches(Regex("[A-Za-z0-9_-]{2,200}"))) {"This cannot be saved"}
        val revision=authRevision
        val response=innerTube.browse(YouTubeClient.WEB_REMIX,browseId=browseId,setLogin=true)
        check(response.status.value<400) {"Could not read album"}
        val root=json.parseToJsonElement(response.bodyAsText())
        val header=catalogRenderer(root,"musicResponsiveHeaderRenderer")?:catalogRenderer(root,"musicDetailHeaderRenderer")
        val playlist=if(browseId.startsWith("VL")) browseId.removePrefix("VL") else catalogValue(header,"playlistId")?:error("This album cannot be saved from its current provider response")
        check(revision==authRevision) {"Account changed while saving"}
        val outcome=sentOutcome {val saved=innerTube.likePlaylist(YouTubeClient.WEB_REMIX,playlist);likeOutcome(saved.status.value,saved.bodyAsText())}
        check(revision==authRevision) {"Account changed while saving"}
        when(outcome) {
            MutationOutcome.Acknowledged -> libraryRevision++
            MutationOutcome.Rejected -> error("Could not save album")
            // Saving twice is the same as saving once, so there is nothing to protect by reading first.
            MutationOutcome.Unknown -> {libraryRevision++;throw MutationUnconfirmed("YouTube Music did not confirm saving this. It is safe to try again.")}
        }
    }
    suspend fun loadLike(videoId: String): Result<Boolean?> = providerResult {
        if (!signedIn) return@providerResult null
        val revision = authRevision
        val response = innerTube.next(YouTubeClient.WEB_REMIX,videoId = videoId)
        check(response.status.value < 400) { "Could not read like status" }
        val value = parseLikeStatus(json.parseToJsonElement(response.bodyAsText()))
        if (revision == authRevision && signedIn && value != null) songLikes[videoId] = value
        value
    }
    suspend fun setSongLike(videoId: String, liked: Boolean): Result<Unit> = providerResult {
        likeGate.withLock {
            check(signedIn) { "Sign in to like songs on YouTube Music" }
            require(videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) { "This song cannot be liked" }
            val revision = authRevision
            val outcome = sentOutcome {
                val response = if(liked) innerTube.likeVideo(YouTubeClient.WEB_REMIX,videoId) else innerTube.unlikeVideo(YouTubeClient.WEB_REMIX,videoId)
                likeOutcome(response.status.value,response.bodyAsText())
            }
            check(revision == authRevision && signedIn) { "Account changed while saving" }
            when(outcome) {
                MutationOutcome.Acknowledged -> Unit
                MutationOutcome.Rejected -> error("YouTube Music did not accept the like")
                MutationOutcome.Unknown -> {
                    // Not answered: what the provider has now decides, not what was asked for.
                    playbackEvents.append("provider_like_unknown")
                    val now = try {
                        val response = innerTube.next(YouTubeClient.WEB_REMIX,videoId = videoId)
                        if(response.status.value >= 400) null else parseLikeStatus(json.parseToJsonElement(response.bodyAsText()))
                    } catch(e: CancellationException) { currentCoroutineContext().ensureActive(); null } catch(e: Exception) { null }
                    check(revision == authRevision && signedIn) { "Account changed while saving" }
                    if(now == null) throw MutationUnconfirmed()
                    songLikes[videoId] = now
                    check(now == liked) { "YouTube Music did not save the like. Try again." }
                }
            }
            songLikes[videoId] = liked; libraryRevision++
        }
    }
    /** Sends YouTube Music's "Not interested" feedback; looks the token up from the song's radio when none is known. */
    suspend fun sendNotInterested(videoId: String?, token: String?): Result<Boolean> = providerResult {
        if (!signedIn) return@providerResult false
        val revision = authRevision
        val feedback = token ?: videoId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }?.let { id ->
            val response = innerTube.next(YouTubeClient.WEB_REMIX, videoId = id, playlistId = "RDAMVM$id")
            if (response.status.value >= 400) null
            else notInterestedTokenFor(json.parseToJsonElement(response.bodyAsText()), id)
        } ?: return@providerResult false
        val response = innerTube.feedback(YouTubeClient.WEB_REMIX, listOf(feedback))
        check(response.status.value < 400 && revision == authRevision) { "YouTube Music did not accept the feedback" }
        true
    }
}
