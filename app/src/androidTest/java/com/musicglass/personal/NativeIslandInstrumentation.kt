package com.musicglass.personal

import android.app.Activity
import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Physical-phone experiment. Included only in androidTest; no production island feature. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class NativeIslandInstrumentation : Instrumentation() {
    private var mode = "baseline"
    private var seed = false
    private var holdSeconds = 35
    private var scene = "MUSIC"
    private var template = 1
    private var activity: Activity? = null
    private var controller: MediaController? = null
    private lateinit var output: File
    private lateinit var manager: NotificationManager
    private var originalSnapshot: String? = null
    private var originalVolume = 1f
    private val stages = JSONArray()
    private val report = JSONObject()
    private val channel = "riff_native_island_probe"
    private val notificationId = 9301
    private var checkpointIndex = 0

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        mode = arguments?.getString("mode") ?: "baseline"
        seed = arguments?.getString("seed") == "true"
        holdSeconds = arguments?.getString("holdSeconds")?.toIntOrNull() ?: 35
        scene = arguments?.getString("scene") ?: "MUSIC"
        template = arguments?.getString("template")?.toIntOrNull() ?: 1
        start()
    }

    // Riff rc16 closes its lyrics HTTP pool during Activity disposal. A live TLS socket can
    // trigger NetworkOnMainThreadException. Permit that existing close only during test teardown;
    // restore StrictMode immediately afterward. This is not a production fix.
    override fun callActivityOnDestroy(activity: Activity) {
        val previous = android.os.StrictMode.getThreadPolicy()
        android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.Builder(previous).permitNetwork().build())
        try { super.callActivityOnDestroy(activity) }
        finally { android.os.StrictMode.setThreadPolicy(previous) }
    }

    private fun main(block: () -> Unit) = runOnMainSync(block)
    private fun hash(text: String?) = text?.let {
        MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) }
    }
    private fun waitFor(label: String, timeoutMs: Long = 35000, predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < until) {
            var success = false
            main { success = predicate() }
            if (success) return
            Thread.sleep(150)
        }
        error("Timed out: $label")
    }

    private val portedToOwnerQueues = false

    override fun onStart() {
        val finishResult = Bundle()
        output = File(targetContext.getExternalFilesDir(null), "native-island-prototype/$mode").apply { mkdirs() }
        manager = targetContext.getSystemService(NotificationManager::class.java)
        // Q04 moved saved queues out of the shared preference this prototype backs up and rewrites. Until it is
        // ported, refuse to run rather than leave a queue there for the upgrade step to set aside.
        if (!portedToOwnerQueues) {
            finish(Activity.RESULT_CANCELED, finishResult.apply { putString("error", "Not ported to per-owner saved queues (Q04)") })
            return
        }
        val prefs = targetContext.getSharedPreferences("last_playback", Context.MODE_PRIVATE)
        val backup = File(targetContext.filesDir, "native-island-original-playback.json")
        if (mode == "verify") {
            val current = prefs.getString("snapshot", null)
            report.put("package", targetContext.packageName).put("snapshotSha256", hash(current))
                .put("queueCount", current?.let(::decodePlaybackSnapshot)?.queue?.size ?: 0)
                .put("backupPending", backup.exists()).put("notificationsEnabled", manager.areNotificationsEnabled())
            File(output, "report.json").writeText(report.toString(2))
            finish(Activity.RESULT_OK, Bundle().apply { putString("reportPath", File(output, "report.json").absolutePath) })
            return
        }
        try {
            if (seed) {
                check(targetContext.packageName != "com.musicglass.personal") { "Refusing to seed the user's real app" }
                val fixture = context.assets.open("native-island-seed.json").bufferedReader().use { it.readText() }
                check(decodePlaybackSnapshot(fixture) != null)
                check(prefs.edit().putString("snapshot", fixture).commit())
            }
            if (backup.exists()) {
                val saved = JSONObject(backup.readText())
                originalSnapshot = saved.optString("snapshot").takeIf { !saved.isNull("snapshot") }
                prefs.edit().apply { if (originalSnapshot == null) remove("snapshot") else putString("snapshot", originalSnapshot) }.commit()
            } else {
                originalSnapshot = prefs.getString("snapshot", null)
                backup.writeText(JSONObject().put("snapshot", originalSnapshot ?: JSONObject.NULL).toString())
            }
            val recovery = File(output.parentFile, "playback-backup.json")
            if (!recovery.exists()) recovery.writeText(JSONObject().put("snapshot", originalSnapshot ?: JSONObject.NULL).toString())
            report.put("package", targetContext.packageName).put("seededFixture", seed)
            report.put("mode", mode).put("model", android.os.Build.MODEL)
                .put("sdk", android.os.Build.VERSION.SDK_INT)
                .put("notificationsEnabled", manager.areNotificationsEnabled())
                .put("originalSnapshotSha256", hash(originalSnapshot))
                .put("originalQueueCount", originalSnapshot?.let(::decodePlaybackSnapshot)?.queue?.size ?: 0)
            if (mode == "restore" || mode == "export") {
                if (mode == "export") File(output, "snapshot.json").writeText(originalSnapshot ?: "null")
                report.put("restoreOnly", true)
            } else {
                val info = uiAutomation.serviceInfo
                info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                uiAutomation.serviceInfo = info
                activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                var future: ListenableFuture<MediaController>? = null
                main {
                    future = MediaController.Builder(targetContext,
                        SessionToken(targetContext, ComponentName(targetContext, MusicPlaybackService::class.java))).buildAsync()
                }
                controller = future!!.get(20, TimeUnit.SECONDS)
                main {
                    originalVolume = controller!!.volume
                    controller!!.volume = 0f
                    check(controller!!.mediaItemCount > 0) { "No real saved queue; refusing to invent test audio" }
                    controller!!.prepare()
                    controller!!.play()
                }
                waitFor("real playback ready", 60000) { controller!!.isPlaying && controller!!.playbackState == Player.STATE_READY }
                checkpoint("real_playback_in_riff")
                home()
                checkpoint("default_media_background")
                val activeMedia = manager.activeNotifications.firstOrNull { it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) }
                report.put("baselineMediaNotificationPresent", activeMedia != null)
                report.put("baselineActions", JSONArray(activeMedia?.notification?.actions?.map { it.title.toString() } ?: emptyList<String>()))
                check(activeMedia != null) { "MediaSession notification missing" }
                val platformToken = activeMedia.notification.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, android.media.session.MediaSession.Token::class.java)
                report.put("mediaTokenPresent", platformToken != null)
                check(platformToken != null)
                if (mode == "baseline") {
                    transportAndLifecycle()
                } else if (mode == "matrix") {
                    val variants = listOf(
                        Triple("media_music_rhythm", "MUSIC", 1),
                        Triple("media_music_text", "MUSIC", 4),
                        Triple("media_music_capsule", "MUSIC", 6),
                        Triple("media_train_control_rhythm", "TRAIN", 1),
                        Triple("media_train_control_progress", "TRAIN", 2),
                        Triple("media_train_control_text", "TRAIN", 4)
                    )
                    for ((name, testScene, testTemplate) in variants) {
                        post(testScene, testTemplate, mediaStyle = true, register = false)
                        checkpoint(name, 2500)
                        cancelProbe()
                    }
                    post("MUSIC", 1, mediaStyle = true, register = true)
                    checkpoint("media_music_registered", 2500)
                    cancelProbe()
                    post("TRAIN", 1, mediaStyle = false, register = true)
                    checkpoint("generic_train_control_registered", 2500)
                    cancelProbe()
                    post("MUSIC", 1, mediaStyle = false, register = true)
                    checkpoint("generic_music_registered", 2500)
                    cancelProbe()
                    post("MUSIC", 2, mediaStyle = false, register = false)
                    checkpoint("generic_music_progress", 2500)
                    cancelProbe()
                } else if (mode == "metadata") {
                    main {
                        val c = controller!!
                        val item = c.currentMediaItem!!
                        val typed = item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon()
                            .setMediaType(androidx.media3.common.MediaMetadata.MEDIA_TYPE_MUSIC)
                            .setIsPlayable(true).setIsBrowsable(false).build()).build()
                        c.replaceMediaItem(c.currentMediaItemIndex, typed)
                    }
                    waitFor("typed music playback") { controller!!.isPlaying }
                    checkpoint("explicit_music_metadata", 6000)
                    post("MUSIC", 1, mediaStyle = true, register = true)
                    checkpoint("typed_music_payload", 6000)
                    transportAndLifecycle()
                } else if (mode == "hold") {
                    post(scene, template, mediaStyle = true, register = true)
                    checkpoint("held_${scene.lowercase()}_$template")
                    sendStatus(7, Bundle().apply { putString("stage", "hold"); putInt("seconds", holdSeconds) })
                    val until = SystemClock.elapsedRealtime() + holdSeconds * 1000L
                    while (SystemClock.elapsedRealtime() < until) {
                        post(scene, template, mediaStyle = true, register = false)
                        Thread.sleep(1000)
                    }
                    checkpoint("held_end")
                    transportAndLifecycle()
                }
            }
            report.put("completed", true)
            finishResult.putString("result", "completed")
        } catch (error: Throwable) {
            report.put("completed", false).put("failure", error.toString()).put("stack", error.stackTraceToString())
            finishResult.putString("error", error.toString())
        } finally {
            runCatching { cancelProbe() }
            runCatching { manager.deleteNotificationChannel(channel) }
            runCatching {
                main {
                    controller?.let { it.pause(); it.volume = originalVolume; it.stop(); it.clearMediaItems(); it.release() }
                    controller = null
                    // Keep the activity backgrounded; Instrumentation.finish terminates the process without blocking HTTP-pool Activity disposal.
                    activity = null
                    targetContext.stopService(Intent(targetContext, MusicPlaybackService::class.java))
                }
                Thread.sleep(1800)
                val restored = prefs.edit().apply {
                    if (originalSnapshot == null) remove("snapshot") else putString("snapshot", originalSnapshot)
                }.commit()
                check(restored && prefs.getString("snapshot", null) == originalSnapshot)
                report.put("restoredSnapshotSha256", hash(prefs.getString("snapshot", null)))
                report.put("queueRestoredExactly", true)
                backup.delete()
            }.onFailure { report.put("restoreFailure", it.toString()) }
            report.put("stages", stages)
            File(output, "report.json").writeText(report.toString(2))
            finishResult.putString("reportPath", File(output, "report.json").absolutePath)
        }
        finish(if (report.optBoolean("completed") && report.optBoolean("queueRestoredExactly")) Activity.RESULT_OK else Activity.RESULT_CANCELED, finishResult)
    }

    private fun home() {
        uiAutomation.injectInputEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME), true)
        uiAutomation.injectInputEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME), true)
        Thread.sleep(1200)
    }

    private fun transportAndLifecycle() {
        val c = controller!!
        var startPosition = 0L
        main { startPosition = c.currentPosition }
        Thread.sleep(2500)
        var nextPosition = 0L
        main { nextPosition = c.currentPosition; check(c.isPlaying) }
        report.put("backgroundPositionAdvancedMs", nextPosition - startPosition)
        check(nextPosition > startPosition + 1000) { "Background audio position did not advance" }
        val toggle = control("toggle")
        toggle.send()
        waitFor("pause action") { !c.isPlaying }
        checkpoint("paused_action")
        toggle.send()
        waitFor("resume action") { c.isPlaying }
        checkpoint("resumed_action")
        var previousId = ""
        var canNext = false
        main { previousId = c.currentMediaItem?.mediaId.orEmpty(); canNext = c.hasNextMediaItem() }
        if (canNext) {
            control("next").send()
            waitFor("next metadata and playback", 60000) { c.isPlaying && c.currentMediaItem?.mediaId != previousId }
            checkpoint("next_action")
            control("previous").send()
            waitFor("previous metadata and playback", 60000) { c.isPlaying && c.currentMediaItem?.mediaId == previousId }
            checkpoint("previous_action")
            report.put("nextPreviousActionsPassed", true)
        } else report.put("nextPreviousActionsPassed", JSONObject.NULL)
        main { c.pause() }
        checkpoint("paused_background_15s", 15000)
        main { c.play() }
        waitFor("resume after idle pause") { c.isPlaying }
        checkpoint("resumed_after_idle_pause")
        main { c.pause() }
        cancelProbe()
        checkpoint("probe_cancelled")
        report.put("probeNotificationRemoved", manager.activeNotifications.none { it.id == notificationId })
    }

    private fun control(command: String): PendingIntent = PendingIntent.getForegroundService(
        targetContext, 9300 + listOf("toggle", "previous", "next").indexOf(command),
        Intent(targetContext, MusicPlaybackService::class.java).setAction(WIDGET_CONTROL)
            .putExtra("widgetToken", RiffWidgets.token(targetContext)).putExtra("command", command),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun register(sceneName: String): String = try {
        val method = NotificationManager::class.java.getMethod("setSuperXInfosSceneList",
            MutableList::class.java, MutableList::class.java, MutableList::class.java, MutableList::class.java)
        method.invoke(manager, arrayListOf(sceneName), arrayListOf("true"), arrayListOf(targetContext.packageName), arrayListOf("true"))
        "invoked"
    } catch (error: Throwable) { "${error.javaClass.simpleName}: ${error.cause?.message ?: error.message}" }

    private fun post(sceneName: String, templateId: Int, mediaStyle: Boolean, register: Boolean, promoted: Boolean = false) {
        manager.createNotificationChannel(NotificationChannel(channel, "Riff native island prototype", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false)
        })
        if (register) report.put("sceneRegistration_$sceneName", register(sceneName))
        var title = ""
        var artist = ""
        var playing = false
        var progress = 0
        main {
            val c = controller!!
            title = c.mediaMetadata.title?.toString().orEmpty()
            artist = c.mediaMetadata.artist?.toString().orEmpty()
            playing = c.isPlaying
            progress = if (c.duration > 0) ((c.currentPosition * 100) / c.duration).toInt().coerceIn(1, 99) else 1
        }
        val icon = Icon.createWithResource(targetContext, R.mipmap.ic_launcher)
        val payload = OriginIslandProbePayload.buildExtras(OriginIslandProbePayload.Request(
            9301, title, artist, subText = if (playing) "Playing" else "Paused",
            template = OriginIslandProbePayload.OriginIslandTemplate.fromId(templateId),
            progress = progress, leftText = title, rightText = if (playing) artist else "Paused"
        ), icon).apply {
            putString("notification.superx.scene", sceneName)
            if (promoted) putBoolean("android.requestPromotedOngoing", true)
        }
        val builder = Notification.Builder(targetContext, channel)
            .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(title).setContentText(artist)
            .setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(PendingIntent.getActivity(targetContext, 9301,
                Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .addAction(Notification.Action.Builder(Icon.createWithResource(targetContext, android.R.drawable.ic_media_previous), "Previous", control("previous")).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(targetContext, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play), if (playing) "Pause" else "Play", control("toggle")).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(targetContext, android.R.drawable.ic_media_next), "Next", control("next")).build())
            .addExtras(payload)
        if (mediaStyle) {
            val token = manager.activeNotifications.firstOrNull { it.id != notificationId && it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) }
                ?.notification?.extras?.getParcelable(Notification.EXTRA_MEDIA_SESSION, android.media.session.MediaSession.Token::class.java)
            check(token != null) { "Cannot attach prototype to Riff's real media session" }
            builder.setStyle(Notification.MediaStyle().setMediaSession(token).setShowActionsInCompactView(0, 1, 2))
        }
                manager.activeNotifications.firstOrNull { it.id != notificationId && it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) }
            ?.notification?.getLargeIcon()?.let { builder.setLargeIcon(it) }
        manager.notify(notificationId, builder.build())
    }

    private fun cancelProbe() {
        if (manager.activeNotifications.any { it.id == notificationId }) {
            manager.notify(notificationId, Notification.Builder(targetContext, channel)
                .setSmallIcon(android.R.drawable.ic_media_play).addExtras(OriginIslandProbePayload.buildEndExtras()).build())
        }
        manager.cancel(notificationId)
    }

    private fun checkpoint(name: String, settleMs: Long = 1500) {
        Thread.sleep(settleMs)
        val prefix = "%02d-%s".format(++checkpointIndex, name)
        uiAutomation.takeScreenshot()?.let { bitmap ->
            File(output, "$prefix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val stage = JSONObject().put("name", name).put("timeElapsedMs", SystemClock.elapsedRealtime())
        main {
            controller?.let { c ->
                stage.put("playing", c.isPlaying).put("state", c.playbackState).put("title", c.mediaMetadata.title)
                    .put("artist", c.mediaMetadata.artist).put("mediaId", c.currentMediaItem?.mediaId)
                    .put("positionMs", c.currentPosition).put("durationMs", c.duration).put("queueCount", c.mediaItemCount)
            }
        }
        stage.put("notifications", JSONArray(manager.activeNotifications.map { n ->
            JSONObject().put("id", n.id).put("title", n.notification.extras.getCharSequence(Notification.EXTRA_TITLE))
                .put("template", n.notification.extras.getInt("notification.superx.template", -1))
                .put("scene", n.notification.extras.getString("notification.superx.scene"))
                .put("flags", n.notification.flags).put("mediaToken", n.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION))
                .put("actions", JSONArray(n.notification.actions?.map { it.title.toString() } ?: emptyList<String>()))
        }))
        val nodes = JSONArray()
        fun visit(node: android.view.accessibility.AccessibilityNodeInfo, window: String, depth: Int = 0) {
            if (depth > 35) return
            val rect = android.graphics.Rect(); node.getBoundsInScreen(rect)
            if (node.text != null || node.contentDescription != null || node.isClickable) nodes.put(JSONObject()
                .put("window", window).put("text", node.text).put("description", node.contentDescription)
                .put("clickable", node.isClickable).put("bounds", rect.flattenToString())
                .put("viewId", node.viewIdResourceName).put("class", node.className))
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, window, depth + 1) }
        }
        uiAutomation.windows.forEach { w -> w.root?.let { visit(it, "type=${w.type},id=${w.id}") } }
        File(output, "$prefix-ui.json").writeText(nodes.toString(2))
        stages.put(stage)
        File(output, "report-live.json").writeText(report.put("stages", stages).toString(2))
        sendStatus(7, Bundle().apply { putString("stage", name); putString("image", "$prefix.png") })
    }
}
