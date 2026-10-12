package com.musicglass.personal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

/**
 * Q10: what the parsers make of each kind of provider answer. The fixtures are invented and generated
 * (scripts/quality/generate_provider_fixtures.py); they show which fields the parsers read and what they do
 * with them. They are not recordings and do not show what YouTube Music sends today.
 */
class ProviderFixtureTest {
    private val index=providerFixtureJson("index.json") as JsonObject
    private val names=(index["fixtures"] as JsonObject).keys.sorted()
    private fun page(name:String)=catalogPage(providerFixtureJson(name))
    private fun song(n:Int)="fxsong%05d".format(n)
    private val artist="UCfixtureArtist00000001"
    private val album="MPREb_fixtureAlbum01"
    private val owned="PLfixtureOwned0000000001"

    // ---- the fixtures themselves

    @Test fun everyFixtureIsTheGeneratedOneAndIsListed() {
        assertEquals(30,names.size)
        names.forEach {name->
            val bytes=requireNotNull(javaClass.getResourceAsStream("/quality/provider/$name")) {"$name is listed but missing"}.use {it.readBytes()}
            val digest=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
            assertEquals("$name was changed by hand; change the generator and run it",((index["fixtures"] as JsonObject)[name] as JsonPrimitive).content,digest)
            assertTrue("$name is small",bytes.size<32_768)
        }
    }
    @Test fun noFixtureHoldsACredentialAnAccountValueOrASignedAddress() {
        val forbidden=listOf(
            "SAPISID","HSID","SSID","__Secure","LOGIN_INFO","Authorization","Bearer ","ya29.","AIza","Cookie","X-Goog","X-Origin",
            "visitorData","onBehalfOfUser","dataSyncId","delegatedSessionId","googlevideo","signature=","&sig=","expire=","pot=","/videoplayback",
            "obfuscatedGaiaId","gaiaId","channelCreationToken","trackingParams","clickTrackingParams")
        val address=Regex("https?://[^\"\\\\ ]+");val mail=Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
        val videoId=Regex("\"videoId\": \"([^\"]*)\"");val channel=Regex("\"(UC[A-Za-z0-9_-]{10,})\"")
        names.forEach {name->
            val text=providerFixture(name)
            forbidden.forEach {assertFalse("$name contains $it",text.contains(it,ignoreCase=true))}
            address.findAll(text).forEach {
                val uri=java.net.URI(it.value)
                assertTrue("$name: ${uri.host}",uri.host in setOf("lh3.googleusercontent.com","yt3.ggpht.com"))
                assertTrue("$name: an invented address",uri.path.startsWith("/fixture-"));assertNull(uri.query)
            }
            mail.findAll(text).forEach {assertTrue("$name: ${it.value}",it.value.endsWith("@example.invalid"))}
            videoId.findAll(text).forEach {assertTrue("$name: ${it.groupValues[1]}",it.groupValues[1].startsWith("fx") || it.groupValues[1]=="short")}
            channel.findAll(text).forEach {assertTrue("$name: ${it.groupValues[1]}",it.groupValues[1].startsWith("UCfixture"))}
            assertTrue("$name is ASCII",text.all {it.code<128})
        }
    }

    // ---- search: one answer for each kind

