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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import kotlin.math.ceil
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
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun ChromeBar(model: ShellModel, compact: Boolean, playback: PlaybackGateway, modifier: Modifier,
    bottomInset: Float, miniMorph: () -> Float, miniDragging: Boolean,
    onMiniDrag: (Float, Long) -> Unit,
    onMiniRelease: () -> Unit, onMiniBounds: (Rect) -> Unit,
    onMiniElementBounds: (String, Rect) -> Unit,
    onNotice: (String) -> Unit,
    /** Feature 30: false on a wide window, where the rail has the tabs; [showMini] false where the pane has the player. */
    tabs: Boolean = true, showMini: Boolean = model.miniVisible) {
    val accessibility = LocalAccessibilitySettings.current
    val miniProgress = miniMorph()
    val navHeight = if (compact) 64.dp else 58.dp
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)
        .padding(bottom = (bottomInset + 10f).dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showMini) {
            Surface(shape = RoundedCornerShape(32.dp), color = Color.Transparent,
                modifier = Modifier.fillMaxWidth().height(62.dp).testTag("mini_player")
                    .riffGlass(GlassRole.Bar)
                    .onGloballyPositioned { if(miniProgress<.001f) onMiniBounds(it.boundsInRoot()) }
                    .graphicsLayer { alpha = miniPlayerAlpha(miniProgress) }
                    .pointerInput(model) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { change, amount ->
                                onMiniDrag(amount, change.uptimeMillis)
                                change.consume()
                            }, onDragEnd = onMiniRelease,
                            onDragCancel = onMiniRelease)
                    }) {
                MiniPlayerContents(playback, onOpen = { PerfTrace.action("player_open"); model.sheet = "mini" },
                    onElementBounds = {name,bounds->if(miniProgress<.001f) onMiniElementBounds(name,bounds)})
            }
        }
        if (tabs) Box(Modifier.graphicsLayer {
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
        // Feature 1: the tabs as drawn, for the drag lens to magnify.
        val tabs=rememberGraphicsLayer()
        val tabsBackdrop=remember(tabs) {RiffBackdrop(tabs)}
        Row(Modifier.fillMaxSize().riffBackdropSource(tabsBackdrop), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = RoundedCornerShape(height / 2), color = Color.Transparent,
                modifier = Modifier.width(mainWidth).height(height).testTag("navigation").riffGlass(GlassRole.Bar)) {
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
                Surface(shape = CircleShape, color = Color.Transparent,
                    modifier = Modifier.size(height).testTag("nav_search").riffGlass(GlassRole.Button)) {
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
        // Feature 1, the drag lens: a glass pill over the tabs while a finger is down, magnifying what it passes over.
        if(!accessibility.solidControls && !motion.reduced && pressState.value>.01f) DragLens(::geometry,tabsBackdrop) {pressState.value}

    }
}

@Composable private fun DragLens(geometry:()->NavLens,backdrop:RiffBackdrop,contact:()->Float) {
    Box(Modifier.layout {measurable,_->
        val lens=geometry()
        val width=(lens.radiusX*2).toInt().coerceAtLeast(1);val height=(lens.radiusY*2).toInt().coerceAtLeast(1)
        val placeable=measurable.measure(Constraints.fixed(width,height))
        layout(width,height) {placeable.place(0,0)}
    }.graphicsLayer {
        val lens=geometry()
        translationX=lens.centerX-lens.radiusX;translationY=lens.centerY-lens.radiusY-6f*density*contact()
    }.riffGlass(GlassRole.Lens,zoom={1f+.2f*contact()},backdrop=backdrop))
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
internal fun NavItem(name: String, selected: Boolean, showLabel: Boolean,
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

/** The page under the shell's glass. Snapshot reads stay inside draws; scrolling does not recompose the shell. */
@Composable internal fun GlassScene(backdrop:RiffBackdrop,content:@Composable ()->Unit) {
    // Feature 2: the page, recorded once a frame while any glass reads it. The old full-screen shader and its frost
    // strips are gone: every piece of glass now draws itself (RiffGlass.kt).
    Box(Modifier.fillMaxSize().imePadding().testTag("scene")) {
        Box(Modifier.fillMaxSize().onGloballyPositioned {backdrop.origin=it.positionInRoot()}.drawWithContent {
            if(backdrop.readers>0) {
                backdrop.layer.compositingStrategy=CompositingStrategy.Offscreen
                backdrop.layer.record {this@drawWithContent.drawContent()}
                drawLayer(backdrop.layer)
            } else drawContent()
        },content={content()})
    }
}


@Composable internal fun VisibleGlassLabels(bounds:Map<String,Rect>,styles:Map<String,GlassChipStyle>,tab:String,route:String,area:IntSize) {
    GlassCategoryLabels(visibleGlassChips(bounds,tab,route,area.width,area.height),styles)
}
