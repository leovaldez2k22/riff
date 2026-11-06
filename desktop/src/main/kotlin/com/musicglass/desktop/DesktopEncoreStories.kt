package com.musicglass.desktop

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import com.musicglass.data.TrackRecord
import com.musicglass.data.encore.*
import com.musicglass.data.encore.EncoreSummary
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlin.math.*

private val LocalEncoreStoryFont=staticCompositionLocalOf{InterFamily}
private const val SLIDE_MS=6000

/** Same Android slides/geometry. Pointer holds pause; desktop adds Esc, arrows and Space. */
@Composable internal fun DesktopEncoreStories(current:EncoreSummary,font:FontFamily,onClose:()->Unit,onPeriod:(EncorePeriod)->Unit){
    val motion=LocalMotionPolicy.current
    val dialogOwner=LocalDesktopWindow.current
    val slides=remember(current){encoreSlides(current)}
    var index by remember(current.period){mutableIntStateOf(0)}
    var held by remember{mutableStateOf(false)}
    var capturing by remember{mutableStateOf(false)}
    var exportStatus by remember{mutableStateOf<String?>(null)}
    var overwrite by remember{mutableStateOf<Pair<java.io.File,ByteArray>?>(null)}
    val scope=rememberCoroutineScope()
    val layer=rememberGraphicsLayer()
    val focus=remember{FocusRequester()}
    LaunchedEffect(Unit){withFrameNanos{};focus.requestFocus()}
    fun save(file:java.io.File,bytes:ByteArray,replace:Boolean=false){scope.launch{
        try{withContext(Dispatchers.IO){DesktopEncoreExport.writeBytes(file,bytes,replace)};exportStatus="Saved ${file.name}"}
        catch(cancel:CancellationException){throw cancel}
        catch(failure:Exception){exportStatus="Could not save slide: ${failure.message?.take(120)}"}
    }}
    fun export(){scope.launch{
        if(capturing)return@launch
        val oldHeld=held;held=true;capturing=true
        try{
            withFrameNanos{};withFrameNanos{}
            val bitmap=layer.toImageBitmap()
            val bytes=withContext(Dispatchers.Default){DesktopEncoreExport.png(bitmap)}
            javax.swing.SwingUtilities.invokeLater{
                chooseDesktopSaveFile(dialogOwner,"Save Encore slide","riff-encore-${current.period.start}-${index+1}.png")?.let{file->if(file.exists())overwrite=file to bytes else save(file,bytes)}
            }
        }catch(cancel:CancellationException){throw cancel}
        catch(failure:Exception){exportStatus="Could not capture slide: ${failure.message?.take(120)}"}
        finally{capturing=false;held=oldHeld}
    }}
    var keyboardPaused by remember{mutableStateOf(false)}
    val progress=remember(current.period,index){Animatable(0f)}
    fun next(){if(index<slides.lastIndex)index++else onClose()}
    LaunchedEffect(current.period,index,held,keyboardPaused,motion.reduced){
        if(motion.reduced||held||keyboardPaused)return@LaunchedEffect
        progress.animateTo(1f,tween(((1f-progress.value)*SLIDE_MS).toInt().coerceAtLeast(1),easing=LinearEasing));next()
    }
    Popup(alignment=Alignment.Center,onDismissRequest=onClose,properties=PopupProperties(focusable=true)){
        CompositionLocalProvider(LocalEncoreStoryFont provides font){
            Box(Modifier.widthIn(max=506.25.dp).heightIn(max=900.dp).aspectRatio(9f/16f,matchHeightConstraintsFirst=true).background(Color.Black).focusRequester(focus).focusable()
                .onPreviewKeyEvent{event->if(event.type!=KeyEventType.KeyDown)false else when(event.key){
                    Key.Escape->{onClose();true};Key.DirectionLeft->{if(index>0)index--;true};Key.DirectionRight->{next();true};Key.Spacebar->{keyboardPaused=!keyboardPaused;true};else->false
                }}){
                Box(Modifier.fillMaxSize().drawWithContent{if(capturing)layer.record{this@drawWithContent.drawContent()};drawContent()}.background(storyGradient(slides[index]))){
                    AnimatedContent(index,transitionSpec={fadeIn(GlassMotion.timed(GlassMotion.selectionMs,motion)) togetherWith fadeOut(GlassMotion.exit(GlassMotion.selectionMs,motion))},label="encore-story-slide"){shown->
                        Box(Modifier.fillMaxSize().padding(horizontal=RiffSpace.screenEdge).padding(top=100.dp,bottom=80.dp),contentAlignment=Alignment.Center){
                            EncoreStorySlide(slides[shown],current,LocalDate.now(),motion.reduced||capturing)
                        }
                    }
                }
                Box(Modifier.fillMaxSize().pointerInput(current.period){
                    awaitEachGesture{
                        val down=awaitFirstDown();held=true
                        val up=waitForUpOrCancellation();held=false
                        if(up!=null){
                            val dx=up.position.x-down.position.x;val dy=up.position.y-down.position.y
                            when{
                                dy>size.height/6f&&abs(dy)>abs(dx)->onClose()
                                abs(dx)>size.width/4f->(if(dx<0)current.period.next(LocalDate.now())else current.period.previous())?.let(onPeriod)
                                up.uptimeMillis-down.uptimeMillis>400->Unit
                                down.position.x<size.width/3f->if(index>0)index--
                                else->next()
                            }
                        }
                    }
                })
                Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    Row(Modifier.fillMaxWidth().semantics{liveRegion=LiveRegionMode.Polite;contentDescription="Slide ${index+1} of ${slides.size}"},horizontalArrangement=Arrangement.spacedBy(RiffEncore.storyBarGap)){
                        slides.indices.forEach{i->Canvas(Modifier.weight(1f).height(RiffEncore.storyBar)){
                            drawRoundRect(White.copy(alpha=.3f));drawRoundRect(White,size=size.copy(width=size.width*when{ i<index->1f;i>index->0f;motion.reduced->1f;else->progress.value}))
                        }}
                    }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                        Label(encorePeriodLabel(current.period,LocalDate.now()),font,White,size=13)
                        DataAction("Save slide PNG",font,::export)
                        DataAction("Close (Esc)",font,onClose)
                    }
                }
                if(exportStatus!=null||overwrite!=null)Column(Modifier.align(Alignment.TopCenter).padding(top=90.dp).background(RiffDesign.ink).padding(16.dp)){
                    exportStatus?.let{Label(it,font,Muted,size=13)}
                    overwrite?.let{pending->Label("Replace ${pending.first.name}?",font,White)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                            DataAction("Replace",font){overwrite=null;save(pending.first,pending.second,true)}
                            DataAction("Cancel",font){overwrite=null}
                        }
                    }
                }
                Row(Modifier.align(Alignment.BottomCenter).padding(20.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){
                    if(index>0)DataAction("Previous",font){index--}
                    DataAction(if(keyboardPaused||held)"Resume"else"Pause",font){keyboardPaused=!keyboardPaused}
                    DataAction(if(index==slides.lastIndex)"Finish"else"Next",font,::next)
                }
            }
        }
    }
}

