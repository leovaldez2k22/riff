package com.musicglass.personal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider

/**
 * A Play key can start the playback service after Android has ended the app, through Media3's MediaButtonReceiver.
 * Android then wants the service in the foreground within seconds, before the saved queue has loaded and Media3 has a
 * notification to show. This holds that place with a plain notification under Media3's own id, which Media3's replaces
 * when the song plays. If it turns out there is nothing to play, the hold is let go and the service stops.
 */
@OptIn(markerClass = [UnstableApi::class])
internal class MediaKeyHold(private val service: Service) {
    private var holding = false

    fun start() {
        holding = true
        val manager = service.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID,
            service.getString(DefaultMediaNotificationProvider.DEFAULT_CHANNEL_NAME_RESOURCE_ID), NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(service, DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID)
            .setSmallIcon(R.drawable.tile_riff).setContentTitle(service.getString(R.string.app_name)).build()
        if (Build.VERSION.SDK_INT >= 29) service.startForeground(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else service.startForeground(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID, notification)
    }

    /** The queue came back, so Media3's notification takes over from here. */
    fun handedOver() { holding = false }

    /** Nothing to play. */
    fun letGo() {
        if (!holding) return
        holding = false
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        service.stopSelf()
    }
}

/** Only a key that found no queue needs the hold: with a song loaded, Media3 has its notification ready. */
internal fun mediaKeyNeedsHold(action: String?, queueSize: Int): Boolean = action == Intent.ACTION_MEDIA_BUTTON && queueSize == 0
