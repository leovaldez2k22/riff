package com.musicglass.personal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
internal val LocalShelfInset=compositionLocalOf {0.dp}
internal fun Modifier.fullBleed(inset:Dp)=layout {measurable,constraints->
    val edge=inset.roundToPx();val width=constraints.maxWidth
    val child=measurable.measure(constraints.copy(minWidth=width+edge*2,maxWidth=width+edge*2))
    layout(width,child.height) {child.placeRelative(-edge,0)}
}
