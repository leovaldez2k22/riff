package com.musicglass.personal

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.error
import coil3.request.fallback

internal fun artworkPlaceholderIndex(key:String)=Math.floorMod(key.hashCode(),20)
internal fun artworkPlaceholder(key:String):Int = intArrayOf(
    R.drawable.cover_fallback_01,R.drawable.cover_fallback_02,R.drawable.cover_fallback_03,R.drawable.cover_fallback_04,
    R.drawable.cover_fallback_05,R.drawable.cover_fallback_06,R.drawable.cover_fallback_07,R.drawable.cover_fallback_08,
    R.drawable.cover_fallback_09,R.drawable.cover_fallback_10,R.drawable.cover_fallback_11,R.drawable.cover_fallback_12,
    R.drawable.cover_fallback_13,R.drawable.cover_fallback_14,R.drawable.cover_fallback_15,R.drawable.cover_fallback_16,
    R.drawable.cover_fallback_17,R.drawable.cover_fallback_18,R.drawable.cover_fallback_19,R.drawable.cover_fallback_20
)[artworkPlaceholderIndex(key)]
/** Stable cover per collection; both absent URLs and failed image requests show real artwork. */
@Composable internal fun CatalogArtwork(url:String?,key:String,modifier:Modifier=Modifier,description:String?=null) {
    val context=LocalPlatformContext.current
    val fallback=artworkPlaceholder(key)
    val request=androidx.compose.runtime.remember(context,url,key) {ImageRequest.Builder(context).data(url?.takeIf {it.isNotBlank()})
        .fallback(fallback).error(fallback).crossfade(false).build()}
    AsyncImage(request,description,modifier=modifier,contentScale=ContentScale.Crop)
}
