package com.musicglass.personal.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/** The app under measurement. The Baseline Profile plugin passes the id of the variant it built. */
internal val TARGET: String
    get() = InstrumentationRegistry.getArguments().getString("targetAppId") ?: "com.musicglass.personal.benchmark"

/** The protocol's count, unless -PriffBenchmarkIterations asks for fewer to check that a journey runs. */
internal fun iterations(protocol: Int): Int =
    InstrumentationRegistry.getArguments().getString("riff.iterations")?.toIntOrNull()?.coerceIn(1, protocol) ?: protocol

private const val WAIT_MS = 15_000L

/** Q18-097: a cold start on a busy phone took longer than [WAIT_MS] to draw Home. */
private const val COLD_WAIT_MS = 30_000L

/** Songs in the invented playlist the account owns, over its three pages. */
internal const val PLAYLIST_SONGS = 4

/**
 * The app as a fresh install that has been opened once: its data cleared, so that no page, download or switch
 * is left from another journey, then one start to do the work only a first start does. Every benchmark and
 * check begins here, which is what makes two runs comparable.
 */
internal fun cleanSlate() {
    val device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    check("Success" in device.executeShellCommand("pm clear $TARGET")) { "Could not clear $TARGET" }
    openTarget(device)
    // Feature 29: a new install's first start opens the tips over Home, in a window of its own; it is closed as a
    // person would, once, and later starts don't show it. Before the tips, a new install that finds a backup an
    // earlier one left offers to restore it; Not now, so the slate stays clean (29 Oct, after the benchmark app was
    // uninstalled on the owner's phone).
    val until = System.currentTimeMillis() + 30_000
    while (System.currentTimeMillis() < until) {
        device.findObject(By.res("restore_offer_not_now"))?.let { it.click(); Thread.sleep(500); continue }
        device.findObject(By.res("whats_new_close"))?.let { it.click(); break }
        if (device.hasObject(By.text("Speed dial"))) break
        Thread.sleep(250)
    }
    check(device.wait(Until.hasObject(By.text("Speed dial")), 30_000)) { "The first start did not reach Home" }
    device.executeShellCommand("am force-stop $TARGET")
}

/** A switch of the app's fixture feed (BenchmarkFeed in :app). Only the shell may set one. */
internal fun MacrobenchmarkScope.feed(method: String, value: String) {
    val answer = device.executeShellCommand("content call --uri content://$TARGET.benchmark-feed --method $method --arg $value")
    check("set=" in answer) { "The fixture feed did not take $method=$value: $answer" }
}

/** Every switch back to what a plain start expects. */
internal fun MacrobenchmarkScope.plainFeed() {
    feed("strict", "off"); feed("latency", "0"); feed("rate", "0"); feed("tracks", "0")
}

/** The node the app tagged [tag]; the journey fails with the tag's name if it does not appear. */
internal fun MacrobenchmarkScope.shown(tag: String): UiObject2 =
    checkNotNull(device.wait(Until.findObject(By.res(tag)), WAIT_MS)) { failed("\"$tag\" did not appear") }

/**
 * A tap on [tag]. A node redrawn between finding and clicking it (the player still sliding in) is found again.
 * The tap goes to the point where the node was found: on 29 Oct the player's Open queue row was redrawn three
 * times in a row between finding and clicking it on the owner's phone, so the click used the node too late.
 */
internal fun MacrobenchmarkScope.tap(tag: String) {
    for (attempt in 1..5) {
        try { val at = shown(tag).visibleCenter; device.click(at.x, at.y); break }
        catch (stale: androidx.test.uiautomator.StaleObjectException) { if (attempt == 5) throw stale; android.os.SystemClock.sleep(200) }
    }
    device.waitForIdle()
}

/**
 * Q18-097: a tap on [tag], and then [result]. A tap that lands while the node is still settling (the mini player
 * sliding in) is lost on a busy phone, so when [result] has not appeared after a short wait, [tag] is tapped once more.
 */
internal fun MacrobenchmarkScope.tapFor(tag: String, result: String) {
    tap(tag)
    if (device.wait(Until.hasObject(By.res(result)), 5_000)) return
    if (device.hasObject(By.res(tag))) tap(tag)
    shown(result)
}

internal fun MacrobenchmarkScope.gone(tag: String) =
    check(device.wait(Until.gone(By.res(tag)), WAIT_MS)) { failed("\"$tag\" stayed on screen") }

/**
 * [message], with the app that was in front instead. Never a screenshot: on the owner's phone a failed wait showed a
 * messaging app's notification and then its open chat (Q18-023), so nothing of the screen is kept.
 */
internal fun MacrobenchmarkScope.failed(message: String): String = "$message (in front: ${device.currentPackageName})"

/** Home with the invented shelves on it: the first usable shell. */
internal fun MacrobenchmarkScope.home() {
    checkNotNull(device.wait(Until.findObject(By.res("navigation")), COLD_WAIT_MS)) { failed("\"navigation\" did not appear") }
    checkNotNull(device.wait(Until.findObject(By.text("Speed dial")), WAIT_MS)) { "Home did not show the fixture feed" }
}

internal fun MacrobenchmarkScope.coldHome() {
    killProcess()
    launch()
    home()
}

/**
 * Q18-023: `am start -W` through the shell never returned on the owner's phone (its output pipe stayed open after
 * the launch), and the library's startActivityAndWait runs exactly that, so every benchmark stopped after its first
 * launch. The app is started from the instrumentation's own context instead, and its first window waited for.
 * [clearTask] false brings a running app back to the front as the launcher would.
 */
