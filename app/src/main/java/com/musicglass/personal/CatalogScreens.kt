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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** One search and what it had shown. Held in memory by the shell; never saved and never shown for another account. */
internal class SearchSnapshot(val query:String,val filter:String,val authRevision:Int,val cards:List<ProviderHomeCard>,val next:String?,val tokens:PageTokens)

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
            Text(catalogRowLabel(card.kind,card.subtitle),color=Muted,fontSize=RiffType.detail,maxLines=2,overflow=TextOverflow.Ellipsis)
        }
        if(onEnqueue!=null && card.track()!=null) Box {
            IconButton({ uiHaptic(); menu=true},modifier=Modifier.testTag("catalog_menu_${card.key()}")) {Icon(Icons.Rounded.MoreHoriz,string(R.string.song_options),tint=Muted)}
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
    val voice=remember(context) { VoiceSearchController(context) }
    DisposableEffect(voice) {onDispose {voice.close()}}
    val micPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {granted->
        if(granted) voice.start {model.searchQuery=it} else voice.permissionDenied()
    }
    // Coming back to Search (from an album, from another tab) shows the same results at once, where they were.
    val kept=remember {model.searchSnapshot?.takeIf {it.query==model.searchQuery && it.filter==model.searchFilter && it.authRevision==provider.authRevision}}
    var cards by remember { mutableStateOf(kept?.cards.orEmpty()) }
    var next by remember { mutableStateOf(kept?.next) }
    // A page token is followed once in one listing, so a provider that sends one again cannot keep Load more alive.
    var pageTokens by remember { mutableStateOf(kept?.tokens?:PageTokens()) }
    fun keep() {model.searchSnapshot=SearchSnapshot(model.searchQuery,model.searchFilter,provider.authRevision,cards,next,pageTokens)}
    var loading by remember { mutableStateOf(false) };var error by remember { mutableStateOf<String?>(null) }
    // A page that could not be added stays said on screen, next to the button that tries it again.
    var moreError by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(model.searchQuery,model.searchFilter,provider.authRevision,retry) {
        if(retry==0 && model.searchSnapshot?.let {it.query==model.searchQuery && it.filter==model.searchFilter && it.authRevision==provider.authRevision && it.cards===cards}==true) return@LaunchedEffect
        cards=emptyList();next=null;error=null;moreError=null;pageTokens=PageTokens()
        if(model.searchQuery.isBlank()) { loading=false;return@LaunchedEffect }
        loading=true;delay(450)
        provider.discover(model.searchQuery,filter=model.searchFilter).fold(
            onSuccess={ cards=it.cards;next=pageTokens.next(it.next);history.record(model.searchQuery);keep() },onFailure={ error=it.message })
        loading=false
    }
    fun open(card:ProviderHomeCard) {history.record(model.searchQuery);keyboard?.hide();openCatalogCard(model,card) {scope.launch {playRealTrack(provider,playback,it,onNotice,video=model.searchFilter=="Videos")}}}
    ScreenList(model,bottom) {
        item { ScreenTitle(string(R.string.search),string(R.string.songs_albums_artists_and_playlists)) }
        item { OutlinedTextField(model.searchQuery,{ model.searchQuery=it.take(160) },singleLine=true,shape=RoundedCornerShape(28.dp),
            label={ Text(string(R.string.search_youtube_music)) },modifier=Modifier.fillMaxWidth().focusRequester(focus).testTag("search_input"),
            keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={history.record(model.searchQuery);keyboard?.hide()}),
            trailingIcon={Row {
                if(model.searchQuery.isNotEmpty()) IconButton({uiHaptic();model.searchQuery=""},modifier=Modifier.testTag("search_clear")) {Icon(Icons.Rounded.Close,string(R.string.clear_search))}
                IconButton({ uiHaptic(); if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) voice.start {model.searchQuery=it} else micPermission.launch(Manifest.permission.RECORD_AUDIO)}) {Icon(Icons.Rounded.Mic,string(R.string.voice_search))}
            }}) }
        item { Row(Modifier.fillMaxWidth().fullBleed(20.dp).horizontalScroll(rememberScrollState()).padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("All","Songs","Albums","Artists","Playlists","Podcasts","Videos").forEach { kind ->
                GlassCategoryChip(kind,"search:$kind",model.searchFilter==kind) {model.searchFilter=kind}
            }
        } }
        if(model.searchQuery.isBlank() && history.entries.isNotEmpty()) {
            item {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(string(R.string.recent_searches),color=White,modifier=Modifier.weight(1f));TextButton(hapticClick(history::clear)) {Text(string(R.string.clear_all))}
            }}
            items(history.entries,key={"search-history:$it"}) {query->
                Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable { uiHaptic(); history.record(query);model.searchQuery=query;keyboard?.hide()}.testTag("search_history_row"),verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Rounded.History,null,tint=Muted);Text(query,color=White,modifier=Modifier.weight(1f).padding(horizontal=12.dp),maxLines=1,overflow=TextOverflow.Ellipsis)
                    IconButton({ uiHaptic(); history.remove(query)}) {Icon(Icons.Rounded.Close,string(R.string.remove_x_from_search_history, query),tint=Muted)}
                }
            }
        }
        voice.message?.let {item {Text(it,color=Muted,fontSize=12.sp)}}
        item {FilterChip(model.searchSort=="Title",{ uiHaptic(HapticCue.Selection); model.searchSort=if(model.searchSort=="Title") "Relevance" else "Title"},label={Text(string(R.string.sort_x, keyLabel(model.searchSort)))})}
        if(loading && cards.isEmpty()) item(key="state:loading") { ListSkeleton(string(R.string.searching)) }
        error?.let { item(key="state:problem") { ContentProblem(it,onRetry={retry++},onSignIn={model.push(Route.Account)}) } }
        if(!loading && error==null && cards.isEmpty()) item(key="state:empty") { ContentEmpty(if(model.searchQuery.isBlank()) string(R.string.find_your_next_listen) else string(R.string.no_matching_results)) }
        items(if(model.searchSort=="Title") cards.sortedBy {it.title.lowercase()} else cards,key={it.key()}) { card -> CatalogCardRow(card,onEnqueue={next->scope.launch{enqueueRealTrack(provider,playback,card,next,onNotice)}}) { open(card) } }
        moreError?.let { item(key="state:more") { Text(it,color=White,fontSize=RiffType.body,modifier=Modifier.testTag("load_more_problem")) } }
        if(next!=null) item { Button({ uiHaptic(); 
            val token=next?:return@Button;val query=model.searchQuery;val filter=model.searchFilter;val revision=provider.authRevision
            loading=true;moreError=null;scope.launch {
                val result=provider.discover(query,token,filter)
                if(query==model.searchQuery && filter==model.searchFilter && revision==provider.authRevision) {
                    result.fold(onSuccess={ cards=(cards+it.cards).distinctBy { c->c.key() };next=pageTokens.next(it.next);keep() },onFailure={ moreError=it.message?:string(R.string.could_not_load_more) })
                    loading=false
                }
            }
        },enabled=!loading,modifier=Modifier.testTag("catalog_load_more")) { Text(string(R.string.load_more)) } }
    }
}
@Composable internal fun CatalogDetailScreen(model:ShellModel,bottom:Dp,provider:YouTubeMusicProvider,
    playback:PlaybackGateway,onNotice:(String)->Unit) {
    val shownRoute=LocalCollectionRoute.current
    val scope=rememberCoroutineScope();val id=shownRoute?.id?:model.selectedPlaylistId;val routeTitle=shownRoute?.title?:model.selectedCollectionTitle
    var page by remember(id) { mutableStateOf(provider.cachedCollection(id)) };var loading by remember(id) { mutableStateOf(true) }
    var error by remember(id) { mutableStateOf<String?>(null) };var retry by remember(id) { mutableIntStateOf(0) }
    var moreError by remember(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(id,provider.authRevision,provider.libraryRevision,retry) {
        loading=true;error=null;moreError=null
        (if(id.startsWith("VL") || id.startsWith("MPRE")) provider.completeCollection(id) else provider.browseCatalog(id)).fold(onSuccess={page=it},onFailure={error=it.message});loading=false
    }
    val shown=page?:provider.partialCollection(id)
    if(shown!=null && (id.startsWith("MPRE") || id.startsWith("UC") || id.startsWith("VL"))) {
        key(id) {ImmersiveCollectionDetail(model,bottom,shown,provider,playback,onNotice,complete=page!=null,loadingError=error,onRetry={retry++})};return
    }
    fun open(card:ProviderHomeCard)=openCatalogCard(model,card) { scope.launch { playRealTrack(provider,playback,it,onNotice) } }
    ScreenList(model,bottom) {
        item { BackHeading(string(R.string.back),model::back) }
        page?.artworkUrl?.let { art -> item { CatalogArtwork(art,id,
            modifier=Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(RiffRadius.hero)).testTag("collection_artwork")) } }
        item { ScreenTitle(page?.title?.ifBlank { routeTitle }?:routeTitle,
            page?.subtitle?.takeIf(String::isNotBlank)?.take(600)) }
        if(loading && page==null) item(key="state:loading") { ListSkeleton(string(R.string.loading)) }
        error?.let { item(key="state:problem") { ContentProblem(it,onRetry={retry++},onSignIn={model.push(Route.Account)}) } }
        val data=page
        if(!loading && error==null && data!=null && data.cards.isEmpty() && data.shelves.isEmpty()) item(key="state:empty") { ContentEmpty(string(R.string.nothing_here_yet)) }
        if(data!=null) {
            if(data.shelves.size>1) data.shelves.forEachIndexed { i,shelf -> item(key="shelf:$i") {
                HomeCardShelf(shelf.title,shelf.cards,150.dp,onOpen=::open)
            } } else items(data.cards,key={it.key()}) {card->CatalogCardRow(card,onEnqueue={next->scope.launch{enqueueRealTrack(provider,playback,card,next,onNotice)}}) {open(card)}}
            moreError?.let { item(key="state:more") { Text(it,color=White,fontSize=RiffType.body,modifier=Modifier.testTag("load_more_problem")) } }
            if(data.next!=null) item { Button({ uiHaptic(); 
                val token=data.next;val revision=provider.authRevision;loading=true;moreError=null
                scope.launch { val result=provider.browseCatalog(id,token)
                    if(revision==provider.authRevision) {
                        result.fold(onSuccess={ page=data.copy(cards=(data.cards+it.cards).distinctBy {c->c.key()},next=it.next?.takeUnless {n->n==token},shelves=emptyList()) },onFailure={moreError=it.message?:string(R.string.could_not_load_more)});loading=false
                    }
                }
            },enabled=!loading) {Text(string(R.string.load_more))} }
        }
    }
}
@Composable internal fun MediaExploreScreen(model:ShellModel,bottom:Dp,title:String,provider:YouTubeMusicProvider,
    playback:PlaybackGateway,onNotice:(String)->Unit) {
    val scope=rememberCoroutineScope();val genre=if(title=="Radio") model.radioCategory else model.podcastGenre
    val epoch by provider.feedCache.revision.collectAsStateWithLifecycle()
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
        item { ScreenTitle(title,if(title=="Radio") string(R.string.live_now_on_youtube) else string(R.string.shows_and_episodes_for_you)) }
        if(title=="Radio") item {Row(Modifier.fillMaxWidth().fullBleed(20.dp).horizontalScroll(rememberScrollState()).padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            radioCategories.forEach {g->GlassCategoryChip(g,key="radio:$g",selected=genre==g) {model.radioCategory=g}}
        }}
        if(title=="Podcasts") item {Row(Modifier.fillMaxWidth().fullBleed(20.dp).horizontalScroll(rememberScrollState()).padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("For you","Tech","True crime","Politics","Science","History","Comedy","Business","Health","Education","Sports").forEach {g->
                GlassCategoryChip(g,key="podcast:$g",selected=genre==g,tag="podcast_genre_${g.lowercase().replace(' ','_')}") {model.podcastGenre=g}}
        }}

        if(loading && shelves.isEmpty()) item(key="state:loading") {ListSkeleton(string(R.string.loading_x, title))}
        error?.let {item(key="state:problem") {ContentProblem(it,onRetry={retry++},onSignIn={model.push(Route.Account)})}}
        shelves.forEachIndexed {i,shelf->item(key="explore:$i"){HomeCardShelf(shelf.title,shelf.cards,180.dp,maxCards=if(title=="Radio") Int.MAX_VALUE else 12,onOpen=::open)}}
        if(!loading && error==null && shelves.isEmpty()) item(key="state:empty") {ContentEmpty(if(title=="Radio") string(R.string.stations_appear_when_their_channels_are_live) else string(R.string.no_recommended_shows_available_yet))}
        item {ToolLink(if(title=="Radio") string(R.string.find_more_live_stations) else string(R.string.find_a_podcast)) {
            model.searchQuery=if(title=="Radio") "live radio" else "podcasts";model.searchFilter=if(title=="Radio") "All" else "Podcasts";model.select("Search")
        }}
    }
}
@Composable internal fun AccountLibraryScreen(model:ShellModel,bottom:Dp,collections:LocalCollections,
    playback:PlaybackGateway,provider:YouTubeMusicProvider,onNotice:(String)->Unit) {
    val scope=rememberCoroutineScope();val kind=model.libraryFilter
    var cards by remember(kind) {mutableStateOf(provider.cachedLibrary(kind)?.cards.orEmpty())};var next by remember(kind){mutableStateOf<String?>(null)}
    var loading by remember(kind){mutableStateOf(false)};var error by remember(kind){mutableStateOf<String?>(null)};var retry by remember(kind){mutableIntStateOf(0)}
    var moreError by remember(kind){mutableStateOf<String?>(null)}
    var newPlaylist by remember {mutableStateOf(false)}
    if(newPlaylist) NewPlaylistDialog(provider,onDismiss={newPlaylist=false}) {name->newPlaylist=false;onNotice(string(R.string.made_playlist_x,name))}
    LaunchedEffect(kind,provider.authRevision,provider.libraryRevision,retry) {
        next=null;error=null;moreError=null
        if(provider.signedIn && kind !in setOf("History","On device")) {
            loading=provider.cachedLibrary(kind)==null
            val result=provider.libraryCatalog(kind)
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
            Box(Modifier.weight(1f)) {ScreenTitle(string(R.string.library),string(R.string.your_youtube_music_collection))}
            IconButton({uiHaptic();model.push(Route.ListeningHistory)},modifier=Modifier.testTag("library_history")) {
                Icon(Icons.Rounded.History,string(R.string.listening_history),tint=White)
            }
        }}
        item {Row(Modifier.fillMaxWidth().fullBleed(20.dp).horizontalScroll(rememberScrollState()).padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("All","Songs","Favorites","Playlists","Albums","Artists","Podcasts","History").forEach {filter->
                GlassCategoryChip(if(filter=="Favorites") string(R.string.liked_songs) else filter,key="library:$filter",selected=kind==filter,tag="library_filter_${filter.lowercase()}") {model.libraryFilter=filter}}
        }}
        item {LibraryShortcutTiles(model)}
        if(!provider.signedIn) item {ToolLink(string(R.string.sign_in_to_youtube_music)){model.push(Route.Account)}}
        if(provider.signedIn && kind=="Playlists") item(key="new_playlist") {ToolLink(string(R.string.new_playlist)) {uiHaptic();newPlaylist=true}}
        if(loading && cards.isEmpty()) item(key="state:loading") {ListSkeleton(string(R.string.loading_your_library))}
        error?.let {item(key="state:problem") {ContentProblem(it,onRetry={retry++},onSignIn={model.push(Route.Account)})}}
        items(cards,key={it.key()}) {card->CatalogCardRow(card,onEnqueue={next->scope.launch{enqueueRealTrack(provider,playback,card,next,onNotice)}}){open(card)}}
        moreError?.let {item(key="state:more") {Text(it,color=White,fontSize=RiffType.body,modifier=Modifier.testTag("load_more_problem"))}}
        if(next!=null) item {Button({ uiHaptic(); 
            val token=next?:return@Button;val revision=provider.authRevision;loading=true;moreError=null
            scope.launch {
                val result=provider.libraryCatalog(kind,token)
                if(model.libraryFilter==kind && revision==provider.authRevision) {result.fold(onSuccess={cards=(cards+it.cards).distinctBy {c->c.key()};next=it.next?.takeUnless {n->n==token}},onFailure={moreError=it.message?:string(R.string.could_not_load_more)});loading=false}
            }
        },enabled=!loading,modifier=Modifier.testTag("library_load_more")){Text(string(R.string.load_more))}}
        val local=when(kind){"History"->collections.onlineHistory;"Favorites"->collections.onlineFavorites;"Playlists"->collections.onlinePlaylist;else->emptyList()}
        if(local.isNotEmpty()) item {Text(if(kind=="History") string(R.string.listening_history) else string(R.string.saved_on_this_phone),color=Muted)}
        items(local.filterNot {track->cards.any {it.videoId==track.videoId}},key={"local:${it.videoId}"}) {track->ProviderSongRow(track,{scope.launch{playRealTrack(provider,playback,track,onNotice)}})}
        if(!loading && error==null && cards.isEmpty() && local.isEmpty() && provider.signedIn) item(key="state:empty") {ContentEmpty(string(R.string.nothing_in_this_collection_yet))}
    }
}
internal suspend fun enqueueRealTrack(provider:YouTubeMusicProvider,playback:PlaybackGateway,
    card:ProviderHomeCard,next:Boolean,onNotice:(String)->Unit) {
    val track=card.track()?:return
    if(playback is MusicPlaybackGateway) {
        val outcome=if(playback.state.queue.isEmpty()) EnqueueOutcome.Added else enqueueOutcome(playback.state.queue,playback.state.index,track,next)
        playback.enqueueSong(track,next);uiHaptic(HapticCue.Confirm);onNotice(enqueueNotice(outcome,next));return
    }
    val revision=provider.authRevision
    provider.resolve(track.videoId,track.isLive).fold(onSuccess={stream->
        if(revision==provider.authRevision) {
            if(playback.state.queue.isEmpty()) playback.playProvider(track,stream)
            else playback.enqueueProvider(track,stream,next)
            uiHaptic(HapticCue.Confirm)
            onNotice(if(next) string(R.string.playing_next) else string(R.string.added_to_queue))
        }
    },onFailure={onNotice(it.message?:string(R.string.could_not_add_to_queue))})
}
