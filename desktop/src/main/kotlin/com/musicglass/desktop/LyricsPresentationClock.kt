package com.musicglass.desktop

/** Content time at the hardware presentation anchor; latency is already reflected and is not subtracted twice. */
internal object LyricsPresentationClock {
    fun position(anchor:AudioEvent.ClockAnchor?,entry:String?,playing:Boolean,nowNanos:Long,fallbackMs:Long,durationMs:Long):Long {
        if(anchor==null||anchor.currentEntryId!=entry)return fallbackMs.coerceAtLeast(0)
        val elapsed=if(playing)(nowNanos-anchor.monotonicTimestampNanos).coerceIn(0,250_000_000)else 0
        val position=anchor.contentPositionMs+(elapsed/1_000_000*anchor.playbackRate).toLong()
        return if(durationMs>0)position.coerceIn(0,durationMs)else position.coerceAtLeast(0)
    }
}
