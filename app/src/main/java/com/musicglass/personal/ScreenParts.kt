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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun ScreenList(model: ShellModel, bottom: androidx.compose.ui.unit.Dp,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val listState = rememberLazyListState()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    androidx.compose.runtime.CompositionLocalProvider(LocalShelfInset provides RiffSpace.screenEdge) {LazyColumn(
        state = listState, modifier = Modifier.fillMaxSize().then(
            if (landscape) Modifier.padding(bottom = bottom) else Modifier),
        contentPadding = PaddingValues(start = RiffSpace.screenEdge, end = RiffSpace.screenEdge,
            top = statusInset + RiffSpace.tight, bottom = if (landscape) RiffSpace.screenEdge else bottom),
        verticalArrangement = Arrangement.spacedBy(RiffSpace.item), content = content
    )}
}

@Composable
internal fun ScreenTitle(title: String, subtitle: String? = null) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(title) { focusRequester.requestFocus() }
    Column(Modifier.focusRequester(focusRequester).focusable()
        .semantics { heading() },
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, color = White, fontSize = RiffType.screenTitle, lineHeight = RiffType.screenTitleLine,
            fontWeight = FontWeight.Bold)
        if (subtitle != null) Text(subtitle, color = Muted, fontSize = RiffType.body)
    }
}

/**
 * For a full-screen layer drawn over another page: a touch that no control in the layer takes stops here instead
 * of reaching the page underneath. Compose gives a touch to the topmost of overlapping siblings that listens for
 * touches, so listening is enough; nothing is consumed, and the layer's own controls work as before.
 */
internal fun Modifier.blockTouchesBelow(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope { while (true) awaitPointerEvent() }
}

internal suspend fun playRealTrack(provider: YouTubeMusicProvider, playback: PlaybackGateway,
    track: ProviderTrack, onNotice: (String) -> Unit,video:Boolean=false) {
    if(!video && !track.isLive) {playback.playSong(track);return}
    val revision = provider.authRevision
    kotlinx.coroutines.withTimeoutOrNull(if(track.isLive) 60_000 else 30_000) {provider.resolve(track.videoId,track.isLive,video=video || track.isLive)}.let {result->
        if(revision!=provider.authRevision) return
        result?.fold(onSuccess={playback.playProvider(track,it);if(track.isLive) (playback as? MusicPlaybackGateway)?.onLivePlay?.invoke(track)},
            onFailure={onNotice(if(track.isLive) string(R.string.live_stream_unavailable_try_refreshing_radio) else it.message?:string(R.string.this_song_is_unavailable))})
            ?: onNotice(string(R.string.preparing_playback_timed_out_try_again))
    }
}

@Composable
internal fun ProviderSongRow(track: ProviderTrack, onPlay: () -> Unit,
    saved: Boolean? = null, onSave: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).clip(RoundedCornerShape(RiffRadius.artwork))
        .clickable(onClick = hapticClick(onPlay)).testTag("provider_result_${track.videoId}"),
        verticalAlignment = Alignment.CenterVertically) {
        // The same stand-in cover whether the address is missing or the image fails, so the row never changes size.
        CatalogArtwork(track.artworkUrl, track.videoId,
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(RiffRadius.artwork)).background(White.copy(alpha = .08f)))
        Column(Modifier.weight(1f).padding(start = RiffSpace.rowGap)) {
            Text(track.title, color = White, fontSize = RiffType.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(track.artist, color = Muted, fontSize = RiffType.detail, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (saved != null) IconButton(onClick = hapticClick(onSave)) {
            Icon(if (saved) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (saved) string(R.string.remove_favorite) else string(R.string.add_favorite), tint = Pink)
        }
    }
}

@Composable
internal fun ToolLink(title: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = RiffSpace.touch).clip(RoundedCornerShape(RiffRadius.card)).clickable(onClick = hapticClick(onClick))
        .background(White.copy(alpha = .06f)).padding(RiffSpace.block), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = White, modifier = Modifier.weight(1f))
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Muted)
    }
}

@Composable
internal fun BackHeading(title: String, onBack: () -> Unit, overPicture: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        // Over a picture the arrow and the word carry their own shadows, no patch under them (Q18-008).
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = hapticClick(onBack), modifier = Modifier.size(RiffSpace.touch).testTag("back")) {
                // A screen whose heading is the word Back says "Back", not "Back to Back" (Q18-011).
                val name = if (title == string(R.string.back)) title else string(R.string.back_to_x, title)
                if (overPicture) OverPictureIcon(Icons.AutoMirrored.Filled.ArrowBack, name)
                else Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = name, tint = White)
            }
            Spacer(Modifier.width(RiffSpace.item))
            Text(title, color = White, fontSize = RiffType.message, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = if (overPicture) TextStyle(shadow = RiffOverPicture.textShadow) else TextStyle.Default)
        }
    }
}

/** A white icon set straight over a picture: two dark copies under it, which a screen reader does not hear (Q18-002, Q18-008). */
@Composable
internal fun OverPictureIcon(icon: ImageVector, contentDescription: String?) {
    Box(contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = Color.Black.copy(alpha = RiffOverPicture.iconHaloAlpha),
            modifier = Modifier.blur(RiffOverPicture.iconHalo, BlurredEdgeTreatment.Unbounded))
        Icon(icon, contentDescription = null, tint = Color.Black.copy(alpha = RiffOverPicture.iconEdgeAlpha),
            modifier = Modifier.blur(RiffOverPicture.iconEdge, BlurredEdgeTreatment.Unbounded))
        Icon(icon, contentDescription = contentDescription, tint = White)
    }
}
