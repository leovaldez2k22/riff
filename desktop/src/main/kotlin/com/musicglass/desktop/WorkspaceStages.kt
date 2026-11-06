package com.musicglass.desktop

import androidx.compose.ui.input.key.*

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ==========================================
// 1. HOME STAGE
// ==========================================
@Composable
internal fun HomeStage(
    font: FontFamily,
    onPlayTrack: (SongItem) -> Unit,
    onOpenAlbum: (String) -> Unit = {},
    onOpenArtist: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val catalog = LocalDesktopCatalog.current
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        // Hero Greeting
        item {
            Column {
                Label(when(java.time.LocalTime.now().hour){in 5..11->"Good morning";in 12..17->"Good afternoon";else->"Good evening"}, font, Color.White, size = 34)
                Spacer(Modifier.height(4.dp))
                Label("Your music stream, live in fluid glass", font, RiffDesign.muted, size = 15)
            }
        }

        if(catalog.all.isEmpty()) item { Label("Drop an audio file, Ctrl+O to import, or Ctrl+K to find a track.",font,RiffDesign.muted) }
        item { LocalDesktopHost.current?.let{host->DataAction("Discover music",font){host.dispatch(DesktopCommand.BrowseProvider())}} }
        // Speed Dial / Quick Picks Shelves
        item {
            Label("QUICK PICKS", font, RiffDesign.muted, size = 13)
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                catalog.all.take(3).forEach { track ->
                    Box(modifier = Modifier.weight(1f)) {
                        SpeedDialCard(track = track, font = font, onClick = { onPlayTrack(track) })
                    }
                }
            }
        }

        // Listen Again Album Cards
        item {
            Label("YOUR ALBUMS", font, RiffDesign.muted, size = 13)
            Spacer(Modifier.height(12.dp))
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(catalog.albums) { album ->
                    AlbumCard(album = album, font = font, onClick = { onOpenAlbum(album.id) })
                }
            }
        }

        // Artists Shelf
        item {
            Label("FEATURED ARTISTS", font, RiffDesign.muted, size = 13)
            Spacer(Modifier.height(12.dp))
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(catalog.artists) { artist ->
                    ArtistCard(artist = artist, font = font, onClick = { onOpenArtist(artist.id) })
                }
            }
        }

        // Recent Tracks Rows
        item {
            Label("LIBRARY TRACKS", font, RiffDesign.muted, size = 13)
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                catalog.all.forEach { track ->
                    TrackListRow(track = track, font = font, onClick = { onPlayTrack(track) })
                }
            }
        }
    }
}

