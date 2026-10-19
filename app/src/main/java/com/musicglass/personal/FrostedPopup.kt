package com.musicglass.personal

import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Popup windows sample the app layer underneath them; their own text stays sharp. */
internal class FrostedBackdrop(val layer:GraphicsLayer) {
    var origin by mutableStateOf(Offset.Zero)
    var readers by mutableIntStateOf(0)
}
internal val LocalFrostedBackdrop = staticCompositionLocalOf<FrostedBackdrop?> {null}
internal val LocalFrostedIntensity = compositionLocalOf {1f}

internal fun Modifier.frostedBackdropSource(backdrop:FrostedBackdrop):Modifier =
    onGloballyPositioned {backdrop.origin=it.positionOnScreen()}.drawWithContent {
        if(backdrop.readers>0) {
            backdrop.layer.record {this@drawWithContent.drawContent()}
            drawLayer(backdrop.layer)
        } else drawContent()
    }

@Composable internal fun Modifier.frostedPopupBackground():Modifier {
    val backdrop=LocalFrostedBackdrop.current
    val intensity=LocalFrostedIntensity.current
    val density=LocalDensity.current.density
    val blurred=rememberGraphicsLayer()
    var origin by remember {mutableStateOf(Offset.Zero)}
    val effect=remember(intensity,density) {
        if(intensity>0f) RenderEffect.createBlurEffect(22f*density*intensity,22f*density*intensity,Shader.TileMode.CLAMP).asComposeRenderEffect() else null
    }
    DisposableEffect(backdrop) {
        backdrop?.let {it.readers++}
        onDispose {backdrop?.let {it.readers--}}
    }
    return onGloballyPositioned {origin=it.positionOnScreen()}.drawWithContent {
        if(intensity<0f) drawRect(Color(0xFF2A2B32)) else {
            if(backdrop!=null) {
                blurred.renderEffect=effect
                blurred.record {
                    val offset=backdrop.origin-origin
                    translate(offset.x,offset.y) {drawLayer(backdrop.layer)}
                }
                drawLayer(blurred)
            } else drawRect(Color(0xFF171C27).copy(alpha=.92f)) // Q18-046: nothing to blur, so the words beneath would show through.
            // A translucent tint keeps controls readable over bright covers, including at zero blur.
            drawRect(Color(0xFF171C27).copy(alpha=.64f))
            drawRect(Brush.linearGradient(listOf(White.copy(alpha=.06f+.10f*intensity.coerceIn(0f,1.5f)),Color.Transparent)))
        }
        drawContent()
    }
}

@Composable internal fun FrostedAlertDialog(onDismissRequest:()->Unit,title:@Composable ()->Unit,
    text:@Composable ()->Unit,confirmButton:@Composable ()->Unit,dismissButton:(@Composable ()->Unit)?=null,
    modifier:Modifier=Modifier) {
    AlertDialog(onDismissRequest=onDismissRequest,title=title,text=text,confirmButton=confirmButton,
        dismissButton=dismissButton,modifier=modifier.clip(RoundedCornerShape(28.dp)).frostedPopupBackground(),containerColor=Color.Transparent,
        tonalElevation=0.dp,shape=RoundedCornerShape(28.dp),titleContentColor=White,textContentColor=White)
}