    @Test fun songResultsCarryArtistAlbumArtworkAndTheNextPage() {
        val page=page("search-songs.json")
        assertEquals(listOf(song(1),song(2)),page.cards.map {it.videoId})
        val first=page.cards[0]
        assertEquals(CatalogKind.Song,first.kind);assertEquals("Fixture Song 1",first.title);assertEquals("Fixture Artist",first.subtitle)
        assertEquals(artist,first.artistId);assertEquals(album,first.albumId);assertEquals("Fixture Album",first.album)
        assertEquals("https://lh3.googleusercontent.com/fixture-song-1=w120-h120",first.artworkUrl?.substringBefore("=w")+"=w120-h120")
        assertFalse(first.musicVideo);assertEquals("fixture-feedback-song-1",first.notInterestedToken);assertNull(page.cards[1].notInterestedToken)
        assertEquals("fixture-search-songs-2",page.next)
        val track=first.track()!!
        assertEquals(ProviderTrack(song(1),"Fixture Song 1","Fixture Artist",first.artworkUrl,artist,album,"Fixture Album"),track)
        val more=page("search-songs-page-2.json")
        assertEquals(listOf(song(3)),more.cards.map {it.videoId});assertNull(more.next)
    }
    @Test fun videoResultsSayWhichAreOfficial() {
        val cards=page("search-videos.json").cards
        assertEquals(listOf(true,true),cards.map {it.musicVideo})
        assertEquals("Only the provider's own marking makes a video official",listOf(true,false),cards.map {it.officialMusicVideo})
        assertTrue(cards.all {it.kind==CatalogKind.Song && it.videoId!=null})
    }
    @Test fun albumArtistPlaylistAndPodcastResultsKeepTheirKindAndWhereTheyLead() {
        fun only(name:String)=page(name).cards.single()
        only("search-albums.json").let {assertEquals(CatalogKind.Album,it.kind);assertEquals(album,it.browseId);assertNull(it.videoId);assertNull(it.track())
            assertEquals("Album • Fixture Artist • 2020",it.subtitle)}
        only("search-artists.json").let {assertEquals(CatalogKind.Artist,it.kind);assertEquals(artist,it.browseId)}
        only("search-playlists.json").let {assertEquals(CatalogKind.Playlist,it.kind);assertEquals("VLPLfixturePublic000000001",it.browseId)}
        val podcasts=page("search-podcasts.json").cards
        assertEquals(listOf(CatalogKind.Podcast,CatalogKind.Episode),podcasts.map {it.kind})
        assertEquals("MPSPPLfixtureShow0000001",podcasts[0].browseId)
        assertFalse("An episode is not music",podcasts[1].track()!!.isMusic)
    }
    @Test fun theUnfilteredAnswerSuppliesTheProvidersOwnFilters() {
        val root=providerFixtureJson("search-mixed.json")
        assertEquals("fixture-songs-filter",catalogSearchFilter(root,"Songs"));assertEquals("fixture-albums-filter",catalogSearchFilter(root,"albums"))
        assertNull("A filter the provider did not offer is not made up",catalogSearchFilter(root,"Playlists"))
        assertEquals(listOf(CatalogKind.Song,CatalogKind.Album,CatalogKind.Artist),catalogPage(root).cards.map {it.kind})
    }
    @Test fun anAnswerWithNoResultsIsAnEmptyPageNotAFailure() {
        val page=page("search-empty.json")
        assertEquals(emptyList<ProviderHomeCard>(),page.cards);assertNull(page.next);assertNull(providerEnvelopeError(providerFixtureJson("search-empty.json")))
    }

    // ---- Home

    @Test fun homeShelvesKeepTheirOrderAndAnEmptyShelfIsLeftOut() {
        val root=providerFixtureJson("home.json");val page=catalogPage(root)
        assertEquals(listOf("Quick picks","Albums for you"),page.shelves.map {it.title})
        assertEquals(listOf(song(1),song(2)),page.shelves[0].cards.map {it.videoId})
        assertEquals(listOf(CatalogKind.Album,CatalogKind.Playlist),page.shelves[1].cards.map {it.kind})
        assertEquals("fixture-home-2",catalogContinuation(root))
        assertEquals(listOf(HomeMood("Relax","FEmusic_home","fixture-mood-relax")),parseHomeMoods(root))
        assertEquals("FEmusic_non_music_audio" to "fixture-podcasts",catalogBrowseFilter(root,"Podcasts"))
        assertEquals(listOf("Forgotten favourites"),page("home-page-2.json").shelves.map {it.title})
    }

