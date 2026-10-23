package com.musicglass.personal

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.metrolist.innertubex.sabr.SabrBootstrap
import io.ktor.client.request.get
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonElement
import kotlinx.coroutines.flow.StateFlow

internal fun providerArtworkUrl(videoId: String): String? =
    videoId.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
        ?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }

internal data class ProviderTrack(val videoId: String, val title: String, val artist: String,
    val artworkUrl: String? = providerArtworkUrl(videoId),
    val artistId: String? = null, val albumId: String? = null, val album: String = "",
    val isLive: Boolean = false, val isMusic: Boolean = true)
internal data class ProviderSearchPage(val tracks: List<ProviderTrack>, val nextToken: String?)
internal data class ProviderHomeCard(val title: String, val subtitle: String,
    val artworkUrl: String?, val videoId: String? = null, val browseId: String? = null, val kind: CatalogKind = CatalogKind.Song,
    val artistId: String? = null, val albumId: String? = null, val album: String = "",val musicVideo:Boolean=false,val officialMusicVideo:Boolean=false,val setVideoId:String?=null,val addedAt:Long?=null,
    val notInterestedToken:String?=null,
    /** The row's stated length (Q18-093); null when YouTube shows none. */
    val durationMs:Long?=null)
internal data class ProviderHomeSection(val title: String, val cards: List<ProviderHomeCard>)
/** [email] comes from the account menu when YouTube includes it; it is shown on the phone and never logged. */
/** [handle] (@name) stands in for the address where YouTube's account menu gives none (Q18-040). */
internal data class ProviderProfile(val name: String, val artworkUrl: String?, val email: String? = null, val handle: String? = null)
internal data class ProviderStream(
    val url: String,
    val headers: Map<String, String>,
    val mimeType: String?,
    val sabrBootstrap: SabrBootstrap? = null,
    val profileId: String? = null,
    val videoUrl:String?=null,val videoMimeType:String?=null,val sabrVideoBootstrap:SabrBootstrap?=null,val sourceVideoId:String?=null,
    val itag:Int=0,val bitrate:Int=0,val contentLength:Long?=null,val durationMs:Long=0,val expiresAtMs:Long=0,
    val boundedRange:Boolean=false,val includesVideo:Boolean=false,val resolvedTitle:String="",val resolvedAuthor:String="",val resolvedChannelId:String?=null,
    /** How far the recording is above the provider's reference loudness, in decibels; null when the provider did not say. */
    val loudnessDb:Double?=null,
)

/**
 * The app's music provider, as callers have always held it. Since Q11 it only assembles four parts and forwards
 * to them: [sessionManager] (who is signed in), [catalog] (what can be read), [library] (the account's own things
 * and every change to them) and [streams] (where audio comes from). It is a migration facade: new code should take
 * the narrowest of [ProviderSession], [ProviderCatalogReader], [ProviderMutations] or [StreamResolver] it needs.
 * Owned by AppContainer for the life of the process; closed only by tests.
 */
