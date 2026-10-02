package com.musicglass.personal

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.dp

/** A symmetric, narrow alpha fade: underlying artwork/theme remains visible. */
internal fun Modifier.fadingEdges()=graphicsLayer {compositingStrategy=CompositingStrategy.Offscreen}.drawWithContent {
    drawContent()
    val edge=minOf(12.dp.toPx()/size.width,.08f)
    drawRect(Brush.horizontalGradient(0f to Color.Transparent,edge to Color.White,
        (1f-edge) to Color.White,1f to Color.Transparent),blendMode=BlendMode.DstIn)
}