    // ---- album and playlists

    @Test fun anAlbumHasItsHeaderItsArtistAndItsTracksInOrder() {
        val page=page("album.json")
        assertEquals("Fixture Album",page.title);assertEquals("Album • 2020",page.subtitle)
        assertEquals("Fixture Artist",page.artistName);assertEquals(artist,page.artistId)
        assertEquals(listOf(song(31),song(32),song(33)),page.albumTracks.map {it.videoId})
        assertEquals(listOf("Album Track 1","Album Track 2","Album Track 3"),page.albumTracks.map {it.title})
        assertFalse(page.editable);assertNull(page.collectionNext);assertNull(page.truncation);assertTrue(page.sortEndpoints.isEmpty())
        assertEquals("OLAK5uy_fixtureAlbum000000000001",catalogValue(catalogRenderer(providerFixtureJson("album.json"),"musicResponsiveHeaderRenderer"),"playlistId"))
    }
    @Test fun anOwnedPlaylistIsEditableAndItsPagesFollowOneAnother() {
        val first=page("playlist-owned.json")
        assertTrue(first.editable);assertEquals("Fixture Owned Playlist",first.title)
        assertEquals(listOf("fixture-set-1","fixture-set-2"),first.albumTracks.map {it.setVideoId})
        assertEquals("fixture-owned-page-2",first.collectionNext)
        val second=page("playlist-owned-page-2.json")
        assertEquals(listOf(song(3)),second.albumTracks.map {it.videoId});assertEquals("fixture-owned-page-3",second.collectionNext)
        val third=page("playlist-owned-page-3.json")
        assertEquals("The appended-items form is read as well",listOf("fixture-set-4"),third.albumTracks.map {it.setVideoId});assertNull(third.collectionNext)
    }
    @Test fun theSameSongTwiceInAPlaylistIsTwoEntries() {
        val tracks=page("playlist-owned-duplicates.json").albumTracks
        assertEquals(listOf(song(1),song(1)),tracks.map {it.videoId});assertEquals(listOf("fixture-set-1","fixture-set-9"),tracks.map {it.setVideoId})
    }
    @Test fun someoneElsesPlaylistIsReadableAndNotEditable() {
        val page=page("playlist-readonly.json")
        assertFalse(page.editable);assertEquals(2,page.albumTracks.size);assertTrue(page.sortEndpoints.isEmpty())
        assertEquals("Entry identifiers are there, but they do not make it editable",listOf("fixture-public-set-1","fixture-public-set-5"),page.albumTracks.map {it.setVideoId})
    }
    @Test fun theSortMenuKeepsTheProvidersCommandsExactlyAndDropsAnythingThatIsNotASortPreference() {
        val sorts=page("playlist-owned.json").sortEndpoints
        assertEquals(setOf(CollectionSort.Original,CollectionSort.Newest,CollectionSort.Oldest,CollectionSort.Title),sorts.keys)
        assertEquals(CollectionSort.Original,page("playlist-owned.json").selectedSort)
        val newest=sorts.getValue(CollectionSort.Newest).playlistEdit!!
        assertEquals("fixture-sort-newest",newest.params);assertEquals(owned,newest.playlistId)
        assertEquals("""{"action":"ACTION_SET_PLAYLIST_VIDEO_ORDER","playlistVideoOrder":1}""",newest.action.toString())
        assertNull("No parameters are invented where the provider sent none",sorts.getValue(CollectionSort.Oldest).playlistEdit!!.params)
        assertTrue(newest.validFor("VL$owned"));assertFalse("A command for one playlist is not sent for another",newest.validFor("VLPLfixturePublic000000001"))
        // "Artist" was offered with a command that removes a song. A label does not make it a sort.
        assertFalse(sorts.containsKey(CollectionSort.Artist));assertFalse(sorts.containsKey(CollectionSort.TopVoted))
        assertFalse(validPlaylistSortAction(Json.parseToJsonElement("""{"action":"ACTION_REMOVE_VIDEO","setVideoId":"fixture-set-1"}""") as JsonObject))
        assertFalse(validPlaylistSortAction(Json.parseToJsonElement("""{"action":"ACTION_SET_PLAYLIST_VIDEO_ORDER","playlistVideoOrder":1,"addedVideoId":"fxsong00001"}""") as JsonObject))
        assertFalse(validPlaylistSortAction(Json.parseToJsonElement("""{"action":"ACTION_SET_PLAYLIST_VIDEO_ORDER","playlistVideoOrder":99}""") as JsonObject))
    }
    @Test fun libraryPlaylistsAreListedWithLikedMusicAmongThem() {
        val cards=page("library-playlists.json").cards
        assertEquals(listOf("VLLM","VL$owned","VLPLfixturePublic000000001"),cards.map {it.browseId});assertTrue(cards.all {it.kind==CatalogKind.Playlist})
    }

