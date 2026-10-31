package com.musicglass.personal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    // the page it opened over (phone checks 29 Oct, bug 11; the owner again on 31 Oct, the Lyrics timing dialog). Each
    // frame of the app's window moves [appFrame], which the drawing below reads, so the popup draws again with the page
    // as it is. The listener is on the app's window whatever LocalView says: inside a dialog LocalView is the app's own
    // view, so the earlier "same window, nothing to follow" check left every dialog without it (found on the phone).
    // Only the popup's window draws again; with nothing changing beneath, nothing does.
    var appFrame by remember {mutableIntStateOf(0)}
    DisposableEffect(backdrop) {
        backdrop?.let {it.readers++}
        val app=backdrop?.view
        val refresh=android.view.ViewTreeObserver.OnPreDrawListener {appFrame++; true}
        app?.viewTreeObserver?.addOnPreDrawListener(refresh)
        onDispose {
            backdrop?.let {it.readers--}
            app?.viewTreeObserver?.takeIf {it.isAlive}?.removeOnPreDrawListener(refresh)
        }
    }
    return onGloballyPositioned {origin=it.positionOnScreen()}.drawWithContent {
        appFrame // read so that the app's next frame draws this again
        if(intensity<0f) drawRect(Color(0xFF2A2B32)) else {
            if(backdrop!=null) {
                // The owner's frosted-glass blur, bent at the rim by the Menu lens.
                val blur=22f*density*intensity.coerceAtLeast(0f)
                val reach=glassReach(blur)
                val radius=cornerRadius.toPx().coerceAtMost(size.minDimension/2f)
                blurred.renderEffect=glassEffect(piece,spec,blur,reach,size.width,size.height,radius,density,1f)
                val area=IntSize(ceil(size.width+2*reach).toInt(),ceil(size.height+2*reach).toInt())
                val at=androidx.compose.ui.unit.Density(density,fontScale)
                val offset=backdrop.origin-origin
                blurred.record(at,androidx.compose.ui.unit.LayoutDirection.Ltr,area) {translate(offset.x+reach,offset.y+reach) {drawLayer(backdrop.layer)}}
                translate(-reach,-reach) {drawLayer(blurred)}
            } else drawRect(RiffMenu.tint.copy(alpha=.94f)) // Q18-046: nothing to blur, so the words beneath would show through.
            // A translucent tint keeps controls readable over bright covers, including at zero blur. Neutral grey, after
            // the owner's reference for every pop-out (31 Oct).
            drawRect(RiffMenu.tint.copy(alpha=RiffMenu.tintAlpha))
            drawRect(Brush.linearGradient(listOf(White.copy(alpha=.04f+.08f*intensity.coerceIn(0f,1.5f)),Color.Transparent)))
            val rim=RiffMenu.rimWidth.toPx()
            val corner=(cornerRadius.toPx()-rim/2).coerceAtLeast(0f)
            drawRoundRect(RiffMenu.rim,Offset(rim/2,rim/2),androidx.compose.ui.geometry.Size(size.width-rim,size.height-rim),CornerRadius(corner),style=Stroke(rim))
        }
        drawContent()
    }
}

@Composable internal fun FrostedAlertDialog(onDismissRequest:()->Unit,title:@Composable ()->Unit,
    text:@Composable ()->Unit,confirmButton:@Composable ()->Unit,dismissButton:(@Composable ()->Unit)?=null,
    modifier:Modifier=Modifier) {
    // The pop-out look (RiffMenu): the menus' panel and type, so a dialog and a menu read as one family.
    AlertDialog(onDismissRequest=onDismissRequest,title={ProvideTextStyle(RiffMenu.title,title)},
        text={ProvideTextStyle(RiffMenu.text.copy(fontSize=16.sp,lineHeight=22.sp),text)},confirmButton=confirmButton,
        dismissButton=dismissButton,modifier=modifier.clip(RoundedCornerShape(RiffMenu.corner)).frostedPopupBackground(RiffMenu.corner),containerColor=Color.Transparent,
        tonalElevation=0.dp,shape=RoundedCornerShape(RiffMenu.corner),titleContentColor=White,textContentColor=White.copy(alpha=.85f))
}

/**
 * A notice at the top of the screen ("Liked on YouTube Music"): one panel of Menu glass with its words and action on
 * it, and a dark tint in the same shape so it stays readable over a bright cover (phone checks 29 Oct, bug 10).
 */
@Composable internal fun GlassNotice(data:SnackbarData) {
    val shape=RoundedCornerShape(RiffRadius.card)
    Row(Modifier.padding(horizontal=RiffSpace.block).fillMaxWidth().riffGlass(GlassRole.Menu,RiffRadius.card)
        .clip(shape).background(Color(0xFF171C27).copy(alpha=.35f)).heightIn(min=RiffSpace.touch).padding(start=RiffSpace.block,end=RiffSpace.tight),
        verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
        Text(data.visuals.message,color=White,style=RiffText.body,modifier=Modifier.weight(1f).padding(vertical=RiffSpace.rowGap))
        data.visuals.actionLabel?.let {label->
            TextButton({data.performAction()}) {Text(label,color=Pink,style=RiffText.body)}
        } ?: Spacer(Modifier.width(RiffSpace.rowGap))
    }
}