internal enum class EncoreSlide { Intro, Time, TopSong, TopFive, TopArtist, TopAlbums, Genres, GoldenHour, Habits, Milestone, Summary }

/** The slides a period has: each is left out when it has nothing to show. */
internal fun encoreSlides(summary: EncoreSummary): List<EncoreSlide> = buildList {
    add(EncoreSlide.Intro); add(EncoreSlide.Time)
    if (summary.top(EncoreKind.SONG).isNotEmpty()) add(EncoreSlide.TopSong)
    if (summary.top(EncoreKind.SONG).size >= 3) add(EncoreSlide.TopFive)
    if (summary.top(EncoreKind.ARTIST).isNotEmpty()) add(EncoreSlide.TopArtist)
    if (summary.top(EncoreKind.ALBUM).size >= 2) add(EncoreSlide.TopAlbums)
    if (summary.top(ENCORE_GENRE_KIND).isNotEmpty()) add(EncoreSlide.Genres)
    if (summary.goldenHour != null) add(EncoreSlide.GoldenHour)
    add(EncoreSlide.Habits)
    if (summary.milestones.isNotEmpty()) add(EncoreSlide.Milestone)
    add(EncoreSlide.Summary)
}

/** A slow gradient per slide, Riff's pink into the night. */
private fun storyGradient(slide: EncoreSlide): Brush {
    val tints = listOf(Color(0xFF3B1530), Color(0xFF1B2440), Color(0xFF2A1A3E), Color(0xFF103A35), Color(0xFF3A2414))
    val tint = tints[slide.ordinal % tints.size]
    return Brush.verticalGradient(listOf(tint, Color(0xFF07070B)))
}

