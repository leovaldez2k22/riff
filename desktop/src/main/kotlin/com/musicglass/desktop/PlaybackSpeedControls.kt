package com.musicglass.desktop
import androidx.compose.runtime.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.window.*
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
internal fun speedLabel(value:Float)=java.lang.String.format(java.util.Locale.ROOT,"%.2f",value).trimEnd('0').trimEnd('.')+"×"
@Composable internal fun PlaybackSpeedControls(font:FontFamily){
    val host=LocalDesktopHost.current?:return;val state by host.state.collectAsState()
    val rate=effectivePlaybackSpeed(state)
    var draft by remember(state.currentEntryId){mutableFloatStateOf(rate)}
    var sent by remember(state.currentEntryId){mutableFloatStateOf(rate)}
    LaunchedEffect(rate){if(draft==sent)draft=rate}
    LaunchedEffect(draft,state.currentEntryId){delay(120);if(draft!=rate){sent=draft;host.dispatch(DesktopCommand.SetPlaybackSpeed(draft,state.currentEntryId))}}
    if(state.currentTrack.durationMs>14400000L){
        Label("Playback speed · 1×",font,Color.White,size=17)
        Label("This recording exceeds the four-hour pitch-preserving limit. It plays at 1×.",font,RiffDesign.muted,size=12)
        return
    }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Label("Playback speed · ${speedLabel(draft)}",font,Color.White,size=17)
        CanonicalEqAxis(draft,.5f,2f,false,"Playback speed",{(it*20).roundToInt()/20f}){draft=it}
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            listOf(.75f,1f,1.25f,1.5f,2f).forEach{speed->DataAction(speedLabel(speed),font){draft=speed;sent=speed;host.dispatch(DesktopCommand.SetPlaybackSpeed(speed,state.currentEntryId))}}
        }
        Label(if(state.currentTrack.isEpisode)"Episodes share their remembered speed."else"Music returns to 1× when Riff restarts.",font,RiffDesign.muted,size=12)
        Label("Pitch stays unchanged. Recordings longer than four hours use 1×.",font,RiffDesign.muted,size=12)
    }
}
@Composable internal fun PlaybackSpeedChip(font:FontFamily){
    val host=LocalDesktopHost.current?:return;val state by host.state.collectAsState();val rate=effectivePlaybackSpeed(state)
    if(rate==1f&&!state.currentTrack.isEpisode)return
    var open by remember{mutableStateOf(false)}
    Box{
        Box(Modifier.clickable{open=true}.padding(vertical=2.dp)){Label(speedLabel(rate),font,RiffDesign.pink,size=12)}
        if(open)Popup(popupPositionProvider=remember{object:PopupPositionProvider{
            override fun calculatePosition(anchorBounds:IntRect,windowSize:IntSize,layoutDirection:LayoutDirection,popupContentSize:IntSize):IntOffset=IntOffset(
                anchorBounds.left.coerceIn(8,(windowSize.width-popupContentSize.width-8).coerceAtLeast(8)),
                (anchorBounds.top-popupContentSize.height-8).coerceIn(8,(windowSize.height-popupContentSize.height-8).coerceAtLeast(8)))
        }},onDismissRequest={open=false},properties=PopupProperties(focusable=true)){
            Column(Modifier.widthIn(max=420.dp).clip(RoundedCornerShape(16.dp)).background(RiffDesign.ink).border(1.dp,Color.White.copy(alpha=.12f),RoundedCornerShape(16.dp)).onPreviewKeyEvent{if(it.type==KeyEventType.KeyDown&&it.key==Key.Escape){open=false;true}else false}.padding(20.dp)){
                PlaybackSpeedControls(font)
            }
        }
    }
}
