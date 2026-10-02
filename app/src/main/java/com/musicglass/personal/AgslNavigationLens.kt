package com.musicglass.personal
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
/** Only one foreground layer is sampled; layout and accessible touch targets stay fixed. */
@RequiresApi(33) internal class AgslNavigationLens {
    private val shader=RuntimeShader("""
        uniform shader foreground;
        uniform float2 center;
        uniform float2 radius;
        uniform float2 area;
        uniform float gain;
        half4 main(float2 p) {
            if(p.x<0.0 || p.y<0.0 || p.x>=area.x || p.y>=area.y) return half4(0.0);
            float2 q=p-center;
            float distance=length(q/max(radius,float2(1.0)));
            float contact=1.0-smoothstep(0.65,1.12,distance);
            float2 sample=p-q*gain*contact;
            return foreground.eval(clamp(sample,float2(0.0),area));
        }
    """)
    fun create(lens:NavLens,width:Float,height:Float):RenderEffect {
        shader.setFloatUniform("center",lens.centerX,lens.centerY)
        shader.setFloatUniform("radius",lens.radiusX,lens.radiusY)
        shader.setFloatUniform("area",width,height)
        shader.setFloatUniform("gain",lens.gain)
        return RenderEffect.createRuntimeShaderEffect(shader,"foreground")
    }
}
