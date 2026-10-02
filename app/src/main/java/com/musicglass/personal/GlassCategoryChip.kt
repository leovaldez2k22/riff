package com.musicglass.personal
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*

internal fun categoryKeyVisible(key:String,tab:String):Boolean=when(tab) {
    "Search"->key.startsWith("search:")
    "Radio"->key.startsWith("radio:")
    "Home"->!key.startsWith("search:") && !key.startsWith("radio:")
    else->false
}
@Composable internal fun GlassCategoryChip(label:String,key:String=label,selected:Boolean=false,onClick:()->Unit) {
    val bounds=LocalGlassBounds.current
    val solid=LocalAccessibilitySettings.current.solidControls
    DisposableEffect(key,bounds) {onDispose {bounds?.remove(key)}}
    val shape=RoundedCornerShape(50)
    Box(Modifier.heightIn(min=48.dp).clickable(role=Role.Button,onClick = hapticClick(onClick)).semantics {this.selected=selected;contentDescription=label}
        .testTag(if(key.startsWith("search:")) "search_filter_${label.lowercase()}" else if(key.startsWith("radio:")) "radio_category_${label.lowercase()}" else "home_category_${label.lowercase()}"),contentAlignment=Alignment.Center) {
        Box(Modifier.height(36.dp).clip(shape).onGloballyPositioned {bounds?.set(key,it.unclippedBoundsInRoot())}
            .background(if(selected) Pink.copy(alpha=.22f) else if(solid) Color(0xFF1D2633) else White.copy(alpha=.04f))
            .border(.7.dp,White.copy(alpha=.35f),shape).padding(horizontal=16.dp),contentAlignment=Alignment.Center) {
            Text(label,color=White,fontSize=13.sp,modifier=Modifier.graphicsLayer {alpha=if(solid || bounds==null) 1f else 0f})
        }
    }
}
@Composable internal fun GlassCategoryLabels(bounds:Map<String,Rect>) {
    val density=LocalDensity.current
    Box(Modifier.fillMaxSize().clip(androidx.compose.ui.graphics.RectangleShape).clearAndSetSemantics {}) {
        bounds.forEach { (key,r)->
            Box(Modifier.offset {IntOffset(r.left.toInt(),r.top.toInt())}.size(with(density){r.width.toDp()},with(density){r.height.toDp()}),contentAlignment=Alignment.Center) {
                Text(key.removePrefix("search:").removePrefix("radio:"),color=White,fontSize=13.sp,maxLines=1)
            }
        }
    }
}
