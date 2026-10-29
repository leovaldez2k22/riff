package com.musicglass.personal

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.ceil

/*
 * Feature 1: Riff's glass, written for Riff from the published maths of Apple-like glass (the quarter-circle rim of
 * Kyant's backdrop, an exact rounded-rectangle distance field, colour fringes strongest at the corners). Any control
 * can be glass: it reads the page recorded behind it ([RiffBackdrop]), blurs it a little, bends it through a lens at
 * its rim, and lays a tint and a rim of light over it. Android 12 has no runtime shaders, so it gets the blur and tint
 * only (the frost); high contrast gets an opaque surface with an outline.
 */

/** The page every glass control reads: recorded once a frame by [riffBackdropSource] while any glass is shown. */
internal class RiffBackdrop(val layer: GraphicsLayer) {
    /** Where the recorded page starts, in root coordinates. Not snapshot state: it is only read while drawing. */
    var origin = Offset.Zero
        get() = coordinates?.takeIf { it.isAttached }?.positionInRoot() ?: field
    /** Read again each time the glass is refreshed, so a page that moved is read from where it is now. */
    var coordinates: LayoutCoordinates? = null
    var readers by mutableIntStateOf(0)
}

internal val LocalRiffBackdrop = staticCompositionLocalOf<RiffBackdrop?> { null }

/**
 * Feature 2: the screens' own background (the artwork-coloured ambient gradient), for glass inside a page. Glass in a
 * page cannot read the shell's backdrop: that is the page, glass included, and a layer can't draw itself.
 */
internal val LocalPageBackdrop = staticCompositionLocalOf<RiffBackdrop?> { null }

/** A backdrop of its own: a screen's background, or a slider's or a switch's track under its glass thumb. */
@Composable internal fun rememberRiffBackdrop(): RiffBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { RiffBackdrop(layer) }
}

/** Records what this draws into [backdrop] for the glass above it, while there is glass to read it. */
internal fun Modifier.riffBackdropSource(backdrop: RiffBackdrop): Modifier =
    onGloballyPositioned { backdrop.coordinates = it; backdrop.origin = it.positionInRoot() }.drawWithContent {
        if (backdrop.readers > 0) {
            backdrop.layer.record { this@drawWithContent.drawContent() }
            drawLayer(backdrop.layer)
        } else drawContent()
    }

/** What a piece of glass is for; each has its own lens and blur (plan, 1.3 step 4). */
internal enum class GlassRole { Bar, Button, Lens, Card, Menu, Slider }

/** How much glass the phone can afford now (1.3 step 10). Solid is high contrast: no glass at all. */
internal enum class GlassQuality { Full, Reduced, Frost, Solid }

/** The settings and the phone's state glass is drawn under. */
internal data class GlassStyle(val intensity: Float = 1f, val frosted: Boolean = false, val frostedIntensity: Float = 1f,
    val quality: GlassQuality = GlassQuality.Full)

internal val LocalGlassStyle = staticCompositionLocalOf { GlassStyle() }

/** One piece of glass, in dp (and fractions): its blur, the rim that bends, how far it bends, and its light. */
internal data class GlassSpec(val blur: Float, val rim: Float, val bend: Float, val depth: Float, val dispersion: Float,
    val highlight: Float, val tint: Color, val lens: Boolean)

/**
 * The starting values of the plan's table, then the settings: the glass intensity scales the bend; frosted glass
 * blurs more and tints more; Reduced drops the colour fringe; Frost keeps only the blur and tint (no blur on the lens).
 */
