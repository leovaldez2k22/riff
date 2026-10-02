package com.musicglass.personal

import android.content.Context
import java.io.File

internal fun removeRetiredContent(context: Context) {
    val target = context.getSharedPreferences("music_collections", Context.MODE_PRIVATE)
    if (target.getBoolean("retired_content_removed", false)) return
    val legacy = context.getSharedPreferences("demo_collections", Context.MODE_PRIVATE)
    val editor = target.edit()
    legacy.all.forEach { (key, value) ->
        if (!target.contains(key) && key == "history_enabled" && value is Boolean) editor.putBoolean(key, value)
        if ((key in setOf("online_favorites", "online_playlist", "online_history") ||
            key.matches(Regex("account_[a-f0-9]{32}_online_(favorites|playlist|history)"))) && value is String && !target.contains(key))
            editor.putString(key, value)
    }
    if (!editor.commit()) return
    legacy.edit().clear().commit()
    context.getSharedPreferences("offline_demo_copies", Context.MODE_PRIVATE).edit().clear().commit()
    context.getSharedPreferences("podcast_progress", Context.MODE_PRIVATE).edit().clear().commit()
    context.getSharedPreferences("playback", Context.MODE_PRIVATE).edit()
        .remove("demo_queue").remove("index").remove("position").commit()
    val retired = File(context.filesDir, "offline_demos").canonicalFile
    if (retired.parentFile == context.filesDir.canonicalFile && retired.exists() && !retired.deleteRecursively()) return
    target.edit().putBoolean("retired_content_removed", true).commit()
}
