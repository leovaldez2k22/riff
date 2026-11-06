@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.musicglass.desktop

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Inspector pane providing Queue management (pointer dragging, revision safety,
 * Alt+Up/Down, multiselect, context menus, Undo) and real-time synchronized karaoke Lyrics.
 * Conforms to M2 workspace ergonomics specification.
 */
@Composable
internal fun InspectorPane(
    activeTab: InspectorTab,
    currentTrack: SongItem,
    queue: List<SongItem>,
    queueEntries: List<QueueEntry> = remember(queue) { queue.map { QueueEntry(song = it) } },
    queueRevision: Long = 0L,
    currentEntryId: String? = null,
    positionMs: Long,
    font: FontFamily,
    lastRemovedItem: Pair<Int, QueueEntry>? = null,
    onSelectTab: (InspectorTab) -> Unit,
    onClose: () -> Unit,
    onPlayTrack: (SongItem) -> Unit,
    onPlayQueueEntry: (String,Long) -> Unit = { id,_ -> queueEntries.firstOrNull{it.queueId==id}?.let{onPlayTrack(it.song)} },
    onReorder: (from: Int, to: Int) -> Unit = { _, _ -> },
    onReorderEntry: (entryId: String, moveUp: Boolean, expectedRevision: Long) -> Unit = { _, _, _ -> },
    onMoveEntryToPosition: (entryId: String, targetIndex: Int, expectedRevision: Long) -> Unit = { _, _, _ -> },
    onRemove: (String) -> Unit = {},
    onRemoveEntry: (String) -> Unit = { id -> onRemove(id) },
    onUndoRemove: () -> Unit = {},
    onOpenAlbum: (String) -> Unit = {},
    onOpenArtist: (String) -> Unit = {},
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val backdrop = rememberRiffBackdrop()

    Column(
        modifier = modifier
            .fillMaxHeight()
            .riffBackdropSource(backdrop)
            .background(RiffDesign.raised)
            .border(1.dp, Color.White.copy(alpha = 0.08f))
            .padding(16.dp)
    ) {
        // Inspector Header: Tabs + Close Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Tab Pill Selector (Queue vs Lyrics)
            Row(
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                InspectorTabButton(
                    label = "Queue",
                    isSelected = activeTab == InspectorTab.Queue,
                    font = font,
                    onClick = { onSelectTab(InspectorTab.Queue) }
                )
                Spacer(Modifier.width(4.dp))
                InspectorTabButton(
                    label = "Lyrics",
                    isSelected = activeTab == InspectorTab.Lyrics,
                    font = font,
                    onClick = { onSelectTab(InspectorTab.Lyrics) }
                )
            }

            // Close Inspector Button
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = "Close Inspector", onClick = onClose)
                    .semantics { contentDescription = "Close Inspector" },
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.size(12.dp)) {
                    val stroke = 1.6.dp.toPx()
                    val ink = RiffDesign.muted
                    drawLine(ink, Offset(0f, 0f), Offset(size.width, size.height), strokeWidth = stroke)
                    drawLine(ink, Offset(size.width, 0f), Offset(0f, size.height), strokeWidth = stroke)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Content Area
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (activeTab) {
                InspectorTab.Queue -> {
                    QueueView(
                        currentTrack = currentTrack,
                        queueEntries = queueEntries,
                        queueRevision = queueRevision,
                        currentEntryId = currentEntryId,
                        lastRemovedItem = lastRemovedItem,
                        font = font,
                        onPlayQueueEntry = onPlayQueueEntry,
                        onReorder = onReorder,
                        onReorderEntry = onReorderEntry,
                        onMoveEntryToPosition = onMoveEntryToPosition,
                        onRemoveEntry = onRemoveEntry,
                        onUndoRemove = onUndoRemove,
                        onOpenAlbum = onOpenAlbum,
                        onOpenArtist = onOpenArtist
                    )
                }
                InspectorTab.Lyrics -> {
                    LyricsView(
                        currentTrack = currentTrack,
                        positionMs = positionMs,
                        font = font,
                        onSeek = onSeek
                    )
                }
                InspectorTab.None -> {}
            }
        }
    }
}

@Composable
private fun InspectorTabButton(
    label: String,
    isSelected: Boolean,
    font: FontFamily,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (isSelected) Color.White.copy(alpha = 0.15f) else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Label(
            text = label,
            font = font,
            color = if (isSelected) Color.White else RiffDesign.muted,
            size = 14
        )
    }
}

