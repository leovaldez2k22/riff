package com.musicglass.personal
import android.os.Build
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asComposeRenderEffect

internal data class NavLens(val centerX:Float,val centerY:Float,val radiusX:Float,val radiusY:Float,val gain:Float)
internal fun navigationLens(center:Float,width:Float,height:Float,press:Float,lag:Float,intensity:Float,reduced:Boolean):NavLens {
    val contact=if(reduced) 0f else press.coerceIn(0f,1f)
    val strain=if(reduced) 0f else (kotlin.math.abs(lag)/width.coerceAtLeast(1f)).coerceIn(0f,1f)*.12f
    // The lens never grows taller than the bar: taller, it read past the tabs and showed cut-off shapes, and stood out
    // of the bar (phone checks 29 Oct, bug 8). It widens a little instead.
    return NavLens(center,height/2f,width/2f*(1f+.10f*contact+strain),height/2f*(1f-strain*.35f),
        (contact*.085f+strain*.55f)*intensity.coerceIn(0f,1.5f))
}
