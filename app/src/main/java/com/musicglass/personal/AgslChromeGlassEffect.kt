package com.musicglass.personal

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.BlendMode
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Rect

/** Kept in its own class so Android 12 never loads RuntimeShader. */
@RequiresApi(33)
internal object AgslChromeGlassEffect {
    private val liquidShader by lazy {RuntimeShader(SHADER)}
    private val frostedShader by lazy {RuntimeShader(SHADER)}
    fun create(shapes:List<Rect>,width:Int,height:Int,density:Float,frosted:Boolean,intensity:Float):RenderEffect {
        fun configure(shader:RuntimeShader, frost:Float) {
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
        val visible=shapes.drop(3).take(8)
        shader.setFloatUniform("categoryCount",visible.size.toFloat())
        shader.setFloatUniform("categoryBand",visible.minOfOrNull {it.top}?:-10000f,
            visible.maxOfOrNull {it.bottom}?:-10000f)
        shader.setFloatUniform("viewport",width.toFloat(),height.toFloat())
        shader.setFloatUniform("bevel",14f*density)
        shader.setFloatUniform("refraction",3f*density*intensity)
        shader.setFloatUniform("frosted",frost)
        shader.setFloatUniform("intensity",intensity)
        }
        configure(liquidShader,0f)
        val liquid=RenderEffect.createRuntimeShaderEffect(liquidShader,"backdrop")
        if(!frosted || intensity<=0f) return liquid
        configure(frostedShader,1f)
        val mask=RenderEffect.createRuntimeShaderEffect(frostedShader,"backdrop")
        val radius=22f*density*intensity
        val blur=RenderEffect.createBlurEffect(radius,radius,Shader.TileMode.CLAMP)
        return RenderEffect.createBlendModeEffect(liquid,RenderEffect.createChainEffect(mask,blur),BlendMode.SRC_OVER)
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
    uniform float categoryCount;
    uniform float2 categoryBand;
    uniform float2 viewport;
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
        return distanceTo(p, rect.xy, rect.zw, rect.w * 0.5);
    }
    half4 main(float2 p) {
        float d0 = distanceTo(p, navOrigin, navSize, navRadius);
        float d1 = distanceTo(p, searchOrigin, searchSize, searchRadius);
        float d2 = distanceTo(p, miniOrigin, miniSize, miniRadius);
        float d = min(d0, min(d1, d2));
        float shape = d0 <= d1 && d0 <= d2 ? 0.0 : (d1 <= d2 ? 1.0 : 2.0);
        if (p.y >= categoryBand.x - 1.0 && p.y <= categoryBand.y + 1.0) {
            for (int i=0; i<8; i++) {
                if (float(i) >= categoryCount) break;
                float4 rect=categoryRects[i];
                float candidate=distanceTo(p,rect.xy,rect.zw,rect.w*0.5);
                if(candidate<d) {d=candidate;shape=float(i)+3.0;}
            }
        }
        half4 original = backdrop.eval(p);
        if (d > 1.0) return frosted>0.5 ? half4(0.0) : original;
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
        // Clamped: a shape running off-screen would otherwise refract in transparent black from outside the view.
        half4 sampled = frosted>0.5 ? base : backdrop.eval(clamp(p + normal * refraction * rim, float2(0.5), viewport - float2(0.5)));
        float light = pow(max(dot(normal, normalize(float2(-0.7, -0.7))), 0.0), 2.0);
        float specular = rim * light * 0.26 * intensity;
        half3 rgb = clamp(sampled.rgb + half3(specular), half3(0.0), half3(1.0));
        float mask = 1.0 - smoothstep(-1.0, 1.0, d);
        return frosted>0.5 ? half4(rgb,sampled.a)*half(mask) : mix(original, half4(rgb, sampled.a), half(mask));
    }
"""
}