// ==========================================
// 2. SEARCH STAGE
// ==========================================
@Composable
internal fun SearchStage(
    query: String,
    searchFocusRequester: FocusRequester,
    font: FontFamily,
    onQueryChange: (String) -> Unit,
    onPlayTrack: (SongItem) -> Unit,
    onOpenAlbum: (String) -> Unit = {},
    focusRequestToken:Int=0,
    modifier: Modifier = Modifier
) {
    val catalog = LocalDesktopCatalog.current
    val host=LocalDesktopHost.current
    val hostState=host?.state?.collectAsState()?.value
    val authority=host?.accounts?.token?.collectAsState()?.value
    var selectedFilter by remember { mutableStateOf("All") }
    val filters = listOf("All", "Songs", "Artists", "Albums")

    val matchingTracks = remember(query, catalog) {
        if (query.isBlank()) catalog.all
        else (catalog.searchResults + catalog.all.filter {
            it.title.contains(query, ignoreCase = true) ||
            it.artist.contains(query, ignoreCase = true) ||
            it.album.contains(query, ignoreCase = true)
        }).distinctBy { it.scope to it.id }
    }

    val matchingAlbums = remember(query, catalog) {
        if (query.isBlank()) catalog.albums
        else catalog.albums.filter {
            it.title.contains(query, ignoreCase = true) ||
            it.artist.contains(query, ignoreCase = true)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Search Input Bar
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (query.isEmpty()) {
                    Label("Search music or paste a YouTube link (Ctrl+K)…", font, RiffDesign.muted, size = 15)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth().focusRequester(searchFocusRequester).onPreviewKeyEvent{event->if(event.type==KeyEventType.KeyDown&&event.key==Key.Enter){host?.dispatch(DesktopCommand.SubmitSearch(query));true}else false},
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = Color.White,
                        fontSize = 15.sp,
                        fontFamily = font
                    ),
                    cursorBrush = SolidColor(RiffDesign.pink),
                    singleLine = true
                )
                LaunchedEffect(focusRequestToken){withFrameNanos{};searchFocusRequester.requestFocus()}
            }
        }

        item { LocalDesktopHost.current?.state?.collectAsState()?.value?.searchStatus?.let{Label(it,font,RiffDesign.muted,size=13)} }
        if(query.isBlank()){
            items(hostState?.recentSearches?.get(authority?.scope).orEmpty()){recent->DataAction(recent,font){host?.dispatch(DesktopCommand.SubmitSearch(recent))}}
        }else{
            items(hostState?.suggestions?.results.orEmpty()){card->
                DataAction("${card.title} · ${card.subtitle}",font){if(card.videoId!=null)host?.dispatch(DesktopCommand.PlayTrack(card.song()))else card.browseId?.let{host?.dispatch(DesktopCommand.BrowseProvider(it))}}
            }
            items(hostState?.suggestions?.queries.orEmpty()){suggestion->DataAction(suggestion,font){host?.dispatch(DesktopCommand.SubmitSearch(suggestion))}}
        }
        // Category Filter Chips
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                filters.forEach { filter ->
                    val isSelected = selectedFilter == filter
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (isSelected) RiffDesign.pink else Color.White.copy(alpha = 0.06f))
                            .clickable { selectedFilter = filter }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Label(filter, font, if (isSelected) Color.White else RiffDesign.muted, size = 13)
                    }
                }
            }
        }

        if(selectedFilter=="All")hostState?.searchTopResult?.let{card->item{
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(RiffRadius.card)).background(RiffDesign.raised).clickable(role=Role.Button){card.browseId?.let{host?.dispatch(DesktopCommand.BrowseProvider(it))}}.padding(RiffSpace.block),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(RiffSpace.block)){
                Box(Modifier.size(RiffTopResult.artwork).clip(if(card.kind==CatalogKind.Artist)CircleShape else RoundedCornerShape(RiffRadius.artwork)).trackArtwork(card.song()))
                Column{Label("TOP RESULT",font,RiffDesign.muted,size=12);Label(card.title,font,Color.White,size=RiffTopResult.title.value.toInt());Label("${card.kind.name} · ${card.subtitle}",font,RiffDesign.muted,size=14)}
            }
        }}
        val remoteCollections=hostState?.searchCards.orEmpty().filter{it.browseId!=null&&when(selectedFilter){"Artists"->it.kind==CatalogKind.Artist;"Albums"->it.kind==CatalogKind.Album;"All"->it.kind in setOf(CatalogKind.Artist,CatalogKind.Album,CatalogKind.Playlist,CatalogKind.Podcast);else->false}}
        items(remoteCollections){card->DataAction("${card.kind.name} · ${card.title} · ${card.subtitle}",font){card.browseId?.let{host?.dispatch(DesktopCommand.BrowseProvider(it))}}}
        // Results Section or Empty State
        if (query.isNotBlank() && matchingTracks.isEmpty() && matchingAlbums.isEmpty() && remoteCollections.isEmpty() && hostState?.searchStatus==null) {
            item {
                StageStateView(
                    title = "No results found",
                    message = "Could not find anything matching \"$query\". Try a different search term.",
                    font = font
                )
            }
        } else {
            if (selectedFilter in listOf("All", "Songs")) {
                item {
                    Label("SONGS (${matchingTracks.size})", font, RiffDesign.muted, size = 12)
                }
                items(matchingTracks) { track ->
                    TrackListRow(track = track, font = font, onClick = { onPlayTrack(track) })
                }
            }

            if (selectedFilter in listOf("All", "Albums") && matchingAlbums.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(12.dp))
                    Label("ALBUMS (${matchingAlbums.size})", font, RiffDesign.muted, size = 12)
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(matchingAlbums) { album ->
                            AlbumCard(album = album, font = font, onClick = { onOpenAlbum(album.id) })
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// 3. LIBRARY STAGE
// ==========================================
@Composable
internal fun LibraryStage(
    font: FontFamily,
    onPlayTrack: (SongItem) -> Unit,
    onOpenAlbum: (String) -> Unit = {},
    onOpenArtist: (String) -> Unit = {},
    onOpenPlaylist: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val catalog = LocalDesktopCatalog.current
    val downloadState=LocalDesktopHost.current?.state?.collectAsState()?.value
    val downloadRows=downloadState?.downloads.orEmpty().filter{(it.job.scope to it.job.trackId)!=downloadState?.pendingDownloadRemoval}
    var selectedCategory by remember { mutableStateOf("Playlists") }
    val categories = listOf("Playlists", "Songs", "Albums", "Artists", "Downloads")

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Label("Your Library", font, Color.White, size = 34)
            Spacer(Modifier.height(4.dp))
            Label("Your collections, playlists, and offline music", font, RiffDesign.muted, size = 15)
        }

        item { LibraryTools(font) }
        // Category Filter Tabs
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { cat ->
                    val isSelected = selectedCategory == cat
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (isSelected) RiffDesign.pink else Color.White.copy(alpha = 0.06f))
                            .clickable { selectedCategory = cat }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Label(cat, font, if (isSelected) Color.White else RiffDesign.muted, size = 14)
                    }
                }
            }
        }

        // Content dynamically changes with selected category
        when (selectedCategory) {
            "Playlists" -> {
                items(catalog.playlists) { playlist ->
                    PlaylistRow(playlist = playlist, font = font, onClick = { onOpenPlaylist(playlist.id) })
                }
            }
            "Songs" -> {
                items(catalog.all) { track ->
                    TrackListRow(track = track, font = font, onClick = { onPlayTrack(track) })
                }
            }
            "Albums" -> {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        catalog.albums.forEach { album ->
                            Box(modifier = Modifier.weight(1f)) {
                                AlbumCard(album = album, font = font, onClick = { onOpenAlbum(album.id) })
                            }
                        }
                    }
                }
            }
            "Artists" -> {
                items(catalog.artists) { artist ->
                    ArtistRow(artist = artist, font = font, onClick = { onOpenArtist(artist.id) })
                }
            }
            "Downloads" -> {
                item { Label("Downloads",font,Color.White,size=22) }
                if(downloadRows.isEmpty())item{Label("Save a track offline from its + menu. Imported files stay in Songs.",font,RiffDesign.muted,size=13)}
                items(downloadRows,key={it.job.scope+":"+it.job.trackId}){DownloadLibraryRow(it,font,onPlayTrack)}
            }
        }
    }
}

