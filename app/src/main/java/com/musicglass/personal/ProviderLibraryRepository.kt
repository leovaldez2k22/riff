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
                check(signedIn) {string(R.string.sign_in_to_sort_this_playlist)}
                require(edit.validFor(id)) {string(R.string.this_playlist_sort_command_is_unavailable)}
                val session=innerTube.sessionSnapshot()
                check(revision==authRevision) {string(R.string.account_changed_while_sorting)}
                // Do not retry this write: a timeout can mean the preference was already applied.
                collectionCache.invalidate(key)
                applyPlaylistSort(http,json,session,edit)
                check(revision==authRevision) {string(R.string.account_changed_while_sorting)}
            }
            val page=completeCollectionFresh(id,endpoint.takeIf {it.playlistEdit==null}).getOrThrow()
            check(revision==authRevision) {string(R.string.account_changed_while_sorting)}
            check(page.albumTracks.isNotEmpty() || page.cards.any {it.track()!=null}) {string(R.string.youtube_music_did_not_return_a_sorted)}
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
        check(signedIn) { string(R.string.sign_in_to_view_your_youtube_music_2) }
        // Q18-080: Liked songs is the Liked music playlist (VLLM). The library's Songs list keeps a song after it is unliked.
        if(kind=="Favorites") return@providerResult catalog.likedSongsPage(token).getOrThrow().let {CatalogPage(it.tracks.map(ProviderTrack::homeCard),it.nextToken)}
        requestCatalog(when(kind) { "Playlists" -> "FEmusic_liked_playlists"; "Albums" -> "FEmusic_liked_albums"
            "Artists" -> "FEmusic_library_corpus_track_artists"; "Podcasts" -> "FEmusic_library_non_music_audio_list"; "All" -> "FEmusic_library_landing"; else -> "FEmusic_liked_videos" },token)
    }
    suspend fun scanLibraryPlaylists():Result<List<ProviderHomeCard>> = providerResult {
        val revision=authRevision
        var page=libraryCatalogFresh("Playlists").getOrThrow()
        val cards=page.cards.toMutableList();val seen=mutableSetOf<String>()
        while(page.next!=null && seen.add(page.next!!) && cards.size<LIBRARY_PLAYLIST_SCAN_LIMIT) {page=libraryCatalogFresh("Playlists",page.next).getOrThrow();cards.addAll(page.cards)}
        check(revision==authRevision) {string(R.string.account_changed_while_scanning_library)}
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
        check(signedIn) {string(R.string.sign_in_to_edit_youtube_music_playlists)}
        require(id.startsWith("VL") && id.length>2 && id!="VLLM" && id!="VLSE" && id.matches(Regex("[A-Za-z0-9_-]{3,200}"))) {string(R.string.choose_an_editable_playlist)}
        val revision=authRevision;val key="$revision:$id:$edit";val cacheKey=feedKey("collection:$id")
        suspend fun readBack():Boolean {
            val page=try {completeCollectionFresh(id).getOrThrow()} catch(e:CancellationException) {currentCoroutineContext().ensureActive();throw MutationUnconfirmed()} catch(e:Exception) {throw MutationUnconfirmed()}
            check(revision==authRevision) {string(R.string.account_changed_while_updating_playlist)}
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
        check(revision==authRevision) {string(R.string.account_changed_while_updating_playlist)}
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
        if(!videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) return Result.failure(IllegalStateException(string(R.string.this_song_cannot_be_added_to_a)))
        return playlistMutation(id,"add:$videoId",{page->page.albumTracks.any {it.videoId==videoId}}) {innerTube.addToPlaylist(YouTubeClient.WEB_REMIX,id,videoId)}
    }
    /**
     * Q18-081: makes a private playlist through YouTube Music's own create call, so it is a real playlist on the
     * account. Never sent twice: an unanswered create may have made one, and the library shows whether it did.
     */
    suspend fun createPlaylist(title:String):Result<String> = sessionSafeMutation {
        check(signedIn) {string(R.string.sign_in_to_edit_youtube_music_playlists)}
        val name=title.trim()
        require(name.isNotEmpty() && name.length<=PLAYLIST_TITLE_LIMIT) {string(R.string.name_the_playlist)}
        val revision=authRevision
        var fault:Exception?=null;var body=""
        val outcome=sentOutcome({fault=it}) {val response=innerTube.createPlaylist(YouTubeClient.WEB_REMIX,name);body=response.bodyAsText();createPlaylistOutcome(response.status.value,body)}
        check(revision==authRevision) {string(R.string.account_changed_while_updating_playlist)}
        libraryRevision++
        when(outcome) {
            MutationOutcome.Acknowledged -> {playbackEvents.append("provider_playlist_created");"VL"+createdPlaylistId(body)!!}
            MutationOutcome.Rejected -> error(fault?.let(::providerErrorMessage)?:string(R.string.youtube_music_could_not_create_the_playlist))
            MutationOutcome.Unknown -> {playbackEvents.append("provider_playlist_create_unknown");throw MutationUnconfirmed(string(R.string.youtube_music_did_not_confirm_the_new_playlist))}
        }
    }
    suspend fun removePlaylistSong(id:String,card:ProviderHomeCard):Result<Unit> {
        val setId=card.setVideoId;val videoId=card.videoId
        if(setId==null || videoId==null) return Result.failure(IllegalStateException(string(R.string.youtube_music_did_not_supply_this_playlist)))
        return playlistMutation(id,"remove:$setId",{page->page.albumTracks.none {it.setVideoId==setId}}) {innerTube.removePlaylistSong(YouTubeClient.WEB_REMIX,id,setId,videoId)}
    }
    suspend fun saveAlbum(browseId:String):Result<Unit> = providerResult {
        check(signedIn) {string(R.string.sign_in_to_save_albums_on_youtube)}
        require(browseId.matches(Regex("[A-Za-z0-9_-]{2,200}"))) {string(R.string.this_cannot_be_saved)}
        val revision=authRevision
        val response=innerTube.browse(YouTubeClient.WEB_REMIX,browseId=browseId,setLogin=true)
        check(response.status.value<400) {string(R.string.could_not_read_album)}
        val root=json.parseToJsonElement(response.bodyAsText())
        val header=catalogRenderer(root,"musicResponsiveHeaderRenderer")?:catalogRenderer(root,"musicDetailHeaderRenderer")
        val playlist=if(browseId.startsWith("VL")) browseId.removePrefix("VL") else catalogValue(header,"playlistId")?:error("This album cannot be saved from its current provider response")
        check(revision==authRevision) {string(R.string.account_changed_while_saving)}
        val outcome=sentOutcome {val saved=innerTube.likePlaylist(YouTubeClient.WEB_REMIX,playlist);likeOutcome(saved.status.value,saved.bodyAsText())}
        check(revision==authRevision) {string(R.string.account_changed_while_saving)}
        when(outcome) {
            MutationOutcome.Acknowledged -> libraryRevision++
            MutationOutcome.Rejected -> error("Could not save album")
            // Saving twice is the same as saving once, so there is nothing to protect by reading first.
            MutationOutcome.Unknown -> {libraryRevision++;throw MutationUnconfirmed(string(R.string.youtube_music_did_not_confirm_saving_this))}
        }
    }
    suspend fun loadLike(videoId: String): Result<Boolean?> = providerResult {
        if (!signedIn) return@providerResult null
        val revision = authRevision
        val response = innerTube.next(YouTubeClient.WEB_REMIX,videoId = videoId)
        check(response.status.value < 400) { string(R.string.could_not_read_like_status) }
        val value = parseLikeStatus(json.parseToJsonElement(response.bodyAsText()))
        if (revision == authRevision && signedIn && value != null) songLikes[videoId] = value
        value
    }
    suspend fun setSongLike(videoId: String, liked: Boolean): Result<Unit> = providerResult {
        likeGate.withLock {
            check(signedIn) { string(R.string.sign_in_to_like_songs_on_youtube) }
            require(videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) { string(R.string.this_song_cannot_be_liked) }
            val revision = authRevision
            val outcome = sentOutcome {
                val response = if(liked) innerTube.likeVideo(YouTubeClient.WEB_REMIX,videoId) else innerTube.unlikeVideo(YouTubeClient.WEB_REMIX,videoId)
                likeOutcome(response.status.value,response.bodyAsText())
            }
            check(revision == authRevision && signedIn) { string(R.string.account_changed_while_saving) }
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
                    check(revision == authRevision && signedIn) { string(R.string.account_changed_while_saving) }
                    if(now == null) throw MutationUnconfirmed()
                    songLikes[videoId] = now
                    check(now == liked) { string(R.string.youtube_music_did_not_save_the_like) }
                }
            }
            songLikes[videoId] = liked; libraryRevision++
        }
    }
    /**
     * Tells YouTube Music the listener is not interested. Its own "Not interested" is offered only on Home's cards
     * (a song's radio, search and Related have none, checked 20 Oct 2026), so a song without that token is disliked
     * instead, the owner's choice on 20 Oct 2026: YouTube Music then keeps it out of mixes and radio. A collection
     * without a token is not sent.
     */
    suspend fun sendNotInterested(videoId: String?, token: String?): Result<NotInterestedSent> = providerResult {
        if (!signedIn) return@providerResult NotInterestedSent.None
        val revision = authRevision
        val song = videoId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
        val feedback = token ?: song?.let { id ->
            val response = innerTube.next(YouTubeClient.WEB_REMIX, videoId = id, playlistId = "RDAMVM$id")
            if (response.status.value >= 400) null
            else notInterestedTokenFor(json.parseToJsonElement(response.bodyAsText()), id)
        }
        if (feedback != null) {
            val response = innerTube.feedback(YouTubeClient.WEB_REMIX, listOf(feedback))
            check(response.status.value < 400 && revision == authRevision) { string(R.string.youtube_music_did_not_accept_the_feedback) }
            return@providerResult NotInterestedSent.Feedback
        }
        if (song == null) return@providerResult NotInterestedSent.None
        check(revision == authRevision && signedIn) { string(R.string.account_changed_while_saving) }
        dislikeSong(song)
        NotInterestedSent.Disliked
    }
    /** A dislike, under the same rules as a like: acknowledged, refused, or settled by reading the song's rating. */
    private suspend fun dislikeSong(videoId: String) = likeGate.withLock {
        val revision = authRevision
        val outcome = sentOutcome {
            val response = innerTube.dislikeVideo(YouTubeClient.WEB_REMIX, videoId)
            likeOutcome(response.status.value, response.bodyAsText())
        }
        check(revision == authRevision && signedIn) { string(R.string.account_changed_while_saving) }
        when (outcome) {
            MutationOutcome.Acknowledged -> Unit
            MutationOutcome.Rejected -> error(string(R.string.youtube_music_did_not_accept_the_feedback))
            MutationOutcome.Unknown -> {
                playbackEvents.append("provider_dislike_unknown")
                val now = try {
                    val response = innerTube.next(YouTubeClient.WEB_REMIX, videoId = videoId)
                    if (response.status.value >= 400) null else catalogValue(json.parseToJsonElement(response.bodyAsText()), "likeStatus")
                } catch (e: CancellationException) { currentCoroutineContext().ensureActive(); null } catch (e: Exception) { null }
                check(revision == authRevision && signedIn) { string(R.string.account_changed_while_saving) }
                if (now == null) throw MutationUnconfirmed()
                check(now == "DISLIKE") { string(R.string.youtube_music_did_not_accept_the_feedback) }
            }
        }
        songLikes[videoId] = false; libraryRevision++
    }
}

/** YouTube Music's longest playlist name. */
internal const val PLAYLIST_TITLE_LIMIT = 150
