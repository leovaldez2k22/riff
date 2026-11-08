package com.musicglass.personal

import android.content.Context
import android.database.ContentObserver
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.media3.common.DeviceInfo
import androidx.media3.common.Player
import kotlin.math.abs

/**
 * Feature 6 (owner, Q6): two switches, both off by default. Resume after Bluetooth reconnects: when a Bluetooth
 * output going away paused playback, the same output coming back within [BT_RESUME_WINDOW_MS] plays again. Pause on
 * mute: playing music pauses when the media volume reaches zero; turning it up again never starts anything.
 *
 * Needs no permission: outputs are told apart by what AudioManager reports (its address is anonymised on recent
 * Android, but stays the same for one device), so BLUETOOTH_CONNECT is not asked for.
 */
internal const val BT_RESUME_KEY = "bluetooth_resume"
internal const val MUTE_PAUSE_KEY = "pause_on_mute"
internal const val BT_RESUME_WINDOW_MS = 10 * 60_000L
/** How far apart Android's "becoming noisy" pause and the output's removal may arrive and still be one event. */
internal const val BT_PAIR_MS = 3_000L
/** The wait after the output is back, so Android has moved media to it before sound starts. */
private const val BT_SETTLE_MS = 1_500L

/**
 * The resume decision, apart from Android. A pause is "armed" only when a Bluetooth output went away and the player
 * paused for becoming noisy at about the same time. Anything else that changes play or pause, a call, an account
 * switch or a new queue disarms it. Only the same output, back in time and outside a call, resumes, once.
 */
internal class RouteResume(private val windowMs: Long = BT_RESUME_WINDOW_MS) {
    private var gone: Pair<String, Long>? = null
    private var noisyAt: Long? = null
    private var armed: Pair<String, Long>? = null
    val waiting get() = armed != null

    fun routeGone(key: String, now: Long) { gone = key to now; pair() }
    fun noisyPause(now: Long) { noisyAt = now; pair() }
    private fun pair() {
        val g = gone ?: return; val n = noisyAt ?: return
        if (abs(g.second - n) <= BT_PAIR_MS) { armed = g.first to maxOf(g.second, n); gone = null; noisyAt = null }
        else if (g.second < n) gone = null else noisyAt = null
    }
    fun cancel() { armed = null; gone = null; noisyAt = null }

    /** Whether [key] coming back now should resume. Another output leaves the wait in place; a late one ends it. */
    fun routeBack(key: String, now: Long, inCall: Boolean): Boolean {
        val a = armed ?: return false
        if (now - a.second > windowMs || inCall) { cancel(); return false }
        if (a.first != key) return false
        cancel(); return true
    }
}

internal fun shouldPauseOnMute(enabled: Boolean, playing: Boolean, local: Boolean, volume: Int) = enabled && playing && local && volume <= 0

private val BLUETOOTH_OUTPUTS = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER)

/** One Bluetooth output, the same across its A2DP and call profiles; null for anything else. */
internal fun bluetoothRouteKey(type: Int, sink: Boolean, name: String, address: String): String? =
    if (sink && type in BLUETOOTH_OUTPUTS) "$address|$name" else null

private fun AudioDeviceInfo.routeKey() = bluetoothRouteKey(type, isSink, productName?.toString().orEmpty(), address.orEmpty())

private fun inCall(mode: Int) = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION ||
    mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_CALL_SCREENING

/** The Android side: listens only while the service runs; [release] stops every callback. */
internal class RouteWatch(context: Context, private val player: Player, private val events: PlaybackEventLog) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(AUDIO_BOOST_PREFS, Context.MODE_PRIVATE)
    private val audioManager = app.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val resume = RouteResume()
    private val settle = Runnable {
        if (player.playbackState != Player.STATE_IDLE && player.mediaItemCount > 0 && !player.playWhenReady) {
            events.append("bluetooth_resume"); player.play()
        }
    }
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            if (!prefs.getBoolean(BT_RESUME_KEY, false)) return
            removed.firstNotNullOfOrNull { it.routeKey() }?.let { resume.routeGone(it, SystemClock.elapsedRealtime()) }
        }
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            if (!resume.waiting || !prefs.getBoolean(BT_RESUME_KEY, false)) return
            val now = SystemClock.elapsedRealtime(); val call = inCall(audioManager.mode)
            if (added.mapNotNull { it.routeKey() }.distinct().any { resume.routeBack(it, now, call) }) {
                handler.removeCallbacks(settle); handler.postDelayed(settle, BT_SETTLE_MS)
            }
        }
    }
    private val modes = AudioManager.OnModeChangedListener { mode -> if (inCall(mode)) cancel() }
    private val volume = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            val local = player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_LOCAL
            if (shouldPauseOnMute(prefs.getBoolean(MUTE_PAUSE_KEY, false), player.isPlaying, local,
                    audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))) { events.append("mute_pause"); player.pause() }
        }
    }

    init {
        audioManager.registerAudioDeviceCallback(devices, handler)
        audioManager.addOnModeChangedListener(app.mainExecutor, modes)
        app.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volume)
    }

    /** From the player's listener: a pause for becoming noisy may be resumed; any other change ends the wait. */
    fun playWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) {
            if (prefs.getBoolean(BT_RESUME_KEY, false)) resume.noisyPause(SystemClock.elapsedRealtime())
        } else cancel()
    }
    fun cancel() { resume.cancel(); handler.removeCallbacks(settle) }

    fun release() {
        cancel()
        runCatching { audioManager.unregisterAudioDeviceCallback(devices) }
        runCatching { audioManager.removeOnModeChangedListener(modes) }
        runCatching { app.contentResolver.unregisterContentObserver(volume) }
    }
}
