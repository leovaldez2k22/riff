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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
    val motion = LocalMotionPolicy.current
    val still = motion.reduced || accessibility.solidControls
    // Feature 1: the pill and its lens, moved by TabLensMotion (NavigationLens.kt) a frame at a time while anything moves;
    // the draws read [tick], so a moving pill redraws without recomposing the bar.
    val lens = remember { TabLensMotion() }
    var tick by remember { mutableLongStateOf(0L) }
    var lensShown by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val frames = remember { arrayOfNulls<Job>(1) }
    fun animate() {
        if (frames[0]?.isActive == true) return
        frames[0] = scope.launch {
            var last = withFrameNanos { it }
            while (lens.moving) {
                withFrameNanos { now -> lens.step(android.os.SystemClock.uptimeMillis(), (now - last) / 1e9f); last = now }
                val shown = lens.frame().lift > .003f
                if (shown != lensShown) lensShown = shown
                tick++
            }
        }
    }
    val active = names.indexOf(model.tab).coerceAtLeast(0)
    BoxWithConstraints(Modifier.fillMaxWidth().height(height).pointerInput(compact, rightToLeft) {
        awaitEachGesture {
            val width = size.width.toFloat()
            val searchWidth = height.value * density
            val gap = 8f * density
            fun logical(x: Float) = if (rightToLeft) width - x else x
            val down = awaitFirstDown(requireUnconsumed = false)
            lens.down(logical(down.position.x), down.uptimeMillis); animate()
            var releasedX = down.position.x
            var lastHover = -1
            do {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                releasedX = change.position.x
                if (!change.pressed) break
                lens.move(logical(releasedX), viewConfiguration.touchSlop)
                if (lens.lifted) {
                    // A tick each time the lens comes over another tab.
                    val hover = lens.hovered()
                    if (lastHover >= 0 && hover != lastHover) uiHaptic(HapticCue.Selection)
                    lastHover = hover
                }
            } while (true)
            val index = when {
                // Lifted, the tab under the lens is chosen, Search included.
                lens.lifted -> lens.up()
                else -> { lens.cancel(); TabHitTest.indexAt(releasedX, width, searchWidth, gap, rightToLeft) }
            }
            animate()
            val reset = model.selectFromTap(names[index], android.os.SystemClock.uptimeMillis())
            uiHaptic(if (reset) HapticCue.Confirm else HapticCue.Selection)
        }
    }) {
        val mainWidth = maxWidth - height - 8.dp
        val slot = mainWidth / 4
        val barWidth = maxWidth.value * density
        val barHeight = height.value * density
        SideEffect {
            lens.layout(slot.value * density, barHeight, (mainWidth.value + height.value / 2f) * density + 8f * density,
                barHeight, active, still)
            if (lens.moving) animate()
        }
        fun frame(): TabLensFrame {
            tick
            val f = lens.frame()
            return if (rightToLeft) f.copy(centerX = barWidth - f.centerX, ghostX = barWidth - f.ghostX) else f
        }
        // Feature 1: the tabs as drawn, for the lens over them.
        val tabs = rememberGraphicsLayer()
        val tabsBackdrop = remember(tabs) { RiffBackdrop(tabs) }
        Row(Modifier.fillMaxSize().riffBackdropSource(tabsBackdrop), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = RoundedCornerShape(height / 2), color = Color.Transparent,
                modifier = Modifier.width(mainWidth).height(height).testTag("navigation").riffGlass(GlassRole.Bar)) {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    names.take(4).forEach { name ->
                        // The selected tab stays lit under a moving lens: the choice is made when the finger lets go.
                        NavItem(name, names[active] == name, !largeText, Modifier.weight(1f)) {
                            uiHaptic(HapticCue.Selection); model.selectFromTap(name, android.os.SystemClock.uptimeMillis())
                        }
                    }
                }
            }
            Surface(shape = CircleShape, color = Color.Transparent,
                modifier = Modifier.size(height).testTag("nav_search").riffGlass(GlassRole.Button)) {
                NavItem("Search", active == 4, false, Modifier.fillMaxSize()) {
                    uiHaptic(HapticCue.Selection); model.selectFromTap("Search", android.os.SystemClock.uptimeMillis())
                }
            }
        }
        RestingPill(::frame, density, accessibility.solidControls)
        if (lensShown && !still) TabLens(::frame, tabsBackdrop, LocalRiffBackdrop.current, barWidth, barHeight, density)
    }
}

