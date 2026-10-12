package com.musicglass.personal

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive

internal const val DEFAULT_LYRICS_OFFSET_MS = 200L

@Composable
internal fun LyricsPanel(playback: PlaybackGateway, provider: YouTubeMusicProvider,
    repository: LyricsRepository, onBack: () -> Unit) {
    val state = playback.state
    val track = remember(state.mediaId,state.title,state.artist,state.album,state.durationMs) {
        LyricsTrack(state.mediaId,state.title,state.artist,state.album,state.durationMs)
    }
    var document by remember(track) { mutableStateOf<LyricsDocument?>(null) }
    var loading by remember(track) { mutableStateOf(true) }
    var failed by remember(track) { mutableStateOf(false) }
    var refresh by remember(track) { mutableIntStateOf(0) }
    var follow by rememberSaveable(state.mediaId) { mutableStateOf(true) }
    var offsetMs by rememberSaveable(state.mediaId) { mutableLongStateOf(DEFAULT_LYRICS_OFFSET_MS) }
    var timingDialog by remember { mutableStateOf(false) }
    val context=LocalContext.current
    var translate by rememberSaveable(state.mediaId) {mutableStateOf(false)}
    var sourceLanguage by rememberSaveable(state.mediaId) {mutableStateOf<String?>(null)}
    var translated by remember(state.mediaId) {mutableStateOf<List<String>>(emptyList())}
    var translationStatus by remember(state.mediaId) {mutableStateOf("")}
    var languageMenu by remember {mutableStateOf(false)}
    val languages=remember {com.google.mlkit.nl.translate.TranslateLanguage.getAllLanguages().sortedBy {java.util.Locale.forLanguageTag(it).getDisplayLanguage(java.util.Locale.ENGLISH)}}
    LaunchedEffect(translate,document,sourceLanguage) {
        translated=emptyList();translationStatus=""
        if(translate && document?.lines?.isNotEmpty()==true) try {
            translated=englishLyrics(context,document!!.lines,sourceLanguage) {translationStatus=it};translationStatus=""
        } catch(e:CancellationException) {throw e} catch(e:Exception) {translationStatus=e.message?:string(R.string.translation_unavailable_try_again)}
    }
    val motion = LocalMotionPolicy.current
    val list = rememberLazyListState()
    val dragged by list.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) { if (dragged) follow = false }
    LaunchedEffect(state.mediaId) { list.scrollToItem(0); follow = true }
    LaunchedEffect(track,refresh) {
        repository.observe(track,force = refresh > 0) {
            provider.lyricsFor(track.mediaId.removePrefix("yt:"),track.durationMs)
        }.collect { result -> document=result.document; loading=result.loading; failed=result.failed }
    }
    val owner = LocalLifecycleOwner.current
    var position by remember(state.mediaId) { mutableLongStateOf(playback.currentPositionMs()) }
    val timed = document?.timed == true
    LaunchedEffect(playback,state.mediaId,state.playing,timed,owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (state.playing && timed) {
                while (isActive) withFrameNanos { position = playback.currentPositionMs() }
            } else {
                snapshotFlow { playback.positionMs }.collect { position = playback.currentPositionMs() }
            }
        }
    }
    val songLyrics = document
    val currentIndex by remember(songLyrics) { derivedStateOf {
        if (songLyrics?.timed == true) activeLyricLine(songLyrics.lines,position+offsetMs) else -1
    } }
    // Silence has no active word, but reopening must still center the latest sung line.
    val scrollIndex by remember(songLyrics) { derivedStateOf {
        if (songLyrics?.timed == true) startedLyricLine(songLyrics.lines,position+offsetMs) else -1
    } }
    LaunchedEffect(scrollIndex,follow,songLyrics) {
        if (follow && scrollIndex >= 0) {
            val inset = -(list.layoutInfo.viewportSize.height / 3)
            if (motion.reduced) list.scrollToItem(scrollIndex,inset)
            else list.animateScrollToItem(scrollIndex,inset)
        }
    }
    val uri = LocalUriHandler.current
    Box(Modifier.fillMaxSize().preferredFrameRate(LocalUiRefreshRate.current)
        .background(Brush.verticalGradient(listOf(Color(0xFF231524),Color(0xFF0B0C12),Color.Black)))) {
        AsyncImage(state.artworkUrl,contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = .075f })
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp,end = 20.dp,top = 8.dp,bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = hapticClick(onBack),modifier = Modifier.testTag("lyrics_back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack,string(R.string.back_to_player),tint = White)
                }
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(string(R.string.lyrics),color = Pink,fontSize = 11.sp,letterSpacing = 2.sp,fontWeight = FontWeight.SemiBold)
                    Text(state.title.ifBlank { string(R.string.nothing_playing) },color = White,fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,maxLines = 1,overflow = TextOverflow.Ellipsis,
                        modifier=Modifier.lyricsSharedBounds("song-title:${state.mediaId}"))
                    Text(playerArtistLabel(state.artist),color = Muted,fontSize = 13.sp,maxLines = 1,
                        overflow = TextOverflow.Ellipsis,modifier=Modifier.lyricsSharedBounds("song-artist:${state.mediaId}"))
                }
                IconButton(onClick = { uiHaptic();  timingDialog = true },enabled = timed,modifier = Modifier.testTag("lyrics_timing")) {
                    Icon(Icons.Rounded.Tune,string(R.string.adjust_lyrics_timing),tint = if (timed) White else Muted)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal=24.dp),verticalAlignment=Alignment.CenterVertically) {
                FilterChip(translate,{ uiHaptic(HapticCue.Selection); translate=!translate},enabled=songLyrics?.lines?.isNotEmpty()==true,label={Text(string(R.string.english_translation))},
                    leadingIcon={Icon(Icons.Rounded.Translate,null,modifier=Modifier.size(18.dp))},modifier=Modifier.testTag("lyrics_translate"))
                Spacer(Modifier.width(8.dp))
                Box {TextButton({ uiHaptic(); languageMenu=true}) {Text(sourceLanguage?.let {java.util.Locale.forLanguageTag(it).getDisplayLanguage(java.util.Locale.ENGLISH)}?:string(R.string.auto_language),maxLines=1)}
                    SongActionMenu(languageMenu,{languageMenu=false},modifier=Modifier.heightIn(max=340.dp)) {
                        DropdownMenuItem(text={Text(string(R.string.auto_detect))},onClick={ uiHaptic(); sourceLanguage=null;languageMenu=false})
                        languages.forEach {language->DropdownMenuItem(text={Text(java.util.Locale.forLanguageTag(language).getDisplayLanguage(java.util.Locale.ENGLISH))},onClick={ uiHaptic(); sourceLanguage=language;languageMenu=false})}
                    }
                }
            }
            if(translate && translationStatus.isNotBlank()) Text(translationStatus,color=Muted,fontSize=12.sp,modifier=Modifier.padding(horizontal=28.dp,vertical=4.dp))
            when {
                songLyrics?.instrumental == true -> LyricsMessage(string(R.string.instrumental),string(R.string.this_recording_has_no_sung_lyrics),Modifier.weight(1f))
                songLyrics != null && songLyrics.lines.isNotEmpty() -> {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val space = maxHeight / 3
                        LazyColumn(state = list,modifier = Modifier.fillMaxSize().testTag("lyrics_list"),
                            contentPadding = PaddingValues(start = 28.dp,end = 28.dp,top = 24.dp,bottom = space),
                            verticalArrangement = Arrangement.spacedBy(22.dp)) {
                            itemsIndexed(songLyrics.lines,key = { index,line -> "${line.startMs}:$index" }) { index,line ->
                                val active by remember(songLyrics,index) { derivedStateOf { currentIndex == index } }
                                KaraokeLine(line,active,{ position+offsetMs },motion,
                                    modifier = Modifier.testTag("lyric_line_$index")
                                        .then(if (line.startMs >= 0) Modifier.clickable { uiHaptic(); 
                                            playback.seekTo((line.startMs-offsetMs).coerceAtLeast(0))
                                            position = playback.currentPositionMs(); follow = true
                                        } else Modifier))
                                if(translate) translated.getOrNull(index)?.let {english->Text(english,color=if(active) White else Muted,fontSize=18.sp,
                                    modifier=Modifier.padding(top=8.dp).testTag("lyric_english_$index"))}
                            }
                        }
                    }
                }
                loading -> Column(Modifier.weight(1f).fillMaxWidth().padding(28.dp),
                    verticalArrangement = Arrangement.Center) {
                    if (!motion.reduced) CircularProgressIndicator(color = Pink,modifier = Modifier.size(26.dp))
                    Spacer(Modifier.height(20.dp))
                    Text(string(R.string.finding_the_best_lyrics),color = White,fontSize = 20.sp)
                    Text(string(R.string.looking_for_word_and_line_timing),color = Muted,fontSize = 14.sp)
                }
                else -> Column(Modifier.weight(1f).fillMaxWidth().padding(28.dp),verticalArrangement = Arrangement.Center) {
                    Text(if (failed) string(R.string.couldn_t_load_lyrics) else string(R.string.no_lyrics_found),color = White,fontSize = 26.sp,fontWeight = FontWeight.Bold)
                    Text(if (failed) string(R.string.check_your_connection_and_try_again) else string(R.string.lyrics_aren_t_available_for_this_recording),
                        color = Muted,fontSize = 15.sp,modifier = Modifier.padding(top = 10.dp,bottom = 16.dp))
                    TextButton(onClick = { uiHaptic();  refresh++ },modifier = Modifier.testTag("lyrics_retry")) { Text(string(R.string.try_again),color = Pink) }
                }
            }
            if (!follow && timed) TextButton(onClick = { uiHaptic();  follow = true },
                modifier = Modifier.align(Alignment.CenterHorizontally).testTag("lyrics_follow")) { Text(string(R.string.follow_lyrics),color = Pink) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp,vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(when { songLyrics?.wordTimed == true -> string(R.string.word_synced); timed -> string(R.string.line_synced)
                    songLyrics?.instrumental == true -> string(R.string.instrumental); songLyrics != null -> string(R.string.text_lyrics); else -> "" },
                    color = Pink,fontSize = 12.sp,modifier = Modifier.testTag("lyrics_sync_level"))
                if (loading && songLyrics != null) Text(string(R.string.finding_finer_timing),color = Muted,fontSize = 11.sp,
                    modifier = Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                if (songLyrics != null) Text(songLyrics.source,color = Muted,fontSize = 11.sp,maxLines = 1,
                    modifier = Modifier.clickable { uiHaptic();  runCatching { uri.openUri(songLyrics.sourceUrl) } }
                        .testTag("lyrics_source"))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.Center,verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = hapticClick(playback::previous),enabled = state.queue.size > 1) { Icon(Icons.Rounded.FastRewind,string(R.string.previous_track),tint = Muted) }
                IconButton(onClick = hapticClick(playback::toggle),modifier = Modifier.size(56.dp).testTag("lyrics_toggle")) {
                    Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (state.playing) string(R.string.pause) else string(R.string.play),tint = White,modifier = Modifier.size(34.dp))
                }
                IconButton(onClick = hapticClick(playback::next),enabled = state.queue.size > 1) { Icon(Icons.Rounded.FastForward,string(R.string.next_track),tint = Muted) }
            }
        }
    }
    if (timingDialog) LyricsTimingDialog(offsetMs, { offsetMs = it }) { timingDialog = false }
}