internal fun glassSpec(role: GlassRole, style: GlassStyle): GlassSpec {
    val base = when (role) {
        GlassRole.Bar -> GlassSpec(2f, 20f, 36f, 1f, 1f, .45f, Color.White.copy(alpha = .06f), true)
        GlassRole.Lens -> GlassSpec(0f, 28f, 56f, 1f, 1f, .55f, Color.Transparent, true)
        GlassRole.Button -> GlassSpec(2f, 14f, 28f, 1f, 1f, .45f, Color.White.copy(alpha = .06f), true)
        GlassRole.Card -> GlassSpec(8f, 12f, 20f, 0f, 0f, .30f, Color.White.copy(alpha = .08f), true)
        GlassRole.Menu -> GlassSpec(20f, 10f, 16f, 0f, 0f, .30f, Color.Black.copy(alpha = .25f), true)
        GlassRole.Slider -> GlassSpec(0f, 12f, 24f, 1f, 1f, .50f, Color.Transparent, true)
    }
    val intensity = style.intensity.coerceIn(0f, 1.5f)
    var spec = base.copy(bend = base.bend * intensity, highlight = base.highlight * intensity.coerceAtMost(1f))
    if (style.frosted && role != GlassRole.Lens && role != GlassRole.Slider) {
        val frost = style.frostedIntensity.coerceIn(0f, 1.5f)
        spec = spec.copy(blur = spec.blur + 14f * frost, tint = spec.tint.copy(alpha = spec.tint.alpha + .10f * frost))
    }
    return when (style.quality) {
        GlassQuality.Full -> spec
        GlassQuality.Reduced -> spec.copy(dispersion = 0f)
        // The drag lens and the slider's thumb stay clear: frosting them would hide what is under the finger.
        GlassQuality.Frost, GlassQuality.Solid -> spec.copy(blur = if (role == GlassRole.Lens || role == GlassRole.Slider) 0f
            else maxOf(spec.blur, 12f), dispersion = 0f, depth = 0f, lens = false,
            tint = spec.tint.copy(alpha = maxOf(spec.tint.alpha, .12f)))
    }
}

/** How far outside its bounds a piece of glass reads the page: three sigmas of its blur, and a little more. */
internal fun glassReach(blurPx: Float): Float = if (blurPx <= 0f) 2f else ceil(3f * (.57735f * blurPx + .5f)) + 2f

/**
 * The quality for the phone's state: power saver or a warm phone drops the colour fringe; a phone about to throttle
 * (headroom 1.0, Android's severe) or Android 12 drops the lens. An idle emulator already reads 0.9.
 */
internal fun glassQuality(sdk: Int, solid: Boolean, powerSave: Boolean, thermalHeadroom: Float?): GlassQuality = when {
    solid -> GlassQuality.Solid
    sdk < 33 -> GlassQuality.Frost
    thermalHeadroom != null && thermalHeadroom >= 1f -> GlassQuality.Frost
    powerSave || (thermalHeadroom != null && thermalHeadroom >= .9f) -> GlassQuality.Reduced
    else -> GlassQuality.Full
}

/** A piece's corner in px: a capsule without one (chips, bars), a tile's own corner, never past a capsule. */
internal fun glassRadius(width: Float, height: Float, corner: Float?): Float =
    (corner?.takeIf { it > 0f } ?: (height / 2f)).coerceAtMost(minOf(width, height) / 2f)

/**
 * Per piece of glass: its place on the screen, its shader when the lens runs, and what it last read, so it can be read
 * again before each frame (Q-phone 6). Not snapshot state.
 */
internal class GlassPiece {
    var position = Offset.Zero
    var lens: Any? = null
    var coordinates: LayoutCoordinates? = null
    var area = IntSize.Zero
    var reach = 0f
    var density: Density? = null

    /**
     * The page under this piece, recorded into [layer] from where both are now. Android redraws a blurred piece of glass
     * only when the piece itself changes, not when the page it reads scrolls or animates, so without this the glass kept
     * an old page (phone checks 29 Oct: bugs 6, 7 and 11).
     */
    fun read(layer: GraphicsLayer, backdrop: RiffBackdrop) {
        val at = density ?: return
        if (area == IntSize.Zero) return
        coordinates?.takeIf { it.isAttached }?.let { position = it.positionInRoot() }
        val offset = backdrop.origin - position
        val margin = reach
        layer.record(at, LayoutDirection.Ltr, area) { translate(offset.x + margin, offset.y + margin) { drawLayer(backdrop.layer) } }
    }
}

