package com.musicglass.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.musicglass.data.encore.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Desktop containers around the actual Android Encore rules, tokens, chart geometry and motion. */
@Composable internal fun DesktopEncoreStage(font:FontFamily,modifier:Modifier=Modifier){
    val host=LocalDesktopHost.current?:return
    val dialogOwner=LocalDesktopWindow.current
    val state by host.state.collectAsState()
    val genreState by host.genreLookupState.collectAsState()
    var selectedGenre by remember{mutableStateOf<String?>(null)}
    val today=LocalDate.now();val first=EncorePeriod.firstDayOfWeek()
    var range by remember{mutableStateOf(EncoreRange.Week)}
    var anchor by remember{mutableStateOf(today)}
    var historyStart by remember{mutableStateOf<LocalDate?>(null)}
    val period=remember(range,anchor,historyStart){EncorePeriod.containing(range,anchor,first,historyStart)}
    var summary by remember(period){mutableStateOf<com.musicglass.data.encore.EncoreSummary?>(null)}
    var error by remember(period){mutableStateOf<String?>(null)}
    var story by remember{mutableStateOf<com.musicglass.data.encore.EncoreSummary?>(null)}
    var sort by remember{mutableStateOf(EncoreSort.Minutes)}
    var kind by remember{mutableStateOf(EncoreKind.SONG)}
    LaunchedEffect(state.historyPrivacy.genreLookup){if(!state.historyPrivacy.genreLookup&&kind=="genre"){kind="song";selectedGenre=null}}
    var requestedDetail by remember{mutableStateOf<Pair<String,String>?>(null)}
    var detail by remember{mutableStateOf<EncoreHistory?>(null)}
    var detailError by remember{mutableStateOf<String?>(null)}
    LaunchedEffect(requestedDetail){
        detail=null;detailError=null
        requestedDetail?.let{(type,key)->try{detail=host.encoreHistory(type,key);if(detail==null)detailError="No retained listening history for this item."}
            catch(cancel:CancellationException){throw cancel}catch(failure:Exception){detailError="Could not read item history: ${failure.message?.take(120)}"}}
    }
    var expanded by remember(period,kind,sort){mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    var exportStatus by remember{mutableStateOf<String?>(null)}
    var pendingExport by remember{mutableStateOf<Triple<java.io.File,String,com.musicglass.data.encore.EncoreSummary>?>(null)}
    fun save(file:java.io.File,format:String,value:com.musicglass.data.encore.EncoreSummary,replace:Boolean=false){scope.launch{
        try{withContext(Dispatchers.IO){DesktopEncoreExport.write(file,value,format,replace)};exportStatus="Saved ${file.name}"}
        catch(cancel:CancellationException){throw cancel}
        catch(failure:Exception){exportStatus="Export failed: ${failure.message?.take(150)}"}
    }}
    fun chooseExport(format:String,value:com.musicglass.data.encore.EncoreSummary){
        javax.swing.SwingUtilities.invokeLater{
            chooseDesktopSaveFile(dialogOwner,"Export Encore locally","riff-encore-${value.period.range.name.lowercase()}-${value.period.start}.$format")?.let{file->
                if(file.exists())pendingExport=Triple(file,format,value)else save(file,format,value)
            }
        }
    }
    LaunchedEffect(host,period,state.initialized,state.historyPrivacy){
        try{host.observeEncore(period).collect{value->summary=value;historyStart=value?.historyStart}}
        catch(cancel:CancellationException){throw cancel}
        catch(failure:Exception){error="Encore could not read history: ${failure.message?.take(150)}"}
    }
    fun open(value:EncorePeriod){range=value.range;anchor=value.start}
    requestedDetail?.let{androidx.compose.ui.window.Popup(alignment=Alignment.Center,onDismissRequest={requestedDetail=null},properties=androidx.compose.ui.window.PopupProperties(focusable=true)){
        LazyColumn(Modifier.widthIn(max=560.dp).heightIn(max=640.dp).clip(RoundedCornerShape(16.dp)).background(RiffDesign.ink).border(1.dp,White.copy(alpha=.12f),RoundedCornerShape(16.dp)).padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            item{DataAction("Close history",font){requestedDetail=null}}
            val value=detail
            if(value==null)item{Label(detailError?:"Reading your history…",font,RiffDesign.muted)}
            else{
                item{Label(value.title,font,White,size=26);Label("Since ${value.firstDay} · ${value.plays} plays · ${encoreTime(value.listenedMs)}",font,RiffDesign.muted);value.rankThisYear?.let{Label("Rank $it this year",font,RiffDesign.pink)}}
                item{DesktopEncoreBars(value.bars,EncorePeriod(EncoreRange.All,value.firstDay,today.plusDays(1)),font){next->requestedDetail=null;open(next)}}
            }
        }
    }}
    story?.let{shown->DesktopEncoreStories(shown,font,{story=null}){next->
        range=next.range;anchor=next.start
        scope.launch{host.observeEncore(next).filterNotNull().first().let{story=it;host.dispatch(DesktopCommand.MarkEncoreStorySeen(it.period))}}
    }}
    LazyColumn(modifier.fillMaxSize().padding(horizontal=32.dp,vertical=24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)){
        item{Label("Encore",font,Color.White,size=34);Label("Your listening, kept on this computer",font,RiffDesign.muted)}
        item{GlassSegmented(listOf("Day","Week","Month","Year","All time"),range.ordinal,{range=EncoreRange.entries[it];anchor=today},"encore-range")}
        item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
            val prior=period.previous()?.takeIf{historyStart?.let{start->it.end.isAfter(start)}==true}
            if(prior!=null)DataAction("‹ Previous",font){open(prior)}else Spacer(Modifier.width(80.dp))
            Label(if(range==EncoreRange.All)"All time"else "${period.start} — ${period.end.minusDays(1)}",font,Color.White)
            val next=period.next(today)
            if(next!=null)DataAction("Next ›",font){open(next)}else Spacer(Modifier.width(80.dp))
        }}
        if(error!=null)item{Label(error!!,font,RiffDesign.muted)}
        val current=summary
        if(current==null&&error==null)item{Label("Reading listening history…",font,RiffDesign.muted)}
        if(current!=null){
            if(current.empty)item{Label(if(!state.historyPrivacy.saveHistory)"Listening history is off in Privacy and data."else"Play music to build your Encore. Episodes are not included.",font,RiffDesign.muted)}
            else{
                item{Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Label(encoreTime(current.listenedMs),font,Color.White,size=RiffEncore.headline.value.toInt())
                    Label("${current.plays} plays · ${current.songs} songs · ${current.artists} artists · ${current.albums} albums",font,RiffDesign.muted)
                    current.previousListenedMs?.let{before->Label("${if(current.listenedMs>=before)"▲"else"▼"} ${encoreTime(kotlin.math.abs(current.listenedMs-before))} against the previous period",font,RiffDesign.muted)}
                    current.historyStart?.let{Label("History available since $it. Artist credits each receive the full heard time.",font,RiffDesign.muted,size=12)}
                }}
                item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    DataAction(if("${current.period.range}:${current.period.start}" in state.encoreSeen)"Watch Encore again"else"● Watch your Encore",font){story=current;host.dispatch(DesktopCommand.MarkEncoreStorySeen(current.period))}
                    DataAction("Export CSV",font){chooseExport("csv",current)}
                    DataAction("Export JSON",font){chooseExport("json",current)}
                }}
                pendingExport?.let{pending->item{Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Label("Replace ${pending.first.name}?",font,Color.White)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        DataAction("Replace",font){pendingExport=null;save(pending.first,pending.second,pending.third,true)}
                        DataAction("Cancel",font){pendingExport=null}
                    }
                }}}
                exportStatus?.let{message->item{Label(message,font,RiffDesign.muted,size=13)}}
                item{DesktopEncoreBars(current.bars,period,font,::open)}
                item{BoxWithConstraints(Modifier.fillMaxWidth()){
                    if(maxWidth>=760.dp)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(28.dp)){
                        Box(Modifier.width(260.dp)){DesktopEncoreClock(current.hours,font)}
                        Column(Modifier.weight(1f)){current.weekHours?.let{DesktopEncoreWeekHeat(it,first,font)}}
                    }else Column(verticalArrangement=Arrangement.spacedBy(16.dp)){
                        DesktopEncoreClock(current.hours,font);current.weekHours?.let{DesktopEncoreWeekHeat(it,first,font)}
                    }
                }}
                if(current.calendar.isNotEmpty())item{
                    Label("Listening calendar",font,Color.White,size=19)
                    DesktopEncoreCalendar(current.calendar,font,::open)
                }
                val genreItems=current.top("genre",sort)
                val kinds=if(state.historyPrivacy.genreLookup&&genreItems.isNotEmpty())listOf("song","album","artist","genre")else listOf("song","album","artist")
                item{GlassSegmented(if(kinds.size==4)listOf("Songs","Albums","Artists","Genres")else listOf("Songs","Albums","Artists"),kinds.indexOf(kind).coerceAtLeast(0),{kind=kinds[it]},"encore-top-kind")}
                if(state.historyPrivacy.genreLookup)item{Label(when(val status=genreState){GenreLookupState.Idle->"Genres will be checked in the background";GenreLookupState.Off->"No eligible artists to look up";GenreLookupState.WaitingForUnmetered->"Genre lookup waits for an unmetered connection";is GenreLookupState.Updating->"MusicBrainz: ${status.checked} checked";is GenreLookupState.Ready->"Cached MusicBrainz genres · ambiguous names stay unassigned";is GenreLookupState.Failed->status.message},font,RiffDesign.muted,size=12)}
                if(kind=="genre"&&genreItems.isNotEmpty())item{DesktopEncoreGenreChart(genreItems,font){selectedGenre=it}}
                if(kind=="genre"&&selectedGenre!=null)item{
                    var facts by remember(current){mutableStateOf<Map<String,List<String>>?>(null)}
                    LaunchedEffect(current){facts=host.encoreGenreFacts(current.top("artist").map{it.title})}
                    Label("Artists in $selectedGenre",font,White,size=19)
                    if(facts==null)Label("Reading cached artist facts…",font,RiffDesign.muted)
                    else current.top("artist").filter{selectedGenre in facts?.get(it.title).orEmpty()}.forEach{Label(it.title,font,RiffDesign.muted)}
                }
                item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    DataAction(if(sort==EncoreSort.Minutes)"✓ Minutes"else"Minutes",font){sort=EncoreSort.Minutes}
                    DataAction(if(sort==EncoreSort.Plays)"✓ Plays"else"Plays",font){sort=EncoreSort.Plays}
                }}
                items(current.top(kind,sort).take(if(expanded)100 else 5),key={it.key}){item->
                    Row(Modifier.fillMaxWidth().padding(vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.CenterVertically){
                        Label("${current.top(kind,sort).indexOf(item)+1}",font,RiffDesign.muted,size=15)
                        Column(Modifier.weight(1f)){
                            Label(item.title,font,Color.White,size=16)
                            Label("${item.subtitle} · ${item.plays} plays · ${encoreTime(item.listenedMs)}",font,RiffDesign.muted,size=13)
                            if(current.previousListenedMs!=null)Label(if(!item.wasRanked)"New in this comparison"else when{(item.movement?:0)>0->"▲ ${item.movement}";(item.movement?:0)<0->"▼ ${-(item.movement?:0)}";else->"Same rank"},font,RiffDesign.muted,size=12)
                        }
                        Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
                            if(kind==EncoreKind.SONG)DataAction("Play",font){scope.launch{val track=host.encoreTrack(item.key);if(track!=null)host.dispatch(DesktopCommand.PlayTrack(track))else exportStatus="This track is no longer in the library."}}
                            if(kind!="genre")DataAction("Your history",font){requestedDetail=kind to item.key}
                        }
                    }
                }
                if(!expanded&&current.top(kind,sort).size>5)item{DataAction("See all",font){expanded=true}}
                item{Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Label("Listening habits",font,Color.White,size=19)
                    Label("${current.habits.streakDays} day streak · at least 10 minutes a day",font,RiffDesign.muted)
                    Label("Longest retained session: ${current.habits.longestSessionPlays} plays · ${encoreTime(current.habits.longestSessionMs)}",font,RiffDesign.muted)
                    current.habits.discovery?.let{Label("New artists: ${(it*100).toInt()}% of credited artist time",font,RiffDesign.muted)}
                    current.habits.completion?.let{Label("Finished ${(it*100).toInt()}% of recorded endings",font,RiffDesign.muted)}
                    current.habits.mostRepeatedTitle?.let{Label("Most repeated: $it · ${current.habits.mostRepeatedPlays} plays in one day",font,RiffDesign.muted)}
                    current.personality?.let{Label(when(it){EncorePersonality.NightOwl->"Night owl · over 40% between 22:00 and 04:00";EncorePersonality.EarlyBird->"Early bird · over 30% between 05:00 and 09:00";EncorePersonality.Loyal->"Loyal · top artist over 25% of listening";EncorePersonality.Explorer->"Explorer · over 30% new artists"},font,RiffDesign.pink)}
                    if(current.milestones.isNotEmpty())Label("Milestones: "+current.milestones.joinToString{it.name},font,RiffDesign.pink)
                    Label("Session detail follows history retention. Completion and remote playback-source statistics appear only when genuinely recorded.",font,RiffDesign.muted,size=12)
                }}
            }
        }
    }
}

