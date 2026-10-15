package com.musicglass.personal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Shuffle and repeat drawn as strokes with round ends and corners, so they match the rounded play, skip and rewind
 * icons beside them. Material's own versions have square line ends. Tinted like any other icon.
 */
internal object RoundedTransportIcons {
    /** Thick enough to sit beside the filled transport icons at 22 dp. */
    const val STROKE = 2.2f

    val Shuffle: ImageVector by lazy {
        icon("RiffShuffle") {
            // Bottom left to top right, with its arrowhead.
            moveTo(4f, 20f); lineTo(20f, 4f)
            moveTo(14f, 4f); lineTo(20f, 4f); lineTo(20f, 10f)
            // Top left to bottom right, broken where it passes under the other.
            moveTo(4f, 4f); lineTo(9.5f, 9.5f)
            moveTo(14.5f, 14.5f); lineTo(20f, 20f)
            moveTo(14f, 20f); lineTo(20f, 20f); lineTo(20f, 14f)
        }
    }

    val Repeat: ImageVector by lazy { icon("RiffRepeat") { loop() } }

    val RepeatOne: ImageVector by lazy {
        icon("RiffRepeatOne") {
            loop()
            moveTo(11f, 10.5f); lineTo(12.5f, 9.5f); lineTo(12.5f, 14.5f)
        }
    }

    /** Two rounded arms of a loop, each ending in an arrowhead. */
    private fun PathBuilder.loop() {
        moveTo(4f, 12f); lineTo(4f, 10f); quadTo(4f, 7f, 7f, 7f); lineTo(19f, 7f)
        moveTo(16f, 4f); lineTo(19f, 7f); lineTo(16f, 10f)
        moveTo(20f, 12f); lineTo(20f, 14f); quadTo(20f, 17f, 17f, 17f); lineTo(5f, 17f)
        moveTo(8f, 14f); lineTo(5f, 17f); lineTo(8f, 20f)
    }

    private fun icon(name: String, data: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .path(stroke = SolidColor(Color.White), strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = data)
            .build()
}
