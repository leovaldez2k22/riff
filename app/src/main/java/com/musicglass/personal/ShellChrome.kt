package com.musicglass.personal

import android.graphics.RenderEffect
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.*
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun ChromeBar(model: ShellModel, compact: Boolean, playback: PlaybackGateway, modifier: Modifier,
    bottomInset: Float, miniMorph: () -> Float, miniDragging: Boolean,
    onMiniDrag: (Float, Long) -> Unit,
    onMiniRelease: () -> Unit, onMiniBounds: (Rect) -> Unit,
    onMiniElementBounds: (String, Rect) -> Unit,
    onNotice: (String) -> Unit) {
    val accessibility = LocalAccessibilitySettings.current
    val miniProgress = miniMorph()
    val navHeight = if (compact) 64.dp else 58.dp
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)
        .padding(bottom = (bottomInset + 10f).dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (model.miniVisible) {
            Surface(shape = RoundedCornerShape(32.dp),
                color = if (accessibility.solidControls) Color(0xFF11131A) else Color.Transparent,
                border = BorderStroke(1.dp, Color.White.copy(
                    alpha = if (accessibility.solidControls) .72f else .42f)),
                modifier = Modifier.fillMaxWidth().height(62.dp).testTag("mini_player")
                    .onGloballyPositioned { if(miniProgress<.001f) onMiniBounds(it.boundsInRoot()) }
                    .graphicsLayer { alpha = 1f - motionRamp(miniProgress, 0f, .02f) }
                    .pointerInput(model) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { change, amount ->
                                onMiniDrag(amount, change.uptimeMillis)
                                change.consume()
                            }, onDragEnd = onMiniRelease,
                            onDragCancel = onMiniRelease)
                    }) {
                MiniPlayerContents(playback, onOpen = { model.sheet = "mini" },
                    onElementBounds = {name,bounds->if(miniProgress<.001f) onMiniElementBounds(name,bounds)})
            }
        }
        Box(Modifier.graphicsLayer {
            alpha = 1f - motionRamp(miniProgress, 0f, .5f)
        }) { LiquidTabBar(model, compact, navHeight) }
    }
}

