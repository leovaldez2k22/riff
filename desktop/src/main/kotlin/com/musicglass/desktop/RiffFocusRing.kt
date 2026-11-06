package com.musicglass.desktop
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color

@Composable
internal fun Modifier.riffFocusRing(radius: androidx.compose.ui.unit.Dp): Modifier {
    var focused by remember { mutableStateOf(false) }
    return onFocusChanged { focused=it.isFocused }.border(1.dp,
        if(focused) RiffDesign.pink else Color.Transparent,RoundedCornerShape(radius))
}
