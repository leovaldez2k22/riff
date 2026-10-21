package com.musicglass.personal

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Rect

/** Kept in its own class so Android 12 never loads RuntimeShader. */
@RequiresApi(33)
internal object AgslChromeGlassEffect {
    private val liquidShader by lazy {RuntimeShader(SHADER)}
    private val frostShader by lazy {RuntimeShader(SHADER)}

    /** The clear (liquid) glass over the whole screen; frosted glass adds [frost] strips on top of it. */
    fun create(shapes:List<Rect>,width:Int,height:Int,density:Float,intensity:Float,categoryRadii:List<Float> = emptyList()):RenderEffect {
        configure(liquidShader,shapes,Rect(0f,0f,width.toFloat(),height.toFloat()),density,categoryRadii,0f,intensity,intensity)
        return RenderEffect.createRuntimeShaderEffect(liquidShader,"backdrop")
    }

    /**
     * The frost over [area] (screen pixels) of a backdrop already blurred: the glass shapes cut out of it, bent at
     * the rim and tinted. Drawn over [create]'s result it gives the frosted look.
     */
    fun frost(shapes:List<Rect>,area:Rect,density:Float,intensity:Float,frostIntensity:Float,
        categoryRadii:List<Float> = emptyList()):RenderEffect {
        configure(frostShader,shapes,area,density,categoryRadii,1f,frostIntensity,intensity*FROST_BEND)
        return RenderEffect.createRuntimeShaderEffect(frostShader,"backdrop")
    }

