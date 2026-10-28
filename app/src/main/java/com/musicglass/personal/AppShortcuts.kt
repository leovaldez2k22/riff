package com.musicglass.personal

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon

/**
 * Feature 27: long-press Riff's icon for Search, Downloads, Shuffle liked songs and, while a song is loaded,
 * Resume. They are published from code rather than res/xml, so debug and release builds (different package ids)
 * both point at themselves. Each opens MainActivity with one of these actions; nothing else in the intent is read.
 */
internal enum class AppShortcut(val action: String) {
    Search("com.musicglass.personal.shortcut.SEARCH"),
    Downloads("com.musicglass.personal.shortcut.DOWNLOADS"),
    ShuffleLiked("com.musicglass.personal.shortcut.SHUFFLE_LIKED"),
    Resume("com.musicglass.personal.shortcut.RESUME"),
}

internal fun appShortcutOf(intent: Intent?): AppShortcut? = AppShortcut.entries.firstOrNull { it.action == intent?.action }

private fun shortcut(context: Context, which: AppShortcut, label: String, icon: Int, rank: Int): ShortcutInfo =
    ShortcutInfo.Builder(context, which.name.lowercase()).setShortLabel(label).setLongLabel(label).setRank(rank)
        .setIcon(Icon.createWithResource(context, icon))
        .setIntent(Intent(which.action).setClass(context, MainActivity::class.java)).build()

/** The three fixed shortcuts, plus Resume with the loaded song's title. Cheap; called at start and on song change. */
internal fun publishAppShortcuts(context: Context, resumeTitle: String?) {
    val manager = context.getSystemService(ShortcutManager::class.java) ?: return
    val list = buildList {
        if (!resumeTitle.isNullOrBlank()) add(shortcut(context, AppShortcut.Resume,
            string(R.string.shortcut_resume_x, resumeTitle.take(40)), R.drawable.shortcut_resume, 0))
        add(shortcut(context, AppShortcut.Search, string(R.string.search), R.drawable.shortcut_search, 1))
        add(shortcut(context, AppShortcut.ShuffleLiked, string(R.string.shortcut_shuffle_liked), R.drawable.shortcut_shuffle, 2))
        add(shortcut(context, AppShortcut.Downloads, string(R.string.shortcut_downloads), R.drawable.shortcut_downloads, 3))
    }.take(manager.maxShortcutCountPerActivity.coerceAtLeast(1))
    runCatching { manager.dynamicShortcuts = list }
}