internal class YouTubeMusicProvider(context: Context,
    private val dependencies: RuntimeDependencies = AppContainer.get(context).runtime) : StreamResolver, ProviderSession, ProviderCatalogReader, ProviderMutations, AutoCloseable {
    companion object {
        fun checkExistingFeedWindow() {AppContainer.existing()?.existingProvider()?.checkFeedWindow()}
        fun checkExistingSession() {AppContainer.existing()?.existingProvider()?.checkSessionIfDue()}
        fun shared(context:Context):YouTubeMusicProvider=AppContainer.get(context).provider
    }
    private val sessionManager = ProviderSessionManager(context, dependencies)
    private val catalog = ProviderCatalogRepository(sessionManager)
    private val library = ProviderLibraryRepository(sessionManager, catalog)
    private val streams = ProviderStreamResolver(context, sessionManager)

    // ---- session
    override val signedIn get() = sessionManager.signedIn
    override val sessionState get() = sessionManager.sessionState
    /** The cookie store no longer holds the committed account's sign-in; nothing is sent with it (Q18-010). */
    internal val detached get() = sessionManager.detached
    override val session: StateFlow<SessionToken> get() = sessionManager.session
    val accountProfile get() = sessionManager.accountProfile
    val authRevision get() = sessionManager.authRevision
    val storageRevision get() = sessionManager.storageRevision
    internal val musicPairingRequests get() = sessionManager.musicPairingRequests
    /** What can be offered for the session as it is now. Read in composition, it follows the session. */
    val capabilities:ProviderCapabilities get()=providerCapabilities(sessionState,accountScope(),streams.cipherIsolated || dependencies.resolver!=null)
    fun checkSessionIfDue()=sessionManager.checkSessionIfDue(false)
    override fun checkSessionIfDue(forced:Boolean)=sessionManager.checkSessionIfDue(forced)
    internal fun simulateExpiredSession()=sessionManager.simulateExpiredSession()
    fun refreshAccount()=sessionManager.refreshAccount()
    fun useWebSession(visitor:String?,syncId:String?,accountIndex:String?)=sessionManager.useWebSession(visitor,syncId,accountIndex)
    fun signInCandidate(visitor:String?,syncId:String?,accountIndex:String?)=sessionManager.signInCandidate(visitor,syncId,accountIndex)
    suspend fun verifySignIn(candidate:WebSignInCandidate)=sessionManager.verifySignIn(candidate)
    fun commitSignIn(verified:VerifiedSignIn)=sessionManager.commitSignIn(verified)
    fun signOut()=sessionManager.signOut()
    suspend fun sessionAccounts()=sessionManager.sessionAccounts()
    override fun accountScope()=sessionManager.accountScope()
    fun signInScope(verified:VerifiedSignIn)=sessionManager.signInScope(verified)
    fun accountScopeKey()=sessionManager.accountScopeKey()
    fun accountScopeDigests()=sessionManager.accountScopeDigests()
    internal fun reconcileAccountStorage()=sessionManager.reconcileAccountStorage()
    override fun storageScopeKey()=sessionManager.storageScopeKey()

    // ---- catalog
    internal val feedCache get() = catalog.feedCache
    internal val collectionCache get() = catalog.collectionCache
    val homeMoods get() = catalog.homeMoods
    fun feedKey(key:String)=catalog.feedKey(key)
    fun cachedFeed(key:String)=catalog.cachedFeed(key)
    fun cachedCollection(id:String)=catalog.cachedCollection(id)
    fun checkFeedWindow()=catalog.checkFeedWindow()
    fun discardPageMemory() {catalog.discardPageMemory();library.discardPageMemory()}
    suspend fun discover(query:String)=catalog.discover(query,null,"All")
    suspend fun discover(query:String,filter:String)=catalog.discover(query,null,filter)
    override suspend fun discover(query:String,token:String?,filter:String)=catalog.discover(query,token,filter)
    suspend fun browseCatalog(id:String)=catalog.browseCatalog(id,null)
    override suspend fun browseCatalog(id:String,token:String?)=catalog.browseCatalog(id,token)
    override suspend fun completeCollection(id:String)=catalog.completeCollection(id)
    override fun partialCollection(id:String)=catalog.partialCollection(id)
    suspend fun podcastSections(genre:String="For you")=catalog.podcastSections(genre)
    suspend fun liveStations(category:String="Featured")=catalog.liveStations(category)
    suspend fun homeSections()=catalog.homeSections("")
    override suspend fun homeSections(mood:String)=catalog.homeSections(mood)
    suspend fun search(query:String)=catalog.search(query)
    suspend fun searchPage(query:String,nextToken:String?=null)=catalog.searchPage(query,nextToken)
    suspend fun likedSongsPage(nextToken:String?=null)=catalog.likedSongsPage(nextToken)
    suspend fun likedSongsCount(onCount:(Int)->Unit={})=catalog.likedSongsCount(onCount)
    suspend fun artistFor(track:ProviderTrack)=catalog.artistFor(track)
    suspend fun albumFor(videoId:String,known:String?)=catalog.albumFor(videoId,known)
    suspend fun recommendations(videoId:String)=catalog.recommendations(videoId,null)
    override suspend fun recommendations(videoId:String,token:String?)=catalog.recommendations(videoId,token)
    override suspend fun videoFor(track:ProviderTrack)=catalog.videoFor(track)
    override suspend fun lyricsFor(videoId:String,durationMs:Long)=catalog.lyricsFor(videoId,durationMs)
    override suspend fun profile()=catalog.profile()
    internal fun parseHomeCard(node:JsonElement)=catalog.parseHomeCard(node)

    // ---- the account's library and every change to it
    val songLikes get() = library.songLikes
    val libraryRevision get() = library.libraryRevision
    val libraryScanRevision get() = library.libraryScanRevision
    val libraryPlaylistSnapshot get() = library.libraryPlaylistSnapshot
    fun cachedLibrary(kind:String)=library.cachedLibrary(kind)
    suspend fun libraryCatalog(kind:String)=library.libraryCatalog(kind,null)
    override suspend fun libraryCatalog(kind:String,token:String?)=library.libraryCatalog(kind,token)
    suspend fun scanLibraryPlaylists()=library.scanLibraryPlaylists()
    fun refreshLibraryIndex()=library.refreshLibraryIndex()
    suspend fun editablePlaylists()=library.editablePlaylists()
    override suspend fun loadLike(videoId:String)=library.loadLike(videoId)
    override suspend fun setSongLike(videoId:String,liked:Boolean)=library.setSongLike(videoId,liked)
    override suspend fun saveAlbum(browseId:String)=library.saveAlbum(browseId)
    override suspend fun addPlaylistSong(id:String,videoId:String)=library.addPlaylistSong(id,videoId)
    override suspend fun createPlaylist(title:String)=library.createPlaylist(title)
    override suspend fun renamePlaylist(id:String,title:String)=library.renamePlaylist(id,title)
    override suspend fun deletePlaylist(id:String)=library.deletePlaylist(id)
    override suspend fun removePlaylistSong(id:String,card:ProviderHomeCard)=library.removePlaylistSong(id,card)
    override suspend fun sortedCollection(id:String,endpoint:CollectionSortEndpoint)=library.sortedCollection(id,endpoint)
    override suspend fun sendNotInterested(videoId:String?,token:String?)=library.sendNotInterested(videoId,token)

    // ---- streams
    internal val cipherIsolated get() = streams.cipherIsolated
    suspend fun resolve(videoId:String,live:Boolean=false,excludedProfiles:Set<String> = emptySet(),video:Boolean=false,download:Boolean=false)=
        streams.resolve(videoId,live,excludedProfiles,video,download)
    override suspend fun resolve(request:StreamRequest)=streams.resolve(request)
    suspend fun invalidateAttestation()=streams.invalidateAttestation()
    fun invalidateStream(videoId:String)=streams.invalidateStream(videoId)
    /** The app is on the screen again: get the next song ready to start quickly ([ProviderStreamResolver.warmUp]). */
    fun warmStreams()=streams.warmUp()

    override fun close() {
        sessionManager.scope.cancel()
        streams.close()
        sessionManager.close()
    }
}


