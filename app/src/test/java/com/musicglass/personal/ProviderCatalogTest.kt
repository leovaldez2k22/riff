package com.musicglass.personal

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ProviderCatalogTest {
    @Test fun aSearchRowNamesItsKindOnce() {
        // Q18-094: YouTube's subtitle for an official playlist already starts with "Playlist".
        assertEquals("Playlist \u2022 YouTube Music \u2022 120 songs",catalogRowLabel(CatalogKind.Playlist,"Playlist \u2022 YouTube Music \u2022 120 songs"))
        assertEquals("Playlist \u00B7 Riff Owner \u2022 40 songs",catalogRowLabel(CatalogKind.Playlist,"Riff Owner \u2022 40 songs"))
        assertEquals("Album \u00B7 Taylor Swift \u2022 2024",catalogRowLabel(CatalogKind.Album,"Taylor Swift \u2022 2024"))
        assertEquals("a word that only begins like the kind is not the kind","Playlist \u00B7 Playlists for Summer",catalogRowLabel(CatalogKind.Playlist,"Playlists for Summer"))
        assertEquals("Song",catalogRowLabel(CatalogKind.Song,""))
    }
    @Test fun aCollectionSaysHowManySongsAndHowLongInOneLine() {
        // Q18-093, layout A: a playlist row's fixed column carries its length; the header's count wins over the loaded rows.
        val row=parse("""{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song","navigationEndpoint":{"watchEndpoint":{"videoId":"video000001"}}}]}}}],
            "fixedColumns":[{"musicResponsiveListItemFixedColumnRenderer":{"text":{"runs":[{"text":"3:45"}]}}}]}}""").single()
        assertEquals(225_000L,row.durationMs)
        assertEquals(3_723_000L,parse("""{"playlistPanelVideoRenderer":{"videoId":"video000002","title":{"simpleText":"Long"},"lengthText":{"simpleText":"1:02:03"}}}""").single().durationMs)
        fun song(i:Int,ms:Long?)=ProviderHomeCard("S$i","A",null,videoId="video${i.toString().padStart(6,'0')}",durationMs=ms)
        val hundred=(1..100).map {song(it,205_200L)} // 100 songs of 3:25.2: 5 h 42 min
        assertEquals("100 songs · 5 h 42 min",collectionSummary(null,hundred,complete=true))
        assertEquals("940 songs · 5 h 42 min so far",collectionSummary(940,hundred,complete=false))
        assertEquals("1 song · 4 min",collectionSummary(null,listOf(song(1,225_000L)),complete=true))
        assertEquals("a song with no length is left out of the sum","2 songs · 4 min",collectionSummary(null,listOf(song(1,225_000L),song(2,null)),complete=true))
        assertEquals("no lengths at all: the count alone","2 songs",collectionSummary(null,listOf(song(1,null),song(2,null)),complete=true))
        assertNull(collectionSummary(12,emptyList(),complete=false))
        val header=Json.parseToJsonElement("""{"header":{"musicResponsiveHeaderRenderer":{"title":{"simpleText":"Mix"},"secondSubtitle":{"runs":[{"text":"940 tracks"},{"text":" • "},{"text":"60+ hours"}]}}}}""")
        assertEquals(940,catalogPage(header).headerSongCount)
    }
    @Test fun podcastViewsCannotReplaceTheChannelByline() {
        val card=parse("""{"musicMultiRowListItemRenderer":{"title":{"simpleText":"An episode"},"subtitle":{"simpleText":"230K views • 2 days ago"},"longBylineText":{"runs":[{"text":"The Channel","navigationEndpoint":{"browseEndpoint":{"browseId":"UC_channel"}}}]},"navigationEndpoint":{"watchEndpoint":{"videoId":"video000001","watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{"musicVideoType":"MUSIC_VIDEO_TYPE_PODCAST_EPISODE"}}}}}}""").single()
        assertEquals(CatalogKind.Episode,card.kind)
        assertEquals("The Channel",card.track()!!.artist)
        assertEquals("UC_channel",card.track()!!.artistId)
    }
    @Test fun unlinkedChannelBylineStillWinsOverStatistics() {
        val card=parse("""{"videoRenderer":{"videoId":"video000001","title":{"simpleText":"Episode"},"subtitle":{"simpleText":"2M views"},"ownerText":{"simpleText":"Channel without a link"}}}""").single()
        assertEquals("Channel without a link",card.track()!!.artist)
    }
    @Test fun modernTileKeepsLinkedCreatorSeparateFromViewCount() {
        val card=parse("""{"lockupViewModel":{"contentId":"video000001","metadata":{"lockupMetadataViewModel":{"title":{"content":"Episode"},"metadata":{"metadataRows":[{"text":{"content":"100K views"}},{"text":{"content":"Podcast Channel","commandRuns":[{"onTap":{"innertubeCommand":{"browseEndpoint":{"browseId":"UC_podcast"}}}}]}}]}}}}}""").single()
        assertEquals("Podcast Channel",card.track()!!.artist)
        assertEquals("UC_podcast",card.artistId)
    }
    @Test fun playlistSortsUseOnlyProviderCommands() {
        val root=Json.parseToJsonElement("""{"musicSortFilterButtonRenderer":{"menu":{"musicMultiSelectMenuRenderer":{"options":[{"musicMultiSelectMenuItemRenderer":{"title":{"simpleText":"Top voted"},"selectedCommand":{"commandExecutorCommand":{"commands":[{"browseSectionListReloadEndpoint":{"continuation":{"reloadContinuationData":{"continuation":"votes-token"}}}}]}}}},{"musicMultiSelectMenuItemRenderer":{"title":{"simpleText":"Newest first"},"selectedCommand":{"browseEndpoint":{"browseId":"VL_playlist","params":"date-params"}}}},{"musicMultiSelectMenuItemRenderer":{"title":{"simpleText":"Oldest first"}}}]}}}}""")
        assertEquals(CollectionSortEndpoint(continuation="votes-token"),collectionSortEndpoints(root)[CollectionSort.TopVoted])
        assertEquals(CollectionSortEndpoint("VL_playlist","date-params"),collectionSortEndpoints(root)[CollectionSort.Newest])
        assertFalse(collectionSortEndpoints(root).containsKey(CollectionSort.Oldest))
    }

    @Test fun realPlaylistSubmenuKeepsSortCommandsAndSelectedPreference() {
        val root=Json.parseToJsonElement("""{"sortFilterSubMenuRenderer":{"title":"Sort","subMenuItems":[
          {"title":"Top voted","selected":false,"serviceEndpoint":{"playlistEditEndpoint":{"playlistId":"PL_example","actions":[{"action":"ACTION_SET_PLAYLIST_VIDEO_ORDER","playlistVideoOrder":6}],"params":"votes-opaque"}}},
          {"title":"Manual ordering","serviceEndpoint":{"playlistEditEndpoint":{"playlistId":"PL_example","actions":[{"action":"ACTION_SET_PLAYLIST_VIDEO_ORDER","playlistVideoOrder":0}]}}},
          {"title":"Newest first","selected":true,"serviceEndpoint":{"playlistEditEndpoint":{"playlistId":"PL_example","actions":[{"action":"ACTION_SET_PLAYLIST_VIDEO_ORDER","playlistVideoOrder":1}],"params":"date-opaque"}}},
          {"title":"Oldest first","serviceEndpoint":{"playlistEditEndpoint":{"playlistId":"PL_example","actions":[{"action":"ACTION_SET_PLAYLIST_VIDEO_ORDER","playlistVideoOrder":2}]}}},
          {"title":"Title","serviceEndpoint":{"playlistEditEndpoint":{"playlistId":"PL_example","actions":[{"action":"ACTION_SET_PLAYLIST_DYNAMIC_SORT_PREFERENCE","playlistDynamicSortPreference":"TITLE"}]}}}
        ]}}""")
        val page=catalogPage(root)
        assertEquals(setOf(CollectionSort.TopVoted,CollectionSort.Original,CollectionSort.Newest,CollectionSort.Oldest,CollectionSort.Title),page.sortEndpoints.keys)
        assertEquals(CollectionSort.Newest,page.selectedSort)
        val edit=page.sortEndpoints.getValue(CollectionSort.Newest).playlistEdit!!
        assertEquals("date-opaque",edit.params)
        assertTrue(edit.validFor("VLPL_example"))
        assertFalse(edit.validFor("VLPL_other"))
    }
    @Test fun sortMenusRejectUnrelatedOrCompoundPlaylistEdits() {
        for(actions in listOf(
            """[{"action":"ACTION_REMOVE_VIDEO","playlistVideoOrder":1}]""",
            """[{"action":"ACTION_SET_PLAYLIST_VIDEO_ORDER","playlistVideoOrder":99}]""",
            """[{"action":"ACTION_SET_PLAYLIST_VIDEO_ORDER","playlistVideoOrder":1,"removedVideoId":"video"}]""",
            """[{"action":"ACTION_SET_PLAYLIST_VIDEO_ORDER","playlistVideoOrder":1},{"action":"ACTION_REMOVE_VIDEO"}]""")) {
            val root=Json.parseToJsonElement("""{"sortFilterSubMenuRenderer":{"subMenuItems":[{"title":"Newest first","serviceEndpoint":{"playlistEditEndpoint":{"playlistId":"PL_example","actions":$actions}}}]}}""")
            assertTrue(collectionSortEndpoints(root).isEmpty())
        }
    }

    @Test fun detailAlbumHeaderRetainsLinkedArtist() {
        val root=Json.parseToJsonElement("""{"header":{"musicDetailHeaderRenderer":{"title":{"runs":[{"text":"Graduation"}]},"subtitle":{"runs":[{"text":"Album • "},{"text":"Kanye West","navigationEndpoint":{"browseEndpoint":{"browseId":"UCartist"}}},{"text":" • 2007"}]}}}}""")
        val page=catalogPage(root)
        assertEquals("Kanye West",page.artistName)
        assertEquals("UCartist",page.artistId)
    }

    private fun parse(value:String)=parseCatalogCards(Json.parseToJsonElement(value))
    @Test fun discoversAlbumsAndArtistsUsingTheMainEndpointRatherThanMenuVideos() {
        val cards=parse("""{"contents":[
          {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Morning Album"}]},"subtitle":{"runs":[{"text":"Album"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"MPRE_album","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}},"menu":{"videoId":"video000001"}}},
          {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Paper Birds"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"UC_artist"}},"menu":{"videoId":"video000002"}}}]}""")
        assertEquals(listOf(CatalogKind.Album,CatalogKind.Artist),cards.map {it.kind})
        assertTrue(cards.all {it.track()==null})
    }
    @Test fun responsiveSongPreservesArtistAndAlbumLinksAndGoogleCovers() {
        val cards=parse("""{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"video000003"},
          "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Morning Path"}]}}},
          {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Paper Birds","navigationEndpoint":{"browseEndpoint":{"browseId":"UC_paper"}}},{"text":" • "},{"text":"Morning Album","navigationEndpoint":{"browseEndpoint":{"browseId":"MPRE_morning"}}}]}}}],
          "thumbnail":{"thumbnails":[{"url":"https://lh3.ggpht.com/art=w544-h544","width":544,"height":544}]}}}""")
        val track=cards.single().track()!!
        assertEquals("Paper Birds",track.artist);assertEquals("UC_paper",track.artistId)
        assertEquals("MPRE_morning",track.albumId);assertEquals("Morning Album",track.album)
        assertEquals("https://lh3.ggpht.com/art=w544-h544",track.artworkUrl)
    }
    @Test fun queuePanelTracksKeepOrderAndExcludeLiveAndDuplicateIds() {
        val root=Json.parseToJsonElement("""{"playlistPanelRenderer":{"contents":[
        {"playlistPanelVideoRenderer":{"videoId":"video000001","title":{"simpleText":"One"},"longBylineText":{"runs":[{"text":"Singer"}]}}},
        {"playlistPanelVideoRenderer":{"videoId":"video000002","title":{"simpleText":"Two"}}},
        {"playlistPanelVideoRenderer":{"videoId":"video000001","title":{"simpleText":"One"}}}]}}""")
        assertEquals(listOf("video000001","video000002"),parseQueueTracks(root).map {it.videoId})
        val candidates=queueCandidates(parseQueueTracks(root)+ProviderTrack("video000004","Live","Station",isLive=true),setOf("video000001"))
        assertEquals(listOf("video000002"),candidates.map {it.videoId})
    }
    @Test fun modernLiveTilesUseActualLiveBadgesAndNotArchivedStreams() {
        val cards=parse("""{"items":[{"lockupViewModel":{"contentId":"video000001","metadata":{"lockupMetadataViewModel":{"title":{"content":"Live beats"}}},"contentImage":{"thumbnailViewModel":{"image":{"sources":[{"url":"https://i.ytimg.com/vi/video000001/hq720.jpg","width":720,"height":404}]},"overlays":[{"thumbnailBadgeViewModel":{"badgeStyle":"THUMBNAIL_OVERLAY_BADGE_STYLE_LIVE","text":"LIVE"}}]}}}},
        {"lockupViewModel":{"contentId":"video000002","metadata":{"lockupMetadataViewModel":{"title":{"content":"Past stream"}}},"contentImage":{"thumbnailViewModel":{"overlays":[{"thumbnailBadgeViewModel":{"text":"2:14:10"}}]}}}}]}""")
        assertEquals(CatalogKind.Live,cards[0].kind);assertEquals(CatalogKind.Song,cards[1].kind)
    }
    @Test fun extractsNestedInitialDataWithoutMistakingQuotedBraces() {
        val root=youtubeInitialData("<script>var ytInitialData = {\"title\":\"a } b\",\"nested\":{\"x\":1}};</script>")
        assertNotNull(root);assertEquals("a } b",catalogValue(root,"title"))
        assertNull(youtubeInitialData("unavailable"))
    }
    @Test fun separatesPodcastShelvesAndPreservesPaginationAndLikeStatus() {
        assertTrue(podcastShelf(ProviderHomeSection("Shows for you",emptyList())))
        assertFalse(podcastShelf(ProviderHomeSection("Albums for you",emptyList())))
        assertEquals(CatalogKind.Podcast,catalogKind(null,"MPSPshow","",false))
        val root=Json.parseToJsonElement("""{"continuations":[{"nextContinuationData":{"continuation":"page-2"}}],"likeButtonRenderer":{"likeStatus":"LIKE"}}""")
        assertEquals("page-2",catalogContinuation(root));assertEquals(true,parseLikeStatus(root))
        assertNull(parseLikeStatus(Json.parseToJsonElement("{}")))
    }
    @Test fun readsRadioContinuationAndServerPodcastNavigation() {
        val root=Json.parseToJsonElement("""{"continuations":[{"nextRadioContinuationData":{"continuation":"radio-next"}}],"chipCloudChipRenderer":{"text":{"runs":[{"text":"Podcasts"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"FEmusic_home","params":"podcast-filter"}}}}""")
        assertEquals("radio-next",catalogContinuation(root))
        assertEquals("FEmusic_home" to "podcast-filter",catalogBrowseFilter(root,"Podcasts"))
    }
    @Test fun artworkValidationAcceptsProviderCdnsAndRejectsLookalikesAndCredentials() {
        assertNotNull(validProviderArtworkUrl("https://lh3.ggpht.com/album"))
        assertNotNull(validProviderArtworkUrl("https://googleusercontent.com/cover"))
        assertNull(validProviderArtworkUrl("https://ggpht.com.evil.example/album"))
        assertNull(validProviderArtworkUrl("https://user:pass@lh3.ggpht.com/album"))
    }
    @Test fun backFromAnAlbumRestoresTheArtistDetail() {
        val model=ShellModel();model.openCollection("UC_artist","Artist");model.openCollection("MPRE_album","Album")
        model.back();assertEquals("UC_artist",model.selectedPlaylistId);assertEquals("Artist",model.selectedCollectionTitle)
        assertEquals("collection",model.route)
    }
    @Test fun songSearchFindsOverlayPlaybackWithoutUsingMenuVideos() {
        val cards=parse("""{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song from search"}]}}}],"overlay":{"musicItemThumbnailOverlayRenderer":{"content":{"musicPlayButtonRenderer":{"playNavigationEndpoint":{"watchEndpoint":{"videoId":"video000003"}}}}}},"menu":{"watchEndpoint":{"videoId":"video000009"}}}}""")
        assertEquals("video000003",cards.single().videoId)
        assertEquals(CatalogKind.Song,cards.single().kind)
    }
}
