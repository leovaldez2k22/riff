package com.musicglass.personal

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/** A stable hit target with restrained feedback on the drawn content. */
internal fun Modifier.motionClickable(onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val motion = LocalMotionPolicy.current
    val scale = animateFloatAsState(if (pressed && !motion.reduced) .985f else 1f,
        GlassMotion.timed(GlassMotion.pressMs, motion), label = "surface press")
    this.clickable(interactionSource = source, indication = null, onClick = hapticClick(onClick))
        .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}
