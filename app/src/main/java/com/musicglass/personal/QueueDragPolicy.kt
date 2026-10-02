package com.musicglass.personal
/** Proportional edge speed, capped to avoid skipping large stretches of the queue. */
internal fun queueEdgeScroll(y:Float,start:Float,end:Float,edge:Float):Float=when {
    edge<=0 || end<=start -> 0f
    y<start+edge -> -18f*((start+edge-y)/edge).coerceIn(0f,1f)
    y>end-edge -> 18f*((y-end+edge)/edge).coerceIn(0f,1f)
    else -> 0f
}