internal val OFFLINE_MESSAGE: String get() = string(R.string.you_re_offline_check_your_connection_and)
internal val TIMEOUT_MESSAGE: String get() = string(R.string.youtube_music_took_too_long_to_answer)

/**
 * What a failed provider request tells the user. Network failures get plain words: the raw
 * message ("Unable to resolve host \"music.youtube.com\"...") showed on Podcasts when offline.
 */
internal fun providerErrorMessage(e: Throwable): String {
    val chain = generateSequence(e) { it.cause }.take(8).toList()
    if (chain.any { it is java.net.UnknownHostException || it is java.net.ConnectException ||
            it is java.net.NoRouteToHostException }) return OFFLINE_MESSAGE
    if (chain.any { it is java.net.SocketTimeoutException || it.javaClass.simpleName.contains("Timeout") }) return TIMEOUT_MESSAGE
    // What the provider's own answer supports, in plain words; otherwise the request's own message, or the general one.
    return when (val fault = classifyProviderFailure(e)) {
        ProviderFault.RateLimited, ProviderFault.NotFound, ProviderFault.Refused, ProviderFault.Provider -> fault.message
        else -> e.message?.takeIf { !it.contains("http",true) && !it.contains("host",true) } ?: ProviderFault.Unknown.message
    }
}
