package com.musicglass.personal

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.testTag

@Composable
internal fun HomeFeedScreen(model: ShellModel, bottom: Dp, playback: PlaybackGateway,
    collections: LocalCollections, provider: YouTubeMusicProvider, onNotice: (String) -> Unit,
    onCategoryBounds: (String, Rect?) -> Unit = { _, _ -> }) {
    val scope = rememberCoroutineScope()
    val categories = listOf("All")+provider.homeMoods.map {it.title}
    val mood=model.homeMood
    val epoch by provider.feedCache.revision.collectAsState()
    val solidControls = LocalAccessibilitySettings.current.solidControls
    DisposableEffect(Unit) {
        onDispose { categories.forEach { onCategoryBounds(it, null) } }
    }
    var shelves by remember(mood,provider.authRevision,epoch) { mutableStateOf(provider.cachedFeed("home:$mood").orEmpty()) }
    val profile = provider.accountProfile
    var feedReady by remember(mood,provider.authRevision,epoch) {mutableStateOf(provider.cachedFeed("home:$mood")!=null)}
    var loading by remember(mood,provider.authRevision,epoch) { mutableStateOf(!feedReady) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(provider.authRevision, mood, epoch, refresh) {
        error = null; loading = provider.cachedFeed("home:$mood")==null
        provider.homeSections(mood).fold(onSuccess = { shelves = it;feedReady=true },
            onFailure = { error = it.message ?: "YouTube Music is unavailable" })
        loading = false
    }
    LaunchedEffect(provider.authRevision, epoch, refresh) {
        if (provider.signedIn) provider.profile()
    }
    fun open(card: ProviderHomeCard) { openCatalogCard(model,card) { track ->
        scope.launch { playRealTrack(provider,playback,track,onNotice) }
    } }
    val recent = if(feedReady && mood.isBlank()) collections.onlineHistory.map(ProviderTrack::homeCard) else emptyList()
    val photoContext=androidx.compose.ui.platform.LocalContext.current
    val hidden = remember(photoContext) { NotInterestedStore.get(photoContext) }
    val musicShelves = shelves.filterNot(::podcastShelf).withoutNotInterestedCards(hidden)
    val speed = if(feedReady) homeSpeedDialCandidates(recent,musicShelves).withoutNotInterested(hidden) else emptyList()
    val photoRepository=remember(photoContext) {MoodPhotos.get(photoContext)}
    val photos by photoRepository.photos.collectAsState()
    var photoCredits by remember {mutableStateOf(false)}
    val currentPhoto=photos[mood]
    if(photoCredits && currentPhoto!=null) {
        val context=androidx.compose.ui.platform.LocalContext.current
        androidx.compose.material3.AlertDialog(onDismissRequest={photoCredits=false},title={Text("Background photo")},text={Column {
            Text(currentPhoto.author);Text(currentPhoto.license)
            androidx.compose.material3.TextButton({context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(currentPhoto.page)))}) {Text("View original photo")}
            if(currentPhoto.licenseUrl.isNotBlank()) androidx.compose.material3.TextButton({context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(currentPhoto.licenseUrl)))}) {Text("View licence")}
        }},confirmButton={androidx.compose.material3.TextButton({photoCredits=false}) {Text("Done")}})
    }
    val statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(Modifier.fillMaxSize()) {
    if(mood.isNotBlank()) MoodBackdrop(mood)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = statusInset + 2.dp, bottom = bottom + 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Home", color = White, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { uiHaptic();  model.push("settings") },modifier = Modifier.testTag("home_settings")) {
                    if (profile?.artworkUrl != null) AsyncImage(profile?.artworkUrl, contentDescription = "Settings",
                        contentScale = ContentScale.Crop, modifier = Modifier.size(38.dp).clip(CircleShape))
                    else Icon(Icons.Filled.AccountCircle, contentDescription = "Settings", tint = White)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { label ->
                    GlassCategoryChip(label,selected=if(label=="All") mood.isBlank() else label==mood) {model.homeMood=if(label=="All") "" else label}

                }
            }
        }
        if(mood.isNotBlank() && currentPhoto!=null) item {Text("Photo credits",color=Muted,fontSize=10.sp,modifier=Modifier.padding(horizontal=20.dp).clickable {photoCredits=true})}
        // Placeholder shelves sized like the real ones (W3); Home still waits for the fresh feed (rc17).
        if (loading && shelves.isEmpty()) item { HomeFeedSkeleton(Modifier.padding(top = 8.dp)) }
        error?.let { message -> item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message, color = Muted)
                Text("Retry", color = Pink, modifier = Modifier.clickable { uiHaptic();  refresh++ }.padding(8.dp))
            }
        } }
        if (speed.isNotEmpty()) item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(if (!provider.signedIn) "Discover your music" else
                    profile?.name?.takeIf(String::isNotBlank)?.let { "Hello, $it" } ?: "Welcome back",
                    color = Muted, fontSize = 12.sp)
                HomeHeading("Speed dial")
            }
            Spacer(Modifier.height(16.dp))
            HomeSpeedDial(speed, ::open)
        }
        if (recent.isNotEmpty() && musicShelves.none {it.title.contains("listen again",true)}) item { HomeRowShelf("Recently played", recent, ::open) }
        val shown=homeDiscoveryShelves(musicShelves)+if(mood.isBlank() && !loading && error==null && musicShelves.none {it.title.contains("forgotten fav",true)}) listOf(ProviderHomeSection("Forgotten favourites",emptyList())) else emptyList()
        orderedHomeShelves(shown).forEachIndexed {index,shelf->
            if(shelf.cards.isNotEmpty()) item(key="shelf:$index") {
                if(shelf.title.contains("quick picks",true)) HomeRowShelf(shelf.title,shelf.cards,::open)
                else HomeCardShelf(shelf.title,shelf.cards,150.dp,onOpen=::open)
            } else if(shelf.title.contains("forgotten fav",true)) item(key="shelf:$index") {
                Column(Modifier.padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    HomeHeading("Forgotten favourites")
                    Text(if(provider.signedIn) "YouTube Music hasn't offered this shelf for your account yet." else "Sign in to find your forgotten favourites.",color=Muted,fontSize=13.sp)
                }
            }
        }
        if (!loading && error == null && speed.isEmpty()) item {
            Text("No recommendations available. Search for music to get started.", color = Muted,
                modifier = Modifier.padding(horizontal = 20.dp))
        }
    }
    }
}