// ==========================================
// 4. PODCASTS STAGE
// ==========================================
@Composable
internal fun PodcastsStage(
    font: FontFamily,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize().padding(32.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
        Label("Podcasts",font,Color.White,size=34)
        Label("Subscribed podcast collections need a supported YouTube Music account session. Public podcast videos are available through search.",font,RiffDesign.muted)
        LocalDesktopHost.current?.let{host->DataAction("Search public podcast videos",font){host.dispatch(DesktopCommand.BrowseProvider(query="podcast"))}}
    }
}

// ==========================================
// 5. RADIO STAGE
// ==========================================
@Composable
internal fun RadioStage(
    font: FontFamily,
    onPlayTrack: (SongItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val host=LocalDesktopHost.current
    val current=host?.state?.collectAsState()?.value?.currentTrack
    Column(modifier.fillMaxSize().padding(32.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
        Label("Radio",font,Color.White,size=34)
        if(current?.playbackSource?.startsWith("youtube:")==true){
            Label("Start recommendations from ${current.title}",font,RiffDesign.muted)
            DataAction("Start radio",font){host.dispatch(DesktopCommand.BrowseProvider(radioVideoId=current.playbackSource.removePrefix("youtube:")))}
        }else{Label("Choose a provider track to start radio.",font,RiffDesign.muted);if(host!=null)DataAction("Find a track",font){host.dispatch(DesktopCommand.SetRoute(WorkspaceRoute.Search))}}
    }
}

// ==========================================
// 6. ALBUM DETAIL STAGE
// ==========================================
@Composable
internal fun AlbumDetailStage(
    albumId: String,
    font: FontFamily,
    onPlayTrack: (SongItem) -> Unit,
    onPlayCollection: (List<SongItem>, Int) -> Unit = { list, idx -> },
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    if(LocalCollectionStatus(albumId,font))return
    val catalog = LocalDesktopCatalog.current
    val album = catalog.albums.firstOrNull { it.id == albumId } ?: run { Label("Album unavailable", font, RiffDesign.muted); return }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Back navigation
        item {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClick = onBack)
                    .padding(vertical = 4.dp, horizontal = 8.dp)
            ) {
                Label("← Back", font, RiffDesign.pink, size = 15)
            }
        }

        // Album Header Banner
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .trackArtwork(album.tracks.firstOrNull()?:EmptyDesktopTrack)
                )
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f)) {
                    Label("ALBUM", font, RiffDesign.muted, size = 12)
                    Spacer(Modifier.height(4.dp))
                    Label(album.title, font, Color.White, size = 28)
                    Spacer(Modifier.height(4.dp))
                    Label(listOfNotNull(album.artist,album.year.takeIf{it>0}?.toString(),"${album.tracks.size} loaded tracks").joinToString(" • "), font, RiffDesign.muted, size = 15)
                    Spacer(Modifier.height(14.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(RiffDesign.pink)
                                .clickable { onPlayCollection(album.tracks, 0) }
                                .padding(horizontal = 18.dp, vertical = 8.dp)
                        ) {
                            Label("Play Album", font, Color.White, size = 14)
                        }
                    }
                }
            }
        }

        // Tracks in Album
        item {
            LocalCollectionTools(font)
            Label("TRACKS", font, RiffDesign.muted, size = 12)
        }

        itemsIndexed(album.tracks) { index, track ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onPlayTrack(track) }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Label("${index + 1}", font, RiffDesign.muted, size = 14)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Label(track.title, font, Color.White, size = 15)
                    Spacer(Modifier.height(2.dp))
                    Label(track.artist, font, RiffDesign.muted, size = 13)
                }
                TrackPlaylistAction(track,font)
        Spacer(Modifier.width(8.dp))
        Label(formatDuration(track.durationMs), font, RiffDesign.muted, size = 13)
            }
        }
    }
}

