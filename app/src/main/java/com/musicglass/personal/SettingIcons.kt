package com.musicglass.personal

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.BlurOff
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DataSaverOff
import androidx.compose.material.icons.rounded.DataSaverOn
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.HistoryToggleOff
import androidx.compose.material.icons.rounded.HorizontalRule
import androidx.compose.material.icons.rounded.MobiledataOff
import androidx.compose.material.icons.rounded.MotionPhotosOff
import androidx.compose.material.icons.rounded.MotionPhotosOn
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.ShuffleOn
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SlowMotionVideo
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.SubtitlesOff
import androidx.compose.material.icons.rounded.SyncDisabled
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.UpdateDisabled
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector

/*
 * On and off icons (owner, 30 Oct): a switch shows one icon while off and another while on, and turning it the one
 * gives way to the other. A switch is given its on icon; the off icon is looked up here, so a switch with no pair
 * (the equalizer's, which the owner asked to stay as it looks) keeps the one icon.
 */

/** The icon a switch shows while off, for a switch whose icon while on is [on]; null when it has no pair. */
internal fun offIconFor(on: ImageVector): ImageVector? = when (on) {
    Icons.Rounded.ShuffleOn -> Icons.Rounded.Shuffle
    Icons.AutoMirrored.Rounded.VolumeUp -> Icons.AutoMirrored.Rounded.VolumeDown
    Icons.Rounded.DataSaverOn -> Icons.Rounded.DataSaverOff
    Icons.Rounded.Vibration -> Icons.Rounded.PhoneAndroid
    // Not the equalizer's own icon: the Equalizer tile sits right above Normalize volume.
    Icons.Rounded.Compress -> Icons.Rounded.GraphicEq
    Icons.Rounded.SkipNext -> Icons.Rounded.PlayArrow
    Icons.Rounded.ContentCut -> Icons.Rounded.HorizontalRule
    Icons.Rounded.BlurOn -> Icons.Rounded.BlurOff
    Icons.Rounded.SlowMotionVideo -> Icons.Rounded.PlayCircle
    Icons.Rounded.MotionPhotosOff -> Icons.Rounded.MotionPhotosOn
    Icons.Rounded.Contrast -> Icons.Rounded.Opacity
    Icons.Rounded.Analytics -> Icons.Rounded.ShowChart
    Icons.Rounded.Subtitles -> Icons.Rounded.SubtitlesOff
    Icons.Rounded.Update -> Icons.Rounded.UpdateDisabled
    Icons.Rounded.History -> Icons.Rounded.HistoryToggleOff
    Icons.Rounded.Search -> Icons.Rounded.SearchOff
    Icons.Rounded.DataUsage -> Icons.Rounded.MobiledataOff
    Icons.Rounded.Backup -> Icons.Rounded.SyncDisabled
    else -> null
}

/**
 * A switch's icon: [icon] while [on], its off icon ([offIconFor]) while off. Turning, the one shrinks and turns away as
 * the other springs in past full size and settles; with reduced motion they only cross-fade.
 */
@Composable internal fun SwitchIcon(icon: ImageVector, on: Boolean, tint: Color, modifier: Modifier) {
    val off = offIconFor(icon)
    if (off == null) { Icon(icon, null, tint = tint, modifier = modifier); return }
    val motion = LocalMotionPolicy.current
    val shown = remember { Animatable(if (on) 1f else 0f) }
    LaunchedEffect(on) {
        shown.animateTo(if (on) 1f else 0f, if (motion.reduced) GlassMotion.timed(GlassMotion.selectionMs, motion) else GlassMotion.pop(motion))
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        Icon(off, null, tint = tint, modifier = Modifier.matchParentSize().graphicsLayer {
            val p = shown.value
            alpha = (1f - p).coerceIn(0f, 1f)
            if (!motion.reduced) { val s = (1f - SWAP_SHRINK * p).coerceAtLeast(SWAP_SMALLEST); scaleX = s; scaleY = s; rotationZ = -SWAP_TURN * p }
        })
        Icon(icon, null, tint = tint, modifier = Modifier.matchParentSize().graphicsLayer {
            val p = shown.value
            alpha = p.coerceIn(0f, 1f)
            if (!motion.reduced) { val s = (1f - SWAP_SHRINK * (1f - p)).coerceAtLeast(SWAP_SMALLEST); scaleX = s; scaleY = s; rotationZ = SWAP_TURN * (1f - p) }
        })
    }
}

private const val SWAP_SHRINK = .5f
private const val SWAP_SMALLEST = .3f
private const val SWAP_TURN = 40f
