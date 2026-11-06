package com.musicglass.desktop
import androidx.compose.runtime.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Canonical seven-fader / tone-pad controls using existing Riff surfaces, typography and glass segments. */
@Composable internal fun CanonicalEqualizerPanel(font:FontFamily){
    val host=LocalDesktopHost.current?:return;val state by host.state.collectAsState();val config=state.equalizerConfig
    fun update(value:EqualizerConfig){if(value!=config)host.dispatch(DesktopCommand.SetEqualizer(value))}
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
            Label("Equalizer",font,Color.White,size=17)
            Box(Modifier.semantics{role=Role.Switch;stateDescription=if(config.enabled)"On"else"Off"}.clickable{update(config.copy(enabled=!config.enabled))}){GlassSwitch(checked=config.enabled)}
        }
        Label("Shapes the sound inside Riff, before it reaches the system",font,RiffDesign.muted,size=13)
        GlassSegmented(listOf("Dynamic","Manual"),config.mode.ordinal,{update(config.copy(mode=EqMode.entries[it]))},"equalizer-mode")
        if(config.mode==EqMode.Dynamic){
            Label("TONE",font,RiffDesign.muted,size=12)
            CanonicalTonePad(config.toneX,config.toneY){x,y->update(config.copy(toneX=x,toneY=y))}
            Label("Tilt ${eqLabel(config.toneX*1.2f)} dB   ·   Contour ${eqLabel(config.toneY*1.2f)} dB",font,RiffDesign.muted,size=13)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                DataAction(if(!config.focused)"✓ Broad"else"Broad",font){update(config.copy(focused=false))}
                DataAction(if(config.focused)"✓ Focused"else"Focused",font){update(config.copy(focused=true))}
            }
        }else{
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                EQ_PRESETS.forEach{(name,gains)->DataAction(if(config.preset==name)"✓ $name"else name,font){update(config.copy(manual=gains,preset=name))}}
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){
                config.manual.forEachIndexed{index,gain->
                    Column(horizontalAlignment=Alignment.CenterHorizontally){
                        Label(eqLabel(gain),font,RiffDesign.pink,size=12)
                        CanonicalEqAxis(gain,-12f,12f,true,"${EqLayout.manualLabels[index]} Hz gain",{manualStep(it)}){value->
                            val gains=config.manual.toMutableList().apply{set(index,value)}
                            update(config.copy(manual=gains,preset=EQ_PRESETS.entries.firstOrNull{it.value==gains}?.key?:"Custom"))
                        }
                        Label(EqLayout.manualLabels[index],font,RiffDesign.muted,size=12)
                    }
                }
            }
        }
        val response=remember(config){eqCurve(eqSections(config))}
        Canvas(Modifier.fillMaxWidth().height(72.dp).semantics{contentDescription="Equalizer response curve"}){
            drawLine(RiffDesign.muted.copy(alpha=.3f),Offset(0f,size.height/2),Offset(size.width,size.height/2))
            val path=Path();response.forEachIndexed{i,value->val x=i*size.width/(response.size-1);val y=size.height/2-value.coerceIn(-24f,24f)*size.height/48;if(i==0)path.moveTo(x,y)else path.lineTo(x,y)}
            drawPath(path,RiffDesign.pink,style=Stroke(2.dp.toPx()))
        }
        Label("Balance · ${if(config.balance==0f)"Centre"else if(config.balance<0)"Left"else"Right"}",font,RiffDesign.muted,size=13)
        CanonicalEqAxis(config.balance,-1f,1f,false,"Left right balance",{if(kotlin.math.abs(it)<.04f)0f else(it*100).roundToInt()/100f}){update(config.copy(balance=it))}
    }
}
@Composable private fun CanonicalTonePad(x:Int,y:Int,onChange:(Int,Int)->Unit){
    val callback by rememberUpdatedState(onChange)
    val px by animateFloatAsState(x.toFloat(),spring(.7f,600f));val py by animateFloatAsState(y.toFloat(),spring(.7f,600f))
    Canvas(Modifier.widthIn(max=340.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha=.04f))
        .semantics{contentDescription="Tone pad. Arrow keys adjust tilt and contour";stateDescription="Tilt $x, contour $y"}
        .onPreviewKeyEvent{event->if(event.type!=KeyEventType.KeyDown)false else when(event.key){
            Key.DirectionLeft->{callback((x-1).coerceAtLeast(-5),y);true};Key.DirectionRight->{callback((x+1).coerceAtMost(5),y);true}
            Key.DirectionUp->{callback(x,(y+1).coerceAtMost(5));true};Key.DirectionDown->{callback(x,(y-1).coerceAtLeast(-5));true};Key.MoveHome->{callback(0,0);true};else->false
        }}.focusable().pointerInput(Unit){awaitEachGesture{
            val down=awaitFirstDown();down.consume()
            fun send(point:Offset){val border=24.dp.toPx();val spanX=(size.width-2*border).coerceAtLeast(1f);val spanY=(size.height-2*border).coerceAtLeast(1f);callback((((point.x-border)/spanX)*10-5).roundToInt().coerceIn(-5,5),(5-((point.y-border)/spanY)*10).roundToInt().coerceIn(-5,5))}
            send(down.position);var pressed=true
            while(pressed){val change=awaitPointerEvent().changes.firstOrNull{it.id==down.id}?:break;pressed=change.pressed;if(pressed){change.consume();send(change.position)}}
        }}){
        val border=24.dp.toPx();val sx=(size.width-2*border)/10;val sy=(size.height-2*border)/10
        for(row in 0..10)for(col in 0..10)drawCircle(Color.White.copy(alpha=if(col==x+5||row==5-y).55f else .25f),2.dp.toPx(),Offset(border+col*sx,border+row*sy))
        drawCircle(RiffDesign.pink,12.dp.toPx(),Offset(border+(px+5)*sx,border+(5-py)*sy))
    }
}
@Composable internal fun CanonicalEqAxis(value:Float,minimum:Float,maximum:Float,vertical:Boolean,label:String,snap:(Float)->Float,onChange:(Float)->Unit){
    val callback by rememberUpdatedState(onChange);val snapper by rememberUpdatedState(snap)
    val fraction=(value-minimum)/(maximum-minimum)
    Canvas((if(vertical)Modifier.width(42.dp).height(180.dp)else Modifier.fillMaxWidth().height(40.dp))
        .semantics{contentDescription=label;progressBarRangeInfo=ProgressBarRangeInfo(value,minimum..maximum);setProgress{callback(snapper(it.coerceIn(minimum,maximum)));true}}
        .onPreviewKeyEvent{event->if(event.type!=KeyEventType.KeyDown)false else{val step=if(vertical).5f else .05f;when(event.key){
            Key.DirectionUp,Key.DirectionRight->{callback(snapper((value+step).coerceAtMost(maximum)));true}
            Key.DirectionDown,Key.DirectionLeft->{callback(snapper((value-step).coerceAtLeast(minimum)));true}
            Key.MoveHome->{callback(0f);true};else->false
        }}}.focusable().pointerInput(vertical){awaitEachGesture{
            val down=awaitFirstDown();down.consume()
            fun send(point:Offset){val ratio=if(vertical)1-point.y/size.height else point.x/size.width;callback(snapper((minimum+ratio.coerceIn(0f,1f)*(maximum-minimum)).coerceIn(minimum,maximum)))}
            send(down.position);var pressed=true
            while(pressed){val change=awaitPointerEvent().changes.firstOrNull{it.id==down.id}?:break;pressed=change.pressed;if(pressed){change.consume();send(change.position)}}
        }}){
        val centre=if(vertical)Offset(size.width/2,size.height*(1-fraction))else Offset(size.width*fraction,size.height/2)
        val start=if(vertical)Offset(size.width/2,0f)else Offset(0f,size.height/2);val end=if(vertical)Offset(size.width/2,size.height)else Offset(size.width,size.height/2)
        drawLine(Color.White.copy(alpha=.16f),start,end,4.dp.toPx());drawCircle(RiffDesign.pink,7.dp.toPx(),centre)
    }
}