internal fun encoreTime(ms:Long):String=when{ms>=3600000->"${ms/3600000} h ${(ms/60000)%60} m";ms>=60000->"${ms/60000} m ${(ms/1000)%60} s";else->"${ms/1000} s"}
private fun barWords(bar:EncoreBar)="${bar.start}${bar.hour?.let{" · %02d:00".format(it)}.orEmpty()} · ${encoreTime(bar.listenedMs)}"
@Composable private fun encoreGrowth(key:Any):Float{
    val motion=LocalMotionPolicy.current;val value=remember(key){Animatable(if(motion.reduced)1f else 0f)}
    LaunchedEffect(key,motion.reduced){if(motion.reduced)value.snapTo(1f)else value.animateTo(1f,GlassMotion.timed(GlassMotion.ambientMs,motion))}
    return value.value
}
@Composable private fun DesktopEncoreBars(bars:List<EncoreBar>,period:EncorePeriod,font:FontFamily,onOpen:(EncorePeriod)->Unit){
    if(bars.isEmpty())return
    var selected by remember(period){mutableStateOf<Int?>(null)}
    val growth=encoreGrowth(period);val max=bars.maxOf{it.listenedMs}.coerceAtLeast(1).toFloat()
    val shown=selected?.let{bars.getOrNull(it)}?:bars.maxByOrNull{it.listenedMs}
    Column{
        Label(shown?.let(::barWords).orEmpty(),font,RiffDesign.muted,size=13)
        Box(Modifier.fillMaxWidth().height(RiffEncore.chart).pointerInput(bars){detectHorizontalDragGestures(onDragStart={selected=(it.x/size.width*bars.size).toInt().coerceIn(0,bars.lastIndex)}){change,_->selected=(change.position.x/size.width*bars.size).toInt().coerceIn(0,bars.lastIndex)}}){
            Canvas(Modifier.fillMaxSize()){
                val slot=size.width/bars.size;val gap=minOf(RiffEncore.barGap.toPx(),slot*.5f)
                bars.forEachIndexed{i,bar->
                    val width=(slot-gap).coerceAtLeast(.5f);val corner=CornerRadius(minOf(RiffEncore.barCorner.toPx(),width/2))
                    drawRoundRect(Color.White.copy(alpha=.08f),Offset(i*slot+gap/2,0f),Size(width,size.height),corner)
                    val height=(bar.listenedMs/max*size.height*growth).coerceAtLeast(if(bar.listenedMs>0)2f else 0f)
                    if(height>0)drawRoundRect(if(bar==shown)RiffDesign.pink else Color.White.copy(alpha=.3f),Offset(i*slot+gap/2,size.height-height),Size(width,height),corner)
                }
            }
            Row(Modifier.fillMaxSize()){bars.forEachIndexed{i,bar->Box(Modifier.weight(1f).fillMaxHeight().semantics{contentDescription=barWords(bar);stateDescription=if(i==selected)"Selected"else""}.clickable(role=Role.Button){selected=i;bar.opens?.let(onOpen)})}}
        }
    }
}
@Composable internal fun DesktopEncoreClock(hours:LongArray,font:FontFamily){
    var selected by remember{mutableStateOf<Int?>(null)};val top=hours.maxOrNull()?.coerceAtLeast(1)?.toFloat()?:1f
    val shown=selected?:hours.withIndex().filter{it.value>0}.maxByOrNull{it.value}?.index
    val growth=encoreGrowth(Unit)
    Column(horizontalAlignment=Alignment.CenterHorizontally){
        Box(Modifier.size(RiffEncore.clock),contentAlignment=Alignment.Center){
            Canvas(Modifier.fillMaxSize().pointerInput(hours.toList()){detectTapGestures{point->val angle=(kotlin.math.atan2(point.y-size.height/2,point.x-size.width/2)+Math.PI/2+2*Math.PI)%(2*Math.PI);selected=(angle/(2*Math.PI)*24).toInt().coerceIn(0,23)}}){
                val outer=size.minDimension/2;val inner=outer*RiffEncore.clockHole
                for(hour in 0..23){
                    val length=(outer-inner)*(hours[hour]/top)*growth
                    val base=inner+(outer-inner)/2
                    drawArc(Color.White.copy(alpha=.08f),hour*15f-89.2f,13.4f,false,Offset(center.x-base,center.y-base),Size(base*2,base*2),style=Stroke(outer-inner))
                    if(length>0){val r=inner+length/2;drawArc(if(hour==shown)RiffDesign.pink else Color.White.copy(alpha=.3f),hour*15f-89.2f,13.4f,false,Offset(center.x-r,center.y-r),Size(r*2,r*2),style=Stroke(length))}
                }
                for(mark in listOf(0,6,12,18)){
                    val angle=mark/24.0*2*Math.PI-Math.PI/2
                    drawCircle(RiffDesign.muted,2f,Offset(center.x+(inner*.82f*kotlin.math.cos(angle)).toFloat(),center.y+(inner*.82f*kotlin.math.sin(angle)).toFloat()))
                }
            }
            if(shown!=null)Column(horizontalAlignment=Alignment.CenterHorizontally){Label("%02d:00".format(shown),font,Color.White,size=19);Label(encoreTime(hours[shown]),font,RiffDesign.muted,size=13)}
        }
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)){
            hours.forEachIndexed{hour,heard->Box(Modifier.semantics{contentDescription="Hour $hour · ${encoreTime(heard)}"}.clickable{selected=hour}.padding(4.dp)){Label("$hour",font,RiffDesign.muted,size=10)}}
        }
    }
}
@Composable private fun DesktopEncoreWeekHeat(rows:List<LongArray>,first:DayOfWeek,font:FontFamily){
    val top=rows.maxOf{it.maxOrNull()?:0}.coerceAtLeast(1).toFloat();var chosen by remember{mutableStateOf<Pair<Int,Int>?>(null)}
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
        Label("Peak hours",font,Color.White,size=19)
        rows.forEachIndexed{r,hours->Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            Box(Modifier.width(36.dp)){Label(first.plus(r.toLong()).getDisplayName(java.time.format.TextStyle.SHORT,Locale.getDefault()),font,RiffDesign.muted,size=10)}
            hours.forEachIndexed{hour,ms->Box(Modifier.weight(1f).aspectRatio(1f).padding(1.dp).clip(RoundedCornerShape(RiffEncore.cellCorner)).background(if(chosen==r to hour)RiffDesign.pink else Color.White.copy(alpha=if(ms>0).1f+.7f*ms/top else .08f)).semantics{contentDescription="${first.plus(r.toLong())}, $hour:00 · ${encoreTime(ms)}"}.clickable{chosen=r to hour})}
        }}
        chosen?.let{(r,h)->Label("${first.plus(r.toLong())}, %02d:00 · %s".format(h,encoreTime(rows[r][h])),font,RiffDesign.muted,size=12)}
    }
}
@Composable private fun DesktopEncoreCalendar(days:Map<LocalDate,Long>,font:FontFamily,onOpen:(EncorePeriod)->Unit){
    val first=days.keys.minOrNull()?:return;val last=days.keys.maxOrNull()?:return
    val top=days.values.maxOrNull()?.coerceAtLeast(1)?.toFloat()?:1f
    var chosen by remember{mutableStateOf<LocalDate?>(null)}
    val weeks=remember(first,last){generateSequence(first.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))){it.plusWeeks(1)}.takeWhile{!it.isAfter(last)}.toList()}
    Column{
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(2.dp)){
            weeks.forEach{week->Column(verticalArrangement=Arrangement.spacedBy(2.dp)){
                repeat(7){index->val day=week.plusDays(index.toLong());val ms=days[day]?:0L
                    Box(Modifier.size(14.dp).clip(RoundedCornerShape(RiffEncore.cellCorner)).background(if(day==chosen)RiffDesign.pink else Color.White.copy(alpha=if(ms>0).1f+.7f*ms/top else .08f)).semantics{contentDescription="$day · ${encoreTime(ms)}"}.clickable{chosen=day;onOpen(EncorePeriod(EncoreRange.Day,day,day.plusDays(1)))})
                }
            }}
        }
        chosen?.let{Label("$it · ${encoreTime(days[it]?:0)}",font,RiffDesign.muted,size=12)}
    }
}

