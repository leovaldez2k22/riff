package com.musicglass.personal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp

/** Feature 30: the window's width class, with the thresholds of Compose's adaptive library. */
internal enum class ShellWidth { Compact, Medium, Expanded }

internal fun shellWidth(widthDp: Float, heightDp: Float = Float.MAX_VALUE) = when {
    // A phone on its side is wide but short: a rail needs room for five tabs.
    heightDp < RiffShell.railLeastHeight.value -> ShellWidth.Compact
    widthDp >= 840f -> ShellWidth.Expanded
    widthDp >= 600f -> ShellWidth.Medium
    else -> ShellWidth.Compact
}

/** Where the screens start and end on a wide window: after the rail, and before the player pane when there is one. */
internal data class ShellInsets(val start: Dp, val end: Dp)

/** Feature 30: the tabs as a glass rail at the start of a wide window, in place of the bottom bar. */
@Composable
internal fun GlassRail(model: ShellModel, modifier: Modifier = Modifier) {
    val names = listOf("Home", "Podcasts", "Radio", "Library", "Search")
    Surface(shape = RoundedCornerShape(RiffShell.rail / 2), color = Color.Transparent,
        modifier = modifier.width(RiffShell.rail).testTag("navigation_rail").riffGlass(GlassRole.Bar)) {
        Column(Modifier.padding(vertical = RiffSpace.item), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(RiffSpace.tight)) {
            names.forEach { name ->
                NavItem(name, model.tab == name, true, Modifier.fillMaxWidth().height(RiffShell.railItem)) {
                    uiHaptic(HapticCue.Selection); model.selectFromTap(name, android.os.SystemClock.uptimeMillis())
                }
            }
        }
    }
}
