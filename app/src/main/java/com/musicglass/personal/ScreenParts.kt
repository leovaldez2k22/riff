package com.musicglass.personal

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun ScreenList(model: ShellModel, bottom: androidx.compose.ui.unit.Dp,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val listState = rememberLazyListState()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    androidx.compose.runtime.CompositionLocalProvider(LocalShelfInset provides 20.dp) {LazyColumn(
        state = listState, modifier = Modifier.fillMaxSize().then(
            if (landscape) Modifier.padding(bottom = bottom) else Modifier),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp,
            top = statusInset + 4.dp, bottom = if (landscape) 20.dp else bottom),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content
    )}
}

@Composable
internal fun ScreenTitle(title: String, subtitle: String? = null) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(title) { focusRequester.requestFocus() }
    Column(Modifier.focusRequester(focusRequester).focusable()
        .semantics { heading() },
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, color = White, fontSize = 31.sp, lineHeight = 34.sp,
            fontWeight = FontWeight.Bold)
        if (subtitle != null) Text(subtitle, color = Muted, fontSize = 14.sp)
    }
}

internal suspend fun playRealTrack(provider: YouTubeMusicProvider, playback: PlaybackGateway,
    track: ProviderTrack, onNotice: (String) -> Unit,video:Boolean=false) {
    if(!video && !track.isLive) {playback.playSong(track);return}
    val revision = provider.authRevision
    kotlinx.coroutines.withTimeoutOrNull(if(track.isLive) 60_000 else 30_000) {provider.resolve(track.videoId,track.isLive,video=video || track.isLive)}.let {result->
        if(revision!=provider.authRevision) return
        result?.fold(onSuccess={playback.playProvider(track,it);if(track.isLive) (playback as? MusicPlaybackGateway)?.onLivePlay?.invoke(track)},
            onFailure={onNotice(if(track.isLive) "Live stream unavailable. Try refreshing Radio." else it.message?:"This song is unavailable")})
            ?: onNotice("Preparing playback timed out. Try again.")
    }
}

@Composable
internal fun ProviderSongRow(track: ProviderTrack, onPlay: () -> Unit,
    saved: Boolean? = null, onSave: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).clip(RoundedCornerShape(12.dp))
        .clickable(onClick = hapticClick(onPlay)).testTag("provider_result_${track.videoId}"),
        verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(model = track.artworkUrl, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(White.copy(alpha = .08f)))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(track.title, color = White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(track.artist, color = Muted, fontSize = 12.sp, maxLines = 1)
        }
        if (saved != null) IconButton(onClick = hapticClick(onSave)) {
            Icon(if (saved) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (saved) "Remove favorite" else "Add favorite", tint = Pink)
        }
    }
}

@Composable
internal fun ToolLink(title: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = hapticClick(onClick))
        .background(White.copy(alpha = .06f)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = White, modifier = Modifier.weight(1f))
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Muted)
    }
}

@Composable
internal fun BackHeading(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = hapticClick(onBack), modifier = Modifier.size(48.dp).testTag("back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back to $title", tint = White)
        }
        Spacer(Modifier.width(8.dp))
        Text(title, color = White, fontSize = 16.sp)
    }
}
