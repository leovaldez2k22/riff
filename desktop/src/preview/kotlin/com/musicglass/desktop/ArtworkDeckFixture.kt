package com.musicglass.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.IntSize

/** Deterministic colorful/near-black fixture; image preparation is cached outside drawing. */
@Composable
internal fun ArtworkDeckFixture(modifier: Modifier = Modifier) {
    val images=remember {
        val pixels=IntArray(128*128) { i ->
            val x=i%128; val y=i/128
            (0xff shl 24) or ((x*2) shl 16) or ((y*2) shl 8) or (180-x)
        }
        fun bitmap(values:IntArray,w:Int,h:Int):ImageBitmap {
            return opaqueArtworkBitmap(values, w, h)
        }
        val mesh=deckMesh(meshColors(pixels,128,128)).map(::deckTone).toIntArray()
        Triple(bitmap(pixels,128,128),bitmap(mesh,6,6),bitmap(boxBlur(pixels,128,128),128,128))
    }
    Canvas(modifier) {
        drawImage(images.second,dstSize=IntSize(size.width.toInt(),size.height.toInt()),filterQuality=FilterQuality.Low)
        drawImage(images.third,dstSize=IntSize(size.width.toInt(),size.height.toInt()),alpha=.6f,filterQuality=FilterQuality.Low)
        drawContext.canvas.saveLayer(Rect(Offset.Zero,size),Paint())
        drawImage(images.first,dstSize=IntSize(size.width.toInt(),size.height.toInt()),filterQuality=FilterQuality.Low)
        drawRect(Brush.verticalGradient(0f to Color.White,.40f to Color.White,
            .60f to Color.White.copy(alpha=.85f),.76f to Color.White.copy(alpha=.40f),
            .92f to Color.White.copy(alpha=.04f),1f to Color.Transparent),blendMode=BlendMode.DstIn)
        drawContext.canvas.restore()
        drawRect(Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.4f)),startY=size.height*.5f),
            topLeft=Offset(0f,size.height*.5f),size=Size(size.width,size.height*.5f))
    }
}


