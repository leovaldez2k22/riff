package com.musicglass.personal

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil3.compose.AsyncImage
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun openCatalogCard(model: ShellModel, card: ProviderHomeCard, play: (ProviderTrack)->Unit) {
    if(card.browseId != null && card.kind !in setOf(CatalogKind.Song,CatalogKind.Live,CatalogKind.Episode)) {
        model.openCollection(card.browseId,card.title)
    } else card.track()?.let(play) ?: card.browseId?.let {
        model.openCollection(it,card.title)
    }
}
@Composable internal fun CatalogCardRow(card:ProviderHomeCard,onEnqueue:((Boolean)->Unit)?=null,onOpen:()->Unit) {
    var menu by remember(card.key()) {mutableStateOf(false)}
    var playlistTrack by remember {mutableStateOf<ProviderTrack?>(null)}
    playlistTrack?.let {PlaylistPicker(it,{playlistTrack=null},{})}
    Row(Modifier.fillMaxWidth().heightIn(min=60.dp).cardPeek(card,14.dp,onOpen).testTag("catalog_${card.kind.name.lowercase()}_${card.key()}"),
        verticalAlignment=Alignment.CenterVertically) {
        CatalogArtwork(card.artworkUrl,card.key(),
            modifier=Modifier.size(48.dp).clip(if(card.kind==CatalogKind.Artist) CircleShape else RoundedCornerShape(12.dp)).background(Raised))
        Column(Modifier.weight(1f).padding(start=12.dp)) {
            Text(card.title,color=White,fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text("${card.kind.label}${card.subtitle.takeIf(String::isNotBlank)?.let { " \u00B7 $it" }.orEmpty()}",color=Muted,fontSize=12.sp,maxLines=2)
        }
        if(onEnqueue!=null && card.track()!=null) Box {
            IconButton({ uiHaptic(); menu=true},modifier=Modifier.testTag("catalog_menu_${card.key()}")) {Icon(Icons.Rounded.MoreHoriz,"Song options",tint=Muted)}
            SongActionMenu(menu,{menu=false}) {
                SongActionItem("Play next",onClick={ menu=false;onEnqueue(true)})
                SongActionItem("Add to queue",onClick={ menu=false;onEnqueue(false)})
                card.track()?.let {DownloadSongAction(it,onDismiss={menu=false});AddToPlaylistAction(it,{menu=false}) {playlistTrack=it}}
            }
        }
    }
}
@Composable internal fun CatalogSearchScreen(model:ShellModel,bottom:Dp,playback:PlaybackGateway,
    provider:YouTubeMusicProvider,onNotice:(String)->Unit) {
    val scope=rememberCoroutineScope()
    val context=LocalContext.current;val keyboard=LocalSoftwareKeyboardController.current
    val history=remember(context) {SearchHistory.get(context)}
    // Searches belong to the owner that made them; switching account swaps the list.
    val searchOwner=remember(provider.authRevision,provider.storageRevision) {provider.storageScopeKey()}
    LaunchedEffect(history,searchOwner) {history.bind(searchOwner)}
    val focus=remember {FocusRequester()}
    LaunchedEffect(model.searchFocusRequest) {if(model.searchFocusRequest>0) {delay(150);focus.requestFocus();keyboard?.show()}}
    val glassBounds=LocalGlassBounds.current
    val voice=remember(context) { VoiceSearchController(context) }
    DisposableEffect(voice) {onDispose {voice.close()}}
    val micPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {granted->
        if(granted) voice.start {model.searchQuery=it} else voice.permissionDenied()
    }
    var cards by remember { mutableStateOf<List<ProviderHomeCard>>(emptyList()) }
    var next by remember { mutableStateOf<String?>(null) }
    // A page token is followed once in one listing, so a provider that sends one again cannot keep Load more alive.
    var pageTokens by remember { mutableStateOf(PageTokens()) }
    var loading by remember { mutableStateOf(false) };var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(model.searchQuery,model.searchFilter,provider.authRevision,retry) {
        cards=emptyList();next=null;error=null;pageTokens=PageTokens()
        if(model.searchQuery.isBlank()) { loading=false;return@LaunchedEffect }
        loading=true;delay(450)
        provider.discover(model.searchQuery,filter=model.searchFilter).fold(
            onSuccess={ cards=it.cards;next=pageTokens.next(it.next);history.record(model.searchQuery) },onFailure={ error=it.message })
        loading=false
    }
    fun open(card:ProviderHomeCard) {history.record(model.searchQuery);keyboard?.hide();openCatalogCard(model,card) {scope.launch {playRealTrack(provider,playback,it,onNotice,video=model.searchFilter=="Videos")}}}
    ScreenList(model,bottom) {
        item { ScreenTitle("Search","Songs, albums, artists and playlists") }
        item { OutlinedTextField(model.searchQuery,{ model.searchQuery=it.take(160) },singleLine=true,shape=RoundedCornerShape(28.dp),
            label={ Text("Search YouTube Music") },modifier=Modifier.fillMaxWidth().focusRequester(focus).testTag("search_input"),
            keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={history.record(model.searchQuery);keyboard?.hide()}),
            trailingIcon={Row {
                if(model.searchQuery.isNotEmpty()) IconButton({uiHaptic();model.searchQuery=""},modifier=Modifier.testTag("search_clear")) {Icon(Icons.Rounded.Close,"Clear search")}
                IconButton({ uiHaptic(); if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) voice.start {model.searchQuery=it} else micPermission.launch(Manifest.permission.RECORD_AUDIO)}) {Icon(Icons.Rounded.Mic,"Voice search")}
            }}) }
        item { Row(Modifier.fillMaxWidth().fullBleed(20.dp).horizontalScroll(rememberScrollState()).padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("All","Songs","Albums","Artists","Playlists","Podcasts","Videos").forEach { kind ->
                GlassCategoryChip(kind,"search:$kind",model.searchFilter==kind) {model.searchFilter=kind}
            }
        } }
        if(model.searchQuery.isBlank() && history.entries.isNotEmpty()) {
            item {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("Recent searches",color=White,modifier=Modifier.weight(1f));TextButton(hapticClick(history::clear)) {Text("Clear all")}
            }}
            items(history.entries,key={"search-history:$it"}) {query->
                Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable { uiHaptic(); history.record(query);model.searchQuery=query;keyboard?.hide()}.testTag("search_history_row"),verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Rounded.History,null,tint=Muted);Text(query,color=White,modifier=Modifier.weight(1f).padding(horizontal=12.dp),maxLines=1,overflow=TextOverflow.Ellipsis)
                    IconButton({ uiHaptic(); history.remove(query)}) {Icon(Icons.Rounded.Close,"Remove $query from search history",tint=Muted)}
                }
            }
        }
        voice.message?.let {item {Text(it,color=Muted,fontSize=12.sp)}}
        item {FilterChip(model.searchSort=="Title",{ uiHaptic(HapticCue.Selection); model.searchSort=if(model.searchSort=="Title") "Relevance" else "Title"},label={Text("Sort: ${model.searchSort}")})}
        if(loading) item { Text("Searching\u2026",color=Muted) }
        error?.let { item { Text(it,color=Pink);TextButton({ uiHaptic();  retry++ }) { Text("Retry") } } }
        if(!loading && error==null && cards.isEmpty()) item { Text(if(model.searchQuery.isBlank()) "Find your next listen." else "No matching results.",color=Muted) }
        items(if(model.searchSort=="Title") cards.sortedBy {it.title.lowercase()} else cards,key={it.key()}) { card -> CatalogCardRow(card,onEnqueue={next->scope.launch{enqueueRealTrack(provider,playback,card,next,onNotice)}}) { open(card) } }
        if(next!=null) item { Button({ uiHaptic(); 
            val token=next?:return@Button;val query=model.searchQuery;val filter=model.searchFilter;val revision=provider.authRevision
            loading=true;scope.launch {
                val result=provider.discover(query,token,filter)
                if(query==model.searchQuery && filter==model.searchFilter && revision==provider.authRevision) {
                    result.fold(onSuccess={ cards=(cards+it.cards).distinctBy { c->c.key() };next=pageTokens.next(it.next) },onFailure={ onNotice(it.message?:"Could not load more") })
                    loading=false
                }
            }
        },enabled=!loading,modifier=Modifier.testTag("catalog_load_more")) { Text("Load more") } }
    }
}
@Composable internal fun CatalogDetailScreen(model:ShellModel,bottom:Dp,provider:YouTubeMusicProvider,
    playback:PlaybackGateway,onNotice:(String)->Unit) {
    val scope=rememberCoroutineScope();val id=model.selectedPlaylistId
    var page by remember(id) { mutableStateOf(provider.cachedCollection(id)) };var loading by remember(id) { mutableStateOf(true) }
    var error by remember(id) { mutableStateOf<String?>(null) };var retry by remember(id) { mutableIntStateOf(0) }
    LaunchedEffect(id,provider.authRevision,provider.libraryRevision,retry) {
        loading=true;error=null
        (if(id.startsWith("VL") || id.startsWith("MPRE")) provider.completeCollection(id) else provider.browseCatalog(id)).fold(onSuccess={page=it},onFailure={error=it.message});loading=false
    }
    val shown=page?:provider.partialCollection(id)
    if(shown!=null && (id.startsWith("MPRE") || id.startsWith("UC") || id.startsWith("VL"))) {
        key(id) {ImmersiveCollectionDetail(model,bottom,shown,provider,playback,onNotice,complete=page!=null,loadingError=error,onRetry={retry++})};return
    }
    fun open(card:ProviderHomeCard)=openCatalogCard(model,card) { scope.launch { playRealTrack(provider,playback,it,onNotice) } }
    ScreenList(model,bottom) {
        item { BackHeading("Back",model::back) }
        page?.artworkUrl?.let { art -> item { AsyncImage(art,null,contentScale=ContentScale.Crop,
            modifier=Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(24.dp)).testTag("collection_artwork")) } }
        item { ScreenTitle(page?.title?.ifBlank { model.selectedCollectionTitle }?:model.selectedCollectionTitle,
            page?.subtitle?.takeIf(String::isNotBlank)?.take(600)) }
        if(loading) item { Text("Loading\u2026",color=Muted) }
        error?.let { item { Text(it,color=Pink);TextButton({ uiHaptic(); retry++}) {Text("Retry")} } }
        val data=page
        if(data!=null) {
            if(data.shelves.size>1) data.shelves.forEachIndexed { i,shelf -> item(key="shelf:$i") {
                HomeCardShelf(shelf.title,shelf.cards,150.dp,onOpen=::open)
            } } else items(data.cards,key={it.key()}) {card->CatalogCardRow(card,onEnqueue={next->scope.launch{enqueueRealTrack(provider,playback,card,next,onNotice)}}) {open(card)}}
            if(data.next!=null) item { Button({ uiHaptic(); 
                val token=data.next;val revision=provider.authRevision;loading=true
                scope.launch { val result=provider.browseCatalog(id,token)
                    if(model.selectedPlaylistId==id && revision==provider.authRevision) {
                        result.fold(onSuccess={ page=data.copy(cards=(data.cards+it.cards).distinctBy {c->c.key()},next=it.next?.takeUnless {n->n==token},shelves=emptyList()) },onFailure={onNotice(it.message?:"Could not load more")});loading=false
                    }
                }
            },enabled=!loading) {Text("Load more")} }
        }
    }
}
@Composable internal fun MediaExploreScreen(model:ShellModel,bottom:Dp,title:String,provider:YouTubeMusicProvider,
    playback:PlaybackGateway,onNotice:(String)->Unit) {
    val scope=rememberCoroutineScope();val genre=if(title=="Radio") model.radioCategory else model.podcastGenre
    val epoch by provider.feedCache.revision.collectAsState()
    val cacheKey=if(title=="Radio") radioFeedKey(genre) else "podcasts:$genre"
    var shelves by remember(title,genre,provider.authRevision) {mutableStateOf(provider.cachedFeed(cacheKey).orEmpty())}
    var loading by remember {mutableStateOf(true)};var error by remember {mutableStateOf<String?>(null)};var retry by remember {mutableIntStateOf(0)}
    
    LaunchedEffect(title,genre,provider.authRevision,epoch,retry) {
        loading=provider.cachedFeed(cacheKey)==null;error=null
        val result=if(title=="Radio") provider.liveStations(genre) else provider.podcastSections(genre)
        result.fold(onSuccess={shelves=it},onFailure={error=it.message});loading=false
    }
    fun open(card:ProviderHomeCard)=openCatalogCard(model,card) {scope.launch {playRealTrack(provider,playback,it,onNotice)}}
    ScreenList(model,bottom) {
        item { ScreenTitle(title,if(title=="Radio") "Live now on YouTube" else "Shows and episodes for you") }
        if(title=="Radio") item {Row(Modifier.fillMaxWidth().fullBleed(20.dp).horizontalScroll(rememberScrollState()).padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            radioCategories.forEach {g->GlassCategoryChip(g,key="radio:$g",selected=genre==g) {model.radioCategory=g}}
        }}
        if(title=="Podcasts") item {Row(Modifier.fillMaxWidth().fullBleed(20.dp).horizontalScroll(rememberScrollState()).padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("For you","Tech","True crime","Politics","Science","History","Comedy","Business","Health","Education","Sports").forEach {g->
                GlassCategoryChip(g,key="podcast:$g",selected=genre==g,tag="podcast_genre_${g.lowercase().replace(' ','_')}") {model.podcastGenre=g}}
        }}

        if(loading) item {Text("Loading\u2026",color=Muted)}
        error?.let {item {Text(it,color=Pink)}}
        shelves.forEachIndexed {i,shelf->item(key="explore:$i"){HomeCardShelf(shelf.title,shelf.cards,180.dp,maxCards=if(title=="Radio") Int.MAX_VALUE else 12,onOpen=::open)}}
        if(!loading && shelves.isEmpty()) item {Text(if(title=="Radio") "Stations appear when their channels are live." else "No recommended shows available yet.",color=Muted)}
        item {ToolLink(if(title=="Radio") "Find more live stations" else "Find a podcast") {
            model.searchQuery=if(title=="Radio") "live radio" else "podcasts";model.searchFilter=if(title=="Radio") "All" else "Podcasts";model.select("Search")
        }}
    }
}
@Composable internal fun AccountLibraryScreen(model:ShellModel,bottom:Dp,collections:LocalCollections,
    playback:PlaybackGateway,provider:YouTubeMusicProvider,onNotice:(String)->Unit) {
    val scope=rememberCoroutineScope();val kind=model.libraryFilter
    var cards by remember(kind) {mutableStateOf(provider.cachedLibrary(if(kind=="Favorites") "Songs" else kind)?.cards.orEmpty())};var next by remember(kind){mutableStateOf<String?>(null)}
    var loading by remember(kind){mutableStateOf(false)};var error by remember(kind){mutableStateOf<String?>(null)};var retry by remember(kind){mutableIntStateOf(0)}
    LaunchedEffect(kind,provider.authRevision,provider.libraryRevision,retry) {
        next=null;error=null
        if(provider.signedIn && kind !in setOf("History","On device")) {
            loading=provider.cachedLibrary(kind)==null
            val result=provider.libraryCatalog(if(kind=="Favorites") "Songs" else kind)
            result.fold(onSuccess={cards=if(kind=="All" && provider.libraryScanRevision>0) it.cards.filterNot {it.kind==CatalogKind.Playlist}+provider.libraryPlaylistSnapshot else if(kind=="Playlists" && provider.libraryScanRevision>0) provider.libraryPlaylistSnapshot else it.cards;next=if(kind=="Playlists" && provider.libraryScanRevision>0) null else it.next},onFailure={error=it.message});loading=false
        }
    }
    LaunchedEffect(provider.libraryScanRevision,kind) {
        if(kind=="Playlists" && provider.libraryScanRevision>0) {cards=provider.libraryPlaylistSnapshot;next=null}
        else if(kind=="All" && provider.libraryScanRevision>0) cards=cards.filterNot {it.kind==CatalogKind.Playlist}+provider.libraryPlaylistSnapshot
    }
    fun open(card:ProviderHomeCard)=openCatalogCard(model,card){scope.launch {playRealTrack(provider,playback,it,onNotice)}}
    ScreenList(model,bottom) {
        item {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Top) {
            Box(Modifier.weight(1f)) {ScreenTitle("Library","Your YouTube Music collection")}
            IconButton({uiHaptic();model.push(Route.ListeningHistory)},modifier=Modifier.testTag("library_history")) {
                Icon(Icons.Rounded.History,"Listening history",tint=White)
            }
        }}
        item {Row(Modifier.fillMaxWidth().fullBleed(20.dp).horizontalScroll(rememberScrollState()).padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("All","Songs","Favorites","Playlists","Albums","Artists","Podcasts","History").forEach {filter->
                GlassCategoryChip(if(filter=="Favorites") "Liked songs" else filter,key="library:$filter",selected=kind==filter,tag="library_filter_${filter.lowercase()}") {model.libraryFilter=filter}}
        }}
        item {LibraryShortcutTiles(model)}
        if(!provider.signedIn) item {ToolLink("Sign in to YouTube Music"){model.push(Route.Account)}}
        if(loading) item {Text("Loading your library\u2026",color=Muted)}
        error?.let {item {Text(it,color=Pink);TextButton({ uiHaptic(); retry++}){Text("Retry")}}}
        items(cards,key={it.key()}) {card->CatalogCardRow(card,onEnqueue={next->scope.launch{enqueueRealTrack(provider,playback,card,next,onNotice)}}){open(card)}}
        if(next!=null) item {Button({ uiHaptic(); 
            val token=next?:return@Button;val revision=provider.authRevision;loading=true
            scope.launch {
                val result=if(kind=="Favorites") provider.likedSongsPage(token).map{CatalogPage(it.tracks.map(ProviderTrack::homeCard),it.nextToken)}
                    else provider.libraryCatalog(kind,token)
                if(model.libraryFilter==kind && revision==provider.authRevision) {result.fold(onSuccess={cards=(cards+it.cards).distinctBy {c->c.key()};next=it.next?.takeUnless {n->n==token}},onFailure={onNotice(it.message?:"Could not load library")});loading=false}
            }
        },enabled=!loading,modifier=Modifier.testTag("library_load_more")){Text("Load more")}}
        val local=when(kind){"History"->collections.onlineHistory;"Favorites"->collections.onlineFavorites;"Playlists"->collections.onlinePlaylist;else->emptyList()}
        if(local.isNotEmpty()) item {Text(if(kind=="History") "Listening history" else "Saved on this phone",color=Muted)}
        items(local.filterNot {track->cards.any {it.videoId==track.videoId}},key={"local:${it.videoId}"}) {track->ProviderSongRow(track,{scope.launch{playRealTrack(provider,playback,track,onNotice)}})}
        if(!loading && error==null && cards.isEmpty() && local.isEmpty() && provider.signedIn) item {Text("Nothing in this collection yet.",color=Muted)}
    }
}
internal suspend fun enqueueRealTrack(provider:YouTubeMusicProvider,playback:PlaybackGateway,
    card:ProviderHomeCard,next:Boolean,onNotice:(String)->Unit) {
    val track=card.track()?:return
    if(playback is MusicPlaybackGateway) {playback.enqueueSong(track,next);uiHaptic(HapticCue.Confirm);onNotice(if(next) "Playing next" else "Added to queue");return}
    val revision=provider.authRevision
    provider.resolve(track.videoId,track.isLive).fold(onSuccess={stream->
        if(revision==provider.authRevision) {
            if(playback.state.queue.isEmpty()) playback.playProvider(track,stream)
            else playback.enqueueProvider(track,stream,next)
            uiHaptic(HapticCue.Confirm)
            onNotice(if(next) "Playing next" else "Added to queue")
        }
    },onFailure={onNotice(it.message?:"Could not add to queue")})
}
