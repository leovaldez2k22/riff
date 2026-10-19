package com.musicglass.personal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.models.YouTubeClient
import io.ktor.http.content.TextContent
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.post
import io.ktor.http.contentType
import io.ktor.http.ContentType
import io.ktor.client.request.get
import io.ktor.client.request.header
import androidx.compose.runtime.mutableStateMapOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Everything that is only read from the provider and is the same for whoever asks: search, collections, the
 * home, podcast and radio feeds, recommendations and lyrics. Pages are kept in memory per session generation
 * and dropped when the session changes. Nothing here changes anything at the provider.
 */
internal class ProviderCatalogRepository(private val session: ProviderSessionManager) {
    private val dependencies get() = session.dependencies
    private val appContext get() = session.appContext
    private val runtimeScope get() = session.scope
    private val innerTube get() = session.innerTube
    private val http get() = session.http
    private val json get() = session.json
    private val signedIn get() = session.signedIn
    private val authRevision get() = session.authRevision
    private suspend fun <T> providerResult(block: suspend () -> T): Result<T> = session.providerResult(block)
    private var accountProfile: ProviderProfile?
        get() = session.accountProfile
        set(value) { session.accountProfile = value }
    internal val feedCache=PageMemoryCache(runtimeScope,scheduled=dependencies.startProviderBackgroundWork,clock={java.time.Instant.ofEpochMilli(dependencies.clocks.wallTimeMillis()).atZone(java.time.ZoneId.systemDefault())},retain={key->val page=key.substringAfter(':');page.startsWith("home:") || page.startsWith("podcasts:") || page=="radio" || page.startsWith("radio:")})
    internal val collectionCache=PageMemoryCache(runtimeScope,scheduled=false,clock={java.time.Instant.ofEpochMilli(dependencies.clocks.wallTimeMillis()).atZone(java.time.ZoneId.systemDefault())})
    var homeMoods by mutableStateOf<List<HomeMood>>(emptyList())
        private set
    fun feedKey(key:String)="$authRevision:$key"
    fun cachedFeed(key:String):List<ProviderHomeSection>?=feedCache.peek(feedKey(key))
    // Home always waits for a fresh feed (rc17 behaviour, kept by the owner on 2026-10-03), so no Home
    // snapshot is stored. Unreleased test builds of plan W3 wrote one here; remove it once.
    private val retiredSnapshots=if (dependencies.startProviderBackgroundWork) runtimeScope.launch {runCatching {java.io.File(appContext.noBackupFilesDir,"feed-snapshots").deleteRecursively()}} else null
    fun cachedCollection(id:String):CatalogPage?=collectionCache.peek(feedKey("collection:$id"))
    fun checkFeedWindow()=feedCache.checkWindow()
    fun discardPageMemory() {cachedHome=emptyList();podcastEndpoint=null;feedCache.clear();collectionCache.clear();homeMoods=emptyList()}
    private val searchCache = object : LinkedHashMap<String, Pair<Long, ProviderSearchPage>>(24, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, ProviderSearchPage>>?) =
            size > 24
    }
    private val cacheLock = Any()
    private val searchPacer = Mutex()
    private var lastSearchMs = 0L
    private val songsFilter = "EgWKAQIIAWoMEA4QChADEAQQCRAF"
    private var cachedHome: List<ProviderHomeSection> = emptyList()
    private var podcastEndpoint: Pair<String,String?>? = null
    init {
        session.onReset {
            cachedHome = emptyList();podcastEndpoint=null
            synchronized(cacheLock) { searchCache.clear() }
            feedCache.clear();collectionCache.clear();homeMoods=emptyList()
        }
    }

    internal suspend fun requestCatalog(browseId: String, token: String? = null, params: String? = null, paced: Boolean = true): CatalogPage {
        val revision = authRevision
        if (paced) paceSearch()
        val response = innerTube.browse(YouTubeClient.WEB_REMIX,browseId = browseId,params = params,
            continuation = token,setLogin = signedIn)
        if (response.status.value >= 400) throw ProviderHttpException(response.status.value)
        check(revision == authRevision) { string(R.string.account_changed_while_loading) }
        val root = json.parseToJsonElement(response.bodyAsText())
        // HTTP 200 can carry an error. It is a failure, not a page with nothing on it.
        providerEnvelopeError(root)?.let { throw it }
        return catalogPage(root)
    }
    // Retries a collection page on network errors, timeouts, 429 and 5xx; other HTTP errors and account changes fail at once.
    private suspend fun requestCollectionPage(id: String, token: String? = null, params: String? = null): CatalogPage {
        val revision = authRevision
        var attempt = 0
        while (true) {
            try {
                return withTimeout(15_000) { requestCatalog(id, token, params, paced = false) }
            } catch (e: Exception) {
                val retryable = when (e) {
                    is TimeoutCancellationException, is java.io.IOException -> true
                    is CancellationException -> throw e
                    is ProviderHttpException -> e.status == 429 || e.status >= 500
                    else -> false
                }
                if (!retryable || attempt >= 2 || revision != authRevision) {
                    if (e is TimeoutCancellationException) throw IllegalStateException(string(R.string.youtube_music_took_too_long_to_respond))
                    throw e
                }
                attempt++
                dependencies.wait(700L * attempt)
            }
        }
    }
    suspend fun discover(query: String)=discover(query,null,"All")
    suspend fun discover(query: String, filter: String)=discover(query,null,filter)
    suspend fun discover(query: String, token: String?, filter: String): Result<CatalogPage> = providerResult {
        val revision = authRevision
        val params = when(filter) {
            "Songs" -> songsFilter
            "Videos" -> "EgWKAQIQAWoMEA4QChADEAQQCRAF"
            "Albums" -> "EgWKAQIYAWoMEA4QChADEAQQCRAF"
            "Artists" -> "EgWKAQIgAWoMEA4QChADEAQQCRAF"
            "Playlists" -> "Eg-KAQwIABAAGAAgACgBMABqChAEEAMQCRAFEAo%3D"
            "Podcasts" -> "EgWKAQJQAWoMEA4QChADEAQQCRAF"
            else -> null
        }
        paceSearch()
        val response = innerTube.search(YouTubeClient.WEB_REMIX,query = query.trim().take(100),params = params,
            continuation = token,setLogin = signedIn)
        if (response.status.value >= 400) throw ProviderHttpException(response.status.value)
        check(revision == authRevision) { string(R.string.account_changed_while_searching) }
        fun matches(card: ProviderHomeCard): Boolean = when(filter) {
            "Videos" -> card.videoId!=null && card.kind==CatalogKind.Song
            "Albums" -> card.kind == CatalogKind.Album
            "Artists" -> card.kind == CatalogKind.Artist
            "Playlists" -> card.kind == CatalogKind.Playlist
            "Podcasts" -> card.kind in setOf(CatalogKind.Podcast,CatalogKind.Episode)
            "Songs" -> card.kind == CatalogKind.Song && card.videoId != null
            else -> true
        }
        val searchRoot = json.parseToJsonElement(response.bodyAsText())
        providerEnvelopeError(searchRoot)?.let { throw it }
        var page = catalogPage(searchRoot)
        if(token==null && params!=null && page.cards.none(::matches)) {
            paceSearch()
            val mixed=innerTube.search(YouTubeClient.WEB_REMIX,query=query.trim().take(100),setLogin=signedIn)
            if(mixed.status.value<400) {
                val root=json.parseToJsonElement(mixed.bodyAsText())
                val dynamic=catalogSearchFilter(root,filter)
                val mixedPage=catalogPage(root)
                if(dynamic!=null && dynamic!=params) {
                    paceSearch()
                    val filtered=innerTube.search(YouTubeClient.WEB_REMIX,query=query.trim().take(100),params=dynamic,setLogin=signedIn)
                    if(filtered.status.value<400) page=catalogPage(json.parseToJsonElement(filtered.bodyAsText()))
                }
                if(page.cards.none(::matches)) page=mixedPage.copy(next=null)
            }
        }
        check(revision == authRevision) { string(R.string.account_changed_while_searching) }
        page.copy(cards=page.cards.filter(::matches))
    }

    suspend fun browseCatalog(id: String)=browseCatalog(id,null)
    suspend fun browseCatalog(id: String, token: String?): Result<CatalogPage> = providerResult {
        require(id.matches(Regex("[A-Za-z0-9_-]{2,200}"))) { string(R.string.collection_is_unavailable) }
        if(token!=null) requestCatalog(id,token) else collectionCache.get(feedKey("collection:$id")) {requestCatalog(id)}
    }
    suspend fun completeCollection(id:String):Result<CatalogPage> = providerResult {
        collectionCache.get(feedKey("collection:$id")) {completeCollectionFresh(id).getOrThrow()}
    }
    // Pages loaded so far for collections still fetching their continuations, so screens can render before the last page.
    private val partialCollections=mutableStateMapOf<String,CatalogPage>()
    fun partialCollection(id:String):CatalogPage?=partialCollections[feedKey("collection:$id")]
    internal suspend fun completeCollectionFresh(id:String, sort:CollectionSortEndpoint?=null):Result<CatalogPage> = providerResult {
        val revision=authRevision;val key=feedKey("collection:$id")
        val publishPartial=sort==null
        fun styled(page:CatalogPage)=if(id=="VLLM") page.copy(artworkUrl=likedArtwork()) else page
        try {
            // Collection pages arrive one after another, so they skip the search pacer rather than queueing 800 ms apart.
            val first=requestCollectionPage(sort?.browseId?:id,sort?.continuation,sort?.params)
            var page=first.copy(next=first.collectionNext)
            val seen=mutableSetOf<String>();var truncation:CollectionTruncation?=null;var pages=1;var barren=0
            while(page.next!=null) {
                // A list that is not the whole collection says so; it is never passed off as complete.
                if(page.albumTracks.size>=COLLECTION_TRACK_LIMIT) {truncation=CollectionTruncation.Limit;break}
                // A token seen before, pages that add nothing, or more pages than any collection needs: the provider is going round.
                if(!seen.add(page.next!!) || barren>=2 || pages>=COLLECTION_PAGE_LIMIT) {truncation=CollectionTruncation.RepeatedPage;break}
                if(publishPartial && revision==authRevision) partialCollections[key]=styled(page.copy(next=null))
                val more=requestCollectionPage(id,page.next);pages++
                val known=page.albumTracks.mapTo(HashSet()) {it.setVideoId?:it.key()}
                barren=if(more.albumTracks.any {(it.setVideoId?:it.key()) !in known}) 0 else barren+1
                page=page.copy(cards=page.cards+more.cards,albumTracks=page.albumTracks+more.albumTracks,next=more.collectionNext)
            }
            if(page.albumTracks.size>COLLECTION_TRACK_LIMIT) truncation=CollectionTruncation.Limit
            check(revision==authRevision) {string(R.string.account_changed_while_loading_this_collectio)}
            styled(page.copy(cards=page.cards.take(COLLECTION_TRACK_LIMIT),albumTracks=page.albumTracks.take(COLLECTION_TRACK_LIMIT),next=null,truncation=truncation))
        } finally {if(publishPartial) partialCollections.remove(key)}
    }
    private fun likedArtwork()="android.resource://${appContext.packageName}/${R.drawable.liked_music_art}"
    internal fun libraryArtwork(page:CatalogPage)=page.copy(cards=page.cards.map {if(it.browseId=="VLLM") it.copy(artworkUrl=likedArtwork()) else it})
    suspend fun podcastSections(genre:String="For you"):Result<List<ProviderHomeSection>> = providerResult {
        feedCache.get(feedKey("podcasts:$genre")) {
            if(genre=="For you") podcastSectionsFresh().getOrThrow() else discover("$genre podcasts",filter="Podcasts").getOrThrow().let {listOf(ProviderHomeSection(genre,it.cards))}
        }
    }
    private suspend fun podcastSectionsFresh(): Result<List<ProviderHomeSection>> = providerResult {
        homeSections().getOrThrow()
        val existing=cachedHome.filter(::podcastShelf)
        val endpoint=podcastEndpoint
        val page=if(endpoint!=null) try {requestCatalog(endpoint.first,params=endpoint.second)}
            catch(e:CancellationException) {throw e} catch(_:Exception) {null} else null
        val shelves=(page?.shelves.orEmpty()+existing).distinctBy(ProviderHomeSection::title)
        if(shelves.isNotEmpty()) shelves else {
            val shows=discover("podcasts",filter="Podcasts").getOrThrow().cards
            if(shows.isEmpty()) emptyList() else listOf(ProviderHomeSection(string(R.string.discover_shows),shows))
        }
    }
    suspend fun artistFor(track: ProviderTrack): Result<String?> = providerResult {
        track.artistId ?: run {
            val response = innerTube.next(YouTubeClient.WEB_REMIX,videoId = track.videoId)
            check(response.status.value < 400) { string(R.string.artist_is_unavailable) }
            val root = json.parseToJsonElement(response.bodyAsText())
            val panel = parseQueueTracks(root).firstOrNull { it.videoId == track.videoId }
            panel?.artistId ?: discover(playerArtistLabel(track.artist),filter = "Artists").getOrThrow().cards
                .firstOrNull { lyricsNormalize(it.title) == lyricsNormalize(playerArtistLabel(track.artist)) }?.browseId
        }
    }
    suspend fun albumFor(videoId: String, known: String?): Result<String?> = providerResult {
        known ?: run {
            val response=innerTube.next(YouTubeClient.WEB_REMIX,videoId=videoId)
            check(response.status.value<400) {string(R.string.album_is_unavailable)}
            parseQueueTracks(json.parseToJsonElement(response.bodyAsText())).firstOrNull {it.videoId==videoId}?.albumId
        }
    }
    suspend fun recommendations(videoId: String)=recommendations(videoId,null)
    suspend fun recommendations(videoId: String, token: String?): Result<Pair<List<ProviderTrack>,String?>> = providerResult {
        val revision=authRevision
        val response=innerTube.next(YouTubeClient.WEB_REMIX,videoId=videoId,playlistId="RDAMVM$videoId",params="wAEB",continuation=token)
        check(response.status.value<400) {string(R.string.up_next_is_unavailable)}
        val root=json.parseToJsonElement(response.bodyAsText())
        var tracks=parseQueueTracks(root)
        var next=catalogContinuation(root)
        if(tracks.none {it.videoId!=videoId} && next!=null && next!=token) {
            val more=innerTube.next(YouTubeClient.WEB_REMIX,videoId=videoId,playlistId="RDAMVM$videoId",params="wAEB",continuation=next)
            if(more.status.value<400) {
                val data=json.parseToJsonElement(more.bodyAsText());tracks=(tracks+parseQueueTracks(data)).distinctBy(ProviderTrack::videoId);next=catalogContinuation(data)
            }
        }
        if(tracks.none {it.videoId!=videoId}) {
            val related=relatedBrowseId(root)?.let {id->try {requestCatalog(id)}
                catch(e:CancellationException){throw e} catch(_:Exception){null}}
            tracks=(tracks+related?.cards.orEmpty().mapNotNull {it.track()}).distinctBy(ProviderTrack::videoId)
        }
        if(tracks.none {it.videoId!=videoId}) {
            // Some sessions expose a single-item radio; artist catalogue rows still come from YouTube Music.
            val artist=tracks.firstOrNull {it.videoId==videoId}?.artist
            if(!artist.isNullOrBlank()) tracks=(tracks+searchPage(playerArtistLabel(artist)).getOrThrow().tracks).distinctBy(ProviderTrack::videoId)
        }
        check(revision==authRevision) {string(R.string.account_changed_while_loading_up_next)}
        // The token just used is never handed back as the next one: the caller would ask for the same page for ever.
        tracks to next?.takeUnless {it==token}
    }
    suspend fun liveStations(category:String="Featured"):Result<List<ProviderHomeSection>> = providerResult {
        feedCache.get(feedKey(radioFeedKey(category))) {liveStationsFresh(category).getOrThrow()}
    }
    private val radioGate=kotlinx.coroutines.sync.Semaphore(3)
    private suspend fun liveStationsFresh(category:String):Result<List<ProviderHomeSection>> = providerResult {
        val shelves=coroutineScope {channelsForRadio(category).map {channel->async(dependencies.dispatchers.io) {
            feedCache.get(feedKey("radio-channel:${channel.handle}")) {RadioShelf(loadRadioChannel(channel))}.shelf
        }}.awaitAll().filterNotNull()}
        val unique=uniqueRadioShelves(shelves)
        if(category=="Featured") check(unique.isNotEmpty()) {string(R.string.no_live_stations_could_be_reached_try)}
        unique
    }
    private data class RadioShelf(val shelf:ProviderHomeSection?)
    private suspend fun loadRadioChannel(channel:RadioChannel):ProviderHomeSection? {
            radioGate.acquire()
            return try {withTimeoutOrNull(18_000) {
                val response=http.get("https://www.youtube.com/@${channel.handle}/streams") {
                    header("User-Agent","Mozilla/5.0");header("Accept-Language","en-US,en;q=0.9")
                }
                if(response.status.value>=400) null else {
                    val root=youtubeInitialData(response.bodyAsText())
                    val cards=root?.let(::parseCatalogCards).orEmpty().filter {it.kind==CatalogKind.Live && it.videoId!=null}.map {it.copy(subtitle=channel.name)}
                    if(cards.isEmpty()) null else ProviderHomeSection(channel.name,cards)
                }
            }} catch(e:CancellationException) {throw e} catch(_:Exception) {null} finally {radioGate.release()}
    }

    suspend fun search(query: String): Result<List<ProviderTrack>> =
        searchPage(query).map(ProviderSearchPage::tracks)

    suspend fun lyricsFor(videoId: String, durationMs: Long): LyricsDocument? = withContext(dependencies.dispatchers.io) {
        if (!videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) return@withContext null
        val revision = authRevision
        val next = innerTube.next(YouTubeClient.WEB_REMIX, videoId = videoId)
        if (next.status.value >= 400) return@withContext null
        val root = json.parseToJsonElement(next.bodyAsText())
        fun lyricsId(node: JsonElement): String? = when (node) {
            is JsonObject -> (node["browseId"] as? JsonPrimitive)?.content?.takeIf { it.startsWith("MPLY") }
                ?: node.values.firstNotNullOfOrNull(::lyricsId)
            is JsonArray -> node.firstNotNullOfOrNull(::lyricsId)
            else -> null
        }
        val browseId = lyricsId(root) ?: return@withContext null
        // Native timed lyrics are public metadata. Keep this request free of web-only
        // nullable/account fields, which make the mobile endpoint reject the body.
        // The shared HTTP client has no default account headers; InnerTube adds them
        // only to its own signed requests. Song lookup and web fallback stay signed.
        val synced = try {
            val locale = innerTube.locale
            val response = http.post("https://music.youtube.com/youtubei/v1/browse") {
                contentType(ContentType.Application.Json)
                setBody(TextContent(youtubeTimedLyricsBody(browseId, locale.hl, locale.gl).toString(), ContentType.Application.Json))
            }
            if (response.status.value >= 400 || revision != authRevision) null
            else parseYoutubeLyrics(json.parseToJsonElement(response.bodyAsText()), durationMs)
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        if (revision != authRevision) return@withContext null
        if (synced?.timed == true) return@withContext synced
        val web = try {
            val response = innerTube.browse(YouTubeClient.WEB_REMIX, browseId = browseId, setLogin = signedIn)
            if (response.status.value >= 400 || revision != authRevision) null
            else parseYoutubeLyrics(json.parseToJsonElement(response.bodyAsText()), durationMs)
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        if (revision != authRevision) null else listOfNotNull(synced, web).maxByOrNull { it.quality }
    }

    suspend fun homeSections()=homeSections("")
    suspend fun homeSections(mood:String):Result<List<ProviderHomeSection>> = providerResult {
        feedCache.get(feedKey("home:$mood")) {
            if(mood.isBlank()) homeSectionsFresh().getOrThrow() else {
                if(homeMoods.isEmpty()) homeSections().getOrThrow()
                val tag=homeMoods.firstOrNull {it.title==mood}?:error("This mood is no longer available")
                val page=requestCatalog(tag.browseId,params=tag.params)
                page.shelves.ifEmpty {listOf(ProviderHomeSection(mood,page.cards))}
            }
        }
    }
    private suspend fun homeSectionsFresh(): Result<List<ProviderHomeSection>> = withContext(dependencies.dispatchers.io) {
        try {
            val revision = authRevision
            val response = innerTube.browse(YouTubeClient.WEB_REMIX,
                browseId = "FEmusic_home", setLogin = signedIn)
            if (response.status.value >= 400) return@withContext Result.failure(
                IllegalStateException(string(R.string.youtube_music_home_is_unavailable_http_x, response.status.value)))
            val root = json.parseToJsonElement(response.bodyAsText())
            val sections = parseHomeSections(root).toMutableList()
            var continuation=catalogContinuation(root)
            var homePages=0
            while(sections.none {it.title.contains("forgotten fav",true)} && continuation!=null && homePages<3) {
                val token=continuation;homePages++
                val more=innerTube.browse(YouTubeClient.WEB_REMIX,browseId="FEmusic_home",continuation=token,setLogin=signedIn)
                if(more.status.value>=400) break
                val moreRoot=json.parseToJsonElement(more.bodyAsText())
                sections.addAll(parseHomeSections(moreRoot).filter {s->sections.none {it.title==s.title}})
                continuation=catalogContinuation(moreRoot)?.takeUnless {it==token}
            }
            if (revision == authRevision) {cachedHome = sections;podcastEndpoint=catalogBrowseFilter(root,"Podcasts");homeMoods=parseHomeMoods(root)
                runtimeScope.launch {suspendCatching {
                    val extra=innerTube.browse(YouTubeClient.WEB_REMIX,browseId="FEmusic_moods_and_genres",setLogin=signedIn)
                    if(extra.status.value<400 && revision==authRevision) homeMoods=(homeMoods+parseHomeMoods(json.parseToJsonElement(extra.bodyAsText()))).distinctBy {it.title}
                }}}
            val serverMessage = findRenderer(root, "messageRenderer")?.let { message ->
                providerText(message["text"]).ifBlank { providerText(message["message"]) }
            }
            if (sections.isEmpty()) return@withContext Result.failure(
                IllegalStateException(serverMessage?.takeIf(String::isNotBlank)
                    ?: string(R.string.youtube_music_returned_no_home_recommendatio)))
            if (authRevision != revision) return@withContext Result.failure(
                IllegalStateException(string(R.string.account_changed_while_loading_home)))
            Result.success(sections)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(IllegalStateException(
                if (error is java.net.UnknownHostException)
                    string(R.string.cannot_reach_music_youtube_com_on_this)
                else string(R.string.could_not_load_youtube_music_home_x, error::class.simpleName)))
        }
    }

    suspend fun profile():Result<ProviderProfile?> = providerResult {
        feedCache.get(feedKey("profile")) {Unit to profileFresh().getOrThrow()}.second.also {accountProfile=it}
    }
    private suspend fun profileFresh(): Result<ProviderProfile?> = withContext(dependencies.dispatchers.io) {
        if (!signedIn) return@withContext Result.success(null)
        try {
            val revision = authRevision
            val response = innerTube.accountMenu(YouTubeClient.WEB_REMIX)
            var profile = if (response.status.value < 400)
                parseAccountProfile(json.parseToJsonElement(response.bodyAsText())) else null
            if (profile == null || profile.name.isBlank() || profile.artworkUrl == null) {
                try {
                    val accounts = innerTube.accountsList(YouTubeClient.WEB_REMIX)
                    if (accounts.status.value < 400) {
                        val selected = parseAccountProfile(json.parseToJsonElement(accounts.bodyAsText()))
                        if (selected != null) profile = ProviderProfile(
                            profile?.name?.takeIf(String::isNotBlank) ?: selected.name,
                            profile?.artworkUrl ?: selected.artworkUrl)
                    }
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { /* Keep a valid partial account profile. */ }
            }
            if (authRevision != revision || !signedIn) return@withContext Result.failure(
                IllegalStateException(string(R.string.account_changed_while_loading_profile)))
            diagnosticLogcat("RiffProfile", "loaded name=${!profile?.name.isNullOrBlank()} photo=${profile?.artworkUrl != null}")
            accountProfile = profile
            Result.success(profile)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            Result.failure(IllegalStateException(
                string(R.string.could_not_load_account_profile_x, error::class.simpleName)))
        }
    }

    /** Read-only account library browse. This does not mutate YouTube Music likes. */
    suspend fun likedSongsPage(nextToken: String? = null): Result<ProviderSearchPage> =
        likedSongsRoot(nextToken).map { root -> ProviderSearchPage(parseTracks(root, limit = 2000), findContinuation(root)) }

    /**
     * Q18-038: how many songs Liked music holds: the number its header states, or every page counted (paced like any
     * other library read). [onCount] hears the count so far. An account change on the way fails it.
     */
    suspend fun likedSongsCount(onCount: (Int) -> Unit = {}): Result<LibraryCount> = withContext(dependencies.dispatchers.io) {
        val revision = authRevision
        try {
            val root = likedSongsRoot(null).getOrThrow()
            collectionHeaderSongCount(root)?.let { onCount(it); return@withContext Result.success(LibraryCount(it, true)) }
            val count = countThroughPages(ProviderSearchPage(parseTracks(root, limit = 2000), findContinuation(root)),
                LIKED_SONGS_COUNT_LIMIT, { token -> likedSongsPage(token).getOrThrow() }) { so ->
                if (authRevision == revision) onCount(so)
            }
            if (authRevision != revision || !signedIn) return@withContext Result.failure(
                IllegalStateException(string(R.string.account_changed_while_loading_the_library)))
            Result.success(count)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend fun likedSongsRoot(nextToken: String?): Result<JsonElement> =
        withContext(dependencies.dispatchers.io) {
            if (!signedIn) return@withContext Result.failure(
                IllegalStateException(string(R.string.sign_in_to_view_your_youtube_music)))
            val revision = authRevision
            try {
                paceSearch()
                val response = innerTube.browse(YouTubeClient.WEB_REMIX,
                    browseId = "VLLM", continuation = nextToken, setLogin = true)
                if (response.status.value >= 400) return@withContext Result.failure(
                    IllegalStateException(string(R.string.account_library_is_unavailable_http_x, response.status.value)))
                val root = json.parseToJsonElement(response.bodyAsText())
                if (authRevision != revision || !signedIn) return@withContext Result.failure(
                    IllegalStateException(string(R.string.account_changed_while_loading_the_library)))
                Result.success(root)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(IllegalStateException(
                    string(R.string.could_not_read_the_account_library_x, error::class.simpleName)))
            }
        }

    suspend fun searchPage(query: String, nextToken: String? = null): Result<ProviderSearchPage> = withContext(dependencies.dispatchers.io) {
        try {
            val revision = authRevision
            val useLogin = signedIn
            val cleanQuery = query.trim().take(100)
            val key = cleanQuery.lowercase() + "\u0000" + nextToken.orEmpty()
            val cached = synchronized(cacheLock) { searchCache[key] }
            if (cached != null && authRevision == revision &&
                dependencies.clocks.monotonicMillis() - cached.first < 5 * 60_000L) {
                return@withContext Result.success(cached.second)
            }
            paceSearch()
            val response = innerTube.search(YouTubeClient.WEB_REMIX,
                query = cleanQuery,
                params = songsFilter,
                continuation = nextToken, setLogin = useLogin)
            if (response.status.value == 429) return@withContext Result.failure(
                IllegalStateException(string(R.string.youtube_music_search_is_temporarily_rate_lim)))
            if (response.status.value >= 400) return@withContext Result.failure(
                IllegalStateException(string(R.string.youtube_music_search_is_unavailable_http_x, response.status.value)))
            var root = json.parseToJsonElement(response.bodyAsText())
            var mixedShelves = 0
            var mixedRows = 0
            var mixedSongTracks = emptyList<ProviderTrack>()
            var liveChipFound = false
            var liveFilterTried = false
            if (nextToken == null && parseTracks(root).isEmpty()) {
                paceSearch()
                val mixed = innerTube.search(YouTubeClient.WEB_REMIX,
                    query = cleanQuery, setLogin = useLogin)
                if (mixed.status.value == 429) return@withContext Result.failure(
                    IllegalStateException(string(R.string.youtube_music_search_is_temporarily_rate_lim)))
                if (mixed.status.value < 400) {
                    val mixedRoot = json.parseToJsonElement(mixed.bodyAsText())
                    mixedShelves = countKey(mixedRoot, "musicShelfRenderer")
                    mixedRows = parseTracks(mixedRoot).size
                    mixedSongTracks = parseSongShelfTracks(mixedRoot)
                    val liveFilter = findSongsFilter(mixedRoot)
                    liveChipFound = !liveFilter.isNullOrBlank()
                    if (!liveFilter.isNullOrBlank() && liveFilter != songsFilter) {
                        liveFilterTried = true
                        paceSearch()
                        val refreshed = innerTube.search(YouTubeClient.WEB_REMIX,
                            query = cleanQuery, params = liveFilter, setLogin = useLogin)
                        if (refreshed.status.value == 429) return@withContext Result.failure(
                            IllegalStateException(string(R.string.youtube_music_search_is_temporarily_rate_lim)))
                        if (refreshed.status.value < 400)
                            root = json.parseToJsonElement(refreshed.bodyAsText())
                    }
                }
            }
            val filteredTracks = parseTracks(root)
            if (authRevision != revision) return@withContext Result.failure(
                IllegalStateException(string(R.string.account_changed_while_searching)))
            val useMixedSongs = nextToken == null && filteredTracks.isEmpty() &&
                mixedSongTracks.isNotEmpty()
            val tracks = if (useMixedSongs) mixedSongTracks else filteredTracks
            if (nextToken == null && tracks.isEmpty() && countKey(root, "musicShelfRenderer") == 0) {
                diagnosticLogcat("RiffSearch", "no_song_rows filtered_shelves=0 mixed_shelves=$mixedShelves mixed_rows=$mixedRows mixed_song_rows=${mixedSongTracks.size} songs_chip=$liveChipFound dynamic_retry=$liveFilterTried")
                Result.failure(IllegalStateException(string(R.string.song_catalog_unavailable_on_this_device)))
            } else {
                val page = ProviderSearchPage(tracks,
                    if (useMixedSongs) null else findContinuation(root))
                synchronized(cacheLock) {
                    if (authRevision == revision)
                        searchCache[key] = dependencies.clocks.monotonicMillis() to page
                }
                Result.success(page)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(IllegalStateException(string(R.string.youtube_music_search_is_unavailable_x, error::class.simpleName)))
        }
    }

    private data class VideoPair(val id:String?)
    suspend fun videoFor(track:ProviderTrack):Result<String?> = providerResult {
        feedCache.get(feedKey("video-pair:${track.videoId}")) {
            val response=innerTube.next(YouTubeClient.WEB_REMIX,videoId=track.videoId,playlistId="RDAMVM${track.videoId}")
            check(response.status.value<400) {string(R.string.could_not_check_the_youtube_music_video)}
            VideoPair(musicVideoPair(json.parseToJsonElement(response.bodyAsText()),track))
        }.id
    }

    private suspend fun paceSearch() {
        searchPacer.withLock {
            val waitMs = (lastSearchMs + 800L - dependencies.clocks.monotonicMillis()).coerceAtLeast(0L)
            if (waitMs > 0L) dependencies.wait(waitMs)
            lastSearchMs = dependencies.clocks.monotonicMillis()
        }
    }

    private fun findSongsFilter(node: JsonElement): String? = when (node) {
        is JsonObject -> {
            val chip = node["chipCloudChipRenderer"] as? JsonObject
            val label = (chip?.get("text") as? JsonObject)?.get("runs") as? JsonArray
            val name = label?.joinToString("") { run ->
                ((run as? JsonObject)?.get("text"))?.textOrNull.orEmpty()
            }
            val endpoint = chip?.get("navigationEndpoint") as? JsonObject
            val search = endpoint?.get("searchEndpoint") as? JsonObject
            val params = search?.get("params")?.textOrNull
            if (name.equals("Songs", ignoreCase = true) && !params.isNullOrBlank()) params
            else node.values.firstNotNullOfOrNull(::findSongsFilter)
        }
        is JsonArray -> node.firstNotNullOfOrNull(::findSongsFilter)
        else -> null
    }

    private fun providerText(node: JsonElement?): String = when (node) {
        is JsonObject -> node["simpleText"]?.let { it.textOrNull }
            ?: (node["runs"] as? JsonArray)?.joinToString("") {
                (it as? JsonObject)?.get("text")?.textOrNull.orEmpty()
            }.orEmpty()
        else -> node?.let { it.textOrNull }.orEmpty()
    }

    private fun findRenderer(node: JsonElement, key: String): JsonObject? = when (node) {
        is JsonObject -> (node[key] as? JsonObject)
            ?: node.values.firstNotNullOfOrNull { findRenderer(it, key) }
        is JsonArray -> node.firstNotNullOfOrNull { findRenderer(it, key) }
        else -> null
    }

    private fun providerValue(node: JsonElement?, key: String): String? = when (node) {
        is JsonObject -> node[key]?.let {
            it.textOrNull
        } ?: node.values.firstNotNullOfOrNull { providerValue(it, key) }
        is JsonArray -> node.firstNotNullOfOrNull { providerValue(it, key) }
        else -> null
    }

    internal fun parseHomeCard(node: JsonElement): ProviderHomeCard? {
        return parseCatalogCards(node).firstOrNull()
    }

    private fun parseHomeSections(root: JsonElement): List<ProviderHomeSection> {
        val sections = mutableListOf<ProviderHomeSection>()
        fun visit(node: JsonElement) {
            when (node) {
                is JsonObject -> {
                    val shelf = listOf("musicCarouselShelfRenderer", "musicShelfRenderer",
                        "musicImmersiveCarouselShelfRenderer")
                        .firstNotNullOfOrNull { node[it] as? JsonObject }
                    if (shelf != null) {
                        val header = shelf["header"] as? JsonObject
                        val title = providerText((header?.get("musicCarouselShelfBasicHeaderRenderer")
                            as? JsonObject)?.get("title")).ifBlank {
                            providerText(shelf["title"])
                        }
                        val cards = (shelf["contents"] as? JsonArray)
                            ?.mapNotNull(::parseHomeCard)?.distinctBy {
                                it.videoId ?: it.browseId ?: it.title
                            }.orEmpty()
                        if (title.isNotBlank() && cards.isNotEmpty())
                            sections += ProviderHomeSection(title, cards)
                    }
                    node.values.forEach(::visit)
                }
                is JsonArray -> node.forEach(::visit)
                else -> Unit
            }
        }
        visit(root)
        return sections.distinctBy(ProviderHomeSection::title)
    }

    private fun parseTracks(root: JsonElement, limit: Int = 30): List<ProviderTrack> {
        return parseCatalogCards(root).mapNotNull { it.track() }.distinctBy(ProviderTrack::videoId).take(limit)
    }

    private fun parseSongShelfTracks(node: JsonElement): List<ProviderTrack> = when (node) {
        is JsonObject -> {
            val shelf = node["musicShelfRenderer"] as? JsonObject
            val title = shelf?.get("title") as? JsonObject
            val runs = title?.get("runs") as? JsonArray
            val name = runs?.joinToString("") { run ->
                (run as? JsonObject)?.get("text")?.textOrNull.orEmpty()
            }
            if (name.equals("Songs", ignoreCase = true) && shelf != null) parseTracks(shelf)
            else node.values.flatMap(::parseSongShelfTracks).distinctBy(ProviderTrack::videoId)
        }
        is JsonArray -> node.flatMap(::parseSongShelfTracks).distinctBy(ProviderTrack::videoId)
        else -> emptyList()
    }

    private fun countKey(node: JsonElement, key: String): Int = when (node) {
        is JsonObject -> (if (key in node) 1 else 0) + node.values.sumOf { countKey(it, key) }
        is JsonArray -> node.sumOf { countKey(it, key) }
        else -> 0
    }

    private fun findContinuation(node: JsonElement): String? = when (node) {
        is JsonObject -> {
            val continuations = node["continuations"] as? JsonArray
            val shelfToken = continuations?.firstNotNullOfOrNull { entry ->
                val data = (entry as? JsonObject)?.get("nextContinuationData") as? JsonObject
                data?.get("continuation")?.textOrNull
            }
            if (!shelfToken.isNullOrBlank()) shelfToken else {
                val item = node["continuationItemRenderer"] as? JsonObject
                val endpoint = item?.get("continuationEndpoint") as? JsonObject
                val command = endpoint?.get("continuationCommand") as? JsonObject
                val token = command?.get("token")?.textOrNull
                if (!token.isNullOrBlank()) token else node.values.firstNotNullOfOrNull(::findContinuation)
            }
        }
        is JsonArray -> node.firstNotNullOfOrNull(::findContinuation)
        else -> null
    }
}