@Composable
internal fun LiquidTabBar(model: ShellModel, compact: Boolean, height: androidx.compose.ui.unit.Dp) {
    val names = listOf("Home", "Podcasts", "Radio", "Library", "Search")
    val accessibility = LocalAccessibilitySettings.current
    val rightToLeft = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current.density
    val largeText = LocalDensity.current.fontScale >= 1.6f
    var fingerX by remember { mutableStateOf<Float?>(null) }
    BoxWithConstraints(Modifier.fillMaxWidth().height(height).pointerInput(compact, rightToLeft) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            fingerX = down.position.x
            var releasedX = down.position.x
            var lastHover=TabHitTest.indexAt(releasedX,size.width.toFloat(),height.value*density,8f*density,rightToLeft)
            do {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                releasedX = change.position.x
                fingerX = releasedX
                val nextHover=TabHitTest.indexAt(releasedX,size.width.toFloat(),height.value*density,8f*density,rightToLeft)
                if(change.pressed && nextHover!=lastHover) {uiHaptic(HapticCue.Selection);lastHover=nextHover}
                if (!change.pressed) break
            } while (true)
            val width = size.width.toFloat()
            val searchWidth = height.value * density
            val gap = 8f * density
            val index = TabHitTest.indexAt(releasedX, width, searchWidth, gap,
                rightToLeft)
            val reset=model.selectFromTap(names[index], android.os.SystemClock.uptimeMillis())
            uiHaptic(if(reset) HapticCue.Confirm else HapticCue.Selection)
            fingerX = null
        }
    }) {
        val navTotalWidth=maxWidth
        val mainWidth = maxWidth - height - 8.dp
        val slot = mainWidth / 4
        val hovered = fingerX?.let { px ->
            TabHitTest.indexAt(px, maxWidth.value * density, height.value * density,
                8f * density, rightToLeft)
        }
        val active = hovered ?: names.indexOf(model.tab).coerceAtLeast(0)
        val motion = LocalMotionPolicy.current
        val targetWidth=if(active==4) height.value else slot.value
        val logicalFinger=fingerX?.let {if(rightToLeft) maxWidth.value-it/density else it/density}
        val targetCenter=logicalFinger?.coerceIn(targetWidth/2f,maxWidth.value-targetWidth/2f)
            ?:if(active==4) mainWidth.value+8f+height.value/2f else slot.value*(active+.5f)
        val center=animateFloatAsState(targetCenter,if(motion.reduced) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(dampingRatio=.82f,stiffness=if(fingerX!=null) 1200f else 600f),label="liquid pill position")
        val width=animateFloatAsState(targetWidth,GlassMotion.settle(motion),label="liquid pill width")
        val pressState=animateFloatAsState(if(fingerX!=null) 1f else 0f,GlassMotion.settle(motion),label="liquid contact")
        fun geometry():NavLens {
            val cx=if(rightToLeft) maxWidth.value-center.value else center.value
            return navigationLens(cx*density,width.value*density,height.value*density,pressState.value,
                (targetCenter-center.value)*density,accessibility.glassIntensity,motion.reduced || accessibility.solidControls)
        }
        val refraction=rememberNavigationRefraction()
        Row(Modifier.fillMaxSize().graphicsLayer {
            val lens=geometry()
            renderEffect=if(lens.gain>0f && refraction!=null) refraction(lens,size.width,size.height) else null
        }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = RoundedCornerShape(height / 2),
                color = if (accessibility.solidControls) Color(0xFF11131A) else Color.Transparent,
                border = BorderStroke(.65.dp, Color.White.copy(alpha =
                    if (accessibility.solidControls) .72f else .4f)),
                modifier = Modifier.width(mainWidth).height(height).testTag("navigation")) {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    names.take(4).forEach { name ->
                        NavItem(name, names[active] == name, !largeText,
                            Modifier.weight(1f).graphicsLayer {
                                if(android.os.Build.VERSION.SDK_INT<33) {val lens=geometry();val itemCenter=slot.value*density*(names.indexOf(name)+.5f)
                                    val physical=if(rightToLeft) navTotalWidth.value*density-itemCenter else itemCenter
                                    val contact=(1f-kotlin.math.abs(physical-lens.centerX)/lens.radiusX.coerceAtLeast(1f)).coerceIn(0f,1f)
                                    scaleX=1f+lens.gain*contact;scaleY=1f+lens.gain*contact}
                            }) { uiHaptic(HapticCue.Selection); model.selectFromTap(name,android.os.SystemClock.uptimeMillis()) }
                    }
                }
            }
            run {
                Surface(shape = CircleShape,
                    color = if (accessibility.solidControls) Color(0xFF11131A) else Color.Transparent,
                    border = BorderStroke(.65.dp, Color.White.copy(alpha =
                        if (accessibility.solidControls) .72f else .4f)),
                    modifier = Modifier.size(height).testTag("nav_search")) {
                    NavItem("Search", active == 4, false, Modifier.fillMaxSize().graphicsLayer {
                        if(android.os.Build.VERSION.SDK_INT<33) {val lens=geometry();val logical=mainWidth.value+8f+height.value/2f;val physical=(if(rightToLeft) navTotalWidth.value-logical else logical)*density
                            val contact=(1f-kotlin.math.abs(physical-lens.centerX)/lens.radiusX.coerceAtLeast(1f)).coerceIn(0f,1f);scaleX=1f+lens.gain*contact;scaleY=1f+lens.gain*contact}
                    }) {
                        uiHaptic(HapticCue.Selection); model.selectFromTap("Search",android.os.SystemClock.uptimeMillis())
                    }
                }
            }
        }
        LiquidSelector(::geometry,density,accessibility.solidControls,fingerX!=null)

    }
}