// ==========================================
// 7. ARTIST DETAIL STAGE
// ==========================================
@Composable
internal fun ArtistDetailStage(
    artistId: String,
    font: FontFamily,
    onPlayTrack: (SongItem) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    if(LocalCollectionStatus(artistId,font))return
    val catalog = LocalDesktopCatalog.current
    val artist = catalog.artists.firstOrNull { it.id == artistId } ?: run { Label("Artist unavailable", font, RiffDesign.muted); return }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClick = onBack)
                    .padding(vertical = 4.dp, horizontal = 8.dp)
            ) {
                Label("← Back", font, RiffDesign.pink, size = 15)
            }
        }

        // Artist Hero Banner
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .trackArtwork(artist.topTracks.firstOrNull()?:EmptyDesktopTrack)
                )
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f)) {
                    Label("ARTIST", font, RiffDesign.muted, size = 12)
                    Spacer(Modifier.height(4.dp))
                    Label(artist.name, font, Color.White, size = 32)
                    Spacer(Modifier.height(4.dp))
                    Label(artist.monthlyListeners, font, RiffDesign.muted, size = 14)
                }
            }
        }

        item {
            LocalCollectionTools(font)
            Label("TOP TRACKS", font, RiffDesign.muted, size = 13)
        }

        items(artist.topTracks) { track ->
            TrackListRow(track = track, font = font, onClick = { onPlayTrack(track) })
        }

        item {
            Spacer(Modifier.height(10.dp))
            Label("DISCOGRAPHY", font, RiffDesign.muted, size = 13)
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                artist.albums.forEach { albTitle ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Label(albTitle, font, Color.White, size = 14)
                    }
                }
            }
        }
    }
}

