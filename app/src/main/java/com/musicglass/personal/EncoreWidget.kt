package com.musicglass.personal

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.widget.RemoteViews
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/*
 * Feature 12 extras: the Encore home widget, today's listening time and most played song, and the last seven days as
 * bars (owner, 31 Oct 2026). It is not one of the player
 * presets (RiffWidgets): it shows no song that is playing and has no controls. Refreshed when a play ends, and every
 * three hours by the launcher so a new day starts at zero. A tap opens Encore.
 */

/** Today's time and top song as the widget words them; "Nothing yet" before the first play. */
internal fun encoreWidgetLines(listenedMs: Long, top: String?): Pair<String, String> =
    if (listenedMs <= 0) string(R.string.encore_widget_none) to string(R.string.encore_widget_none_detail)
    else encoreDuration(listenedMs) to (top?.let { string(R.string.encore_widget_top, it) } ?: "")

/** Each of the last seven days, today last, as a share of the most listened of them; a day with none keeps a stub. */
internal fun encoreWeekShares(week: LongArray): FloatArray {
    val most = week.maxOrNull()?.takeIf { it > 0 } ?: return FloatArray(week.size) { ENCORE_BAR_STUB }
    return FloatArray(week.size) { maxOf(ENCORE_BAR_STUB, week[it].toFloat() / most) }
}
private const val ENCORE_BAR_STUB = .08f

internal object EncoreWidgets {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun refresh(context: Context, finished: () -> Unit = {}) {
        val app = context.applicationContext
        scope.launch {
            try {
                val manager = AppWidgetManager.getInstance(app)
                val ids = manager.getAppWidgetIds(ComponentName(app, EncoreWidget::class.java))
                if (ids.isEmpty()) return@launch
                val today = LocalDate.now()
                val (quick, week) = suspendCatching {
                    val process = AppContainer.get(app)
                    process.stateReady.await()
                    val scopes = listOfNotNull(OfflineMusicStore.get(app).currentScope.takeIf { it != UNVERIFIED_SCOPE_KEY }, DEVICE_LOCAL_SCOPE_KEY)
                    val days = process.encore.daily(scopes, EncorePeriod(EncoreRange.Week, today.minusDays(6), today.plusDays(1)))
                    process.encore.quick(scopes, EncorePeriod.containing(EncoreRange.Day, today, EncorePeriod.firstDayOfWeek(), null)) to
                        LongArray(7) { days[today.minusDays(6L - it)] ?: 0L }
                }.getOrNull() ?: ((0L to null) to LongArray(7))
                val (listened, top) = quick
                val (time, detail) = encoreWidgetLines(listened, top?.title)
                ids.forEach { manager.updateAppWidget(it, views(app, it, time, detail, week)) }
            } finally { finished() }
        }
    }

    internal fun views(context: Context, id: Int, time: String, detail: String, week: LongArray = LongArray(7)): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_encore)
        views.setTextViewText(R.id.widget_encore_time, time)
        views.setTextViewText(R.id.widget_encore_detail, detail)
        views.setImageViewBitmap(R.id.widget_encore_week, weekBars(encoreWeekShares(week)))
        views.setContentDescription(R.id.widget_encore_week, string(R.string.encore_widget_week, encoreDuration(week.sum())))
        val open = PendingIntent.getActivity(context, id, Intent(AppShortcut.Encore.action).setClass(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        views.setOnClickPendingIntent(R.id.widget_encore_root, open)
        return views
    }

    /** Seven rounded bars, today's in the accent. */
    private fun weekBars(shares: FloatArray): Bitmap {
        val width = 168; val height = 108; val gap = 8f
        val bar = (width - gap * (shares.size - 1)) / shares.size
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        shares.forEachIndexed { i, share ->
            paint.color = if (i == shares.lastIndex) 0xFFFF6DB2.toInt() else 0x59F9F8FF
            val left = i * (bar + gap)
            canvas.drawRoundRect(left, height * (1 - share), left + bar, height.toFloat(), bar / 2, bar / 2, paint)
        }
        return bitmap
    }
}

class EncoreWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync(); EncoreWidgets.refresh(context) { pending.finish() }
    }
}