    // warp: the edge refraction, from the glass intensity in both looks; strength: the frost's tint and sheen.
    private fun configure(shader:RuntimeShader,shapes:List<Rect>,band:Rect,density:Float,categoryRadii:List<Float>,
        frost:Float,strength:Float,warp:Float) {
        listOf("nav","search","mini").forEachIndexed {index,name->
            val rect=shapes[index]
            shader.setFloatUniform("${name}Origin",rect.left,rect.top)
            shader.setFloatUniform("${name}Size",rect.width,rect.height)
            shader.setFloatUniform("${name}Radius",rect.height/2f)
        }
        val categories=FloatArray(32) {if(it%4<2) -10000f else 1f}
        shapes.drop(3).take(8).forEachIndexed {index,rect->
            categories[index*4]=rect.left;categories[index*4+1]=rect.top
            categories[index*4+2]=rect.width;categories[index*4+3]=rect.height
        }
        shader.setFloatUniform("categoryRects",categories)
        // A radius of 0 (or none) keeps the shape a pill; tiles pass their own corner radius.
        shader.setFloatUniform("categoryRadii",FloatArray(8) {i->shapes.getOrNull(3+i)?.let {glassCornerRadius(it,categoryRadii.getOrNull(i))}?:1f})
        val visible=shapes.drop(3).take(8)
        shader.setFloatUniform("categoryCount",visible.size.toFloat())
        shader.setFloatUniform("categoryBand",visible.minOfOrNull {it.top}?:-10000f,
            visible.maxOfOrNull {it.bottom}?:-10000f)
        shader.setFloatUniform("offset",band.left,band.top)
        shader.setFloatUniform("bounds",band.left,band.top,band.right,band.bottom)
        shader.setFloatUniform("bevel",14f*density)
        shader.setFloatUniform("refraction",3f*density*warp)
        shader.setFloatUniform("frosted",frost)
        shader.setFloatUniform("intensity",strength)
    }

private const val SHADER = """
    uniform shader backdrop;
    uniform float2 navOrigin;
    uniform float2 navSize;
    uniform float navRadius;
    uniform float2 searchOrigin;
    uniform float2 searchSize;
    uniform float searchRadius;
    uniform float2 miniOrigin;
    uniform float2 miniSize;
    uniform float miniRadius;
    uniform float4 categoryRects[8];
    uniform float categoryRadii[8];
    uniform float categoryCount;
    uniform float2 categoryBand;
    // The area this effect draws, in screen pixels: its top left, and the box a bent sample stays inside.
    uniform float2 offset;
    uniform float4 bounds;
    uniform float bevel;
    uniform float refraction;
    uniform float frosted;
    uniform float intensity;

    float distanceTo(float2 p, float2 origin, float2 dimensions, float radius) {
        if (p.x < origin.x - 1.0 || p.y < origin.y - 1.0 ||
            p.x > origin.x + dimensions.x + 1.0 || p.y > origin.y + dimensions.y + 1.0)
            return 10000.0;
        float2 q = abs(p - (origin + dimensions * 0.5)) -
            max(dimensions * 0.5 - float2(radius), float2(0.0));
        return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - radius;
    }
    float shapeDistance(float2 p, float shape) {
        if (shape < 0.5) return distanceTo(p, navOrigin, navSize, navRadius);
        if (shape < 1.5) return distanceTo(p, searchOrigin, searchSize, searchRadius);
        if (shape < 2.5) return distanceTo(p, miniOrigin, miniSize, miniRadius);
        float4 rect = shape < 3.5 ? categoryRects[0] :
            shape < 4.5 ? categoryRects[1] :
            shape < 5.5 ? categoryRects[2] :
            shape < 6.5 ? categoryRects[3] :
            shape < 7.5 ? categoryRects[4] :
            shape < 8.5 ? categoryRects[5] :
            shape < 9.5 ? categoryRects[6] : categoryRects[7];
        float radius = shape < 3.5 ? categoryRadii[0] :
            shape < 4.5 ? categoryRadii[1] :
            shape < 5.5 ? categoryRadii[2] :
            shape < 6.5 ? categoryRadii[3] :
            shape < 7.5 ? categoryRadii[4] :
            shape < 8.5 ? categoryRadii[5] :
            shape < 9.5 ? categoryRadii[6] : categoryRadii[7];
        return distanceTo(p, rect.xy, rect.zw, radius);
    }
    half4 main(float2 local) {
        float2 p = local + offset;
        float d0 = distanceTo(p, navOrigin, navSize, navRadius);
        float d1 = distanceTo(p, searchOrigin, searchSize, searchRadius);
        float d2 = distanceTo(p, miniOrigin, miniSize, miniRadius);
        float d = min(d0, min(d1, d2));
        float shape = d0 <= d1 && d0 <= d2 ? 0.0 : (d1 <= d2 ? 1.0 : 2.0);
        if (p.y >= categoryBand.x - 1.0 && p.y <= categoryBand.y + 1.0) {
            for (int i=0; i<8; i++) {
                if (float(i) >= categoryCount) break;
                float4 rect=categoryRects[i];
                float candidate=distanceTo(p,rect.xy,rect.zw,categoryRadii[i]);
                if(candidate<d) {d=candidate;shape=float(i)+3.0;}
            }
        }
        // Outside the glass the frost is empty; it reads nothing there.
        if (d > 1.0 && frosted > 0.5) return half4(0.0);
        half4 original = backdrop.eval(local);
        if (d > 1.0) return original;
        float activeBevel = shape >= 3.0 ? bevel * 0.36 : bevel;
        half4 base=original;
        if(frosted>0.5) {
            base.rgb=mix(base.rgb,half3(0.70,0.76,0.84),half(0.18*min(intensity,1.0)));
        }
        if (d < -activeBevel) return base;
        float2 gradient = float2(
            shapeDistance(p + float2(1.0, 0.0), shape) -
                shapeDistance(p - float2(1.0, 0.0), shape),
            shapeDistance(p + float2(0.0, 1.0), shape) -
                shapeDistance(p - float2(0.0, 1.0), shape));
        float2 normal = gradient / max(length(gradient), 0.001);
        float rim = 1.0 - smoothstep(0.0, activeBevel, max(-d, 0.0));
        // Clamped: a shape running off-screen (or off its strip) would otherwise refract in transparent black from outside.
        // Frosted glass bends its own frost at the rim the same way (its backdrop is the blurred one), then takes the tint.
        half4 sampled = backdrop.eval(clamp(p + normal * refraction * rim, bounds.xy + float2(0.5), bounds.zw - float2(0.5)) - offset);
        if(frosted>0.5) sampled.rgb=mix(sampled.rgb,half3(0.70,0.76,0.84),half(0.18*min(intensity,1.0)));
        float light = pow(max(dot(normal, normalize(float2(-0.7, -0.7))), 0.0), 2.0);
        float specular = rim * light * 0.26 * intensity;
        half3 rgb = clamp(sampled.rgb + half3(specular), half3(0.0), half3(1.0));
        float mask = 1.0 - smoothstep(-1.0, 1.0, d);
        return frosted>0.5 ? half4(rgb,sampled.a)*half(mask) : mix(original, half4(rgb, sampled.a), half(mask));
    }
"""
}
