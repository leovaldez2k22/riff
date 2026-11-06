package com.musicglass.desktop.automix
/** Optional, bounded source facts; these never grant a stream/auth capability. */
internal data class TransitionHints(val isMusic:Boolean=true,val classical:Boolean=false,val album:String="",val vocals:List<LongRange> = emptyList(),val trackNumber:Int?=null)
internal fun checkedTransitionHints(value:TransitionHints):TransitionHints{
    require(value.album.length<=512&&value.vocals.size<=8192&&(value.trackNumber==null||value.trackNumber in 1..1000000))
    require(value.vocals.all{it.first>=0&&it.last>=it.first&&it.last<=86400000})
    return value.copy(vocals=value.vocals.toList())
}
internal enum class TransitionKind{Plain,Smart,BeatAligned,Echo}
internal enum class TransitionPhase{Waiting,Prepared,Playing,Bypassed}
