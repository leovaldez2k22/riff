package com.musicglass.personal

import androidx.compose.ui.geometry.Rect

internal fun chromeGlassShapes(width:Int,height:Int,density:Float,showMini:Boolean,compact:Boolean,
    collapsed:Boolean,bottomInset:Int,categories:List<Rect>,bottomChrome:Boolean=true):List<Rect> {
    // Feature 1: with the bottom chrome drawn by riffGlass, its three shapes are placed off screen and read nothing.
    if(!bottomChrome) return List(3) {Rect(-1000f,-1000f,-999f,-999f)}+categories.take(8)
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

/**
 * Q18-030: frosted glass is blurred only in strips around the glass, not over the whole screen. One strip holds the
 * bottom chrome (bar, search button, mini player), one the category chips; strips that touch become one. Each grows
 * by [margin] so the blur and the rim's bend read the same pixels they would read on the whole screen, and keeps
 * whole pixels so the half-size copy lines up with the screen.
 */
internal fun frostBands(shapes:List<Rect>,width:Int,height:Int,margin:Float):List<Rect> {
    fun band(group:List<Rect>):Rect? {
        val inView=group.filter {it.right>0f && it.left<width && it.bottom>0f && it.top<height && it.width>0f && it.height>0f}
        if(inView.isEmpty()) return null
        return Rect(kotlin.math.floor(maxOf(0f,inView.minOf {it.left}-margin)),kotlin.math.floor(maxOf(0f,inView.minOf {it.top}-margin)),
            kotlin.math.ceil(minOf(width.toFloat(),inView.maxOf {it.right}+margin)),kotlin.math.ceil(minOf(height.toFloat(),inView.maxOf {it.bottom}+margin)))
    }
    val bands=listOfNotNull(band(shapes.take(3)),band(shapes.drop(3)))
    return if(bands.size==2 && bands[0].overlaps(bands[1])) listOf(Rect(minOf(bands[0].left,bands[1].left),
        minOf(bands[0].top,bands[1].top),maxOf(bands[0].right,bands[1].right),maxOf(bands[0].bottom,bands[1].bottom))) else bands
}

/** How far outside a shape the frost reads: the rim's bend, then three sigmas of the blur (Android's radius to sigma). */
internal fun frostMargin(density:Float,intensity:Float,frostIntensity:Float):Float {
    val bend=3f*density*intensity*FROST_BEND
    val sigma=.57735f*frostBlurRadius(density,frostIntensity)+.5f
    return bend+3f*sigma+2f
}

internal fun frostBlurRadius(density:Float,frostIntensity:Float):Float = 22f*density*frostIntensity

/** The frost bends further than clear glass: a blur of its own size would otherwise smooth the bend away. */
internal const val FROST_BEND = 3f

/** The frost is blurred on a copy at half the width and height (a quarter of the pixels); it is soft, so no detail is lost. */
internal const val FROST_SCALE = 2
