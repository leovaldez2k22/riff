package com.musicglass.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun DesktopDock(
    currentTrack: SongItem,
    isPlaying: Boolean,
    isBuffering: Boolean = false,
    positionMs: Long,
    volume: Float,
    isShuffle: Boolean,
    repeatMode: RepeatMode,
    inspectorTab: InspectorTab,
    font: FontFamily,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleInspector: (InspectorTab) -> Unit,
    onOpenMiniPlayer: () -> Unit,
    onOpenFocusedPlayer: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val backdrop = rememberRiffBackdrop()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(88.dp)
            .riffBackdropSource(backdrop)
            .background(RiffDesign.raised)
            .border(1.dp, Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left block: Artwork & Track Metadata (clamped width for 800dp-1920dp responsiveness)
            Row(
                modifier = Modifier
                    .widthIn(min = 180.dp, max = 280.dp)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = "Open Now Playing view",
                        onClick = onOpenFocusedPlayer
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // The same keyed memory/disk artwork path used by the player and Mini-Player.
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).trackArtwork(currentTrack))

                Spacer(Modifier.width(14.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Label(
                        text = currentTrack.title,
                        font = font,
                        color = Color.White,
                        size = 17
                    )
                    Spacer(Modifier.height(2.dp))
                    Label(
                        text = currentTrack.artist,
                        font = font,
                        color = RiffDesign.muted,
                        size = 13
                    )
                    PlaybackSpeedChip(font)
                }

                // Genuine SQLite local-save state. No in-memory imitation of an account Like.
                val saveHost=LocalDesktopHost.current
                val saved=saveHost?.state?.collectAsState()?.value?.isCurrentTrackSaved==true
                DockIconButton(
                    description=if(saved)"Remove local save"else"Save locally",
                    onClick={if(currentTrack.id.isNotBlank())saveHost?.dispatch(DesktopCommand.ToggleSavedTrack(currentTrack))}
                ){color->
                    Canvas(Modifier.size(20.dp)){
                        withTransform({scale(size.width/24f,size.height/24f,pivot=Offset.Zero)}){drawPath(if(saved)RiffHeartFilled else RiffHeartOutline,if(saved)RiffDesign.pink else color)}
                    }
                }
            }

            Spacer(Modifier.width(16.dp))

            // Center block: Transport Controls & Seek Slider
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Transport Buttons Row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Shuffle
                    DockIconButton(
                        description = "Shuffle",
                        onClick = onToggleShuffle
                    ) { color ->
                        Canvas(Modifier.size(18.dp)) {
                            val activeColor = if (isShuffle) RiffDesign.pink else color
                            drawLine(activeColor, Offset(0f, size.height * 0.2f), Offset(size.width, size.height * 0.8f), strokeWidth = 2.dp.toPx())
                            drawLine(activeColor, Offset(0f, size.height * 0.8f), Offset(size.width, size.height * 0.2f), strokeWidth = 2.dp.toPx())
                        }
                    }

                    // Previous Track (44dp button)
                    DockIconButton(
                        description = "Previous Track",
                        size = 44.dp,
                        onClick = onPrevious
                    ) { color ->
                        Canvas(Modifier.size(22.dp)) {
                            drawLine(color, Offset(2.dp.toPx(), 2.dp.toPx()), Offset(2.dp.toPx(), size.height - 2.dp.toPx()), strokeWidth = 2.5.dp.toPx())
                            val path = androidx.compose.ui.graphics.Path().apply {
                                moveTo(size.width - 2.dp.toPx(), 2.dp.toPx())
                                lineTo(4.dp.toPx(), size.height * 0.5f)
                                lineTo(size.width - 2.dp.toPx(), size.height - 2.dp.toPx())
                                close()
                            }
                            drawPath(path, color)
                        }
                    }

                    // Primary Play/Pause Button (56dp prominent action)
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .clickable(role = Role.Button, onClickLabel = if (isPlaying) "Pause" else "Play", onClick = onTogglePlay)
                            .semantics { contentDescription = if (isPlaying) "Pause" else "Play" },
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(Modifier.size(24.dp)) {
                            val ink = RiffDesign.ink
                            if (isPlaying) {
                                drawRect(ink, Offset(size.width * 0.22f, size.height * 0.15f), Size(size.width * 0.2f, size.height * 0.7f))
                                drawRect(ink, Offset(size.width * 0.58f, size.height * 0.15f), Size(size.width * 0.2f, size.height * 0.7f))
                            } else {
                                val path = androidx.compose.ui.graphics.Path().apply {
                                    moveTo(size.width * 0.25f, size.height * 0.15f)
                                    lineTo(size.width * 0.85f, size.height * 0.5f)
                                    lineTo(size.width * 0.25f, size.height * 0.85f)
                                    close()
                                }
                                drawPath(path, ink)
                            }
                        }
                    }

                    // Next Track (44dp button)
                    DockIconButton(
                        description = "Next Track",
                        size = 44.dp,
                        onClick = onNext
                    ) { color ->
                        Canvas(Modifier.size(22.dp)) {
                            drawLine(color, Offset(size.width - 2.dp.toPx(), 2.dp.toPx()), Offset(size.width - 2.dp.toPx(), size.height - 2.dp.toPx()), strokeWidth = 2.5.dp.toPx())
                            val path = androidx.compose.ui.graphics.Path().apply {
                                moveTo(2.dp.toPx(), 2.dp.toPx())
                                lineTo(size.width - 4.dp.toPx(), size.height * 0.5f)
                                lineTo(2.dp.toPx(), size.height - 2.dp.toPx())
                                close()
                            }
                            drawPath(path, color)
                        }
                    }

                    // Repeat Mode
                    DockIconButton(
                        description = "Repeat: $repeatMode",
                        onClick = onToggleRepeat
                    ) { color ->
                        Canvas(Modifier.size(18.dp)) {
                            val activeColor = if (repeatMode != RepeatMode.Off) RiffDesign.pink else color
                            drawRoundRect(activeColor, Offset(1.dp.toPx(), 2.dp.toPx()), Size(size.width - 2.dp.toPx(), size.height - 4.dp.toPx()), cornerRadius = CornerRadius(4.dp.toPx()), style = Stroke(1.8.dp.toPx()))
                            if (repeatMode == RepeatMode.One) {
                                drawCircle(RiffDesign.pink, radius = 2.5.dp.toPx(), center = Offset(size.width * 0.5f, size.height * 0.5f))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                // Timeline Seek Bar (click-to-seek, drag cancellation, keyboard increments, and accessibility)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Label(formatDuration(positionMs), font, RiffDesign.muted, size = 12)
                    Spacer(Modifier.width(10.dp))

                    var isDraggingSeek by remember { mutableStateOf(false) }
                    var dragProgress by remember { mutableFloatStateOf(0f) }
                    var isSeekFocused by remember { mutableStateOf(false) }

                    val progressFraction = if (isDraggingSeek) dragProgress
                    else (positionMs.toFloat() / currentTrack.durationMs.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(24.dp)
                            .onFocusChanged { isSeekFocused = it.isFocused }
                            .focusable()
                            .semantics {
                                progressBarRangeInfo = ProgressBarRangeInfo(
                                    current = positionMs.toFloat(),
                                    range = 0f..currentTrack.durationMs.toFloat()
                                )
                                contentDescription = "Playback seek slider. Current position: ${formatDuration(positionMs)} of ${formatDuration(currentTrack.durationMs)}. Use Left and Right arrow keys to seek."
                            }
                            .onKeyEvent { keyEvent ->
                                if (keyEvent.type == KeyEventType.KeyDown) {
                                    val stepMs = if (keyEvent.isShiftPressed) 15_000L else 5_000L
                                    when (keyEvent.key) {
                                        Key.DirectionLeft -> {
                                            onSeek((positionMs - stepMs).coerceAtLeast(0L))
                                            true
                                        }
                                        Key.DirectionRight -> {
                                            onSeek((positionMs + stepMs).coerceAtMost(currentTrack.durationMs))
                                            true
                                        }
                                        Key.MoveHome -> {
                                            onSeek(0L)
                                            true
                                        }
                                        Key.MoveEnd -> {
                                            onSeek(currentTrack.durationMs)
                                            true
                                        }
                                        else -> false
                                    }
                                } else false
                            }
                            .pointerInput(currentTrack.durationMs) {
                                detectTapGestures { offset ->
                                    val frac = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                    onSeek((frac * currentTrack.durationMs).toLong())
                                }
                            }
                            .pointerInput(currentTrack.durationMs) {
                                detectHorizontalDragGestures(
                                    onDragStart = { offset ->
                                        isDraggingSeek = true
                                        dragProgress = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                    },
                                    onDragEnd = {
                                        isDraggingSeek = false
                                        val targetMs = (dragProgress * currentTrack.durationMs).toLong()
                                        onSeek(targetMs)
                                    },
                                    onDragCancel = { isDraggingSeek = false },
                                    onHorizontalDrag = { change, _ ->
                                        dragProgress = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                    }
                                )
                            }
                            .then(
                                if (isSeekFocused) Modifier.border(1.dp, RiffDesign.pink, RoundedCornerShape(4.dp))
                                else Modifier
                            ),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Canvas(Modifier.fillMaxWidth().height(if (isDraggingSeek) 8.dp else 4.dp)) {
                            val corner = CornerRadius(size.height / 2)
                            drawRoundRect(Color.White.copy(alpha = 0.15f), cornerRadius = corner)
                            drawRoundRect(
                                color = if (isDraggingSeek) RiffDesign.pink else Color.White,
                                size = Size(size.width * progressFraction, size.height),
                                cornerRadius = corner
                            )
                        }
                    }

                    Spacer(Modifier.width(10.dp))
                    Label(formatDuration(currentTrack.durationMs), font, RiffDesign.muted, size = 12)
                }
            }

            Spacer(Modifier.width(16.dp))

            // Right block: Volume & Inspector Toggles (width constrained for 800dp-1920dp)
            Row(
                modifier = Modifier.widthIn(min = 180.dp, max = 260.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                // Volume Icon
                Canvas(Modifier.size(18.dp)) {
                    val color = RiffDesign.muted
                    val stroke = 1.6.dp.toPx()
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(0f, size.height * 0.35f)
                        lineTo(size.width * 0.3f, size.height * 0.35f)
                        lineTo(size.width * 0.6f, size.height * 0.15f)
                        lineTo(size.width * 0.6f, size.height * 0.85f)
                        lineTo(size.width * 0.3f, size.height * 0.65f)
                        lineTo(0f, size.height * 0.65f)
                        close()
                    }
                    drawPath(path, color)
                    if (volume > 0.05f) {
                        drawArc(color, -45f, 90f, false, topLeft = Offset(size.width * 0.4f, size.height * 0.25f), size = Size(size.width * 0.5f, size.height * 0.5f), style = Stroke(stroke))
                    }
                }

                Spacer(Modifier.width(8.dp))

                // Volume Slider (click, drag, wheel-scoped, keyboard a11y)
                var isDraggingVol by remember { mutableStateOf(false) }
                var isVolFocused by remember { mutableStateOf(false) }

                Box(
                    modifier = Modifier
                        .width(72.dp)
                        .height(24.dp)
                        .onFocusChanged { isVolFocused = it.isFocused }
                        .focusable()
                        .semantics {
                            progressBarRangeInfo = ProgressBarRangeInfo(
                                current = volume,
                                range = 0f..1f
                            )
                            contentDescription = "Volume slider. Current volume: ${(volume * 100).roundToInt()} percent. Use Up and Down or Left and Right arrows to adjust."
                        }
                        .onKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown) {
                                val step = if (keyEvent.isShiftPressed) 0.10f else 0.05f
                                when (keyEvent.key) {
                                    Key.DirectionLeft, Key.DirectionDown -> {
                                        onVolumeChange((volume - step).coerceIn(0f, 1f))
                                        true
                                    }
                                    Key.DirectionRight, Key.DirectionUp -> {
                                        onVolumeChange((volume + step).coerceIn(0f, 1f))
                                        true
                                    }
                                    Key.M -> {
                                        onVolumeChange(if (volume > 0.01f) 0f else 0.8f)
                                        true
                                    }
                                    else -> false
                                }
                            } else false
                        }
                        .onPointerEvent(PointerEventType.Scroll) { event ->
                            val scrollDelta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                            if (scrollDelta != 0f) {
                                val delta = if (scrollDelta < 0f) 0.04f else -0.04f
                                onVolumeChange((volume + delta).coerceIn(0f, 1f))
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                onVolumeChange((offset.x / size.width.toFloat()).coerceIn(0f, 1f))
                            }
                        }
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragStart = { offset ->
                                    isDraggingVol = true
                                    onVolumeChange((offset.x / size.width.toFloat()).coerceIn(0f, 1f))
                                },
                                onDragEnd = { isDraggingVol = false },
                                onDragCancel = { isDraggingVol = false },
                                onHorizontalDrag = { change, _ ->
                                    onVolumeChange((change.position.x / size.width.toFloat()).coerceIn(0f, 1f))
                                }
                            )
                        }
                        .then(
                            if (isVolFocused) Modifier.border(1.dp, RiffDesign.pink, RoundedCornerShape(4.dp))
                            else Modifier
                        ),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Canvas(Modifier.fillMaxWidth().height(if (isDraggingVol) 6.dp else 3.dp)) {
                        val corner = CornerRadius(size.height / 2)
                        drawRoundRect(Color.White.copy(alpha = 0.15f), cornerRadius = corner)
                        drawRoundRect(
                            color = if (isDraggingVol) RiffDesign.pink else Color.White,
                            size = Size(size.width * volume, size.height),
                            cornerRadius = corner
                        )
                    }
                }

                Spacer(Modifier.width(16.dp))

                // Queue Toggle Button
                val isQueueOpen = inspectorTab == InspectorTab.Queue
                DockIconButton(
                    description = "Queue",
                    onClick = { onToggleInspector(InspectorTab.Queue) }
                ) { color ->
                    val activeColor = if (isQueueOpen) RiffDesign.pink else color
                    Canvas(Modifier.size(18.dp)) {
                        drawLine(activeColor, Offset(0f, size.height * 0.25f), Offset(size.width, size.height * 0.25f), strokeWidth = 1.8.dp.toPx())
                        drawLine(activeColor, Offset(0f, size.height * 0.55f), Offset(size.width, size.height * 0.55f), strokeWidth = 1.8.dp.toPx())
                        drawLine(activeColor, Offset(0f, size.height * 0.85f), Offset(size.width * 0.65f, size.height * 0.85f), strokeWidth = 1.8.dp.toPx())
                    }
                }

                Spacer(Modifier.width(8.dp))

                // Lyrics Toggle Button
                val isLyricsOpen = inspectorTab == InspectorTab.Lyrics
                DockIconButton(
                    description = "Lyrics",
                    onClick = { onToggleInspector(InspectorTab.Lyrics) }
                ) { color ->
                    val activeColor = if (isLyricsOpen) RiffDesign.pink else color
                    Canvas(Modifier.size(18.dp)) {
                        val stroke = 1.6.dp.toPx()
                        drawRoundRect(activeColor, Offset(1.dp.toPx(), 1.dp.toPx()), Size(size.width - 2.dp.toPx(), size.height - 2.dp.toPx()), cornerRadius = CornerRadius(4.dp.toPx()), style = Stroke(stroke))
                        drawLine(activeColor, Offset(size.width * 0.25f, size.height * 0.35f), Offset(size.width * 0.75f, size.height * 0.35f), strokeWidth = stroke)
                        drawLine(activeColor, Offset(size.width * 0.25f, size.height * 0.55f), Offset(size.width * 0.65f, size.height * 0.55f), strokeWidth = stroke)
                        drawLine(activeColor, Offset(size.width * 0.25f, size.height * 0.75f), Offset(size.width * 0.5f, size.height * 0.75f), strokeWidth = stroke)
                    }
                }

                Spacer(Modifier.width(8.dp))

                // Mini-Player Toggle Button
                DockIconButton(
                    description = "Open Mini-Player",
                    onClick = onOpenMiniPlayer
                ) { color ->
                    Canvas(Modifier.size(18.dp)) {
                        val stroke = 1.6.dp.toPx()
                        drawRoundRect(color, Offset(1.dp.toPx(), 3.dp.toPx()), Size(size.width - 2.dp.toPx(), size.height - 6.dp.toPx()), cornerRadius = CornerRadius(3.dp.toPx()), style = Stroke(stroke))
                        drawRect(RiffDesign.pink, Offset(size.width * 0.55f, size.height * 0.45f), Size(size.width * 0.35f, size.height * 0.35f))
                    }
                }
            }
        }
    }
}

@Composable
private fun DockIconButton(
    description: String,
    size: androidx.compose.ui.unit.Dp = 36.dp,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit
) {
    var isHovered by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description }
            .then(
                if (isFocused) Modifier.border(1.dp, RiffDesign.pink, CircleShape)
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        val color = when {
            isHovered || isFocused -> Color.White
            else -> RiffDesign.muted
        }
        icon(color)
    }
}
