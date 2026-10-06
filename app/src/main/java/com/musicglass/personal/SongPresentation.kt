package com.musicglass.personal

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.media3.common.MediaItem

internal data class SongPresentation(val mediaId:String,val title:String,val artist:String,val artworkUrl:String?,val renderer:Int)
internal fun songPresentation(state:PlaybackUiState)=SongPresentation(state.mediaId,state.title,state.artist,state.artworkUrl,
    if(state.embedded) 2 else if(state.video) 1 else 0)

internal fun songPresentation(item:MediaItem)=SongPresentation(item.mediaId,item.mediaMetadata.title?.toString().orEmpty(),
    item.mediaMetadata.artist?.toString().orEmpty(),item.mediaMetadata.artworkUri?.toString(),0)

/** One actual audio handoff. Progress is read in drawing, so audio ticks do not recompose metadata. */
internal class SongCrossfade(val outgoing:SongPresentation,val incomingId:String,val durationMs:Long) {
    var progress by mutableFloatStateOf(0f)
}
// Service and UI live in the app process. A transient signal prevents replay on process restoration.
internal object SongCrossfadeState {var active by mutableStateOf<SongCrossfade?>(null)}
internal val LocalSongCrossfade=compositionLocalOf<SongCrossfade?> {null}
internal data class SongPresentationTransition(val current:SongPresentation,val blend:SongCrossfade?)

@Composable internal fun rememberSongPresentationTransition(state:PlaybackUiState):SongPresentationTransition {
    val blend=LocalSongCrossfade.current?.takeIf {it.incomingId==state.mediaId && it.outgoing.mediaId!=state.mediaId}
    return SongPresentationTransition(songPresentation(state),blend)
}

/** Only the audio engine's live blend fades content; ordinary song changes replace it immediately. */
@Composable internal fun SongPresentationTransition.SongContentFade(modifier:Modifier=Modifier,
    content:@Composable (SongPresentation)->Unit) {
    val active=blend.takeUnless {LocalMotionPolicy.current.reduced}
    Box(modifier) {
        if(active!=null) {
            key(active) {Box(Modifier.graphicsLayer {alpha=1f-active.progress.coerceIn(0f,1f)}) {content(active.outgoing)}}
            Box(Modifier.graphicsLayer {alpha=active.progress.coerceIn(0f,1f)}) {content(current)}
        } else content(current)
    }
}