/**
 * Makes this control glass. [cornerRadius] null is a capsule. [zoom] magnifies what the lens shows (the drag lens);
 * [backdrop] is the page the glass reads, the shell's by default.
 */
@Composable internal fun Modifier.riffGlass(role: GlassRole, cornerRadius: Dp? = null, zoom: () -> Float = { 1f },
    backdrop: RiffBackdrop? = LocalRiffBackdrop.current): Modifier {
    val style = LocalGlassStyle.current
    val spec = remember(role, style) { glassSpec(role, style) }
    val density = LocalDensity.current.density
    val layer = rememberGraphicsLayer()
    val piece = remember { GlassPiece() }
    val solid = style.quality == GlassQuality.Solid
    val view = LocalView.current
    DisposableEffect(backdrop, solid, view) {
        val reading = backdrop.takeUnless { solid }
        reading?.let { it.readers++ }
        // Before every frame the window draws (and only then: a still screen draws none), the piece reads the page again.
        val refresh = android.view.ViewTreeObserver.OnPreDrawListener { reading?.let { piece.read(layer, it) }; true }
        if (reading != null) view.viewTreeObserver.addOnPreDrawListener(refresh)
        onDispose {
            reading?.let { it.readers-- }
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnPreDrawListener(refresh)
        }
    }
    return onGloballyPositioned { piece.coordinates = it; piece.position = it.positionInRoot() }.drawWithContent {
        val radius = glassRadius(size.width, size.height, cornerRadius?.toPx())
        val shape = RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius))
        if (solid) {
            drawRoundRect(Color(0xFF11131A), cornerRadius = CornerRadius(radius))
            drawRoundRect(Color.White.copy(alpha = .72f), cornerRadius = CornerRadius(radius), style = Stroke(density))
            drawContent(); return@drawWithContent
        }
        val path = Path().apply { addRoundRect(shape) }
        if (backdrop == null) drawPath(path, Color(0xFF171C27).copy(alpha = .9f))
        else {
            val blur = spec.blur * density
            val reach = glassReach(blur)
            val area = IntSize(ceil(size.width + 2 * reach).toInt(), ceil(size.height + 2 * reach).toInt())
            layer.renderEffect = glassEffect(piece, spec, blur, reach, size.width, size.height, radius, density, zoom())
            piece.area = area; piece.reach = reach; piece.density = Density(density, fontScale)
            piece.read(layer, backdrop)
            clipPath(path) { translate(-reach, -reach) { drawLayer(layer) } }
        }
        drawPath(path, spec.tint)
        // The rim catches the light at the top and fades along the sides.
        drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .30f + .5f * spec.highlight), Color.White.copy(alpha = .08f),
            Color.White.copy(alpha = .16f * spec.highlight))), cornerRadius = CornerRadius(radius), style = Stroke(.65f * density))
        drawContent()
    }
}

internal fun glassEffect(piece: GlassPiece, spec: GlassSpec, blur: Float, reach: Float, width: Float, height: Float,
    radius: Float, density: Float, zoom: Float): androidx.compose.ui.graphics.RenderEffect? {
    val blurEffect = if (blur > 0f) RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP) else null
    if (!spec.lens || Build.VERSION.SDK_INT < 33) return blurEffect?.asComposeRenderEffect()
    val lens = (piece.lens as? GlassLens) ?: GlassLens().also { piece.lens = it }
    val bent = lens.effect(width, height, reach, radius, spec.rim * density, spec.bend * density, spec.depth, spec.dispersion,
        spec.highlight, zoom)
    return (if (blurEffect != null) RenderEffect.createChainEffect(bent, blurEffect) else bent).asComposeRenderEffect()
}

