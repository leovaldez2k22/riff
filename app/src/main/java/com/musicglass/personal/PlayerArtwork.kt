package com.musicglass.personal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil3.request.ImageRequest
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

internal fun playerBackground(color:Color=Color(0xFF283444)) = Brush.verticalGradient(
    0f to color.readableArtworkTone(), .48f to color.readableArtworkTone(), 1f to color.readableArtworkTone())

@Composable
internal fun PlayerArtwork(url: String?, modifier: Modifier = Modifier, blend: Float = 1f) {
    val fade = blend.coerceIn(0f, 1f)
    val context=LocalContext.current
    // A morph changes layout constraints every frame; keep its bitmap request dimensions stable.
    val image=remember(url,context) {ImageRequest.Builder(context).data(url).size(1024).build()}
    AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop,
        modifier = modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(
                    0f to Color.White, .40f to Color.White,
                    .60f to Color.White.copy(alpha = 1f - .15f * fade),
                    .76f to Color.White.copy(alpha = 1f - .60f * fade),
                    .92f to Color.White.copy(alpha = 1f - .96f * fade),
                    1f to Color.White.copy(alpha = 1f - fade)), blendMode = BlendMode.DstIn)
            })
}
