package com.musicglass.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun MiniPlayerContent(
    currentTrack: SongItem,
    isPlaying: Boolean,
    isBuffering: Boolean = false,
    positionMs: Long,
    volume: Float,
    isExpanded: Boolean,
    isAlwaysOnTop: Boolean = false,
    font: FontFamily,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit = {},
    onVolumeChange: (Float) -> Unit = {},
    onToggleAlwaysOnTop: () -> Unit = {},
    onToggleExpand: () -> Unit,
    onRestoreMain: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backdrop = rememberRiffBackdrop()

    Column(
        modifier = modifier
            .fillMaxSize()
            .riffBackdropSource(backdrop)
            .background(RiffDesign.ink)
            .padding(horizontal=14.dp,vertical=if(isExpanded)14.dp else 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        if (!isExpanded) {
            // ==========================================
            // COMPACT MODE (360x160)
            // ==========================================
            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .trackArtwork(currentTrack)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    androidx.compose.foundation.text.BasicText(currentTrack.title,style=RiffText.body.copy(fontFamily=font,fontSize=15.sp,color=Color.White),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    androidx.compose.foundation.text.BasicText(if(isBuffering)"Buffering..."else currentTrack.artist,style=RiffText.body.copy(fontFamily=font,fontSize=12.sp,color=RiffDesign.muted),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }

                // Always on top toggle pin
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClickLabel = "Always on top", onClick = onToggleAlwaysOnTop)
                        .semantics { contentDescription = "Always on top" },
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(Modifier.size(10.dp)) {
                        val color = if (isAlwaysOnTop) RiffDesign.pink else RiffDesign.muted
                        drawCircle(color, radius = 3.dp.toPx())
                        drawLine(color, Offset(size.width * 0.5f, size.height * 0.5f), Offset(size.width * 0.5f, size.height), strokeWidth = 1.5.dp.toPx())
                    }
                }

                Spacer(Modifier.width(4.dp))

                // Expand toggle
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClickLabel = "Expand Mini-Player", onClick = onToggleExpand)
                        .semantics { contentDescription = "Expand Mini-Player" },
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(Modifier.size(10.dp)) {
                        val stroke = 1.6.dp.toPx()
                        drawLine(RiffDesign.muted, Offset(0f, size.height * 0.7f), Offset(size.width * 0.5f, size.height * 0.2f), strokeWidth = stroke)
                        drawLine(RiffDesign.muted, Offset(size.width, size.height * 0.7f), Offset(size.width * 0.5f, size.height * 0.2f), strokeWidth = stroke)
                    }
                }

                Spacer(Modifier.width(4.dp))

                // Restore Main
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClickLabel = "Restore Main Workspace", onClick = onRestoreMain)
                        .semantics { contentDescription = "Restore Main Workspace" },
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(Modifier.size(10.dp)) {
                        drawRect(RiffDesign.muted, style = androidx.compose.ui.graphics.drawscope.Stroke(1.4.dp.toPx()))
                    }
                }
            }

            // Compact Transport row
            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MiniTransportButton("Previous", onClick = onPrevious) { color ->
                    Canvas(Modifier.size(14.dp)) {
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(size.width, 0f)
                            lineTo(0f, size.height * 0.5f)
                            lineTo(size.width, size.height)
                            close()
                        }
                        drawPath(path, color)
                    }
                }

                // Play / Pause Circle
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable(role = Role.Button, onClick = onTogglePlay),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(Modifier.size(16.dp)) {
                        if (isPlaying) {
                            drawRect(RiffDesign.ink, Offset(size.width * 0.2f, size.height * 0.15f), Size(size.width * 0.22f, size.height * 0.7f))
                            drawRect(RiffDesign.ink, Offset(size.width * 0.58f, size.height * 0.15f), Size(size.width * 0.22f, size.height * 0.7f))
                        } else {
                            val path = androidx.compose.ui.graphics.Path().apply {
                                moveTo(size.width * 0.25f, size.height * 0.15f)
                                lineTo(size.width * 0.85f, size.height * 0.5f)
                                lineTo(size.width * 0.25f, size.height * 0.85f)
                                close()
                            }
                            drawPath(path, RiffDesign.ink)
                        }
                    }
                }

                MiniTransportButton("Next", onClick = onNext) { color ->
                    Canvas(Modifier.size(14.dp)) {
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(0f, 0f)
                            lineTo(size.width, size.height * 0.5f)
                            lineTo(0f, size.height)
                            close()
                        }
                        drawPath(path, color)
                    }
                }
            }
        } else {
            // ==========================================
            // EXPANDED MODE (360x460)
            // ==========================================
            // Header bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Label("Now Playing", font, RiffDesign.muted, size = 12)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isAlwaysOnTop) RiffDesign.pink.copy(alpha = 0.25f) else Color.Transparent)
                            .clickable(onClick = onToggleAlwaysOnTop)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Label(if (isAlwaysOnTop) "Pinned" else "Pin", font, if (isAlwaysOnTop) RiffDesign.pink else RiffDesign.muted, size = 11)
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onToggleExpand)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Label("Compact", font, RiffDesign.muted, size = 11)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Bounded Album Art
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .trackArtwork(currentTrack)
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
            )

            Spacer(Modifier.height(10.dp))

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.foundation.text.BasicText(currentTrack.title,style=RiffText.rowTitle.copy(fontFamily=font,color=Color.White),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                androidx.compose.foundation.text.BasicText(if(isBuffering)"Buffering..."else currentTrack.artist,style=RiffText.subtitle.copy(fontFamily=font,color=RiffDesign.muted),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }

            // Real-time current lyric line
            val host=LocalDesktopHost.current
            val state=host?.state?.collectAsState()?.value
            val loaded=(state?.lyricsState as? LyricsLoadState.Loaded)?.takeIf{state.lyricsTrackId==currentTrack.id}
            var frameNanos by remember(currentTrack.id){mutableLongStateOf(System.nanoTime())}
            LaunchedEffect(host,currentTrack.id){while(true)withFrameNanos{frameNanos=System.nanoTime()}}
            val presented=LyricsPresentationClock.position(host?.clockAnchor?.value,state?.currentEntryId,state?.isPlaying==true&&!state.isBuffering,frameNanos,state?.playbackPositionMs?:positionMs,currentTrack.durationMs)
            val lyricLines=remember(loaded?.document){loaded?.document?.let{if(it.timed)withInstrumentalGaps(it.lines)else it.lines}.orEmpty()}
            val currentLine=miniLyricText(loaded,presented,lyricLines)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp),
                contentAlignment = Alignment.Center
            ) {
                if (currentLine != null) {
                    androidx.compose.foundation.text.BasicText(currentLine,style=RiffText.body.copy(fontFamily=font,fontSize=13.sp,color=RiffDesign.pink),maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }

            // Seek slider
            val progressFraction = (positionMs.toFloat() / currentTrack.durationMs.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Label(formatDuration(positionMs), font, RiffDesign.muted, size = 11)
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(16.dp)
                        .pointerInput(currentTrack.durationMs) {
                            detectTapGestures { offset ->
                                val frac = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                onSeek((frac * currentTrack.durationMs).toLong())
                            }
                        },
                    contentAlignment = Alignment.CenterStart
                ) {
                    Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                        val corner = CornerRadius(size.height / 2)
                        drawRoundRect(Color.White.copy(alpha = 0.15f), cornerRadius = corner)
                        drawRoundRect(Color.White, size = Size(size.width * progressFraction, size.height), cornerRadius = corner)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Label(formatDuration(currentTrack.durationMs), font, RiffDesign.muted, size = 11)
            }

            // Transport row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MiniTransportButton("Previous", onClick = onPrevious) { color ->
                    Canvas(Modifier.size(16.dp)) {
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(size.width, 0f)
                            lineTo(0f, size.height * 0.5f)
                            lineTo(size.width, size.height)
                            close()
                        }
                        drawPath(path, color)
                    }
                }

                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable(role = Role.Button, onClick = onTogglePlay),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(Modifier.size(18.dp)) {
                        if (isPlaying) {
                            drawRect(RiffDesign.ink, Offset(size.width * 0.2f, size.height * 0.15f), Size(size.width * 0.22f, size.height * 0.7f))
                            drawRect(RiffDesign.ink, Offset(size.width * 0.58f, size.height * 0.15f), Size(size.width * 0.22f, size.height * 0.7f))
                        } else {
                            val path = androidx.compose.ui.graphics.Path().apply {
                                moveTo(size.width * 0.25f, size.height * 0.15f)
                                lineTo(size.width * 0.85f, size.height * 0.5f)
                                lineTo(size.width * 0.25f, size.height * 0.85f)
                                close()
                            }
                            drawPath(path, RiffDesign.ink)
                        }
                    }
                }

                MiniTransportButton("Next", onClick = onNext) { color ->
                    Canvas(Modifier.size(16.dp)) {
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(0f, 0f)
                            lineTo(size.width, size.height * 0.5f)
                            lineTo(0f, size.height)
                            close()
                        }
                        drawPath(path, color)
                    }
                }
            }

            // Restore Main button
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClick = onRestoreMain)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Label("Restore Main Workspace", font, RiffDesign.pink, size = 12)
            }
        }
    }
}

@Composable
private fun MiniTransportButton(
    description: String,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        icon(Color.White)
    }
}

/** Plain documents have no timed current line; respect the shared document offset and romanization. */
internal fun miniLyricText(loaded:LyricsLoadState.Loaded?,presentedMs:Long,lines:List<LyricLine> = loaded?.document?.lines.orEmpty()):String? {
    if(loaded?.document?.timed!=true)return null
    val line=lines.getOrNull(activeLyricLine(lines,presentedMs+loaded.offsetMs))?:return null
    if(line.gap)return "Instrumental"
    val roman=line.romanized?.let{it+if(line.romanizedAuto)" · Auto"else""}
    return when(loaded.romanMode){
        LyricsRomanMode.Original->line.text
        LyricsRomanMode.Romanized->roman?:line.text
        LyricsRomanMode.Both->if(roman==null)line.text else line.text+"\n"+roman
    }
}