@Composable private fun EncoreStorySlide(slide: EncoreSlide, summary: EncoreSummary, today: java.time.LocalDate, still: Boolean) {
    val motion = LocalMotionPolicy.current
    val rise = remember(slide) { Animatable(if (still) 0f else 1f) }
    LaunchedEffect(slide) { if (!still) rise.animateTo(0f, GlassMotion.timed(GlassMotion.artworkMs, motion)) }
    val shownRise=if(still)0f else rise.value
    val lift = Modifier.offset { IntOffset(0, (shownRise * 80).roundToInt()) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(RiffSpace.block)) {
        when (slide) {
            EncoreSlide.Intro -> {
                Text(encoreStoryTitle(summary.period.range), color = White, fontSize = RiffEncore.storyTitle, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text(encorePeriodLabel(summary.period, today), color = Muted, fontSize = RiffEncore.storyBody)
            }
            EncoreSlide.Time -> {
                Text("You listened for", color = Muted, fontSize = RiffEncore.storyBody)
                Text(encoreTime(summary.listenedMs), color = White, fontSize = RiffEncore.storyNumber, fontWeight = FontWeight.Bold, modifier = lift)
                summary.previousListenedMs?.let { before ->
                    val change = summary.listenedMs - before
                    if (abs(change) >= 60_000L) Text("That’s ${encoreTime(abs(change))} ${if(change>0)"more"else"less"} than the previous period", color = White, fontSize = RiffEncore.storyBody, textAlign = TextAlign.Center)
                }
            }
            EncoreSlide.TopSong -> summary.top(EncoreKind.SONG).first().let { song ->
                Text("Your top song", color = Muted, fontSize = RiffEncore.storyBody)
                CatalogArtwork(song.artwork, song.key, modifier = lift.size(RiffEncore.storyArt).rotate(shownRise * -8f).clip(RoundedCornerShape(RiffRadius.hero)))
                Text(song.title, color = White, fontSize = RiffEncore.storyTitle, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${song.plays} plays · ${encoreTime(song.listenedMs)}", color = Muted, fontSize = RiffEncore.storyBody)
            }
            EncoreSlide.TopFive -> {
                Text("Your top 5", color = White, fontSize = RiffEncore.storyTitle, fontWeight = FontWeight.Bold)
                summary.top(EncoreKind.SONG).take(5).forEachIndexed { i, song -> StoryRow(i + 1, song, Modifier.offset { IntOffset(((shownRise * (i + 1) * 60)).roundToInt(), 0) }) }
            }
            EncoreSlide.TopArtist -> summary.top(EncoreKind.ARTIST).first().let { artist ->
                Text("Your top artist", color = Muted, fontSize = RiffEncore.storyBody)
                val cover = summary.top(EncoreKind.SONG).firstOrNull { it.subtitle.contains(artist.title) }
                CatalogArtwork(cover?.artwork, artist.key, modifier = lift.size(RiffEncore.storyArt).clip(CircleShape))
                Text(artist.title, color = White, fontSize = RiffEncore.storyTitle, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                if (summary.listenedMs > 0) Text("${encorePercent(artist.listenedMs.toFloat() / summary.listenedMs)} of your listening",
                    color = Muted, fontSize = RiffEncore.storyBody)
            }
            EncoreSlide.TopAlbums -> {
                Text("Your top albums", color = White, fontSize = RiffEncore.storyTitle, fontWeight = FontWeight.Bold)
                Box(Modifier.fillMaxWidth().height(RiffEncore.storyArt), contentAlignment = Alignment.Center) {
                    summary.top(EncoreKind.ALBUM).take(5).reversed().forEachIndexed { i, album ->
                        val turn = (i - 2) * 9f * (1f - shownRise)
                        CatalogArtwork(album.artwork, album.key, modifier = Modifier.size(RiffEncore.storyArt * .62f).rotate(turn)
                            .offset { IntOffset(((i - 2) * 46 * (1f - shownRise)).roundToInt(), 0) }.clip(RoundedCornerShape(RiffRadius.artwork)))
                    }
                }
                summary.top(EncoreKind.ALBUM).take(3).forEachIndexed { i, album -> Text("${i + 1}. ${album.title}", color = White, fontSize = RiffEncore.storyBody, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            EncoreSlide.Genres -> {
                Text("Your top genres", color = White, fontSize = RiffEncore.storyTitle, fontWeight = FontWeight.Bold)
                DesktopEncoreStoryDonut(summary.top(ENCORE_GENRE_KIND).take(6))
            }
            EncoreSlide.GoldenHour -> {
                DesktopEncoreClock(summary.hours, LocalEncoreStoryFont.current)
                Text("Most of your listening happens at ${encoreHourLabel(summary.goldenHour ?: 0)}", color = White, fontSize = RiffEncore.storyBody, textAlign = TextAlign.Center)
            }
            EncoreSlide.Habits -> {
                Text("Your habits", color = White, fontSize = RiffEncore.storyTitle, fontWeight = FontWeight.Bold)
                Text("Streak" + ": " + "${summary.habits.streakDays} days", color = White, fontSize = RiffEncore.storyBody)
                summary.habits.discovery?.let { Text("Discovery" + ": " + encorePercent(it), color = White, fontSize = RiffEncore.storyBody) }
                summary.personality?.let {
                    Text(encorePersonalityName(it), color = Pink, fontSize = RiffEncore.storyTitle, fontWeight = FontWeight.Bold, modifier = lift)
                    Text(encorePersonalityRule(it), color = Muted, fontSize = RiffEncore.storyBody, textAlign = TextAlign.Center)
                }
            }
            EncoreSlide.Milestone -> {
                Text("A milestone", color = Muted, fontSize = RiffEncore.storyBody)
                summary.milestones.forEach { Text(encoreMilestoneName(it), color = White, fontSize = RiffEncore.storyTitle, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = lift) }
            }
            EncoreSlide.Summary -> SummaryCard(summary, today)
        }
    }
}

@Composable private fun StoryRow(rank: Int, item: EncoreItem, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(rank.toString(), color = Pink, fontSize = RiffEncore.storyBody, fontWeight = FontWeight.Bold)
        Spacer(Modifier.size(RiffSpace.rowGap))
        CatalogArtwork(item.artwork, item.key, modifier = Modifier.size(RiffEncore.storyRowArt).clip(RoundedCornerShape(RiffRadius.artwork)))
        Column(Modifier.weight(1f).padding(start = RiffSpace.rowGap)) {
            Text(item.title, color = White, fontSize = RiffEncore.storyBody, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.subtitle, color = Muted, fontSize = RiffType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The shareable card: time, top song, top artist, top genre, personality, and Riff's two waves in the corner. */
@Composable private fun SummaryCard(summary: EncoreSummary, today: java.time.LocalDate) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(RiffRadius.hero)).background(White.copy(alpha = .08f)).padding(RiffSpace.block * 1.5f),
        verticalArrangement = Arrangement.spacedBy(RiffSpace.block)) {
        Text(encoreStoryTitle(summary.period.range), color = White, fontSize = RiffEncore.storyBody, fontWeight = FontWeight.Bold)
        Text(encorePeriodLabel(summary.period, today), color = Muted, fontSize = RiffType.body)
        SummaryLine("Listening time", encoreTime(summary.listenedMs))
        summary.top(EncoreKind.SONG).firstOrNull()?.let { SummaryLine("Your top song", it.title) }
        summary.top(EncoreKind.ARTIST).firstOrNull()?.let { SummaryLine("Your top artist", it.title) }
        summary.top(ENCORE_GENRE_KIND).firstOrNull()?.let { SummaryLine("Top genres", it.title) }
        summary.personality?.let { SummaryLine("Listening personality", encorePersonalityName(it)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Canvas(Modifier.size(RiffEncore.storyShare)) {
                val stroke = size.width / 10f
                for (row in 0..1) {
                    val path = androidx.compose.ui.graphics.Path()
                    val y = size.height * (.38f + row * .28f)
                    path.moveTo(0f, y); path.cubicTo(size.width * .3f, y - size.height * .25f, size.width * .6f, y + size.height * .25f, size.width, y)
                    drawPath(path, Pink, style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                }
            }
        }
    }
}

@Composable private fun SummaryLine(label: String, value: String) {
    Column {
        Text(label, color = Muted, fontSize = RiffType.body)
        Text(value, color = White, fontSize = RiffEncore.storyBody, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}


@Composable private fun Text(text:String,color:Color=White,fontSize:TextUnit=RiffType.body,fontWeight:FontWeight=FontWeight.Normal,textAlign:TextAlign=TextAlign.Start,maxLines:Int=Int.MAX_VALUE,overflow:TextOverflow=TextOverflow.Clip,modifier:Modifier=Modifier){
    BasicText(text,modifier,style=TextStyle(color=color,fontFamily=if(fontSize.value>=20)InterDisplayFamily else LocalEncoreStoryFont.current,fontSize=fontSize,fontWeight=fontWeight,textAlign=textAlign),maxLines=maxLines,overflow=overflow)
}
@Composable private fun CatalogArtwork(artwork:String?,key:String,modifier:Modifier){
    val repository=LocalArtworkRepository.current
    val bitmap by produceState<ImageBitmap?>(null,artwork,repository){
        if(artwork!=null&&repository!=null){
            try{
                val record=TrackRecord("guest","encore-art:"+com.musicglass.data.SqliteLibraryRepository.digest(key),"Encore artwork",artworkKey=artwork.takeUnless{it.startsWith("https:")},artworkUrl=artwork.takeIf{it.startsWith("https:")})
                val actual=repository.obtain(record).artworkKey
                value=actual?.let{repository.bitmap(it)}
            }catch(cancel:CancellationException){throw cancel}catch(_:Exception){value=null}
        }
    }
    Box(modifier.background(Color(0xFF181A22))){bitmap?.let{androidx.compose.foundation.Image(it,"",Modifier.fillMaxSize(),contentScale=androidx.compose.ui.layout.ContentScale.Crop)}}
}
@Composable private fun DesktopEncoreStoryDonut(items:List<EncoreItem>){
    val total=items.sumOf{it.listenedMs}.coerceAtLeast(1).toFloat()
    Canvas(Modifier.size(RiffEncore.donut).semantics{contentDescription=items.joinToString{it.title+" · "+encoreTime(it.listenedMs)}}){
        var angle=-90f
        items.forEachIndexed{index,item->val sweep=item.listenedMs/total*360;drawArc(Pink.copy(alpha=(1f-index*.12f).coerceAtLeast(.2f)),angle,sweep-1,false,Offset(RiffEncore.donutStroke.toPx()/2,RiffEncore.donutStroke.toPx()/2),Size(size.width-RiffEncore.donutStroke.toPx(),size.height-RiffEncore.donutStroke.toPx()),style=Stroke(RiffEncore.donutStroke.toPx()));angle+=sweep}
    }
}
private fun encoreStoryTitle(range:EncoreRange)=when(range){EncoreRange.Day->"Your day in Encore";EncoreRange.Week->"Your week in Encore";EncoreRange.Month->"Your month in Encore";EncoreRange.Year->"Your year in Encore";EncoreRange.All->"Your Encore so far"}
private fun encorePeriodLabel(period:EncorePeriod,today:LocalDate)=if(period.range==EncoreRange.All)"Since ${period.start}"else"${period.start} — ${period.end.minusDays(1)}"
private fun encoreHourLabel(hour:Int)="%02d:00".format(hour)
private fun encorePercent(value:Float)="${(value*100).roundToInt()}%"
private fun encorePersonalityName(value:EncorePersonality)=when(value){EncorePersonality.NightOwl->"Night owl";EncorePersonality.EarlyBird->"Early bird";EncorePersonality.Loyal->"Loyal";EncorePersonality.Explorer->"Explorer"}
private fun encorePersonalityRule(value:EncorePersonality)=when(value){EncorePersonality.NightOwl->"Over 40% of listening between 22:00 and 04:00";EncorePersonality.EarlyBird->"Over 30% of listening between 05:00 and 09:00";EncorePersonality.Loyal->"Your top artist accounts for over 25% of listening";EncorePersonality.Explorer->"Over 30% of artist time was new in this period"}
private fun encoreMilestoneName(value:EncoreMilestone)=when(value){EncoreMilestone.Hours10->"10 hours";EncoreMilestone.Hours100->"100 hours";EncoreMilestone.Hours1000->"1,000 hours";EncoreMilestone.Play1000->"1,000 plays";EncoreMilestone.Streak30->"30-day streak";EncoreMilestone.Artists100->"100 artists"}