@Composable private fun DesktopEncoreGenreChart(items:List<EncoreItem>,font:FontFamily,onSelect:(String)->Unit){
    var selected by remember{mutableStateOf<String?>(null)}
    val shown=items.take(6);val total=shown.sumOf{it.listenedMs}.coerceAtLeast(1).toFloat()
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Canvas(Modifier.size(RiffEncore.donut).semantics{contentDescription="Known genres from ranked artists"}.pointerInput(shown){detectTapGestures{point->
            val angle=((kotlin.math.atan2(point.y-size.height/2,point.x-size.width/2)*180/Math.PI+450)%360).toFloat();var boundary=0f
            for(item in shown){boundary+=item.listenedMs/total*360;if(angle<boundary){selected=item.key;onSelect(item.key);break}}
        }}){
            var start=-90f;val width=RiffEncore.donutStroke.toPx()
            shown.forEachIndexed{index,item->val sweep=item.listenedMs/total*360
                drawArc(if(item.key==selected)White else Pink.copy(alpha=(1f-index*.12f).coerceAtLeast(.2f)),start,(sweep-1).coerceAtLeast(0f),false,Offset(width/2,width/2),Size(size.width-width,size.height-width),style=Stroke(width));start+=sweep
            }
        }
        shown.forEach{item->DataAction("${item.title} · ${encoreTime(item.listenedMs)}",font){selected=item.key;onSelect(item.key)}}
        Label("Known genres for ranked artists; each artist's time is split across its first two genres.",font,RiffDesign.muted,size=12)
    }
}