@Composable
private fun QueueView(
    currentTrack: SongItem,
    queueEntries: List<QueueEntry>,
    queueRevision: Long,
    currentEntryId: String?,
    lastRemovedItem: Pair<Int, QueueEntry>?,
    font: FontFamily,
    onPlayQueueEntry: (String,Long) -> Unit,
    onReorder: (from: Int, to: Int) -> Unit,
    onReorderEntry: (entryId: String, moveUp: Boolean, expectedRevision: Long) -> Unit,
    onMoveEntryToPosition: (entryId: String, targetIndex: Int, expectedRevision: Long) -> Unit,
    onRemoveEntry: (String) -> Unit,
    onUndoRemove: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit
) {
    val projection=remember(queueEntries,currentEntryId){queueInspectorProjection(queueEntries,currentEntryId)}
    var selectedQueueIds by remember { mutableStateOf(setOf<String>()) }
    var contextMenuEntry by remember { mutableStateOf<QueueEntry?>(null) }
    var draggingEntryId by remember { mutableStateOf<String?>(null) }
    var dragOffsetPx by remember { mutableStateOf(0f) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Undo Banner if an item was recently removed
        if (lastRemovedItem != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .background(Color(0xFF331422), RoundedCornerShape(8.dp))
                    .border(1.dp, RiffDesign.pink.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Label("Track removed from queue", font, Color.White, size = 13)
                Box(
                    modifier = Modifier
                        .clickable(role = Role.Button, onClick = onUndoRemove)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Label("Undo", font, RiffDesign.pink, size = 13)
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Now Playing Section
            item {
                Label("NOW PLAYING", font, RiffDesign.muted, size = 12)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .trackArtwork(currentTrack)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Label(currentTrack.title, font, Color.White, size = 15)
                        Spacer(Modifier.height(2.dp))
                        Label(currentTrack.artist, font, RiffDesign.muted, size = 13)
                    }
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(RiffDesign.pink, CircleShape)
                    )
                }
                Spacer(Modifier.height(14.dp))
                val upNextCount = projection.upNextCount
                Label("UP NEXT ($upNextCount)", font, RiffDesign.muted, size = 12)
            }

            // Up Next items with pointer dragging, keyboard reordering, multiselect, and context menu
            itemsIndexed(
                items = projection.rows,
                key = { _, row -> row.entry.queueId }
            ) { displayIndex, row ->
                val index=row.queueIndex
                val entry=row.entry
                if(row.earlier && (displayIndex==0 || !projection.rows[displayIndex-1].earlier))Label("EARLIER IN QUEUE (${projection.rows.size-projection.upNextCount})",font,RiffDesign.muted,size=12)
                val track = entry.song
                val isSelected = entry.queueId in selectedQueueIds
                val isCurrentlyDragged = entry.queueId == draggingEntryId
                var isRowFocused by remember { mutableStateOf(false) }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset {
                            if (isCurrentlyDragged) IntOffset(0, dragOffsetPx.roundToInt())
                            else IntOffset.Zero
                        }
                        .zIndex(if (isCurrentlyDragged) 10f else 1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            when {
                                isCurrentlyDragged -> Color.White.copy(alpha = 0.25f)
                                isSelected -> Color.White.copy(alpha = 0.16f)
                                isRowFocused -> Color.White.copy(alpha = 0.08f)
                                else -> Color.Transparent
                            }
                        )
                        .border(
                            width = if (isCurrentlyDragged) 1.5.dp else 0.dp,
                            color = if (isCurrentlyDragged) RiffDesign.pink else Color.Transparent,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .onFocusChanged { isRowFocused = it.isFocused }
                        .focusable()
                        .onKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown) {
                                when {
                                    // Alt+Up revision-safe reorder
                                    keyEvent.isAltPressed && keyEvent.key == Key.DirectionUp -> {
                                        if (index > 0) {
                                            onReorderEntry(entry.queueId, true, queueRevision)
                                            true
                                        } else false
                                    }
                                    // Alt+Down revision-safe reorder
                                    keyEvent.isAltPressed && keyEvent.key == Key.DirectionDown -> {
                                        if (index < queueEntries.size - 1) {
                                            onReorderEntry(entry.queueId, false, queueRevision)
                                            true
                                        } else false
                                    }
                                    // Shift+F10 context menu
                                    keyEvent.isShiftPressed && keyEvent.key == Key.F10 -> {
                                        contextMenuEntry = entry
                                        true
                                    }
                                    // Delete key removes entry
                                    keyEvent.key == Key.Delete -> {
                                        if (selectedQueueIds.contains(entry.queueId) && selectedQueueIds.size > 1) {
                                            selectedQueueIds.forEach { onRemoveEntry(it) }
                                            selectedQueueIds = emptySet()
                                        } else {
                                            onRemoveEntry(entry.queueId)
                                        }
                                        true
                                    }
                                    keyEvent.key == Key.Enter -> {
                                        onPlayQueueEntry(entry.queueId,queueRevision)
                                        true
                                    }
                                    else -> false
                                }
                            } else false
                        }
                        .pointerInput(entry.queueId) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type == PointerEventType.Press &&
                                        event.button == PointerButton.Secondary) {
                                        contextMenuEntry = entry
                                    }
                                }
                            }
                        }
                        .pointerInput(entry.queueId,queueRevision) {
                            detectTapGestures(
                                onTap = {
                                    selectedQueueIds = if (entry.queueId in selectedQueueIds) emptySet() else setOf(entry.queueId)
                                },
                                onDoubleTap = {
                                    onPlayQueueEntry(entry.queueId,queueRevision)
                                }
                            )
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Drag Grip Handle with Pointer Drag Gestures
                        Box(
                            modifier = Modifier
                                .size(20.dp, 32.dp)
                                .pointerInput(entry.queueId, queueRevision, currentEntryId) {
                                    detectVerticalDragGestures(
                                        onDragStart = {
                                            draggingEntryId = entry.queueId
                                            dragOffsetPx = 0f
                                        },
                                        onDragEnd = {
                                            val rowHeightPx = 48.dp.toPx()
                                            val deltaIndex = (dragOffsetPx / rowHeightPx).roundToInt()
                                            if (deltaIndex != 0) {
                                                val targetIndex = projection.rows[(displayIndex + deltaIndex).coerceIn(projection.rows.indices)].queueIndex
                                                if (targetIndex != index) {
                                                    onMoveEntryToPosition(entry.queueId, targetIndex, queueRevision)
                                                }
                                            }
                                            draggingEntryId = null
                                            dragOffsetPx = 0f
                                        },
                                        onDragCancel = {
                                            draggingEntryId = null
                                            dragOffsetPx = 0f
                                        },
                                        onVerticalDrag = { change, dragAmount ->
                                            change.consume()
                                            dragOffsetPx += dragAmount
                                        }
                                    )
                                }
                                .padding(end = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(Modifier.size(10.dp, 16.dp)) {
                                val dotRadius = 1.2.dp.toPx()
                                val color = if (isCurrentlyDragged) RiffDesign.pink else RiffDesign.muted.copy(alpha = 0.6f)
                                drawCircle(color, dotRadius, Offset(2.dp.toPx(), 4.dp.toPx()))
                                drawCircle(color, dotRadius, Offset(6.dp.toPx(), 4.dp.toPx()))
                                drawCircle(color, dotRadius, Offset(2.dp.toPx(), 8.dp.toPx()))
                                drawCircle(color, dotRadius, Offset(6.dp.toPx(), 8.dp.toPx()))
                                drawCircle(color, dotRadius, Offset(2.dp.toPx(), 12.dp.toPx()))
                                drawCircle(color, dotRadius, Offset(6.dp.toPx(), 12.dp.toPx()))
                            }
                        }

                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .trackArtwork(track)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Label(track.title, font, Color.White, size = 14)
                            Spacer(Modifier.height(2.dp))
                            Label(track.artist, font, RiffDesign.muted, size = 12)
                        }

                        // Move Up Action (revision-safe)
                        if (index > 0) {
                            ActionMiniIcon("Move Up (Alt+Up)", onClick = {
                                onReorderEntry(entry.queueId, true, queueRevision)
                            }) {
                                drawLine(it, Offset(0f, 7.dp.toPx()), Offset(5.dp.toPx(), 2.dp.toPx()), strokeWidth = 1.4.dp.toPx())
                                drawLine(it, Offset(10.dp.toPx(), 7.dp.toPx()), Offset(5.dp.toPx(), 2.dp.toPx()), strokeWidth = 1.4.dp.toPx())
                            }
                        }

                        // Move Down Action (revision-safe)
                        if (index < queueEntries.size - 1) {
                            ActionMiniIcon("Move Down (Alt+Down)", onClick = {
                                onReorderEntry(entry.queueId, false, queueRevision)
                            }) {
                                drawLine(it, Offset(0f, 3.dp.toPx()), Offset(5.dp.toPx(), 8.dp.toPx()), strokeWidth = 1.4.dp.toPx())
                                drawLine(it, Offset(10.dp.toPx(), 3.dp.toPx()), Offset(5.dp.toPx(), 8.dp.toPx()), strokeWidth = 1.4.dp.toPx())
                            }
                        }

                        // Overflow / Context Menu Action Button
                        ActionMiniIcon("More Actions", onClick = { contextMenuEntry = entry }) {
                            val dotRad = 1.2.dp.toPx()
                            drawCircle(it, dotRad, Offset(5.dp.toPx(), 3.dp.toPx()))
                            drawCircle(it, dotRad, Offset(5.dp.toPx(), 7.dp.toPx()))
                            drawCircle(it, dotRad, Offset(5.dp.toPx(), 11.dp.toPx()))
                        }

                        // Remove Action
                        ActionMiniIcon("Remove from queue", onClick = { onRemoveEntry(entry.queueId) }) {
                            drawLine(it, Offset(1.dp.toPx(), 1.dp.toPx()), Offset(9.dp.toPx(), 9.dp.toPx()), strokeWidth = 1.4.dp.toPx())
                            drawLine(it, Offset(9.dp.toPx(), 1.dp.toPx()), Offset(1.dp.toPx(), 9.dp.toPx()), strokeWidth = 1.4.dp.toPx())
                        }
                    }
                }
            }
        }

        // Themed Glass Context Menu
        contextMenuEntry?.let { entry ->
            val track = entry.song
            Popup(
                alignment = Alignment.Center,
                onDismissRequest = { contextMenuEntry = null }
            ) {
                Column(
                    modifier = Modifier
                        .width(220.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF2A2C31).copy(alpha = 0.95f))
                        .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                        .padding(8.dp)
                ) {
                    ContextMenuItem("Play Now", font) {
                        onPlayQueueEntry(entry.queueId,queueRevision)
                        contextMenuEntry = null
                    }
                    ContextMenuItem("Go to Album", font) {
                        onOpenAlbum(track.album)
                        contextMenuEntry = null
                    }
                    ContextMenuItem("Go to Artist", font) {
                        onOpenArtist(track.artist)
                        contextMenuEntry = null
                    }
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.10f)).padding(vertical = 4.dp))
                    ContextMenuItem("Remove from Queue", font, color = RiffDesign.pink) {
                        onRemoveEntry(entry.queueId)
                        contextMenuEntry = null
                    }
                    if (selectedQueueIds.size > 1) {
                        ContextMenuItem("Remove Selected (${selectedQueueIds.size})", font, color = RiffDesign.pink) {
                            selectedQueueIds.forEach { onRemoveEntry(it) }
                            selectedQueueIds = emptySet()
                            contextMenuEntry = null
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextMenuItem(
    label: String,
    font: FontFamily,
    color: Color = Color.White,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Label(label, font, color, size = 13)
    }
}

@Composable
private fun ActionMiniIcon(
    action: String,
    onClick: () -> Unit,
    drawIcon: androidx.compose.ui.graphics.drawscope.DrawScope.(Color) -> Unit
) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = action, onClick = onClick)
            .semantics { contentDescription = action },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(10.dp, 12.dp)) {
            drawIcon(RiffDesign.muted)
        }
    }
}

