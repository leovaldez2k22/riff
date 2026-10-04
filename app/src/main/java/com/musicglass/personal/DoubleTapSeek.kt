package com.musicglass.personal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

internal const val SEEK_NUDGE_MS = 10_000L

/** Where a double tap lands: back or forward by [stepMs], kept inside the song (a forward nudge never skips it). */
internal fun seekNudgeTarget(positionMs: Long, durationMs: Long, forward: Boolean, stepMs: Long = SEEK_NUDGE_MS): Long {
    val target = positionMs + if (forward) stepMs else -stepMs
    val end = if (durationMs > 0) (durationMs - 1_000).coerceAtLeast(0) else Long.MAX_VALUE
    return target.coerceIn(0, maxOf(end, if (forward) positionMs else 0))
}

/** Repeated double taps on the same side within [windowMs] add up, like a video player. */
internal fun seekNudgeTotal(previousSeconds: Int, sameSide: Boolean, sinceLastMs: Long, windowMs: Long = 1_000) =
    if (sameSide && sinceLastMs in 0..windowMs) previousSeconds + (SEEK_NUDGE_MS / 1000).toInt() else (SEEK_NUDGE_MS / 1000).toInt()

internal class SeekNudge {
    var forward by mutableStateOf(true); private set
    var seconds by mutableIntStateOf(0); private set
    var stamp by mutableLongStateOf(0L); private set
    fun show(forward: Boolean, now: Long) {
        seconds = seekNudgeTotal(seconds, forward == this.forward, now - stamp)
        this.forward = forward; stamp = now
    }
}

/** Double tap the left half to go back 10 s, the right half to go forward 10 s. */
internal fun Modifier.doubleTapSeek(enabled: Boolean, nudge: SeekNudge, playback: PlaybackGateway, durationMs: Long) =
    if (!enabled) this else pointerInput(durationMs) {
        detectTapGestures(onDoubleTap = { offset ->
            val forward = offset.x > size.width / 2f
            playback.seekTo(seekNudgeTarget(playback.currentPositionMs(), durationMs, forward))
            nudge.show(forward, System.currentTimeMillis())
            uiHaptic(HapticCue.Click)
        })
    }

@Composable internal fun SeekNudgeOverlay(nudge: SeekNudge, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(nudge.stamp) { if (nudge.stamp > 0) { visible = true; delay(700); visible = false } }
    Box(modifier.fillMaxSize().padding(horizontal = 28.dp)) {
        AnimatedVisibility(visible, enter = fadeIn() + scaleIn(initialScale = .85f), exit = fadeOut(),
            modifier = Modifier.align(if (nudge.forward) Alignment.CenterEnd else Alignment.CenterStart)) {
            Column(Modifier.size(92.dp).clip(CircleShape).background(Color.Black.copy(alpha = .42f)).testTag("seek_nudge"),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(if (nudge.forward) Icons.Rounded.FastForward else Icons.Rounded.FastRewind, null, tint = White,
                    modifier = Modifier.size(30.dp))
                Text("${if (nudge.forward) "+" else "−"}${nudge.seconds}s", color = White, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
