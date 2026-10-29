package com.musicglass.personal

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/*
 * Feature 12 extras: the weekly recap notification, "Your week in Encore is ready", on Sunday evening. Opt-in and off
 * by default. One job at a time, set for the coming Sunday at 18:00; it posts when the switch is still on, the week has
 * some listening and that week was not already announced, then sets the next one. Everything it reads is on the phone.
 */

private const val RECAP_KEY = "encore_recap"
private const val SENT_KEY = "encore_recap_sent"
private const val CHANNEL = "encore-recap"
private const val JOB = 15301
private val RECAP_AT: LocalTime = LocalTime.of(18, 0)
/** How late past 18:00 the system may run it, so a phone that is asleep does not post on Monday. */
private const val RECAP_WINDOW_MS = 3 * 3_600_000L

private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("music_collections", Context.MODE_PRIVATE)
internal fun encoreRecapOn(context: Context) = prefs(context).getBoolean(RECAP_KEY, false)
internal fun setEncoreRecapOn(context: Context, on: Boolean) { prefs(context).edit().putBoolean(RECAP_KEY, on).apply(); EncoreRecap.schedule(context) }

/** When the next recap is due: this Sunday at 18:00, or next Sunday's once this one has passed or been sent. */
internal fun nextEncoreRecap(now: ZonedDateTime, sentThisWeek: Boolean): ZonedDateTime {
    val sunday = now.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
    val due = sunday.atTime(RECAP_AT).atZone(now.zone)
    val late = now.isAfter(due.plusNanos(RECAP_WINDOW_MS * 1_000_000))
    return if (sentThisWeek || late) due.plusWeeks(1) else due
}

internal object EncoreRecap {
    /** On: the job for the next Sunday; off: none. Idempotent, and cheap enough to call whenever Encore opens. */
    fun schedule(context: Context, now: ZonedDateTime = ZonedDateTime.now()) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (!encoreRecapOn(context)) { scheduler.cancel(JOB); return }
        val sent = prefs(context).getString(SENT_KEY, null) == weekKey(now.toLocalDate())
        val latency = (nextEncoreRecap(now, sent).toInstant().toEpochMilli() - now.toInstant().toEpochMilli()).coerceAtLeast(0)
        scheduler.schedule(JobInfo.Builder(JOB, ComponentName(context, EncoreRecapJob::class.java))
            .setMinimumLatency(latency).setOverrideDeadline(latency + RECAP_WINDOW_MS).setPersisted(true).build())
    }

    /** The week a recap is for, as its Encore period's first day. */
    internal fun weekKey(day: LocalDate) = EncorePeriod.containing(EncoreRange.Week, encoreRecapDay(day), EncorePeriod.firstDayOfWeek(), null).start.toString()

    /** Posts this week's recap if it is due and there is something to say. */
    internal suspend fun post(context: Context) {
        val today = LocalDate.now()
        if (!encoreRecapOn(context) || !encoreRecapToday(today)) return
        val week = weekKey(today)
        if (prefs(context).getString(SENT_KEY, null) == week) return
        val container = AppContainer.get(context)
        container.stateReady.await()
        val account = container.provider.storageScopeKey()
        val scopes = listOfNotNull(account.takeIf { it != UNVERIFIED_SCOPE_KEY }, DEVICE_LOCAL_SCOPE_KEY)
        val (listened, top) = container.encore.quick(scopes, EncorePeriod.containing(EncoreRange.Week, encoreRecapDay(today), EncorePeriod.firstDayOfWeek(), null))
        if (listened <= 0) return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, string(R.string.encore_recap_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, JOB, Intent(AppShortcut.Encore.action).setClass(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = top?.let { string(R.string.encore_recap_text_top, encoreDuration(listened), it.title) } ?: string(R.string.encore_recap_text, encoreDuration(listened))
        manager.notify(JOB, Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(string(R.string.encore_recap_title)).setContentText(text).setContentIntent(open).setAutoCancel(true).build())
        prefs(context).edit().putString(SENT_KEY, week).apply()
    }
}

class EncoreRecapJob : JobService() {
    private var work: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        work = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { EncoreRecap.post(this@EncoreRecapJob) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            jobFinished(params, false)
            EncoreRecap.schedule(this@EncoreRecapJob)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { work?.cancel(); return true }
}
