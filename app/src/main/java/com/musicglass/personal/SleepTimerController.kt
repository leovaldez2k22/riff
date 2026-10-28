package com.musicglass.personal
import kotlinx.coroutines.*

/** Feature 18: how long the timer fades out: off, 5, 15 (the default, as before), 30 or 60 s. */
internal val SLEEP_FADE_CHOICES_MS = listOf(0L, 5_000L, 15_000L, 30_000L, 60_000L)
internal const val SLEEP_FADE_DEFAULT_MS = 15_000L
internal fun sleepFadeGain(remainingMs:Long,fadeMs:Long=SLEEP_FADE_DEFAULT_MS):Float=
    if(fadeMs<=0) (if(remainingMs>0) 1f else 0f) else (remainingMs/fadeMs.toFloat()).coerceIn(0f,1f)
/** Monotonic service timer. Fade affects the player only; cancellation restores its prior level. */
internal class SleepTimerController(private val scope:CoroutineScope,private val clock:()->Long,
    private val wait:suspend (Long)->Unit={delay(it)},private val pause:()->Unit,private val publish:(Long)->Unit,
    private val readVolume:()->Float={1f},private val writeVolume:(Float)->Unit={},
    /** Saves the deadline (0 = none) so it survives the process being killed (W6). Not called by close(). */
    private val persist:(Long)->Unit={},
    /** Feature 18: read when the fade starts, so a change applies to the running timer. */
    private val fadeMs:()->Long={SLEEP_FADE_DEFAULT_MS}) {
    private var job:Job?=null;private var generation=0L
    private var originalVolume:Float?=null
    private var lastApplied:Float?=null
    private fun restoreVolume() {
        originalVolume?.let {writeVolume(it)};originalVolume=null;lastApplied=null
    }
    fun set(minutes:Int) {
        require(minutes in 0..1440)
        arm(sleepDeadline(minutes,clock()))
    }
    /** Starts the timer for an absolute [deadline] on [clock]; 0 cancels. Used directly when restoring. */
    fun arm(deadline:Long) {
        job?.cancel();restoreVolume()
        val ticket=++generation;publish(deadline);persist(deadline)
        if(deadline>0) job=scope.launch {
            while(isActive && ticket==generation) {
                val remaining=deadline-clock()
                val fade=fadeMs()
                if(remaining<=fade) {
                    if(originalVolume==null) originalVolume=readVolume().coerceIn(0f,1f)
                    // Respect explicit changes to the player's own level during the fade.
                    val current=readVolume();val applied=lastApplied
                    if(applied!=null && kotlin.math.abs(current-applied)>.001f) originalVolume=current.coerceIn(0f,1f)
                    val next=originalVolume!!*sleepFadeGain(remaining,fade)
                    writeVolume(next);lastApplied=next
                }
                if(remaining<=0) break
                wait(if(remaining>fade) remaining-fade else minOf(100L,remaining))
            }
            ensureActive()
            if(ticket==generation) {pause();restoreVolume();publish(0);persist(0)}
        }
    }
    fun close() {generation++;job?.cancel();restoreVolume();publish(0)}
}

/**
 * Feature 18: "End of this song", "After 2 / 3 / 5 songs" and "End of queue". [songsLeft] counts the songs still to
 * finish, the current one included; the timer stops playback when the last of them ends.
 */
internal class SleepSongs {
    var songsLeft = 0; private set
    val active get() = songsLeft > 0
    fun set(songs: Int) { songsLeft = songs.coerceIn(0, 2000) }
    fun cancel() { songsLeft = 0 }
    /** A song finished. True when that was the last one and playback should stop now. */
    fun finishedOne(): Boolean { if (songsLeft <= 0) return false; songsLeft--; return songsLeft == 0 }
    /** Whether the song playing now is the last one, so its end is the deadline the fade leads up to. */
    val onLastSong get() = songsLeft == 1
}

/** The deadline that ends the last song: what is left of it at [speed], plus a moment so the fade reaches its end. */
internal fun sleepSongDeadline(now: Long, durationMs: Long, positionMs: Long, speed: Float): Long =
    now + ((durationMs - positionMs).coerceAtLeast(0) / speed.coerceAtLeast(.1f)).toLong() + SLEEP_SONG_GRACE_MS

internal const val SLEEP_SONG_GRACE_MS = 600L