@Composable
internal fun LyricsView(
    currentTrack: SongItem,
    positionMs: Long,
    font: FontFamily,
    onSeek: (Long) -> Unit
) {
    val host=LocalDesktopHost.current
    val hostState=host?.state?.collectAsState()?.value
    LaunchedEffect(currentTrack.id,currentTrack.scope){host?.dispatch(DesktopCommand.LoadLyrics)}
    var frameNanos by remember{mutableLongStateOf(System.nanoTime())}
    LaunchedEffect(host,currentTrack.id){while(true)withFrameNanos{frameNanos=System.nanoTime()}}
    val position:()->Long={
        val snapshot=host?.snapshot
        LyricsPresentationClock.position(host?.clockAnchor?.value,snapshot?.currentEntryId,snapshot?.isPlaying==true&&!snapshot.isBuffering,frameNanos,snapshot?.playbackPositionMs?:positionMs,currentTrack.durationMs)+(hostState?.lyricsState as? LyricsLoadState.Loaded)?.offsetMs.orZero()
    }
    val loaded=(hostState?.lyricsState as? LyricsLoadState.Loaded)?.takeIf{hostState.lyricsTrackId==currentTrack.id}
    val lyrics=remember(loaded,currentTrack.lyrics){loaded?.document?.let{if(it.timed)withInstrumentalGaps(it.lines)else it.lines}?:currentTrack.lyrics}
    val listState=rememberLazyListState()
    val coroutineScope=rememberCoroutineScope()
    var isUserScrolling by remember(currentTrack.id){mutableStateOf(false)}
    val latestPosition by rememberUpdatedState(position)
    val activeIndex by remember(lyrics,loaded?.document?.timed){derivedStateOf{if(loaded?.document?.timed==false)-1 else activeLyricLine(lyrics,latestPosition())}}
    LaunchedEffect(activeIndex,isUserScrolling){if(!isUserScrolling&&activeIndex in lyrics.indices){if(LocalReducedMotionValue.reduced(host))listState.scrollToItem((activeIndex-1).coerceAtLeast(0))else listState.animateScrollToItem((activeIndex-1).coerceAtLeast(0))}}
    Column(Modifier.fillMaxSize()){
        when(val status=hostState?.lyricsState){
            LyricsLoadState.Loading->Label("Loading lyrics…",font,RiffDesign.muted)
            LyricsLoadState.NoLyrics->Label("No lyrics found. Put a matching .lrc, .ttml or .txt beside a local audio file.",font,RiffDesign.muted)
            is LyricsLoadState.Failed->Label(status.message,font,RiffDesign.muted)
            is LyricsLoadState.Ambiguous->{
                Label("Choose the matching recording (${status.count})",font,RiffDesign.muted)
                Column(Modifier.heightIn(max=220.dp).verticalScroll(rememberScrollState())){status.candidates.forEach{candidate->
                    DataAction("${candidate.title} · ${candidate.artist} · ${formatDuration(candidate.durationMs)}",font){host?.dispatch(DesktopCommand.SelectLyricsCandidate(candidate.id))}
                }}
            }
            else->Unit
        }
        if(loaded!=null){
            Label(loaded.document.source+if(loaded.cached)" · cached"else"",font,RiffDesign.muted,size=12)
            if(loaded.document.instrumental)Label("This recording has no sung lyrics",font,RiffDesign.muted)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                DataAction("−0.1s",font){host?.dispatch(DesktopCommand.SetLyricsPreferences((loaded.offsetMs-100).coerceAtLeast(-5000),loaded.romanized,loaded.romanMode))}
                Label("${loaded.offsetMs}ms",font,RiffDesign.muted,size=12)
                DataAction("+0.1s",font){host?.dispatch(DesktopCommand.SetLyricsPreferences((loaded.offsetMs+100).coerceAtMost(5000),loaded.romanized,loaded.romanMode))}
                if(lyrics.any{it.romanized!=null})DataAction("Romanized",font){host?.dispatch(DesktopCommand.SetLyricsPreferences(loaded.offsetMs,!loaded.romanized,loaded.romanMode))}
            }
        }
        LazyColumn(state=listState,modifier=Modifier.weight(1f).fillMaxWidth().pointerInput(currentTrack.id){awaitPointerEventScope{while(true){val event=awaitPointerEvent();if(event.type==PointerEventType.Scroll||event.changes.any{it.pressed})isUserScrolling=true}}},contentPadding=PaddingValues(vertical=32.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            itemsIndexed(lyrics,key={index,line->"$index-${line.startMs}"}){index,line->
                val active=index==activeIndex
                val motion=LocalMotionPolicy.current
                Column(Modifier.fillMaxWidth().clickable(enabled=line.startMs>=0){onSeek((line.startMs-(loaded?.offsetMs?:0)).coerceAtLeast(0))}){
                    if(line.gap){if(active)Canvas(Modifier.height(40.dp).fillMaxWidth().semantics{contentDescription="Instrumental"}){val p=((position()-line.startMs).toFloat()/(line.endMs-line.startMs).coerceAtLeast(1)).coerceIn(0f,1f);repeat(3){dot->drawCircle(Color.White.copy(alpha=.25f+.75f*lyricGapDotFill(p,dot)),6.5.dp.toPx(),Offset((6.5f+18*dot).dp.toPx(),20.dp.toPx()))}}}
                    else {
                        val mode=loaded?.romanMode?:LyricsRomanMode.Original
                        if(mode!=LyricsRomanMode.Romanized||line.romanized==null){
                            if(loaded?.document?.timed==false)Label(line.text,font,Color.White,size=18)
                            else KaraokeLine(line,active,line.backing?.let{position() in it.startMs..it.endMs}==true,position,motion,"desktop-backing-$index")
                        }
                        if(mode!=LyricsRomanMode.Original&&line.romanized!=null){
                            Label(line.romanized,font,if(active)Color.White else RiffDesign.muted,size=if(mode==LyricsRomanMode.Both)14 else 18)
                            if(line.romanizedAuto)Label("Auto",font,RiffDesign.muted,size=11)
                        }
                    }
                }
            }
        }
        if(isUserScrolling)DataAction("Resume follow",font){isUserScrolling=false}
    }
}
private fun Long?.orZero()=this?:0L
private object LocalReducedMotionValue{fun reduced(host:DesktopHost?)=host?.snapshot?.reducedMotion==true}
