package com.musicglass.personal

import android.os.Trace
import android.view.View
import java.lang.ref.WeakReference

/**
 * Q14: marks, in the system trace, the time from a player or queue action to the frame that shows its result.
 * The benchmark module reads the sections as "riff:<name>" (docs/quality/performance.md). When nobody is
 * tracing, which is always outside a measurement, each call is one check and nothing else.
 *
 * Main thread only. A section opens in the handler of the action, so the time the system took to deliver the
 * touch is not in it.
 */
internal object PerfTrace {
    private var view = WeakReference<View>(null)
    private val open = HashMap<String, Int>()
    private var cookies = 0

    fun bind(target: View) { view = WeakReference(target) }
    fun unbind(target: View) { if (view.get() === target) view.clear() }

    /** The action [name] was asked for. A second request before the first is shown replaces it. */
    fun began(name: String) {
        if (!tracing()) return
        open.remove(name)?.let { Trace.endAsyncSection(section(name), it) }
        val cookie = ++cookies
        open[name] = cookie
        Trace.beginAsyncSection(section(name), cookie)
    }

    /** The state that answers [name] is composed; the section closes when the frame holding it is on screen. */
    fun shown(name: String) {
        val cookie = open.remove(name) ?: return
        val target = view.get()?.takeIf { it.isAttachedToWindow }
        if (target == null) { Trace.endAsyncSection(section(name), cookie); return }
        target.viewTreeObserver.registerFrameCommitCallback { Trace.endAsyncSection(section(name), cookie) }
        target.invalidate()
    }

    /** For an action whose result is local state: the next frame shows it. */
    fun action(name: String) { began(name); shown(name) }

    /** [name] is over now, whatever is on screen. */
    fun ended(name: String) { open.remove(name)?.let { Trace.endAsyncSection(section(name), it) } }

    /** A piece of work on the calling thread, any thread. [block] must not suspend. */
    inline fun <T> block(name: String, block: () -> T): T {
        if (!tracing()) return block()
        Trace.beginSection(section(name))
        try { return block() } finally { Trace.endSection() }
    }

    @PublishedApi internal fun section(name: String) = "riff:$name"
    @PublishedApi internal fun tracing() = runCatching { Trace.isEnabled() }.getOrDefault(false)
}