    // ---- account, watch queue, lyrics

    @Test fun theAccountMenuGivesANameAndAPictureAndNothingElse() {
        assertEquals(ProviderProfile("Fixture Listener","https://yt3.ggpht.com/fixture-avatar=s108"),
            parseAccountProfile(providerFixtureJson("account-menu.json"))!!.let {it.copy(artworkUrl=it.artworkUrl?.substringBefore("=s")+"=s108")})
        val signedOut=providerFixtureJson("account-menu-signed-out.json")
        assertNull(parseAccountProfile(signedOut));assertEquals(401,providerEnvelopeError(signedOut)!!.code)
        assertTrue(isAuthFailure(providerEnvelopeError(signedOut)))
    }
    @Test fun theWatchQueuePairsASongWithItsOfficialVideoOnly() {
        val track=ProviderTrack(song(1),"Fixture Song 1","Fixture Artist")
        assertEquals("fxvideo0001",musicVideoPair(providerFixtureJson("watch-next.json"),track))
        assertNull("A fan upload is not the official video",musicVideoPair(providerFixtureJson("watch-next-unofficial-video.json"),track))
        assertNull("Another song's video is not this song's",musicVideoPair(providerFixtureJson("watch-next.json"),track.copy(videoId=song(2),title="Fixture Song 2")))
    }
    @Test fun theWatchQueueGivesUpNextLikeStateFeedbackAndWhereTheLyricsAre() {
        val root=providerFixtureJson("watch-next.json")
        assertEquals("The video copy is not a second entry in Up next",listOf(song(1),song(2),song(3)),parseQueueTracks(root).map {it.videoId})
        assertEquals("fixture-radio-2",catalogContinuation(root))
        assertEquals(true,parseLikeStatus(root))
        assertEquals("fixture-feedback-song-1",notInterestedTokenFor(root,song(1)));assertNull(notInterestedTokenFor(root,song(2)))
        assertEquals("MPTRt_fixtureRelated01",relatedBrowseId(root))
        assertNull(parseLikeStatus(providerFixtureJson("watch-next-unofficial-video.json")))
    }
    @Test fun timedAndPlainLyricsAreToldApart() {
        val timed=parseYoutubeLyrics(providerFixtureJson("lyrics-timed.json"),10_000)!!
        assertTrue(timed.timed);assertEquals(listOf(0L,1500L,3000L),timed.lines.map {it.startMs})
        assertEquals("First fixture line",timed.lines[0].text)
        val plain=parseYoutubeLyrics(providerFixtureJson("lyrics-plain.json"),10_000)!!
        assertFalse(plain.timed);assertEquals(listOf("First fixture line","Second fixture line"),plain.lines.map {it.text})
        assertNull(parseYoutubeLyrics(providerFixtureJson("search-empty.json"),10_000))
    }

    // ---- answers to a change

