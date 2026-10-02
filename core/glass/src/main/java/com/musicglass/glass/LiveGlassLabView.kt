package com.musicglass.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import kotlin.math.min

/**
 * GPU-backed backdrop experiment: Android renders SceneView into its RenderNode, then
 * RenderEffect supplies those actual pixels to AGSL. Labels are a separate foreground View.
 * A caller can attach a ComposeView for live sampling or a SurfaceView video fallback.
 */
class LiveGlassLabView(context: Context) : FrameLayout(context) {
    var glassEnabled: Boolean = true
        set(value) {
            field = value
            applySceneEffect()
            sceneContainer.invalidate()
            foreground.invalidate()
        }
    var sceneMode: Int = 0
        set(value) {
            field = value.coerceIn(0, 3)
            updateSceneVisibility()
            onSceneModeChanged?.invoke(field)
            foreground.invalidate()
        }
    var onSceneModeChanged: ((Int) -> Unit)? = null
    var refractionDp: Float = 3f
        set(value) { field = value.coerceIn(0f, 12f); updateUniforms() }
    var bevelDp: Float = 15f
        set(value) { field = value.coerceIn(2f, 32f); updateUniforms() }
    var highlight: Float = 0.65f
        set(value) { field = value.coerceIn(0f, 1f); updateUniforms() }

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val startedAt = SystemClock.uptimeMillis()
    private val sceneShader = RuntimeShader(SCENE_SHADER)
    private val glassShader = RuntimeShader(GLASS_SHADER)
    private var effect: RenderEffect? = null
    private val sceneContainer = FrameLayout(context)
    private var composeScene: View? = null
    private var videoScene: View? = null
    private val scenePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = sceneShader }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
    }
    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(42, 255, 255, 255)
    }
    private val videoRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val backdrop = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return
            val time = (SystemClock.uptimeMillis() - startedAt) / 1000f
            sceneShader.setFloatUniform("resolution", w, h)
            sceneShader.setFloatUniform("time", time)
            sceneShader.setFloatUniform("sceneMode", if (sceneMode == 0) 0f else 1f)
            canvas.drawRect(0f, 0f, w, h, scenePaint)
            // This text is part of the real backdrop View, so its edge pixels must refract.
            textPaint.color = Color.WHITE
            textPaint.textSize = 12f * scaledDensity
            canvas.drawText("LIVE VIEW CONTENT  •  SHARP TEXT UNDER GLASS", 18f * density,
                cardBounds(w, h).top + 22f * density, textPaint)
            canvas.drawText("EDGE SAMPLING TEST  •  0123456789", 18f * density,
                cardBounds(w, h).bottom - 10f * density, textPaint)
            if (isShown) postInvalidateOnAnimation()
        }
    }
    private val foreground = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return
            val card = cardBounds(w, h)
            val circle = circleBounds(w, h)
            val capsule = capsuleBounds(w, h)
            val bar = barBounds(w, h)
            if (!glassEnabled) {
                canvas.drawRoundRect(card, 30f * density, 30f * density, baselinePaint)
                canvas.drawRoundRect(circle, circle.width() / 2f, circle.width() / 2f,
                    baselinePaint)
                canvas.drawRoundRect(capsule, capsule.height() / 2f, capsule.height() / 2f,
                    baselinePaint)
                canvas.drawRoundRect(bar, bar.height() / 2f, bar.height() / 2f,
                    baselinePaint)
            } else if (sceneMode == 3) {
                // A video surface cannot be sampled through this RenderEffect.
                // Keep a clear rim over it until a safe video sampling path is proven.
                canvas.drawRoundRect(card, 30f * density, 30f * density, videoRimPaint)
                canvas.drawRoundRect(circle, circle.width() / 2f, circle.width() / 2f,
                    videoRimPaint)
                canvas.drawRoundRect(capsule, capsule.height() / 2f,
                    capsule.height() / 2f, videoRimPaint)
                canvas.drawRoundRect(bar, bar.height() / 2f, bar.height() / 2f,
                    videoRimPaint)
            }
            drawCentered(canvas, "CLEAR INTERIOR", card, 15f)
            drawCentered(canvas, "●", circle, 23f)
            drawCentered(canvas, "CAPSULE", capsule, 15f)
            drawCentered(canvas, "BOTTOM BAR", bar, 13f)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = 12f * scaledDensity
            textPaint.color = Color.WHITE
            val caption = when (sceneMode) {
                0 -> "MOVING CHECKER + STRIPES"
                1 -> "MOVING COLOR FIELD"
                2 -> "SCROLLING COMPOSE CONTENT"
                else -> "VIDEO FALLBACK · CLEAR RIM"
            }
            canvas.drawText(caption,
                w / 2f, h - 18f * density, textPaint)
        }
    }

    init {
        sceneContainer.addView(backdrop,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(sceneContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(foreground, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        updateUniforms()
    }

    fun setComposeScene(view: View) {
        check(composeScene == null) { "Compose scene already attached" }
        composeScene = view
        sceneContainer.addView(view,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        updateSceneVisibility()
    }

    fun setVideoScene(view: View) {
        check(videoScene == null) { "Video scene already attached" }
        videoScene = view
        sceneContainer.addView(view,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        updateSceneVisibility()
    }

    private fun updateSceneVisibility() {
        backdrop.visibility = if (sceneMode <= 1) View.VISIBLE else View.GONE
        composeScene?.visibility = if (sceneMode == 2) View.VISIBLE else View.GONE
        videoScene?.visibility = if (sceneMode == 3) View.VISIBLE else View.GONE
        applySceneEffect()
        sceneContainer.invalidate()
    }

    private fun applySceneEffect() {
        sceneContainer.setRenderEffect(if (glassEnabled && sceneMode != 3) effect else null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateUniforms()
    }

    private fun updateUniforms() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val card = cardBounds(w, h)
        val circle = circleBounds(w, h)
        val capsule = capsuleBounds(w, h)
        val bar = barBounds(w, h)
        setBounds("card", card, 30f * density)
        setBounds("circle", circle, circle.width() / 2f)
        setBounds("capsule", capsule, capsule.height() / 2f)
        setBounds("bar", bar, bar.height() / 2f)
        glassShader.setFloatUniform("bevel", bevelDp * density)
        glassShader.setFloatUniform("refraction", refractionDp * density)
        glassShader.setFloatUniform("highlight", highlight)
        effect = RenderEffect.createRuntimeShaderEffect(glassShader, "backdrop")
        applySceneEffect()
        sceneContainer.invalidate()
        foreground.invalidate()
    }

    private fun setBounds(name: String, bounds: RectF, radius: Float) {
        glassShader.setFloatUniform("${name}Origin", bounds.left, bounds.top)
        glassShader.setFloatUniform("${name}Size", bounds.width(), bounds.height())
        glassShader.setFloatUniform("${name}Radius", radius)
    }

    private fun cardBounds(w: Float, h: Float): RectF {
        val inset = 22f * density
        val top = h * 0.23f
        return RectF(inset, top, w - inset, top + min(170f * density, h * 0.34f))
    }

    private fun circleBounds(w: Float, h: Float): RectF {
        val inset = 22f * density
        val size = min(76f * density, w * 0.22f)
        val top = min(h * 0.68f, cardBounds(w, h).bottom + 18f * density)
        return RectF(inset, top, inset + size, top + size)
    }

    private fun capsuleBounds(w: Float, h: Float): RectF {
        val circle = circleBounds(w, h)
        return RectF(circle.right + 12f * density, circle.top, w - 22f * density,
            circle.bottom)
    }

    private fun barBounds(w: Float, h: Float): RectF {
        val inset = 22f * density
        val bottom = h - 42f * density
        return RectF(inset, bottom - 50f * density, w - inset, bottom)
    }

    private fun drawCentered(canvas: Canvas, value: String, bounds: RectF, sp: Float) {
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.WHITE
        textPaint.textSize = sp * scaledDensity
        canvas.drawText(value, bounds.centerX(),
            bounds.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
    }

    companion object {
        private const val SCENE_SHADER = """
            uniform float2 resolution;
            uniform float time;
            uniform float sceneMode;
            half4 main(float2 p) {
                if (sceneMode < 0.5) {
                    float checker = mod(floor(p.x / 30.0) + floor(p.y / 30.0), 2.0);
                    float stripe = 0.5 + 0.5 * sin((p.x - time * 95.0) / 8.0);
                    half3 dark = half3(0.035, 0.06, 0.13);
                    half3 light = half3(0.30, 0.55, 0.68);
                    return half4(mix(dark, light, half(checker * 0.75 + stripe * 0.25)), 1.0);
                }
                float2 uv = p / resolution;
                float a = 0.5 + 0.5 * sin(uv.x * 12.0 + uv.y * 8.0 - time * 2.2);
                float b = 0.5 + 0.5 * sin(uv.x * -7.0 + uv.y * 14.0 + time * 1.3);
                return half4(mix(mix(half3(0.05, 0.08, 0.22),
                    half3(0.65, 0.15, 0.42), half(a)),
                    half3(0.95, 0.65, 0.22), half(b * 0.35)), 1.0);
            }
        """

        private const val GLASS_SHADER = """
            uniform shader backdrop;
            uniform float2 cardOrigin;
            uniform float2 cardSize;
            uniform float cardRadius;
            uniform float2 circleOrigin;
            uniform float2 circleSize;
            uniform float circleRadius;
            uniform float2 capsuleOrigin;
            uniform float2 capsuleSize;
            uniform float capsuleRadius;
            uniform float2 barOrigin;
            uniform float2 barSize;
            uniform float barRadius;
            uniform float bevel;
            uniform float refraction;
            uniform float highlight;

            float roundedDistance(float2 p, float2 origin, float2 dimensions, float radius) {
                float2 center = origin + dimensions * 0.5;
                float2 inner = max(dimensions * 0.5 - float2(radius), float2(0.0));
                float2 q = abs(p - center) - inner;
                return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - radius;
            }
            float boundedDistance(float2 p, float2 origin, float2 dimensions, float radius) {
                if (p.x < origin.x - 1.0 || p.y < origin.y - 1.0 ||
                    p.x > origin.x + dimensions.x + 1.0 ||
                    p.y > origin.y + dimensions.y + 1.0) return 10000.0;
                return roundedDistance(p, origin, dimensions, radius);
            }
            float shapeDistance(float2 p, float shape) {
                if (shape < 0.5) return roundedDistance(p, cardOrigin, cardSize, cardRadius);
                if (shape < 1.5) return roundedDistance(p, circleOrigin, circleSize, circleRadius);
                if (shape < 2.5) return roundedDistance(p, capsuleOrigin, capsuleSize, capsuleRadius);
                return roundedDistance(p, barOrigin, barSize, barRadius);
            }
            half4 main(float2 p) {
                float d0 = boundedDistance(p, cardOrigin, cardSize, cardRadius);
                float d1 = boundedDistance(p, circleOrigin, circleSize, circleRadius);
                float d2 = boundedDistance(p, capsuleOrigin, capsuleSize, capsuleRadius);
                float d3 = boundedDistance(p, barOrigin, barSize, barRadius);
                float dist = min(min(d0, d1), min(d2, d3));
                half4 original = backdrop.eval(p);
                if (dist > 1.0) return original;
                // The center is intentionally unfrosted. Skip normals and a second
                // backdrop lookup where the bevel cannot affect the pixel.
                if (dist < -bevel) return original;
                float shape = d0 <= d1 && d0 <= d2 && d0 <= d3 ? 0.0 :
                    (d1 <= d2 && d1 <= d3 ? 1.0 : (d2 <= d3 ? 2.0 : 3.0));
                float2 gradient = float2(
                    shapeDistance(p + float2(1.0, 0.0), shape) -
                        shapeDistance(p - float2(1.0, 0.0), shape),
                    shapeDistance(p + float2(0.0, 1.0), shape) -
                        shapeDistance(p - float2(0.0, 1.0), shape));
                float2 normal = gradient / max(length(gradient), 0.001);
                float rim = 1.0 - smoothstep(0.0, bevel, max(-dist, 0.0));
                half4 sampled = backdrop.eval(p + normal * refraction * rim);
                float light = pow(max(dot(normal, normalize(float2(-0.7, -0.7))), 0.0), 2.0);
                float specular = rim * light * highlight * 0.40;
                float shade = rim * max(dot(normal, normalize(float2(0.7, 0.7))), 0.0) * 0.10;
                half3 rgb = clamp(sampled.rgb * half(1.0 - shade) + half3(specular),
                    half3(0.0), half3(1.0));
                float mask = 1.0 - smoothstep(-1.0, 1.0, dist);
                return mix(original, half4(rgb, sampled.a), half(mask));
            }
        """
    }
}
