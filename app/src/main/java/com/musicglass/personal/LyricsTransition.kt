package com.musicglass.personal

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

internal val LocalLyricsSharedScope = compositionLocalOf<SharedTransitionScope?> { null }
internal val LocalLyricsVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

@Composable internal fun Modifier.lyricsSharedBounds(key: String): Modifier {
    val scope=LocalLyricsSharedScope.current ?: return this
    val visibility=LocalLyricsVisibilityScope.current ?: return this
    val motion=LocalMotionPolicy.current
    if(motion.reduced) return this
    return with(scope) { this@lyricsSharedBounds.sharedBounds(
        sharedContentState=rememberSharedContentState(key),animatedVisibilityScope=visibility,
        boundsTransform={_,_->GlassMotion.timed(GlassMotion.playerMs,motion)},
        enter=fadeIn(GlassMotion.timed(GlassMotion.playerMs,motion)),
        exit=fadeOut(GlassMotion.exit(GlassMotion.playerMs,motion))) }
}