@Composable private fun LiquidSelector(geometry:()->NavLens,density:Float,solid:Boolean,pressed:Boolean) {
    Box(Modifier.fillMaxSize().drawWithCache {
        onDrawBehind {
            val lens=geometry()
            val left=lens.centerX-lens.radiusX;val top=lens.centerY-lens.radiusY
            val dimensions=androidx.compose.ui.geometry.Size(lens.radiusX*2,lens.radiusY*2)
            val origin=androidx.compose.ui.geometry.Offset(left,top)
            val radius=androidx.compose.ui.geometry.CornerRadius(lens.radiusY)
            drawRoundRect(Brush.verticalGradient(if(solid) listOf(Pink.copy(alpha=.10f),Pink.copy(alpha=.10f))
                else if(pressed) listOf(White.copy(alpha=.11f),Pink.copy(alpha=.04f),Color.Transparent)
                else listOf(Color.Transparent,Color.Transparent),startY=top,endY=top+dimensions.height),origin,dimensions,radius)
            drawRoundRect(Brush.linearGradient(listOf(White.copy(alpha=.82f),Pink.copy(alpha=.56f),White.copy(alpha=.25f)),start=origin,end=origin+androidx.compose.ui.geometry.Offset(dimensions.width,dimensions.height)),origin,dimensions,radius,style=androidx.compose.ui.graphics.drawscope.Stroke(density))
        }
    })
}

@Composable
private fun NavItem(name: String, selected: Boolean, showLabel: Boolean,
    modifier: Modifier, onClick: () -> Unit) {
    val icon = when (name) {
        "Home" -> Icons.Rounded.Home
        "Podcasts" -> Icons.Rounded.Podcasts
        "Radio" -> Icons.Rounded.Radio
        "Library" -> Icons.Rounded.LibraryMusic
        else -> Icons.Rounded.Search
    }
    Column(modifier.fillMaxSize().onKeyEvent {event ->
        if(event.type==KeyEventType.KeyUp && event.key in listOf(Key.Enter,Key.NumPadEnter,Key.Spacebar)) {onClick();true} else false
    }.focusable().testTag("tab_${name.lowercase()}")
        .semantics {
            contentDescription = string(if (selected) R.string.x_tab_selected else R.string.x_tab, keyLabel(name))
            this.selected = selected
            role = Role.Tab
            onClick { onClick(); true }
        },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, contentDescription = null, tint = if (selected) Pink else White,
            modifier = Modifier.size(23.dp))
        if (showLabel) Text(keyLabel(name), color = if (selected) Pink else if(LocalAccessibilitySettings.current.solidControls) White else Muted, fontSize = 10.sp, maxLines = 1)
    }
}

/** Snapshot geometry reads stay inside layer updates; scrolling does not recompose the shell. */
@Composable internal fun GlassScene(bounds:Map<String,Rect>,styles:Map<String,GlassChipStyle>,area:IntSize,density:Float,compact:Boolean,
    showMini:Boolean,chrome:Boolean,inset:Int,tab:String,route:String,settings:AccessibilitySettings,content:@Composable ()->Unit) {
    val solid=settings.solidControls;val frosted=settings.frostedGlass;val intensity=settings.glassIntensity
    val frostIntensity=settings.frostedIntensity
    // The cache itself is not snapshot state; mutating it inside a layer never schedules composition.
    val cache=remember {GlassEffectCache()}
    Box(Modifier.fillMaxSize().imePadding().testTag("scene").graphicsLayer {
        val visible=visibleGlassChips(bounds,tab,route,area.width,area.height)
        val categories=visible.map {it.second}
        val radii=visible.map {styles[it.first]?.corner?:0f}
        val key=listOf(area,density,compact,showMini,chrome,inset,solid,frosted,intensity,frostIntensity,categories,radii)
        if(cache.key!=key) {
            cache.key=key
            cache.effect=if(chrome && !solid) chromeGlassEffect(area.width,area.height,density,showMini,compact,
                false,inset,categories,frosted,intensity,radii,frostIntensity)?.asComposeRenderEffect() else null
        }
        renderEffect=cache.effect
    },content={content()})
}

private class GlassEffectCache {
    var key:List<Any?>?=null
    var effect:androidx.compose.ui.graphics.RenderEffect?=null
}

@Composable internal fun VisibleGlassLabels(bounds:Map<String,Rect>,styles:Map<String,GlassChipStyle>,tab:String,route:String,area:IntSize) {
    GlassCategoryLabels(visibleGlassChips(bounds,tab,route,area.width,area.height),styles)
}