/** The lens, in its own class so Android 12 never loads RuntimeShader. One per piece of glass: each has its own size. */
@RequiresApi(33)
private class GlassLens {
    private val shader = RuntimeShader(GLASS_SHADER)
    fun effect(width: Float, height: Float, reach: Float, radius: Float, rim: Float, bend: Float, depth: Float,
        dispersion: Float, highlight: Float, zoom: Float): RenderEffect {
        shader.setFloatUniform("size", width, height)
        shader.setFloatUniform("reach", reach)
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("rim", rim.coerceAtLeast(1f))
        shader.setFloatUniform("bend", bend)
        shader.setFloatUniform("depth", depth)
        shader.setFloatUniform("dispersion", dispersion)
        shader.setFloatUniform("highlight", highlight)
        shader.setFloatUniform("zoom", zoom.coerceAtLeast(.5f))
        return RenderEffect.createRuntimeShaderEffect(shader, "page")
    }
}

/*
 * page: the backdrop around the glass, [reach] px of margin on each side. Inside the rim the sample moves toward the
 * centre along the shape's normal (bent by [depth] toward the centre, so it reads as a convex lens), by a quarter-circle
 * profile: little in the middle of the rim, most at the edge. The rim stops at half the short side, the bend at 0.6
 * of the rim. The colour fringe samples red further and blue nearer, most at the corners. A light from the top left
 * brightens the rim.
 */
private const val GLASS_SHADER = """
    uniform shader page;
    uniform float2 size;
    uniform float reach;
    uniform float radius;
    uniform float rim;
    uniform float bend;
    uniform float depth;
    uniform float dispersion;
    uniform float highlight;
    uniform float zoom;

    float box(float2 p, float2 b, float r) {
        float2 q = abs(p) - b + float2(r);
        return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0))) - r;
    }
    half4 main(float2 coord) {
        float2 b = size * 0.5;
        float2 p = coord - float2(reach) - b;
        float r = min(radius, min(b.x, b.y));
        float d = box(p, b, r);
        if (d > 1.0) return page.eval(coord);
        float2 centre = float2(reach) + b;
        float2 base = centre + p / zoom;
        float inner = -d;
        // The rim is at most half the short side, or the edge would read past the centre. The bend is at most 0.6
        // of the rim: bending by the whole rim folds it back over itself, a second ring inside a round lens.
        float band = min(rim, 0.5 * min(b.x, b.y));
        if (inner >= band) return page.eval(base);
        float x = 1.0 - max(inner, 0.0) / band;
        float amount = (1.0 - sqrt(max(1.0 - x * x, 0.0))) * min(bend, 0.6 * band);
        float e = 0.5;
        float2 g = float2(box(p + float2(e, 0.0), b, r) - box(p - float2(e, 0.0), b, r),
                          box(p + float2(0.0, e), b, r) - box(p - float2(0.0, e), b, r));
        float2 n = normalize(normalize(g + float2(0.00001)) + depth * normalize(p + float2(0.00001)));
        float2 shift = -n * amount;
        float corner = clamp(abs(p.x * p.y) / max(b.x * b.y, 1.0), 0.0, 1.0);
        float k = dispersion * (0.12 + 0.30 * corner);
        half4 c = page.eval(base + shift);
        if (k > 0.0) {
            c.r = page.eval(base + shift * (1.0 + k)).r;
            c.b = page.eval(base + shift * (1.0 - k)).b;
        }
        float light = pow(max(dot(n, normalize(float2(-0.6, -0.8))), 0.0), 3.0) + 0.35 * pow(max(dot(n, normalize(float2(0.6, 0.8))), 0.0), 3.0);
        float glint = light * x * x * highlight * 0.55;
        c.rgb = clamp(c.rgb + half3(glint) * c.a, half3(0.0), half3(c.a));
        return c;
    }
"""
