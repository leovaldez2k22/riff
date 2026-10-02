package com.musicglass.personal
import kotlinx.coroutines.*

internal fun sleepFadeGain(remainingMs:Long):Float=(remainingMs/15_000f).coerceIn(0f,1f)
/** Monotonic service timer. Fade affects the player only; cancellation restores its prior level. */
internal class SleepTimerController(private val scope:CoroutineScope,private val clock:()->Long,
    private val wait:suspend (Long)->Unit={delay(it)},private val pause:()->Unit,private val publish:(Long)->Unit,
    private val readVolume:()->Float={1f},private val writeVolume:(Float)->Unit={}) {
    private var job:Job?=null;private var generation=0L
    private var originalVolume:Float?=null
    private var lastApplied:Float?=null
    private fun restoreVolume() {
        originalVolume?.let {writeVolume(it)};originalVolume=null;lastApplied=null
    }
    fun set(minutes:Int) {
        require(minutes in 0..1440)
        job?.cancel();restoreVolume()
        val ticket=++generation;val deadline=sleepDeadline(minutes,clock());publish(deadline)
        if(deadline>0) job=scope.launch {
            while(isActive && ticket==generation) {
                val remaining=deadline-clock()
                if(remaining<=15_000) {
                    if(originalVolume==null) originalVolume=readVolume().coerceIn(0f,1f)
                    // Respect explicit changes to the player's own level during the fade.
                    val current=readVolume();val applied=lastApplied
                    if(applied!=null && kotlin.math.abs(current-applied)>.001f) originalVolume=current.coerceIn(0f,1f)
                    val next=originalVolume!!*sleepFadeGain(remaining)
                    writeVolume(next);lastApplied=next
                }
                if(remaining<=0) break
                wait(if(remaining>15_000) remaining-15_000 else minOf(100L,remaining))
            }
            ensureActive()
            if(ticket==generation) {pause();restoreVolume();publish(0)}
        }
    }
    fun close() {generation++;job?.cancel();restoreVolume();publish(0)}
}
