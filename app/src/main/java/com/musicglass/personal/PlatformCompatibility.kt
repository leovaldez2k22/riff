package com.musicglass.personal

import android.os.Build

/** Request access only on the on-device audio screen, using the permission this OS implements. */
internal fun audioLibraryPermission(sdk:Int=Build.VERSION.SDK_INT):String =
    if(sdk>=33) "android.permission.READ_MEDIA_AUDIO" else "android.permission.READ_EXTERNAL_STORAGE"