internal const val LYRICS_OFFSET_STEP_MS = 100L
internal const val LYRICS_OFFSET_LIMIT_MS = 5000L
/** The offset as it is shown: signed, in seconds. */
internal fun lyricsOffsetLabel(offsetMs: Long) = string(R.string.x_seconds_short, "${if (offsetMs >= 0) "+" else ""}${offsetMs / 1000f}")
/** One step later or earlier, kept inside the limit. */
internal fun lyricsOffsetStep(offsetMs: Long, later: Boolean) =
    (offsetMs + if (later) LYRICS_OFFSET_STEP_MS else -LYRICS_OFFSET_STEP_MS).coerceIn(-LYRICS_OFFSET_LIMIT_MS, LYRICS_OFFSET_LIMIT_MS)

/** Moves the highlight earlier or later for this recording. Reset returns to the default, which is not zero. */
@Composable
internal fun LyricsTimingDialog(offsetMs: Long, onOffset: (Long) -> Unit, onDismiss: () -> Unit) {
    FrostedAlertDialog(onDismissRequest = onDismiss,
        title = { Text(string(R.string.lyrics_timing)) },text = {
            Column {
                Text(string(R.string.adjust_the_highlight_if_this_recording_is))
                Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { uiHaptic(); onOffset(lyricsOffsetStep(offsetMs, later = false)) },modifier = Modifier.testTag("lyrics_offset_minus")) { Text(string(R.string.x_seconds_short, "\u2212 0.1")) }
                    Text(lyricsOffsetLabel(offsetMs),modifier = Modifier.testTag("lyrics_offset_value"))
                    TextButton(onClick = { uiHaptic(); onOffset(lyricsOffsetStep(offsetMs, later = true)) },modifier = Modifier.testTag("lyrics_offset_plus")) { Text(string(R.string.x_seconds_short, "+ 0.1")) }
                }
                TextButton(onClick = { uiHaptic(); onOffset(DEFAULT_LYRICS_OFFSET_MS) },modifier = Modifier.testTag("lyrics_offset_reset")) { Text(string(R.string.reset)) }
            }
        },confirmButton = { TextButton(onClick = { uiHaptic(); onDismiss() }) { Text(string(R.string.done)) } })
}

