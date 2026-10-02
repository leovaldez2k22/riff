package com.musicglass.personal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import java.util.Locale

/** Keep the provider's ordering, moving just Forgotten favourites behind Quick picks. */
internal fun orderedHomeShelves(shelves:List<ProviderHomeSection>):List<ProviderHomeSection> {
    val forgotten=shelves.filter {it.title.contains("forgotten fav",true)}
    val rest=shelves.filterNot {it.title.contains("forgotten fav",true)}.toMutableList()
    val quick=rest.indexOfLast {it.title.contains("quick picks",true)}
    rest.addAll(if(quick>=0) quick+1 else rest.size,forgotten)
    return rest
}

/** Real, daily refreshed photographs; the fade keeps the feed legible. */
@Composable internal fun MoodBackdrop(mood:String) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val repository=remember(context) {MoodPhotos.get(context)}
    val photos by repository.photos.collectAsState()
    val photo=photos[mood]
    LaunchedEffect(mood) {repository.ensure(mood)}
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(.56f)) {
            if(photo!=null) coil3.compose.AsyncImage(photo.file,null,contentScale=androidx.compose.ui.layout.ContentScale.Crop,modifier=Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha=.52f),.3f to Color.Black.copy(alpha=.42f),1f to Color.Black)))
        }
    }
}
