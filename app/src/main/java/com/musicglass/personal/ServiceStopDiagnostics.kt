package com.musicglass.personal

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One past process death as reported by Android, reduced to fields that are safe to log. */
internal data class ProcessExit(val timestamp: Long, val reason: Int, val status: Int, val importance: Int,
                                val pssKb: Long, val rssKb: Long, val processName: String, val description: String?)

/** Names for ApplicationExitInfo.REASON_* (API 30+). Unknown future codes keep their number. */
internal fun exitReasonName(reason: Int): String = when (reason) {
    ApplicationExitInfo.REASON_UNKNOWN -> "unknown"
    ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self"
    ApplicationExitInfo.REASON_SIGNALED -> "signaled"
    ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
    ApplicationExitInfo.REASON_CRASH -> "crash"
    ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash_native"
    ApplicationExitInfo.REASON_ANR -> "anr"
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "init_failure"
    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission_change"
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive_resource_usage"
    ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
    ApplicationExitInfo.REASON_USER_STOPPED -> "user_stopped"
    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency_died"
    ApplicationExitInfo.REASON_OTHER -> "other"
    ApplicationExitInfo.REASON_FREEZER -> "freezer"
    ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package_state_change"
    ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "package_updated"
    else -> "reason_$reason"
}

/** Crashes that took playback down with them; the next start tells the user once (W1.5). */
internal fun isAbnormalExit(reason: Int): Boolean = reason == ApplicationExitInfo.REASON_CRASH ||
    reason == ApplicationExitInfo.REASON_CRASH_NATIVE || reason == ApplicationExitInfo.REASON_ANR

/** Exits newer than [lastSeen], oldest first, so the event log reads in order. */
internal fun newProcessExits(exits: List<ProcessExit>, lastSeen: Long): List<ProcessExit> =
    exits.filter { it.timestamp > lastSeen }.sortedBy { it.timestamp }

/** One log line. The system description is free text: kept short, single-line and URL-free. */
internal fun describeExit(exit: ProcessExit): String {
    val description = exit.description.orEmpty().replace(Regex("\\s+"), " ")
        .replace(Regex("https?://\\S+"), "<url>").take(80).trim()
    val process = exit.processName.substringAfter(':', "main")
    return "process_exit at=${exit.timestamp} process=$process reason=${exitReasonName(exit.reason)} status=${exit.status} importance=${exit.importance} pss_kb=${exit.pssKb} rss_kb=${exit.rssKb}" +
        if (description.isEmpty()) "" else " desc=\"$description\""
}

/**
 * The service is going away while the user still wants sound, and not because the app was
 * swiped from recents: the unexplained stop seen once in the stress test (F5).
 */
internal fun isUnexpectedStop(playWhenReady: Boolean, taskRemoved: Boolean): Boolean = playWhenReady && !taskRemoved

private const val EXIT_PREFS = "playback_diagnostics"
private const val EXIT_SEEN = "exit_seen_at"

/**
 * Logs process deaths Android recorded since the last service start, including the cause of a
 * stop that the system log has since rotated away. Call off the main thread (binder + disk).
 * Returns the newest abnormal exit not yet reported, or null.
 */
internal fun logNewProcessExits(context: Context, events: PlaybackEventLog): ProcessExit? {
    val prefs = context.getSharedPreferences(EXIT_PREFS, Context.MODE_PRIVATE)
    val lastSeen = prefs.getLong(EXIT_SEEN, 0L)
    val exits = runCatching {
        context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, 8)
            .map { ProcessExit(it.timestamp, it.reason, it.status, it.importance, it.pss, it.rss, it.processName, it.description) }
    }.getOrElse { events.append("process_exit_unavailable ${it.javaClass.simpleName}"); return null }
    val fresh = newProcessExits(exits, lastSeen)
    if (fresh.isEmpty()) return null
    fresh.forEach { events.append(describeExit(it)) }
    prefs.edit().putLong(EXIT_SEEN, fresh.last().timestamp).apply()
    val noticeWorthy = crashNoticeExit(fresh, context.packageName, System.currentTimeMillis())
    if (noticeWorthy != null) { events.append("crash_notice_raised"); CrashNotice.raise(context) }
    return fresh.lastOrNull { isAbnormalExit(it.reason) }
}

/** How long after a crash the next start still mentions it. */
internal const val CRASH_NOTICE_WINDOW_MS = 6 * 60 * 60 * 1000L

/**
 * The crash that the next start should mention (W1.5): an abnormal exit of the main process,
 * where the player lives, and a recent one. A :solver crash never stops the music, and the
 * first start after an update reads exits that may be days old.
 */
internal fun crashNoticeExit(exits: List<ProcessExit>, packageName: String, now: Long): ProcessExit? =
    exits.lastOrNull { isAbnormalExit(it.reason) && it.processName == packageName && now - it.timestamp in 0..CRASH_NOTICE_WINDOW_MS }

/**
 * One-time "Playback stopped unexpectedly" line in the mini player. The restored queue is
 * paused as always; this only explains why. Cleared when the user plays or dismisses it.
 */
internal object CrashNotice {
    private const val KEY = "crash_notice_pending"
    var pending by mutableStateOf(false)
        private set
    /** Reads the stored flag; call off the main thread. */
    fun load(context: Context) {
        if (context.getSharedPreferences(EXIT_PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)) pending = true
    }
    fun raise(context: Context) {
        context.getSharedPreferences(EXIT_PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
        pending = true
    }
    fun clear(context: Context) {
        if (!pending) return
        pending = false
        context.getSharedPreferences(EXIT_PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, false).apply()
    }
}

/** Power state at the moment the service is destroyed: what decides whether Android stopped us. */
internal fun stopContext(context: Context, playWhenReady: Boolean, playbackState: Int, taskRemoved: Boolean): String {
    val power = context.getSystemService(PowerManager::class.java)
    val activity = context.getSystemService(ActivityManager::class.java)
    val bucket = runCatching { context.getSystemService(UsageStatsManager::class.java).appStandbyBucket }.getOrDefault(-1)
    return "service_destroy play_when_ready=$playWhenReady state=$playbackState task_removed=$taskRemoved idle=${power.isDeviceIdleMode} power_save=${power.isPowerSaveMode} bg_restricted=${activity.isBackgroundRestricted} standby_bucket=$bucket"
}
