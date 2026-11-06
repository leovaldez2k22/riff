package com.musicglass.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.awt.Cursor

/**
 * Draggable, keyboard-adjustable pane divider with accessibility semantics and focus indicators.
 * Conforms to M2 desktop ergonomics requirements.
 */
@Composable
internal fun WorkspaceDivider(
    label: String,
    currentWidth: Dp,
    minWidth: Dp,
    maxWidth: Dp,
    defaultWidth: Dp,
    isLeftAnchor: Boolean, // True for sidebar (drag right expands), false for inspector (drag left expands)
    onWidthChange: (Dp) -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    var isHovered by remember { mutableStateOf(false) }

    val cursor = remember { PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)) }

    Box(
        modifier = modifier
            .width(8.dp)
            .fillMaxHeight()
            .pointerHoverIcon(cursor)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .semantics {
                role = Role.Button
                contentDescription = "$label divider. Current width: ${currentWidth.value.toInt()} dp. Use Left and Right arrow keys to adjust, Enter to reset to default."
                stateDescription = "${currentWidth.value.toInt()} dp"
            }
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    val step = if (keyEvent.isShiftPressed) 24.dp else 8.dp
                    when (keyEvent.key) {
                        Key.DirectionLeft -> {
                            val next = if (isLeftAnchor) currentWidth - step else currentWidth + step
                            onWidthChange(next.coerceIn(minWidth, maxWidth))
                            true
                        }
                        Key.DirectionRight -> {
                            val next = if (isLeftAnchor) currentWidth + step else currentWidth - step
                            onWidthChange(next.coerceIn(minWidth, maxWidth))
                            true
                        }
                        Key.Enter, Key.Spacebar -> {
                            onWidthChange(defaultWidth)
                            true
                        }
                        else -> false
                    }
                } else false
            }
            .pointerInput(currentWidth, minWidth, maxWidth, isLeftAnchor) {
                detectHorizontalDragGestures { change, dragAmount ->
                    change.consume()
                    val deltaDp = dragAmount.dp
                    val next = if (isLeftAnchor) currentWidth + deltaDp else currentWidth - deltaDp
                    onWidthChange(next.coerceIn(minWidth, maxWidth))
                }
            }
            .pointerInput(defaultWidth) {
                detectTapGestures(
                    onDoubleTap = {
                        onWidthChange(defaultWidth)
                    }
                )
            }
            .then(
                if (isFocused) Modifier.border(1.dp, RiffDesign.pink, RoundedCornerShape(2.dp))
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        // Subtle 1dp divider hairline
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(
                    when {
                        isFocused -> RiffDesign.pink
                        isHovered -> Color.White.copy(alpha = 0.35f)
                        else -> Color.White.copy(alpha = 0.08f)
                    }
                )
        )
    }
}
