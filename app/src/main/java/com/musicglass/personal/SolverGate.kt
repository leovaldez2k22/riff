package com.musicglass.personal

/**
 * Q18-098: the order in which the solver's binder threads enter the library. The library runs one operation at a
 * time and holds that turn through a player's whole download and preparation, so a song's lookup that arrived while
 * two players were being preloaded waited for both. Here preloads (and the script's warm-up) enter one at a time and
 * step aside while a lookup waits or runs; a lookup is never held, so at most it waits for the one preload already
 * inside. A preload held longer than [maxHoldMs] goes in anyway: it is work a later song needs.
 */
internal class SolverGate(private val maxHoldMs: Long = 20_000, private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = Object()
    private var lookups = 0
    private var backgroundInside = false

    fun <T> lookup(block: () -> T): T {
        synchronized(lock) { lookups++ }
        try { return block() } finally { synchronized(lock) { lookups--; lock.notifyAll() } }
    }

    /** Runs [block] once no lookup is waiting and no other background work is inside; [onHeld] gets the ms it waited for lookups. */
    fun <T> background(onHeld: (Long) -> Unit = {}, block: () -> T): T {
        var heldForLookups = 0L
        synchronized(lock) {
            val deadline = clock() + maxHoldMs
            while (backgroundInside || lookups > 0) {
                val left = deadline - clock()
                if (left <= 0 && !backgroundInside) break
                val before = clock()
                val waitingForLookups = !backgroundInside
                lock.wait(if (left > 0) left else maxHoldMs)
                if (waitingForLookups) heldForLookups += clock() - before
            }
            backgroundInside = true
        }
        if (heldForLookups > 0) onHeld(heldForLookups)
        try { return block() } finally { synchronized(lock) { backgroundInside = false; lock.notifyAll() } }
    }
}
