package com.musicglass.desktop

/** Existing desktop preset labels map to actual worker coefficients; none silently aliases Flat. */
internal object DesktopEqPresets {
    val names=listOf("Flat","Bass Boost","Vocal","Acoustic","Club","Rock")
    private val curves=mapOf(
        "Flat" to FloatArray(10),
        "Bass Boost" to floatArrayOf(6f,5f,4f,2f,0f,0f,0f,0f,0f,0f),
        "Vocal" to floatArrayOf(-2f,-1f,0f,2f,4f,4f,2f,1f,0f,-1f),
        "Acoustic" to floatArrayOf(3f,2f,0f,2f,3f,2f,3f,4f,3f,2f),
        "Club" to floatArrayOf(0f,0f,3f,4f,4f,4f,3f,0f,0f,0f),
        "Rock" to floatArrayOf(4f,3f,2f,1f,-1f,-2f,0f,2f,3f,4f),
        "Electronic" to floatArrayOf(5f,4f,1f,0f,-1f,2f,3f,4f,4f,3f)
    )
    fun bands(name:String):FloatArray=checkNotNull(curves[name]){"Unknown desktop EQ preset"}.copyOf()
}
