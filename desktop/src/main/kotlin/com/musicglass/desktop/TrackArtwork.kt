package com.musicglass.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.background
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CancellationException

internal val LocalArtworkRepository=staticCompositionLocalOf<ArtworkRepository?>{null}

/** A draw result carries its content key, so an old asynchronous image cannot paint a new song. */
@Composable internal fun Modifier.trackArtwork(track:SongItem):Modifier {
    val repository=LocalArtworkRepository.current
    val key=track.artworkKey?:track.artworkUrl
    val cached=if(key!=null&&track.artworkKey!=null)repository?.cachedBitmap(track.artworkKey)?.let{key to it}else null
    val loaded by produceState<Pair<String,ImageBitmap>?>(cached,key,repository){
        value=cached
        if(key!=null&&repository!=null)try{val actual=repository.obtain(track.record()).artworkKey
            actual?.let{repository.bitmap(it)}?.let{value=key to it}}catch(cancel:CancellationException){throw cancel}catch(_:Exception){value=null}
    }
    val image=loaded?.takeIf{it.first==key}?.second
    return background(track.dominantColor).drawBehind {
        if(image!=null&&size.width>0&&size.height>0){
            val aspect=size.width/size.height
            val sw=minOf(image.width,(image.height*aspect).toInt()).coerceAtLeast(1)
            val sh=minOf(image.height,(image.width/aspect).toInt()).coerceAtLeast(1)
            drawImage(image,srcOffset=IntOffset((image.width-sw)/2,(image.height-sh)/2),srcSize=IntSize(sw,sh),dstSize=IntSize(size.width.toInt().coerceAtLeast(1),size.height.toInt().coerceAtLeast(1)))
        }
    }
}
