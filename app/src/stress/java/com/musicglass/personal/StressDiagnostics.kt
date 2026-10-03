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
        // adb shell am broadcast -a com.musicglass.personal.stress.FAIL_SABR --ei count 1
        // makes the next song stream fail with ATTESTATION_REQUIRED (scenarios.py sabr_attestation).
        checkNotNull(context).registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                SabrFaultInjection.failNextAttestations(intent.getIntExtra("count", 1))
            }
        }, IntentFilter("com.musicglass.personal.stress.FAIL_SABR"), Context.RECEIVER_EXPORTED)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