@Composable
private fun HomeHeading(title: String, action: (() -> Unit)? = null,
    actionLabel: String = "Play all") {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            maxLines = 2, modifier = Modifier.weight(1f))
        if (action != null) Text(actionLabel, color = White, fontSize = 12.sp,
            modifier = Modifier.clip(RoundedCornerShape(20.dp))
                .background(White.copy(alpha = .10f)).clickable(onClick = hapticClick(action))
                .padding(horizontal = 12.dp, vertical = 7.dp))
    }
}

@Composable
private fun HomeSpeedDial(cards: List<ProviderHomeCard>, onOpen: (ProviderHomeCard) -> Unit) {
    val pages = ((cards.size + 8) / 9).coerceAtLeast(1)
    val pager = rememberPagerState(pageCount = { pages })
    val pagerDragged by pager.interactionSource.collectIsDraggedAsState()
    var manualPage by remember {mutableStateOf(false)}
    LaunchedEffect(pagerDragged) {if(pagerDragged) manualPage=true}
    LaunchedEffect(pager.settledPage,pager.isScrollInProgress) {
        if(manualPage && !pager.isScrollInProgress) {uiHaptic(HapticCue.Selection);manualPage=false}
    }
    Column {
        HorizontalPager(state = pager,modifier=Modifier) { page ->
            Column(Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(3) { column ->
                            val card = cards.getOrNull(page * 9 + row * 3 + column)
                            Box(Modifier.weight(1f).aspectRatio(1f)
                                .then(if (card != null) Modifier.cardPeek(card, 13.dp) { onOpen(card) }
                                    else Modifier).clip(RoundedCornerShape(13.dp))
                                .background(White.copy(alpha = .08f))) {
                                if (card != null) {
                                    HomeArtwork(card.artworkUrl,card.key(), Modifier.fillMaxSize())
                                    Box(Modifier.fillMaxWidth().height(42.dp)
                                        .background(Brush.verticalGradient(listOf(Color.Transparent,
                                            Color.Black.copy(alpha = .9f))))
                                        .align(Alignment.BottomCenter))
                                    Text(card.title, color = White, fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.align(Alignment.BottomStart)
                                            .padding(8.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.Center) {
            repeat(pages) { index ->
                Box(Modifier.padding(horizontal = 3.dp).size(7.dp).clip(CircleShape)
                    .background(if (pager.currentPage == index) White else White.copy(alpha = .3f)))
            }
        }
    }
}

@Composable
private fun HomeArtwork(url:String?,key:String,modifier:Modifier=Modifier) {
    CatalogArtwork(url,key,modifier)
}

@Composable
internal fun HomeRowShelf(title: String, cards: List<ProviderHomeCard>,
    onOpen: (ProviderHomeCard) -> Unit, emptyMessage: String = "Loading from YouTube Music\u2026",
    action: (() -> Unit)? = null, actionLabel: String = "Play all") {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.padding(horizontal = 20.dp)) {
            HomeHeading(title, action.takeIf { cards.isNotEmpty() }, actionLabel)
        }
        if (cards.isEmpty()) Text(emptyMessage, color = Muted, fontSize = 13.sp,
            modifier = Modifier.padding(end = 20.dp))
        else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=20.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            cards.chunked(4).forEach { group ->
                Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    group.forEach { card ->
                        Row(Modifier.fillMaxWidth().cardPeek(card, 12.dp) { onOpen(card) },
                            verticalAlignment = Alignment.CenterVertically) {
                            HomeArtwork(card.artworkUrl,card.key(), Modifier.size(58.dp)
                                .clip(RoundedCornerShape(9.dp)))
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(card.title, color = White, fontSize = 13.sp, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                Text(card.subtitle, color = Muted, fontSize = 12.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun HomeCardShelf(title: String, cards: List<ProviderHomeCard>, width: Dp, maxCards:Int=12,
    onOpen: (ProviderHomeCard) -> Unit) {
    Column(Modifier.fillMaxWidth().fullBleed(LocalShelfInset.current), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Box(Modifier.padding(horizontal=20.dp)) {HomeHeading(title)}
        if (cards.isEmpty()) Text("Loading from YouTube Music\u2026", color = Muted,
            fontSize = 13.sp)
        else LazyRow(Modifier.fillMaxWidth(),contentPadding=PaddingValues(horizontal=20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(cards.take(maxCards),key={index,card->"$index:${card.key()}"}) {_,card->
                Column(Modifier.width(width).cardPeek(card, 15.dp) { onOpen(card) }) {
                    HomeArtwork(card.artworkUrl,card.key(), Modifier.fillMaxWidth()
                        .aspectRatio(if (width > 200.dp && title.contains("videos", true)) 1.7f
                            else 1f).clip(RoundedCornerShape(15.dp)))
                    Spacer(Modifier.height(7.dp))
                    Text(card.title, color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(card.subtitle, color = Muted, fontSize = 12.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
