package com.musicglass.personal

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Feature 13: Android's "Open by default" page for Riff. The page arrived in Android 12; on Android 11 the same
 * switches are a section of the app's App info page, so that opens instead.
 */
@SuppressLint("InlinedApi") // the newer action is only used where sdk says the phone has it
internal fun openLinksByDefaultAction(sdk: Int = Build.VERSION.SDK_INT): String =
    if (sdk >= 31) Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS else Settings.ACTION_APPLICATION_DETAILS_SETTINGS

internal fun openLinkSettings(context: Context) {
    try { context.startActivity(Intent(openLinksByDefaultAction(), Uri.parse("package:${context.packageName}"))) }
    catch (_: ActivityNotFoundException) {}
}

/** Request access only on the on-device audio screen, using the permission this OS implements. */
internal fun audioLibraryPermission(sdk:Int=Build.VERSION.SDK_INT):String =
    if(sdk>=33) "android.permission.READ_MEDIA_AUDIO" else "android.permission.READ_EXTERNAL_STORAGE"