@Composable
private fun LyricsMessage(title: String,subtitle: String,modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(28.dp),verticalArrangement = Arrangement.Center) {
        Text(title,color = White,fontSize = 28.sp,fontWeight = FontWeight.Bold)
        Text(subtitle,color = Muted,fontSize = 16.sp,modifier = Modifier.padding(top = 12.dp))
    }
}
private data class LyricHighlight(val part: LyricPart,val path: Path,val bounds: Rect,val rtl: Boolean)

/** Only drawing reads the frame clock. Text measurement stays cached through the whole word. */
@Composable
private fun KaraokeLine(line: LyricLine,active: Boolean,position: () -> Long,motion: MotionPolicy,
    modifier: Modifier = Modifier) {
    var layout by remember(line) { mutableStateOf<TextLayoutResult?>(null) }
    val highlights = remember(layout,line) {
        val measured = layout
        if (measured == null) emptyList() else line.parts.map { part ->
            val path = measured.getPathForRange(part.from,part.to)
            LyricHighlight(part,path,path.getBounds(),measured.getBidiRunDirection(part.from) == ResolvedTextDirection.Rtl)
        }
    }
    val baseColor by animateColorAsState(if (active) White.copy(alpha = if (line.parts.isEmpty()) 1f else .38f)
        else White.copy(alpha = .40f),GlassMotion.timed(GlassMotion.selectionMs,motion),label = "lyric focus")
    val scale = animateFloatAsState(if (active && !motion.reduced) 1.025f else 1f,
        GlassMotion.timed(GlassMotion.selectionMs,motion),label = "lyric emphasis")
    Text(line.text.ifBlank { "♪" },color = baseColor,fontSize = 29.sp,lineHeight = 38.sp,fontWeight = FontWeight.Bold,
        onTextLayout = { layout = it },modifier = modifier.fillMaxWidth().padding(vertical = 5.dp)
            .semantics { if (active) stateDescription = string(R.string.current_lyric) }
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .drawWithContent {
                drawContent()
                val measured = layout
                if (active && measured != null) {
                    val now = position()
                    for (highlight in highlights) {
                        val part = highlight.part
                        val fraction = ((now-part.startMs).toFloat()/(part.endMs-part.startMs)).coerceIn(0f,1f)
                        if (fraction <= 0) continue
                        val bounds = highlight.bounds
                        clipPath(highlight.path) {
                            clipRect(left = if (highlight.rtl) bounds.right-bounds.width*fraction else bounds.left,
                                top = bounds.top,right = if (highlight.rtl) bounds.right else bounds.left+bounds.width*fraction,
                                bottom = bounds.bottom) {
                                drawText(measured,color = if (now >= part.endMs) White else Pink)
                            }
                        }
                    }
                }
            })
}
