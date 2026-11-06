package com.musicglass.desktop

internal enum class SleepMode { Off,Deadline,Tracks,Queue }
internal data class SleepPlan(val mode:SleepMode=SleepMode.Off,val deadlineMs:Long=0,val songsLeft:Int=0,val fadeMs:Long=15000){
    init{require(deadlineMs>=0&&songsLeft in 0..5000&&fadeMs in setOf(0L,5000L,15000L,30000L,60000L));if(mode==SleepMode.Tracks)require(songsLeft>0);if(mode==SleepMode.Deadline)require(deadlineMs>0)}
    fun restored(now:Long)=if(mode==SleepMode.Deadline&&(deadlineMs<=now||deadlineMs-now>86_400_000))SleepPlan()else this
}
internal fun sleepFadeGain(remainingMs:Long,fadeMs:Long):Float=if(fadeMs==0L)if(remainingMs>0)1f else 0f else (remainingMs.toFloat()/fadeMs).coerceIn(0f,1f)