internal fun openTarget(device: androidx.test.uiautomator.UiDevice, clearTask: Boolean = true) {
    val context = InstrumentationRegistry.getInstrumentation().context
    TestKeepAlive.start(context)
    val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        .setClassName(TARGET, "com.musicglass.personal.MainActivity")
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
            if (clearTask) android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK else android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    context.startActivity(intent)
    if (device.wait(Until.hasObject(By.pkg(TARGET).depth(0)), 30_000)) return
    // Q18-074: once on the owner's phone a cold start with the profile never showed a window, though by hand the same
    // build opened 30 times in 30. The launch is sent once more before the run is called failed, and the failure
    // names what was in front.
    context.startActivity(intent)
    check(device.wait(Until.hasObject(By.pkg(TARGET).depth(0)), 30_000)) { "$TARGET did not open (in front: ${device.currentPackageName})" }
}

/** startActivityAndWait without the shell (Q18-023). */
internal fun MacrobenchmarkScope.launch() = openTarget(device)

/** Library, then the invented playlist the account owns, with its Play button ready. */
internal fun MacrobenchmarkScope.openPlaylist() {
    tap("tab_library")
    // Library keeps the screen it was left on: the playlist may already be open.
    val row = java.util.regex.Pattern.compile("catalog_playlist_VLPLfixtureOwned.*")
    val target = By.res(java.util.regex.Pattern.compile("playlist_detail|$row"))
    // Q18-073: on the API 33 emulator the first tap on a tab after the app's data was cleared was lost; once more.
    if (!device.wait(Until.hasObject(target), 5_000)) tap("tab_library")
    check(device.wait(Until.hasObject(target), WAIT_MS)) { "The fixture playlist is not in Library" }
    if (!device.hasObject(By.res("playlist_detail"))) device.findObject(By.res(row)).click()
    shown("playlist_detail")
    check(device.wait(Until.hasObject(By.res("album_play").enabled(true)), WAIT_MS)) { "The playlist did not finish loading" }
}

/** Play on the open playlist, and wait until the mini player says it is playing. */
internal fun MacrobenchmarkScope.playPlaylist() {
    tap("album_play")
    shown("mini_player")
    playing()
}

/** Library, then Downloads, once its heading counts [songs] finished downloads. */
internal fun MacrobenchmarkScope.openDownloads(songs: Int = PLAYLIST_SONGS, waitMs: Long = WAIT_MS) {
    tap("tab_library"); tap("library_downloads"); shown("downloads_detail")
    // Looked for inside the Downloads screen only: while it slides in, Library's tile is still there, and its
    // count includes downloads that have not finished.
    val heading = By.text(java.util.regex.Pattern.compile("$songs songs\\b.*"))
    val deadline = android.os.SystemClock.uptimeMillis() + waitMs
    while (device.findObject(By.res("downloads_detail"))?.hasObject(heading) != true) {
        check(android.os.SystemClock.uptimeMillis() < deadline) { "Downloads did not reach $songs songs" }
        android.os.SystemClock.sleep(250)
    }
    check(device.wait(Until.hasObject(By.res("downloads_play").enabled(true)), WAIT_MS)) { "Downloads cannot be played" }
}

/** The invented playlist kept on the device as complete bundles: asked for, then waited for in Downloads. */
internal fun MacrobenchmarkScope.downloadPlaylist() {
    openPlaylist()
    tap("collection_download_all")
    device.pressBack()
    openDownloads(waitMs = 300_000)
}

/** Play in Downloads, and wait until the mini player says it is playing. */
internal fun MacrobenchmarkScope.playDownloads() {
    tap("downloads_play")
    shown("mini_player")
    playing()
}

/** The transport button offers Pause, which it does only while the player reports that it is playing. */
internal fun MacrobenchmarkScope.playing() =
    check(device.wait(Until.hasObject(By.desc("Pause")), WAIT_MS)) { "Playback did not start" }

internal fun MacrobenchmarkScope.paused() =
    check(device.wait(Until.hasObject(By.desc("Play")), WAIT_MS)) { "Playback did not pause" }

internal fun MacrobenchmarkScope.openPlayer() = tapFor("mini_player", "playback_toggle")
internal fun MacrobenchmarkScope.closePlayer() { tap("player_close"); shown("navigation") }
internal fun MacrobenchmarkScope.openQueue() { reveal("player_queue_bottom"); tap("player_queue_bottom"); shown("queue_list") }

/**
 * Q18-074: the player scrolls when the window is short or the text large, and then its bottom row (Open queue) is
 * below the fold, where uiautomator cannot find it. The player is scrolled up, in its middle, until [tag] shows.
 */
internal fun MacrobenchmarkScope.reveal(tag: String) {
    repeat(4) {
        if (device.wait(Until.hasObject(By.res(tag)), 1_000)) return
        val x = device.displayWidth / 2
        device.swipe(x, device.displayHeight * 7 / 10, x, device.displayHeight * 3 / 10, 12)
        device.waitForIdle()
    }
}
internal fun MacrobenchmarkScope.closeQueue() { tap("queue_back"); gone("queue_list") }

/** One down and up pass over the scrolling content of the screen tagged [tag]. */
internal fun MacrobenchmarkScope.scrollScreen(tag: String = "scene") {
    shown(tag)
    // The middle of the screen: clear of the system gesture areas, the navigation bar and the mini player.
    val x = device.displayWidth / 2; val high = device.displayHeight * 3 / 10; val low = device.displayHeight * 7 / 10
    device.swipe(x, low, x, high, 12)
    device.waitForIdle()
    device.swipe(x, high, x, low, 12)
    device.waitForIdle()
}
