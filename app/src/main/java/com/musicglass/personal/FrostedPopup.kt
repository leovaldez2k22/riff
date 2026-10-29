package com.musicglass.personal

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
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/** Popup windows sample the app layer underneath them; their own text stays sharp. */
internal class FrostedBackdrop(val layer:GraphicsLayer) {
    var origin by mutableStateOf(Offset.Zero)
    var readers by mutableIntStateOf(0)
    /** The app's window, whose frames the popups follow (phone checks 29 Oct, bug 11). */
    var view:android.view.View?=null
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

/** Feature 2: [cornerRadius] is the popup's own corner, for the Menu lens's rim. */
@Composable internal fun Modifier.frostedPopupBackground(cornerRadius:Dp=RiffGlassPopup.corner):Modifier {
    val backdrop=LocalFrostedBackdrop.current
    val intensity=LocalFrostedIntensity.current
    val density=LocalDensity.current.density
    val blurred=rememberGraphicsLayer()
    var origin by remember {mutableStateOf(Offset.Zero)}
    val style=LocalGlassStyle.current
    val spec=remember(style) {glassSpec(GlassRole.Menu,style)}
    val piece=remember {GlassPiece()}
    // A popup is a window of its own: it draws only when it changes, not when the app under it does, so its glass kept
    // the page it opened over (phone checks 29 Oct, bug 11). Before each frame of the app's window it reads the page
    // again and asks its own window for a frame.
    val popupView=LocalView.current
    val read=remember {arrayOfNulls<()->Unit>(1)}
    DisposableEffect(backdrop,popupView) {
        backdrop?.let {it.readers++}
        val app=backdrop?.view?.takeIf {it!==popupView}
        val refresh=android.view.ViewTreeObserver.OnPreDrawListener {read[0]?.let {it(); popupView.invalidate()}; true}
        app?.viewTreeObserver?.addOnPreDrawListener(refresh)
        onDispose {
            backdrop?.let {it.readers--}
            app?.viewTreeObserver?.takeIf {it.isAlive}?.removeOnPreDrawListener(refresh)
        }
    }
    return onGloballyPositioned {origin=it.positionOnScreen()}.drawWithContent {
        if(intensity<0f) drawRect(Color(0xFF2A2B32)) else {
            if(backdrop!=null) {
                // The owner's frosted-glass blur, bent at the rim by the Menu lens.
                val blur=22f*density*intensity.coerceAtLeast(0f)
                val reach=glassReach(blur)
                val radius=cornerRadius.toPx().coerceAtMost(size.minDimension/2f)
                blurred.renderEffect=glassEffect(piece,spec,blur,reach,size.width,size.height,radius,density,1f)
                val area=IntSize(ceil(size.width+2*reach).toInt(),ceil(size.height+2*reach).toInt())
                val at=androidx.compose.ui.unit.Density(density,fontScale)
                read[0]={
                    val offset=backdrop.origin-origin
                    blurred.record(at,androidx.compose.ui.unit.LayoutDirection.Ltr,area) {translate(offset.x+reach,offset.y+reach) {drawLayer(backdrop.layer)}}
                }
                read[0]?.invoke()
                translate(-reach,-reach) {drawLayer(blurred)}
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
        dismissButton=dismissButton,modifier=modifier.clip(RoundedCornerShape(28.dp)).frostedPopupBackground(RiffRadius.pill),containerColor=Color.Transparent,
        tonalElevation=0.dp,shape=RoundedCornerShape(28.dp),titleContentColor=White,textContentColor=White)
}
