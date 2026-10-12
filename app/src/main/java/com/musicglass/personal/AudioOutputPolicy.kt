package com.musicglass.personal
internal enum class OutputKind(private val labelRes:Int) { Phone(R.string.phone_speaker),Earphones(R.string.earphones),Headphones(R.string.headphones),Speaker(R.string.external_speaker);val label:String get()=string(labelRes) }
/** Use the selected media route. Device names refine Bluetooth's otherwise generic headset type. */
internal fun outputKind(type:Int,name:String):OutputKind {
    val n=name.lowercase(java.util.Locale.ROOT)
    return when {
        type in setOf(1,2)->OutputKind.Phone
        type in setOf(24,27) || listOf("speaker","soundbar","homepod","echo","nest audio","flip ","charge ","boombox","soundlink").any(n::contains)->OutputKind.Speaker
        listOf("airpods","earbud","earphone","buds","in-ear","in ear","wf-","freebuds","nothing ear").any(n::contains)->OutputKind.Earphones
        type in setOf(3,4,8,22,26,23)->OutputKind.Headphones
        else->OutputKind.Speaker
    }
}