/** The frosted pill in the selected tab; it fades as it lifts into the lens. Glass segmented controls draw it too. */
@Composable internal fun RestingPill(frame: () -> TabLensFrame, density: Float, solid: Boolean) {
    Box(Modifier.fillMaxSize().drawWithCache {
        onDrawBehind {
            val f = frame()
            val rest = (1f - f.lift).coerceIn(0f, 1f)
            if (rest <= 0f) return@onDrawBehind
            val origin = Offset(f.centerX - f.width / 2f, size.height / 2f - f.height / 2f)
            val dimensions = androidx.compose.ui.geometry.Size(f.width, f.height)
            val radius = androidx.compose.ui.geometry.CornerRadius(f.height / 2f)
            drawRoundRect(if (solid) Pink.copy(alpha = .10f) else White.copy(alpha = .12f * rest), origin, dimensions, radius)
            drawRoundRect(Brush.verticalGradient(listOf(White.copy(alpha = .34f * rest), White.copy(alpha = .08f * rest)), startY = origin.y,
                endY = origin.y + f.height), origin, dimensions, radius, style = androidx.compose.ui.graphics.drawscope.Stroke(density))
        }
    })
}

/**
 * The lens: clear glass over the [page] and the tabs together, so where it stands out of the bar it bends the page and
 * not empty space (bug 8); its bright rim; and the fainter rim trailing it. Glass segmented controls use it too.
 */
@Composable internal fun TabLens(frame: () -> TabLensFrame, tabs: RiffBackdrop, page: RiffBackdrop?, barWidth: Float, barHeight: Float, density: Float) {
    val composite = rememberRiffBackdrop()
    val bar = remember { arrayOf(Offset.Zero) }
    // Read above and below the bar: what the lens stands out of it, and the page its rim bends in from outside.
    val spill = barHeight * .3f
    DisposableEffect(tabs) { tabs.readers++; onDispose { tabs.readers-- } }
    Box(Modifier.fillMaxSize().onGloballyPositioned { bar[0] = it.positionInRoot() }.drawWithCache {
        onDrawBehind {
            val f = frame()
            if (f.ghostAlpha <= .002f) return@onDrawBehind
            val origin = Offset(f.ghostX - f.width / 2f, size.height / 2f - f.height / 2f)
            val dimensions = androidx.compose.ui.geometry.Size(f.width, f.height)
            drawRoundRect(Brush.linearGradient(listOf(White.copy(alpha = .9f * f.ghostAlpha), White.copy(alpha = .35f * f.ghostAlpha),
                White.copy(alpha = .7f * f.ghostAlpha)), start = origin, end = origin + Offset(dimensions.width, dimensions.height)),
                origin, dimensions, androidx.compose.ui.geometry.CornerRadius(f.height / 2f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(density))
        }
    })
    Box(Modifier.layout { measurable, constraints ->
        val f = frame()
        val placeable = measurable.measure(Constraints.fixed(f.width.toInt().coerceAtLeast(1), f.height.toInt().coerceAtLeast(1)))
        // Reported at the bar's size and placed at its corner: a child taller than its slot is centred on it by
        // Compose, which lifted the lens by half of what it stands out, so it grew upward only (phone check 30 Oct).
        layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
    }.graphicsLayer {
        val f = frame()
        translationX = f.centerX - f.width.toInt() / 2f; translationY = barHeight / 2f - f.height.toInt() / 2f
        alpha = (3f * f.lift).coerceIn(0f, 1f)
    }.drawWithContent {
        // The page and the tabs as one backdrop, a little taller than the bar, from where both are now.
        val origin = bar[0] - Offset(0f, spill)
        composite.origin = origin
        composite.layer.record(IntSize(barWidth.toInt().coerceAtLeast(1), (barHeight + 2 * spill).toInt())) {
            page?.let { translate(it.origin.x - origin.x, it.origin.y - origin.y) { drawLayer(it.layer) } }
            translate(tabs.origin.x - origin.x, tabs.origin.y - origin.y) { drawLayer(tabs.layer) }
        }
        drawContent()
        drawRoundRect(Brush.linearGradient(listOf(White.copy(alpha = .85f), White.copy(alpha = .25f), White.copy(alpha = .6f)),
            end = Offset(size.width, size.height)), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(1.4f * density))
    }.riffGlass(GlassRole.Lens, backdrop = composite))
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
