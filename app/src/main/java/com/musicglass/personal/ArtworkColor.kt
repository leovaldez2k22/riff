package com.musicglass.personal

import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.allowHardware
import coil3.request.ImageRequest
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val artworkColors=java.util.Collections.synchronizedMap(object:LinkedHashMap<String,Color>(64,.75f,true) {
    override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Color>?)=size>100
})
@Composable internal fun rememberArtworkColor(url:String?):Color = rememberArtworkColorState(url).value
@Composable internal fun rememberArtworkColorState(url:String?):State<Color> {
    val context=LocalContext.current
    var target by remember(url) {mutableStateOf(artworkColors[url]?:Color(0xFF283444))}
    LaunchedEffect(url) {
        if(url==null) return@LaunchedEffect
        artworkColors[url]?.let {target=it;return@LaunchedEffect}
        val color=withContext(Dispatchers.IO) {suspendCatching {
            val image=SingletonImageLoader.get(context).execute(ImageRequest.Builder(context).data(url).size(72).allowHardware(false).build()).image?:return@suspendCatching null
            val bitmap=image.toBitmap();val pixels=IntArray(bitmap.width*bitmap.height)
            bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            val rgb=dominantArtworkRgb(pixels)
            Color(0xFF000000.toInt() or rgb)

        }.getOrNull()}
        color?.let {artworkColors[url]=it;target=it}
    }
    return animateColorAsState(target,GlassMotion.timed(GlassMotion.artworkMs,LocalMotionPolicy.current),label="artwork palette")
}
/**
 * The colour a screen takes from its artwork: 48% of it, as in rc20. Q13 darkened bright covers' tone until Pink
 * read at 4.5:1; the owner wanted rc20's colours back (24 Oct 2026), and accessibility.md records the trade-off.
 */
internal fun Color.readableArtworkTone()=Color(red*.48f,green*.48f,blue*.48f)
