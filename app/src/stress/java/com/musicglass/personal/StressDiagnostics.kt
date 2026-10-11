package com.musicglass.personal

import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.StrictMode

/** Stress build only: logs StrictMode violations (logcat tag StrictMode) from process start. */
class StressDiagnostics : ContentProvider() {
    override fun onCreate(): Boolean {
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
        // Not detectAll(): untagged-socket reports would fire on every OkHttp request, and
        // detectActivityLeaks() runs a blocking System.gc() on every activity destroy, which
        // caused a harness-only ANR under rotation churn. Leaks are measured via heap trends.
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .detectLeakedSqlLiteObjects()
                .detectFileUriExposure()
                .detectContentUriWithoutPermission()
                .detectUnsafeIntentLaunch()
                .detectIncorrectContextUse()
                .penaltyLog()
                .build()
        )
        // Same-UID instrumentation only. Shell broadcasts cannot mutate fault/session state.
        androidx.core.content.ContextCompat.registerReceiver(checkNotNull(context), object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                SabrFaultInjection.failNextAttestations(intent.getIntExtra("count", 1))
            }
        }, IntentFilter("com.musicglass.personal.stress.FAIL_SABR"), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        // scrambles the in-memory session cookies so the expired-session UI shows (W5). Saved
        // cookies are untouched; Finish sign-in or a restart restores the real session.
        androidx.core.content.ContextCompat.registerReceiver(checkNotNull(context), object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                YouTubeMusicProvider.shared(context).simulateExpiredSession()
            }
        }, IntentFilter("com.musicglass.personal.stress.FAKE_EXPIRED"), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
