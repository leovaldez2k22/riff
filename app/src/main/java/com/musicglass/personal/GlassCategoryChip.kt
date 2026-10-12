package com.musicglass.personal
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*

/** What the label layer draws for a glass chip; its geometry lives in [LocalGlassBounds] under the same key. */
internal data class GlassChipStyle(val label:String,val selected:Boolean,val natural:IntSize=IntSize.Zero,
    /** Corner radius in px for a rounded-rectangle tile; 0 is a pill-shaped chip with a label. */
    val corner:Float=0f,
    /** A tile's content, drawn above the glass so frost and refraction never blur its cover or text. */
    val content:(@Composable () -> Unit)?=null)
internal val LocalGlassChips=compositionLocalOf<SnapshotStateMap<String,GlassChipStyle>?> {null}

internal fun categoryKeyVisible(key:String,tab:String):Boolean=when(tab) {
    "Search"->key.startsWith("search:")
    "Radio"->key.startsWith("radio:")
    "Podcasts"->key.startsWith("podcast:")
    "Library"->key.startsWith("library:")
    "Home"->listOf("search:","radio:","podcast:","library:").none(key::startsWith)
    else->false
}
/** Chips on screen for this tab, left to right; the shader has 8 slots, so off-screen chips must not take them. */
internal fun visibleGlassChips(bounds:Map<String,Rect>,tab:String,route:String,width:Int,height:Int):List<Pair<String,Rect>> =
    if(route.isNotEmpty()) emptyList() else bounds.entries.filter {(key,r)->
        r.bottom>0 && r.top<height && r.right>0 && r.left<width && categoryKeyVisible(key,tab)
    }.sortedWith(compareBy({it.value.top},{it.value.left})).map {it.key to it.value}.take(8)

/** Bounds after ancestor layer transforms, so a chip inside a stretching list reports its stretched size. */
internal fun LayoutCoordinates.transformedBoundsInRoot():Rect {
    val a=localToRoot(Offset.Zero);val b=localToRoot(Offset(size.width.toFloat(),size.height.toFloat()))
    return Rect(minOf(a.x,b.x),minOf(a.y,b.y),maxOf(a.x,b.x),maxOf(a.y,b.y))
}
private val chipShape=RoundedCornerShape(50)
private fun chipFill(selected:Boolean,solid:Boolean)=if(selected) Pink.copy(alpha=.22f) else if(solid) Color(0xFF1D2633) else White.copy(alpha=.04f)
private fun Modifier.chipFace(selected:Boolean,solid:Boolean)=clip(chipShape).background(chipFill(selected,solid))
    .border(.7.dp,White.copy(alpha=.35f),chipShape)

@Composable internal fun GlassCategoryChip(label:String,key:String=label,selected:Boolean=false,tag:String?=null,onClick:()->Unit) {
    val bounds=LocalGlassBounds.current;val chips=LocalGlassChips.current
    val solid=LocalAccessibilitySettings.current.solidControls
    // With glass on, the face is drawn above the shader by GlassCategoryLabels. Drawing it here too would put it
    // under the refraction, which pulls the border and fill inward and leaves a second, smaller outline.
    val glass=!solid && bounds!=null && chips!=null
    SideEffect {chips?.let {it[key]=GlassChipStyle(keyLabel(label),selected,it[key]?.natural?:IntSize.Zero)}}
    DisposableEffect(key,bounds,chips) {onDispose {bounds?.remove(key);chips?.remove(key)}}
    Box(Modifier.heightIn(min=48.dp).clickable(role=Role.Button,onClick = hapticClick(onClick)).semantics {this.selected=selected;contentDescription=keyLabel(label)}
        .testTag(tag?:if(key.startsWith("search:")) "search_filter_${label.lowercase()}" else if(key.startsWith("radio:")) "radio_category_${label.lowercase()}" else "home_category_${label.lowercase()}"),contentAlignment=Alignment.Center) {
        Box(Modifier.height(36.dp).onGloballyPositioned {
                bounds?.set(key,it.transformedBoundsInRoot())
                chips?.get(key)?.takeIf {s->s.natural!=it.size}?.let {s->chips[key]=s.copy(natural=it.size)}
            }
            .then(if(glass) Modifier else Modifier.chipFace(selected,solid)).padding(horizontal=16.dp),contentAlignment=Alignment.Center) {
            // Kept for measurement either way; only visible when this chip draws its own face.
            Text(keyLabel(label),color=if(glass) Color.Transparent else White,fontSize=13.sp,maxLines=1)
        }
    }
}
/** Faces and labels for the glass chips, drawn above the scene so neither refraction nor frost distorts them. */
@Composable internal fun GlassCategoryLabels(chips:List<Pair<String,Rect>>,styles:Map<String,GlassChipStyle>) {
    val density=LocalDensity.current
    Box(Modifier.fillMaxSize().clip(androidx.compose.ui.graphics.RectangleShape).clearAndSetSemantics {}) {
        chips.forEach { (key,r)->
            val style=styles[key]?:return@forEach
            if(style.corner>0f) {
                // A tile's face, outline and content; the page keeps an invisible copy for touch and accessibility.
                val shape=RoundedCornerShape(with(density) {style.corner.toDp()})
                Box(Modifier.offset {IntOffset(r.left.toInt(),r.top.toInt())}.size(with(density){r.width.toDp()},with(density){r.height.toDp()})
                    .clip(shape).background(White.copy(alpha=.04f)).border(.7.dp,White.copy(alpha=.35f),shape)) {style.content?.invoke()}
                return@forEach
            }
            Box(Modifier.offset {IntOffset(r.left.toInt(),r.top.toInt())}.size(with(density){r.width.toDp()},with(density){r.height.toDp()})
                .chipFace(style.selected,false),contentAlignment=Alignment.Center) {
                // Stretch the label with its chip while the page overscrolls.
                val n=style.natural
                Text(style.label,color=White,fontSize=13.sp,maxLines=1,softWrap=false,modifier=Modifier.graphicsLayer {
                    if(n.width>0 && n.height>0) {scaleX=r.width/n.width;scaleY=r.height/n.height}
                })
            }
        }
    }
}