    @Test fun aChangeIsAcknowledgedOnlyByAnAnswerThatSaysSo() {
        val ok=providerFixture("edit-succeeded.json");val failed=providerFixture("edit-failed.json");val envelope=providerFixture("error-envelope.json")
        assertEquals(MutationOutcome.Acknowledged,playlistEditOutcome(200,ok))
        assertEquals(MutationOutcome.Rejected,playlistEditOutcome(200,failed));assertEquals(MutationOutcome.Rejected,playlistEditOutcome(200,envelope))
        // HTTP 200 with nothing that says "succeeded" is not a success. It is not a failure either.
        listOf("","{}","not json","[]","""{"responseContext":{}}""").forEach {assertEquals(it,MutationOutcome.Unknown,playlistEditOutcome(200,it))}
        assertEquals(MutationOutcome.Rejected,playlistEditOutcome(403,ok));assertEquals(MutationOutcome.Rejected,playlistEditOutcome(429,""))
        listOf(408,500,502,503).forEach {assertEquals(MutationOutcome.Unknown,playlistEditOutcome(it,ok))}
        assertTrue(playlistEditAccepted(ok));assertFalse(playlistEditAccepted(failed))

        assertEquals(MutationOutcome.Acknowledged,likeOutcome(200,providerFixture("like-acknowledged.json")))
        listOf("","{}","not json").forEach {assertEquals(it,MutationOutcome.Unknown,likeOutcome(200,it))}
        assertEquals(MutationOutcome.Rejected,likeOutcome(200,envelope));assertEquals(MutationOutcome.Rejected,likeOutcome(401,""));assertEquals(MutationOutcome.Unknown,likeOutcome(500,""))
    }
    @Test fun aRequestThatNeverLeftIsRejectedAndOneThatMayHaveArrivedIsUnknown() {
        assertEquals(MutationOutcome.Rejected,transportOutcome(java.net.UnknownHostException("fixture")))
        assertEquals(MutationOutcome.Rejected,transportOutcome(RuntimeException(java.net.ConnectException("fixture"))))
        assertEquals(MutationOutcome.Unknown,transportOutcome(java.net.SocketTimeoutException("fixture")))
        assertEquals(MutationOutcome.Unknown,transportOutcome(java.io.IOException("stream was reset")))
    }

    // ---- failures

    @Test fun anErrorInsideA200IsReadAsAnErrorAndOnlyItsNumberAndStatusAreKept() {
        val error=providerEnvelopeError(providerFixtureJson("error-envelope.json"))!!
        assertEquals(403,error.code);assertEquals("PERMISSION_DENIED",error.status)
        assertFalse("The provider's own sentence is not kept",error.message!!.contains("caller"))
        assertEquals(ProviderFault.Refused,classifyProviderFailure(error))
        assertEquals(ProviderFault.NotFound,classifyProviderFailure(providerEnvelopeError(providerFixtureJson("error-not-found.json"))!!))
        assertNull(providerEnvelopeError(providerFixtureJson("album.json")));assertNull(providerEnvelopeError(null))
        // A status word with no number still classifies; a status that is not a word is cut down to one.
        assertEquals(ProviderFault.RateLimited,classifyProviderFailure(providerEnvelopeError(Json.parseToJsonElement("""{"error":{"status":"RESOURCE_EXHAUSTED"}}"""))!!))
        assertEquals("ab",providerEnvelopeError(Json.parseToJsonElement("""{"error":{"status":"a b<script>"}}"""))!!.status.take(2))
    }
    @Test fun failuresAreClassifiedByWhatWasSeenAndOtherwiseSaidToBeUnknown() {
        fun fault(status:Int)=classifyProviderFailure(ProviderHttpException(status))
        assertEquals(ProviderFault.Auth,fault(401));assertEquals(ProviderFault.Refused,fault(403));assertEquals(ProviderFault.NotFound,fault(404))
        assertEquals(ProviderFault.NotFound,fault(410));assertEquals(ProviderFault.RateLimited,fault(429))
        assertEquals(ProviderFault.Provider,fault(500));assertEquals(ProviderFault.Provider,fault(503));assertEquals(ProviderFault.Unknown,fault(400))
        assertEquals(ProviderFault.Offline,classifyProviderFailure(RuntimeException(java.net.UnknownHostException("music.youtube.com"))))
        assertEquals(ProviderFault.Timeout,classifyProviderFailure(java.net.SocketTimeoutException("timeout")))
        assertEquals(ProviderFault.Unknown,classifyProviderFailure(IllegalStateException("anything else")))
        // The words shown: the class's own sentence, never an address, a status line or the provider's text.
        assertEquals("YouTube Music is limiting requests. Try again shortly.",providerErrorMessage(ProviderHttpException(429)))
        assertEquals("This is not available on YouTube Music.",providerErrorMessage(ProviderHttpException(404)))
        assertEquals("YouTube Music refused this request.",providerErrorMessage(ProviderEnvelopeException(403,"PERMISSION_DENIED")))
        assertEquals("YouTube Music is having trouble. Try again later.",providerErrorMessage(ProviderHttpException(502)))
        assertEquals("YouTube Music could not load this content",providerErrorMessage(ProviderHttpException(400)))
        assertEquals(OFFLINE_MESSAGE,providerErrorMessage(java.net.UnknownHostException("music.youtube.com")))
        assertEquals("Nothing is called a region restriction: no answer Riff reads says so",false,ProviderFault.entries.any {it.message.contains("region",true) || it.message.contains("country",true)})
    }

