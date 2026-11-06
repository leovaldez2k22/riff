package com.musicglass.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.input.key.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

/** Live backdrop fixture. Controls sample only the scene, never a layer containing themselves. */
@Composable
internal fun GlassGallery(font: androidx.compose.ui.text.font.FontFamily, visible: Boolean,
    reduced: Boolean, onReducedMotion: (Boolean)->Unit) {
    val reference = remember { System.getProperty("riff.referenceGallery")=="true" }
    val backdrop = rememberRiffBackdrop()
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableFloatStateOf(if(reference) 2.5f else 0f) }
    var amount by remember { mutableFloatStateOf(.6f) }
    var held by remember { mutableStateOf(false) }
    val sliderBackdrop=rememberRiffBackdrop()
    var enabled by remember { mutableStateOf(true) }
    val timingOpener = remember { FocusRequester() }
    val timingCloser = remember { FocusRequester() }
    var timing by remember { mutableStateOf(false) }
    val ambient = if(reference) remember { mutableStateOf(ambientPalettes.first()) } else rememberAmbientPalette(MotionPolicy(reduced || !visible,1f))
    LaunchedEffect(timing) { if(timing) timingCloser.requestFocus() }
    var quality by remember { mutableStateOf(GlassQuality.Full) }
    LaunchedEffect(playing, visible) {
        if (playing && visible) {
            var before = withFrameNanos { it }
            while (true) withFrameNanos { now ->
                position = (position + (now-before)/1_000_000_000f) % 12f
                before = now
            }
        }
    }
    CompositionLocalProvider(LocalRiffBackdrop provides backdrop, LocalPageBackdrop provides backdrop,
        LocalMotionPolicy provides MotionPolicy(reduced || !visible,1f),
        LocalGlassStyle provides GlassStyle(intensity=1.25f, frosted=true, frostedIntensity=.25f, quality=quality)) {
        Box(Modifier.fillMaxSize().onPreviewKeyEvent {
            if(timing && it.type==KeyEventType.KeyDown && it.key==Key.Escape) {
                timing=false; timingOpener.requestFocus(); true
            } else false
        }) {
            Box(Modifier.fillMaxSize().riffBackdropSource(backdrop)) {
                AmbientBackdrop(ambient)
                LazyColumn(Modifier.fillMaxSize().padding(32.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
                    item { Label("Riff — live glass reference",font,size=34) }
                    item { Label("Scroll the scene beneath the fixed controls. Fixture clock; no audio.",font,RiffDesign.muted) }
                    item { ArtworkDeckFixture(Modifier.size(280.dp)) }
                items(18) { i ->
                    val active by remember(i) { derivedStateOf { (position/3).toInt() == i % 4 } }
                    Row(Modifier.fillMaxWidth().heightIn(min=128.dp)
                        .background(Color(if(i%2==0) 0xFF35294B else 0xFF103B40),RoundedCornerShape(14.dp))
                        .padding(20.dp), horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                        Box(Modifier.size(48.dp).background(if(active) RiffDesign.pink else RiffDesign.raised,RoundedCornerShape(12.dp)))
                        Column {
                            val text = listOf("Cinnamon Girl", "संगीत बहता है", "الموسيقى", "Instrumental")[i%4]
                            val line = remember(text) {
                                val words=Regex("\\S+").findAll(text).toList()
                                LyricLine(0,3000,text,words.mapIndexed { n, match ->
                                    LyricPart(n*3000L/words.size,(n+1)*3000L/words.size,match.range.first,match.range.last+1) })
                            }
                            KaraokeLine(line,active,false,{ ((position%3f)*1000).toLong() },
                                MotionPolicy(reduced,1f),"backing-$i")
                            Label("Inter Display / 29sp · fixture line ${i+1}",font,RiffDesign.muted)
                        }
                    }
                }
            }
            }
            Column(Modifier.align(Alignment.TopEnd).padding(20.dp).width(340.dp)
                .riffGlass(GlassRole.Card,24.dp).padding(20.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Label("Optical roles",font,size=22)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    GlassAction(if(playing) "Pause fixture" else "Play fixture",font) { playing=!playing }
                    GlassAction("Timing",font,modifier=Modifier.focusRequester(timingOpener)) { timing=!timing }
                }
                GlassAction(if(reduced) "Reduced motion: on" else "Reduced motion: off",font) { onReducedMotion(!reduced) }
                GlassAction("Mode: ${quality.name}",font) { quality=GlassQuality.entries[(quality.ordinal+1)%4] }
                Row(Modifier.heightIn(min=48.dp).riffFocusRing(14.dp).semantics { contentDescription = "Fixture switch" }.toggleable(value=enabled,role=Role.Switch,onValueChange={enabled=it}),
                    verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    GlassSwitch(enabled)
                    Box(Modifier.clearAndSetSemantics { }) { Label(if(enabled) "Switch on" else "Switch off",font) }
                }
                BoxWithConstraints(Modifier.fillMaxWidth().height(48.dp).riffFocusRing(28.dp).onKeyEvent {
                    if(it.type!=KeyEventType.KeyDown) false else when(it.key) {
                        Key.DirectionLeft,Key.DirectionDown -> { amount=(amount-.05f).coerceAtLeast(0f);true }
                        Key.DirectionRight,Key.DirectionUp -> { amount=(amount+.05f).coerceAtMost(1f);true }
                        Key.MoveHome -> { amount=0f;true }; Key.MoveEnd -> { amount=1f;true }; else -> false
                    }
                }.focusable().semantics {
                    contentDescription="Fixture level"
                    progressBarRangeInfo=ProgressBarRangeInfo(amount,0f..1f)
                    setProgress { amount=it.coerceIn(0f,1f); true }
                }.pointerInput(Unit) { detectHorizontalDragGestures(onDragStart={held=true},onDragEnd={held=false},onDragCancel={held=false}) { change, _ ->
                    change.consume(); amount=(change.position.x/size.width).coerceIn(0f,1f)
                } }) {
                    Box(Modifier.matchParentSize().riffBackdropSource(sliderBackdrop).riffGlass(GlassRole.Bar)) {
                        Box(Modifier.fillMaxWidth().align(Alignment.CenterStart).padding(horizontal=15.dp).height(6.dp)) {
                            Box(Modifier.fillMaxWidth(amount).fillMaxHeight().background(RiffDesign.pink))
                        }
                    }
                    Box(Modifier.offset(x=(maxWidth-30.dp).coerceAtLeast(0.dp)*amount).align(Alignment.CenterStart)) {
                        GlassThumb(held,sliderBackdrop)
                    }
                }
                GlassSegmented(GlassQuality.entries.map {it.name},quality.ordinal,{quality=GlassQuality.entries[it]},"quality")
                FixtureStatus(amount, { position }, font)
            }
            if(timing) Column(Modifier.align(Alignment.Center).width(290.dp).riffGlass(GlassRole.Menu,RiffMenu.corner).background(if(quality==GlassQuality.Solid) Color.Transparent else RiffMenu.tint.copy(alpha=RiffMenu.tintAlpha),RoundedCornerShape(RiffMenu.corner)).border(RiffMenu.rimWidth,if(quality==GlassQuality.Solid) Color.White else RiffMenu.rim,RoundedCornerShape(RiffMenu.corner))
                .semantics { paneTitle="Lyrics timing reference" }.padding(20.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Label("Lyrics timing",font,size=20)
                Label("Scene and fixture lyrics remain live beneath this panel.",font)
                GlassAction("Close timing",font,modifier=Modifier.focusRequester(timingCloser)) { timing=false; timingOpener.requestFocus() }
            }
            Box(Modifier.align(Alignment.BottomCenter).padding(20.dp).riffGlass(GlassRole.Bar).padding(20.dp)) {
                Label("Bar • same scene texture, blur, refraction, dispersion and rim",font)
            }
        }
    }
}

@Composable
private fun GlassAction(text:String,font:androidx.compose.ui.text.font.FontFamily,
    role:GlassRole=GlassRole.Button,modifier:Modifier=Modifier,action:()->Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(modifier.onFocusChanged { focused=it.isFocused }.border(1.dp,if(focused) RiffDesign.pink else Color.Transparent,RoundedCornerShape(28.dp)).heightIn(min=48.dp).riffGlass(role).clickable(role=Role.Button,onClickLabel=text,onClick=action)
        .padding(horizontal=12.dp),contentAlignment=Alignment.Center) { Label(text,font) }
}







@Composable
internal fun Modifier.riffFocusRing(radius: androidx.compose.ui.unit.Dp): Modifier {
    var focused by remember { mutableStateOf(false) }
    return onFocusChanged { focused=it.isFocused }.border(1.dp,
        if(focused) RiffDesign.pink else Color.Transparent,RoundedCornerShape(radius))
}





/** Keep the fixture clock read in its own recomposition scope. */
@Composable
private fun FixtureStatus(amount: Float, position: () -> Float, font: androidx.compose.ui.text.font.FontFamily) {
    Label("Level ${(amount * 100).toInt()}% · ${"%.2f".format(position())}s", font)
}
