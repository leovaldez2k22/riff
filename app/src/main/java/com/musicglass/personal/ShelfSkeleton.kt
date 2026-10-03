package com.musicglass.personal

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Placeholder Home shelves shown while the first feed loads (W3), sized like HomeRowShelf and
 * HomeCardShelf so nothing jumps when content arrives. One shared pulse for the whole screen;
 * static under reduced motion. Announced once as "Loading Home"; the blocks themselves are hidden
 * from accessibility services.
 */
@Composable
internal fun HomeFeedSkeleton(modifier: Modifier = Modifier) {
    val reduced = LocalMotionPolicy.current.reduced
    val pulse: State<Float> = if (reduced) remember { mutableFloatStateOf(0.09f) } else
        rememberInfiniteTransition(label = "skeleton").animateFloat(0.06f, 0.12f,
            infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "skeleton-alpha")
    Column(modifier.fillMaxWidth().testTag("home_skeleton").clearAndSetSemantics {
        contentDescription = "Loading Home"; liveRegion = LiveRegionMode.Polite
    }, verticalArrangement = Arrangement.spacedBy(28.dp)) {
        // Quick picks: HomeRowShelf geometry (300 dp columns of 58 dp rows, 10 dp apart).
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SkeletonHeading(pulse)
            Row(Modifier.padding(start = 20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                repeat(2) {
                    Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        repeat(4) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SkeletonBlock(pulse, Modifier.size(58.dp), 9.dp)
                                Column(Modifier.padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    SkeletonBlock(pulse, Modifier.width(170.dp).height(12.dp), 6.dp)
                                    SkeletonBlock(pulse, Modifier.width(110.dp).height(10.dp), 5.dp)
                                }
                            }
                        }
                    }
                }
            }
        }
        // Two HomeCardShelf rows of 150 dp square cards, 12 dp apart.
        repeat(2) {
            Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                SkeletonHeading(pulse)
                Row(Modifier.padding(start = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(3) {
                        Column(Modifier.width(150.dp)) {
                            SkeletonBlock(pulse, Modifier.fillMaxWidth().aspectRatio(1f), 15.dp)
                            Spacer(Modifier.height(9.dp))
                            SkeletonBlock(pulse, Modifier.width(120.dp).height(12.dp), 6.dp)
                            Spacer(Modifier.height(6.dp))
                            SkeletonBlock(pulse, Modifier.width(80.dp).height(10.dp), 5.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SkeletonHeading(pulse: State<Float>) {
    Box(Modifier.padding(horizontal = 20.dp)) { SkeletonBlock(pulse, Modifier.width(180.dp).height(24.dp), 8.dp) }
}

/** Reads the pulse in the draw phase only, so the animation never recomposes the screen. */
@Composable
private fun SkeletonBlock(pulse: State<Float>, modifier: Modifier, corner: Dp) {
    Box(modifier.graphicsLayer { alpha = pulse.value / 0.12f }.background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(corner)))
}