// ==========================================
// 8. PLAYLIST DETAIL STAGE
// ==========================================
@Composable
internal fun PlaylistDetailStage(
    playlistId: String,
    font: FontFamily,
    onPlayTrack: (SongItem) -> Unit,
    onPlayCollection: (List<SongItem>, Int) -> Unit = { list, idx -> },
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val catalog = LocalDesktopCatalog.current
    val playbackHost=LocalDesktopHost.current
    val playlist = catalog.playlists.firstOrNull { it.id == playlistId } ?: run { Label("Playlist unavailable", font, RiffDesign.muted); return }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClick = onBack)
                    .padding(vertical = 4.dp, horizontal = 8.dp)
            ) {
                Label("← Back", font, RiffDesign.pink, size = 15)
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .trackArtwork(playlist.tracks.firstOrNull()?:EmptyDesktopTrack)
                )
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f)) {
                    Label("PLAYLIST", font, RiffDesign.muted, size = 12)
                    Spacer(Modifier.height(4.dp))
                    Label(playlist.name, font, Color.White, size = 28)
                    Spacer(Modifier.height(4.dp))
                    Label(playlist.description, font, RiffDesign.muted, size = 14)
                    Spacer(Modifier.height(14.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(RiffDesign.pink)
                                .clickable { if(playbackHost!=null)playbackHost.dispatch(DesktopCommand.PlayPlaylist(playlist.id))else onPlayCollection(playlist.tracks,0) }
                                .padding(horizontal = 18.dp, vertical = 8.dp)
                        ) {
                            Label("Play All", font, Color.White, size = 14)
                        }
                    }
                }
            }
        }

        item {
            PlaylistEditTools(playlist,font)
            PlaylistPagingTools(playlist,font)
            Spacer(Modifier.height(12.dp))
            Label("PLAYLIST SONGS (${playlist.trackCount})", font, RiffDesign.muted, size = 12)
        }

        itemsIndexed(playlist.tracks) { index,track ->
            Column {
                TrackListRow(track = track, font = font, onClick = { onPlayTrack(track) })
                PlaylistOccurrenceTools(playlist,index,font)
            }
        }
    }
}

// ==========================================
// 9. FOCUSED PLAYER STAGE
// ==========================================
@Composable
internal fun FocusedPlayerStage(
    currentTrack: SongItem,
    isPlaying: Boolean,
    isBuffering: Boolean = false,
    positionMs: Long,
    font: FontFamily,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp)
    ) {
        // Left Column: Large Bounded Artwork & Controls
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.Start)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClick = onBack)
                    .padding(vertical = 4.dp, horizontal = 8.dp)
            ) {
                Label("← Back to Workspace", font, RiffDesign.pink, size = 14)
            }

            Spacer(Modifier.height(16.dp))

            // Bounded Album Art
            Box(
                modifier = Modifier
                    .sizeIn(maxWidth = 360.dp, maxHeight = 360.dp)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .trackArtwork(currentTrack)
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
            )

            Spacer(Modifier.height(24.dp))

            Label(currentTrack.title, font, Color.White, size = 24)
            Spacer(Modifier.height(4.dp))
            Label("${currentTrack.artist} • ${currentTrack.album}", font, RiffDesign.muted, size = 16)

            Spacer(Modifier.height(20.dp))

            // Transport Controls
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onPrevious),
                    contentAlignment = Alignment.Center
                ) {
                    Label("⏮", font, Color.White, size = 20)
                }

                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable(onClick = onTogglePlay),
                    contentAlignment = Alignment.Center
                ) {
                    Label(if (isPlaying) "⏸" else "▶", font, RiffDesign.ink, size = 24)
                }

                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onNext),
                    contentAlignment = Alignment.Center
                ) {
                    Label("⏭", font, Color.White, size = 20)
                }
            }
        }

        // Right Column: Lyrics View
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight()
        ) {
            Label("SYNCHRONIZED LYRICS", font, RiffDesign.muted, size = 13)
            Spacer(Modifier.height(16.dp))
            Box(Modifier.weight(1f)) {
                LyricsView(
                    currentTrack = currentTrack,
                    positionMs = positionMs,
                    font = font,
                    onSeek = onSeek
                )
            }
        }
    }
}

