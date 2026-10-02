package com.musicglass.personal

import androidx.compose.ui.geometry.Rect

internal fun chromeGlassShapes(width:Int,height:Int,density:Float,showMini:Boolean,compact:Boolean,
    collapsed:Boolean,bottomInset:Int,categories:List<Rect>):List<Rect> {
    val inset=16f*density
    val navHeight=(if(collapsed) 46f else if(compact) 64f else 58f)*density
    val top=height-bottomInset-10f*density-navHeight
    val gap=8f*density
    val navWidth=(width-inset*2-navHeight-gap).coerceAtLeast(1f)
    return listOf(Rect(inset,top,inset+navWidth,top+navHeight),
        Rect(inset+navWidth+gap,top,width-inset,top+navHeight),
        if(showMini) Rect(inset,top-gap-62f*density,width-inset,top-gap)
        else Rect(-1000f,-1000f,-999f,-999f))+categories.take(8)
}
