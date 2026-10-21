package com.musicglass.personal.benchmark

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Q18-023: on the owner's phone (vivo, Android 16) the system's own process freezer froze the test process a few
 * seconds after Riff came to the front: every thread sat in do_freezer_trap, Android's freezer said isFrozen=false
 * and "should not freeze" (an active instrumentation), and `am unfreeze --sticky` changed nothing. The test process
 * shows no window, so it looks like a cached app. A foreground service is the platform's way to say a process is
 * doing visible work; while it runs the process is not frozen. Started once per process by [openTarget].
 */
class TestKeepAlive : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Benchmark run", NotificationManager.IMPORTANCE_MIN))
        val notification = Notification.Builder(this, CHANNEL).setContentTitle("Benchmark running")
            .setSmallIcon(android.R.drawable.stat_sys_download).build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "benchmark"
        @Volatile private var started = false

        fun start(context: Context) {
            if (started) return
            context.startForegroundService(Intent(context, TestKeepAlive::class.java))
            started = true
        }
    }
}
