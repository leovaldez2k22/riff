package com.musicglass.personal

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/*
 * Feature 12 extras: the Encore home widget, today's listening time and most played song. It is not one of the player
 * presets (RiffWidgets): it shows no song that is playing and has no controls. Refreshed when a play ends, and every
 * three hours by the launcher so a new day starts at zero. A tap opens Encore.
 */

/** Today's time and top song as the widget words them; "Nothing yet" before the first play. */
internal fun encoreWidgetLines(listenedMs: Long, top: String?): Pair<String, String> =
    if (listenedMs <= 0) string(R.string.encore_widget_none) to string(R.string.encore_widget_none_detail)
    else encoreDuration(listenedMs) to (top?.let { string(R.string.encore_widget_top, it) } ?: "")

internal object EncoreWidgets {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun refresh(context: Context, finished: () -> Unit = {}) {
        val app = context.applicationContext
        scope.launch {
            try {
                val manager = AppWidgetManager.getInstance(app)
                val ids = manager.getAppWidgetIds(ComponentName(app, EncoreWidget::class.java))
                if (ids.isEmpty()) return@launch
                val (listened, top) = suspendCatching {
                    val process = AppContainer.get(app)
                    process.stateReady.await()
                    val scopes = listOfNotNull(OfflineMusicStore.get(app).currentScope.takeIf { it != UNVERIFIED_SCOPE_KEY }, DEVICE_LOCAL_SCOPE_KEY)
                    process.encore.quick(scopes, EncorePeriod.containing(EncoreRange.Day, LocalDate.now(), EncorePeriod.firstDayOfWeek(), null))
                }.getOrNull() ?: (0L to null)
                val (time, detail) = encoreWidgetLines(listened, top?.title)
                ids.forEach { manager.updateAppWidget(it, views(app, it, time, detail)) }
            } finally { finished() }
        }
    }

    internal fun views(context: Context, id: Int, time: String, detail: String): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_encore)
        views.setTextViewText(R.id.widget_encore_time, time)
        views.setTextViewText(R.id.widget_encore_detail, detail)
        val open = PendingIntent.getActivity(context, id, Intent(AppShortcut.Encore.action).setClass(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        views.setOnClickPendingIntent(R.id.widget_encore_root, open)
        return views
    }
}

class EncoreWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync(); EncoreWidgets.refresh(context) { pending.finish() }
    }
}