// ==========================================
// 10. SETTINGS STAGE
// ==========================================
@Composable
internal fun SettingsStage(
    glassQuality: GlassQuality,
    reducedMotion: Boolean,
    eqPreset: String,
    volumeBoost: Boolean,
    crossfadeEnabled: Boolean,
    moodFilter: String?,
    font: FontFamily,
    onSetGlassQuality: (GlassQuality) -> Unit,
    onSetReducedMotion: (Boolean) -> Unit,
    onSetEqPreset: (String) -> Unit = {},
    onSetVolumeBoost: (Boolean) -> Unit = {},
    onSetCrossfade: (Boolean) -> Unit,
    onSetMoodFilter: (String?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val moods = listOf("Energize", "Focus", "Chill", "Uplift", "Sleep")
    val eqPresets = DesktopEqPresets.names

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item {
            Label("Settings", font, Color.White, size = 34)
            Spacer(Modifier.height(4.dp))
            Label("Visual physics, appearance, moods, and audio DSP preferences", font, RiffDesign.muted, size = 15)
        }

        item { HistoryPrivacyTools(font) }
        item { SleepTimerTools(font) }
        // Mood Filter Section
        item {
            Label("LISTENING MOOD", font, RiffDesign.muted, size = 13)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                moods.forEach { mood ->
                    val isSelected = moodFilter == mood
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) RiffDesign.pink else Color.White.copy(alpha = 0.08f))
                            .clickable { onSetMoodFilter(if (isSelected) null else mood) }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Label(mood, font, if (isSelected) Color.White else RiffDesign.muted, size = 14)
                    }
                }
            }
        }

        // Look and feel Section
        item {
            Label("LOOK AND FEEL", font, RiffDesign.muted, size = 13)
            Spacer(Modifier.height(10.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Label("Glass Quality Mode", font, Color.White, size = 17)
                Label("Riff's Skia shader: Full (dispersion & glints), Reduced (no dispersion), Frost, or Solid.", font, RiffDesign.muted, size = 13)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassQuality.entries.forEach { q ->
                        val isSelected = glassQuality == q
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) RiffDesign.pink else Color.White.copy(alpha = 0.1f))
                                .clickable { onSetGlassQuality(q) }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Label(q.name, font, if (isSelected) Color.White else RiffDesign.muted, size = 14)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Label("Reduced Motion", font, Color.White, size = 17)
                        Spacer(Modifier.height(2.dp))
                        Label("Disables non-essential springs and animations for instant transitions.", font, RiffDesign.muted, size = 13)
                    }
                    Box(Modifier.clickable { onSetReducedMotion(!reducedMotion) }) {
                        GlassSwitch(checked = reducedMotion)
                    }
                }
            }
        }

        // Audio DSP Controls Section
        item {
            Label("AUDIO DSP & ENGINE", font, RiffDesign.muted, size = 13)
            Spacer(Modifier.height(10.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Label("Crossfade",font,Color.White,size=17)
                        Label("Blend the final two seconds into the next track.",font,RiffDesign.muted,size=13)
                    }
                    Box(Modifier.clickable{onSetCrossfade(!crossfadeEnabled)}){GlassSwitch(checked=crossfadeEnabled)}
                }
                PlaybackSpeedControls(font)
                CanonicalEqualizerPanel(font)
                val normalizationHost=checkNotNull(LocalDesktopHost.current)
                val normalizationState by normalizationHost.state.collectAsState()
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
                    Column(Modifier.weight(1f)){Label("Normalize volume",font,Color.White,size=17);Label("Provider loudness, attenuation only (up to 12 dB). Unknown/local recordings stay unchanged.",font,RiffDesign.muted,size=13)}
                    Box(Modifier.clickable{normalizationHost.dispatch(DesktopCommand.SetNormalization(!normalizationState.normalizationEnabled))}){GlassSwitch(checked=normalizationState.normalizationEnabled)}
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Label("Volume Boost (1.5x with Limiter)", font, Color.White, size = 17)
                        Spacer(Modifier.height(2.dp))
                        Label("Dynamic peak limiter prevents digital clipping when boosted.", font, RiffDesign.muted, size = 13)
                    }
                    Box(Modifier.clickable { onSetVolumeBoost(!volumeBoost) }) {
                        GlassSwitch(checked = volumeBoost)
                    }
                }
            }
        }

        // About Section
        item {
            Label("ABOUT", font, RiffDesign.muted, size = 13)
            Spacer(Modifier.height(10.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Label("Riff Desktop — Milestone 2 Preview", font, Color.White, size = 17)
                Label("Windows 11 x64 Feasibility Build • Compose Multiplatform 1.12.2 • JDK 21", font, RiffDesign.muted, size = 13)
                Label("Bundled Inter 4.1 variable font (opsz=14/32) with AGSL-to-SkSL shader parity.", font, RiffDesign.muted, size = 13)
            }
        }
    }
}