    // ---- a provider that has changed

    @Test fun unknownRenderersAndFieldsOfTheWrongTypeAreSkippedAndWhatIsUnderstoodIsKept() {
        val page=page("unknown-renderers.json")
        assertEquals(listOf(song(43),song(4)),page.albumTracks.map {it.videoId})
        assertEquals("Simple text of the wrong type",page.albumTracks[0].title)
        assertEquals("fixture-public-set-4",page.albumTracks[1].setVideoId)
        assertNull("A page token that is not text is no token",page.collectionNext);assertNull(page.next)
        assertEquals("",page.title);assertFalse(page.editable)
        assertNull(parseAccountProfile(providerFixtureJson("unknown-renderers.json")))
        assertEquals(emptyMap<CollectionSort,CollectionSortEndpoint>(),page.sortEndpoints)
    }
    @Test fun emptyAndBareAnswersParseToNothing() {
        listOf("{}","[]","null","7","\"text\"","""{"contents":[]}""","""{"contents":{"sectionListRenderer":{"contents":[]}}}""",
            """{"continuationContents":{"musicPlaylistShelfContinuation":{"contents":[],"continuations":[]}}}""").forEach {text->
            val root=Json.parseToJsonElement(text);val page=catalogPage(root)
            assertEquals(text,emptyList<ProviderHomeCard>(),page.cards);assertEquals(text,emptyList<ProviderHomeCard>(),page.albumTracks)
            assertNull(text,page.next);assertNull(text,page.collectionNext);assertEquals(text,emptyList<ProviderTrack>(),parseQueueTracks(root))
            assertNull(text,parseLikeStatus(root));assertNull(text,parseYoutubeLyrics(root,1000));assertNull(text,musicVideoPair(root,ProviderTrack(song(1),"T","A")))
            assertEquals(text,emptyList<HomeMood>(),parseHomeMoods(root))
        }
    }
    @Test fun pageTokensAreFollowedOnce() {
        val tokens=PageTokens()
        assertEquals("a",tokens.next("a"));assertEquals("b",tokens.next("b"))
        assertNull("The provider going round ends the listing",tokens.next("a"));assertNull(tokens.next("b"))
        assertNull(tokens.next(null));assertNull(tokens.next(""));assertEquals("c",tokens.next("c"))
    }
}