// ==========================================
// 11. ENCORE STAGE
// ==========================================
@Composable
internal fun EncoreStage(font: FontFamily, modifier: Modifier = Modifier) = DesktopEncoreStage(font,modifier)

// ==========================================
// REUSABLE SUBCOMPONENTS
// ==========================================
@Composable
private fun SpeedDialCard(
    track: SongItem,
    font: FontFamily,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .trackArtwork(track)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Label(track.title, font, Color.White, size = 15)
            Spacer(Modifier.height(2.dp))
            Label(track.artist, font, RiffDesign.muted, size = 13)
        }
    }
}

@Composable
private fun AlbumCard(
    album: AlbumItem,
    font: FontFamily,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(160.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(148.dp)
                .clip(RoundedCornerShape(12.dp))
                .trackArtwork(album.tracks.firstOrNull()?:EmptyDesktopTrack)
        )
        Spacer(Modifier.height(8.dp))
        Label(album.title, font, Color.White, size = 15)
        Spacer(Modifier.height(2.dp))
        Label(album.artist, font, RiffDesign.muted, size = 13)
    }
}

@Composable
private fun ArtistCard(
    artist: ArtistItem,
    font: FontFamily,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(130.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(110.dp)
                .clip(CircleShape)
                .trackArtwork(artist.topTracks.firstOrNull()?:EmptyDesktopTrack)
        )
        Spacer(Modifier.height(8.dp))
        Label(artist.name, font, Color.White, size = 15)
    }
}

@Composable
private fun PlaylistRow(
    playlist: PlaylistItem,
    font: FontFamily,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .trackArtwork(playlist.tracks.firstOrNull()?:EmptyDesktopTrack)
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Label(playlist.name, font, Color.White, size = 15)
            Spacer(Modifier.height(2.dp))
            Label(playlist.description, font, RiffDesign.muted, size = 13)
        }
        Label("${playlist.trackCount} tracks", font, RiffDesign.muted, size = 13)
    }
}

@Composable
private fun ArtistRow(
    artist: ArtistItem,
    font: FontFamily,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .trackArtwork(artist.topTracks.firstOrNull()?:EmptyDesktopTrack)
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Label(artist.name, font, Color.White, size = 16)
            Spacer(Modifier.height(2.dp))
            Label(artist.monthlyListeners, font, RiffDesign.muted, size = 13)
        }
    }
}

@Composable
private fun TrackListRow(
    track: SongItem,
    font: FontFamily,
    isDownloaded: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled=track.available,role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .trackArtwork(track)
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Label(track.title, font, if(track.available)Color.White else RiffDesign.muted, size = 15)
            Spacer(Modifier.height(2.dp))
            Label(if(track.available)track.artist else "File unavailable · ${track.artist}", font, RiffDesign.muted, size = 13)
        }
        if (isDownloaded) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.1f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Label("Offline", font, RiffDesign.pink, size = 11)
            }
            Spacer(Modifier.width(12.dp))
        }
        TrackPlaylistAction(track,font)
        Spacer(Modifier.width(8.dp))
        Label(formatDuration(track.durationMs), font, RiffDesign.muted, size = 13)
    }
}

@Composable
private fun StageStateView(
    title: String,
    message: String,
    font: FontFamily
) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Label(title, font, Color.White, size = 20)
            Spacer(Modifier.height(6.dp))
            Label(message, font, RiffDesign.muted, size = 14)
        }
    }
}

@Composable
private fun EncoreStatCard(
    number: String,
    label: String,
    font: FontFamily,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(16.dp))
            .padding(20.dp)
    ) {
        Label(number, font, RiffDesign.pink, size = 34)
        Spacer(Modifier.height(4.dp))
        Label(label, font, RiffDesign.muted, size = 14)
    }
}
