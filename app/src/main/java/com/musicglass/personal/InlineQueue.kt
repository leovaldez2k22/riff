package com.musicglass.personal

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil3.compose.AsyncImage

private class QueueCoordinates {var value:LayoutCoordinates?=null}
/** How long a dropped row waits for the player's list to agree before the list on screen follows the player anyway. */
private const val QUEUE_MOVE_WAIT_MS=1_500L

/** What a queue row offers without dragging or swiping (Q13). A move that would change nothing is not offered. */
internal enum class QueueRowAction(private val labelRes:Int) {MoveUp(R.string.move_up),MoveDown(R.string.move_down),Remove(R.string.remove_from_queue);val label:String get()=string(labelRes)}
internal fun queueRowActions(index:Int,current:Int,size:Int):List<QueueRowAction> = listOfNotNull(
    QueueRowAction.MoveUp.takeIf {index>0},QueueRowAction.MoveDown.takeIf {index<size-1},QueueRowAction.Remove)
/** Q18-096: the last of the [upNext] songs is on screen, so more are wanted. */
internal fun queueWantsMore(lastVisible:Int,upNext:Int)=upNext>0 && lastVisible>=upNext-1

internal fun queuePositionLabel(index:Int,current:Int,size:Int)=if(index==current) string(R.string.playing_now_x_of_x, index+1, size) else string(R.string.x_of_x, index+1, size)

@Composable internal fun InlineQueue(playback:PlaybackGateway,provider:YouTubeMusicProvider,
    onClose:()->Unit,onLike:()->Unit,onMore:()->Unit,artworkModifier:Modifier=Modifier,onMoreBounds:(IntRect)->Unit={},
    /** Feature 2: the player's backdrop, which the queue's buttons and toggles bend. */
    glass:RiffBackdrop?=null) {
    val state=playback.state
    val songTransition=rememberSongPresentationTransition(state)
    val motion=LocalMotionPolicy.current
    var history by remember {mutableStateOf(false)}
    val listState=rememberLazyListState()
    val listCoordinates=remember {QueueCoordinates()}
    LaunchedEffect(history) {listState.scrollToItem(0)}
    val scope=rememberCoroutineScope()
    val latestQueue by rememberUpdatedState(state.queue)
    var draggingKey by remember {mutableStateOf<String?>(null)}
    var fingerY by remember {mutableFloatStateOf(0f)}
    var grabY by remember {mutableFloatStateOf(0f)}
    var autoScroll by remember {mutableStateOf<Job?>(null)}
    fun rowKey(index:Int,item:QueueEntry)=item.queueId.ifBlank {"${item.mediaId}:${latestQueue.take(index).count {it.mediaId==item.mediaId}}"}
    // The order on screen during a drag. Sending each step to the player and waiting for the list to come back left the
    // row and the scroll position fighting: it doubled over its neighbours, and at the top the list jumped (owner's video, 5 Nov).
    var dragOrder by remember {mutableStateOf<List<IndexedValue<QueueEntry>>?>(null)}
    var landed by remember {mutableStateOf<Pair<String,Int>?>(null)}
    val visible=dragOrder?:state.queue.withIndex().filter {history || it.index>state.index}
    val latestVisible by rememberUpdatedState(visible)
    LaunchedEffect(landed) {
        val (key,to)=landed?:return@LaunchedEffect
        withTimeoutOrNull(QUEUE_MOVE_WAIT_MS) {snapshotFlow {latestQueue}.first {queue->queue.getOrNull(to)?.let {rowKey(to,it)}==key}}
        // The same rows in the same order, so the list stays where it is: left alone, it jumped on release (seen on the phone).
        val anchor=listState.firstVisibleItemIndex;val offset=listState.firstVisibleItemScrollOffset
        dragOrder=null;landed=null;listState.requestScrollToItem(anchor,offset)
    }
    var reshuffled by remember {mutableStateOf(false)}
    LaunchedEffect(state.queue) {if(reshuffled) {reshuffled=false;listState.requestScrollToItem(0)}}
    fun reorder() {
        val order=dragOrder?:return
        val target=listState.layoutInfo.visibleItemsInfo.minByOrNull {kotlin.math.abs(fingerY-(it.offset+it.size/2f))}?:return
        val from=order.indexOfFirst {rowKey(it.index,it.value)==draggingKey}
        // Dragging up works as dragging down does: the song never takes the first row on screen while the list can still scroll
        // up under it. Taking it pinned the list to the song, mostly above the top, and each step shifted the whole list and lost
        // the row under the finger (owner, 6 Nov: "scroll down some and then drag a song to the very top").
        val to=queueDragTarget(target.index,from,listState.firstVisibleItemIndex,listState.firstVisibleItemScrollOffset)
        if(from>=0 && to in order.indices && to!=from) {
            val anchor=listState.firstVisibleItemIndex;val offset=listState.firstVisibleItemScrollOffset
            dragOrder=order.toMutableList().apply {add(to,removeAt(from))}
            listState.requestScrollToItem(anchor,offset);uiHaptic(HapticCue.Selection)
        }
    }
    // Q18-096: scrolling to the last song asks for more recommendations; a collection is already whole.
    val radio=!history && state.queueMessage !in setOf("Album order","Collection order")
    LaunchedEffect(listState,radio) {
        if(!radio) return@LaunchedEffect
        snapshotFlow {queueWantsMore(listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?:-1,latestVisible.size) to latestQueue.size}
            .distinctUntilChanged().collect {(wants,_)->if(wants) playback.loadMoreQueue()}
    }
    fun finishDrag() {
        autoScroll?.cancel();autoScroll=null
        val key=draggingKey;val order=dragOrder
        val at=order?.indexOfFirst {rowKey(it.index,it.value)==key}?:-1
        val from=latestQueue.withIndex().indexOfFirst {rowKey(it.index,it.value)==key}
        val to=(if(history) 0 else state.index+1)+at
        if(at>=0 && from>=0 && from!=to && key!=null) {playback.moveQueueItem(from,to);landed=key to to} else dragOrder=null
        // A song dropped at the top edge stays in sight, not a row above it.
        if(at>=0 && at<=listState.firstVisibleItemIndex) listState.requestScrollToItem(at,0)
        draggingKey=null;uiHaptic(HapticCue.DragEnd)
    }
    DisposableEffect(Unit) {onDispose {autoScroll?.cancel()}}
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal=20.dp).testTag("inline_queue")) {
        // Q18-079: the header rows grow with large text rather than cut it off.
        Row(Modifier.fillMaxWidth().heightIn(min=62.dp),verticalAlignment=Alignment.CenterVertically) {
            // Keep the hit target outside the shared artwork layer: its animated overlay can
            // otherwise leave touch coordinates at the full-size hero's position.
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))
                .clickable(onClick=hapticClick(onClose)).semantics {contentDescription=string(R.string.back_to_player)}.testTag("queue_artwork_back")) {
                songTransition.SongContentFade(artworkModifier.fillMaxSize()) {song->
                    AsyncImage(song.artworkUrl,null,contentScale=ContentScale.Crop,modifier=Modifier.fillMaxSize())
                }
            }
            songTransition.SongContentFade(Modifier.weight(1f).padding(horizontal=12.dp)) {song->
            Column {
                Text(song.title,color=White,fontSize=15.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(playerArtistLabel(song.artist),color=White.copy(alpha=.6f),fontSize=12.sp,maxLines=1)
            }
            }
            val liked=provider.songLikes[state.mediaId.removePrefix("yt:")]==true
            IconButton(onLike,modifier=Modifier.testTag("queue_like").semantics {stateDescription=if(liked) string(R.string.liked) else string(R.string.not_liked)}) {Icon(if(liked) Icons.Rounded.Star else Icons.Rounded.StarBorder,string(R.string.like_on_youtube_music),tint=White)}
            IconButton(hapticClick(onMore),modifier=Modifier.testTag("queue_more").onGloballyPositioned {val r=it.boundsInWindow();onMoreBounds(IntRect(r.topLeft.round(),r.bottomRight.round()))}) {Icon(Icons.Rounded.MoreHoriz,string(R.string.song_options),tint=White)}
            IconButton(hapticClick(onClose),modifier=Modifier.testTag("queue_back")) {Icon(Icons.Rounded.ExpandMore,string(R.string.close_queue),tint=White)}
        }
        Row(Modifier.fillMaxWidth().heightIn(min=46.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            // Glass pills of their own rather than Material chips with glass around them, which drew the chip's
            // outline inside the glass (phone checks 29 Oct, bug 3).
            QueueModeButton(state.shuffle,true,Icons.Rounded.Shuffle,string(R.string.shuffle),glass,hapticClick(playback::toggleShuffle))
            QueueModeButton(state.repeat!=0,true,if(state.repeat==1) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,string(R.string.repeat),glass,hapticClick(playback::cycleRepeat))
            QueueReshuffleButton(state.queue.size>1,glass) {reshuffled=true;uiHaptic(HapticCue.Confirm);playback.reshuffleQueue()}
            Edition.QueueOrderAction(playback,glass,Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(top=6.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(string(R.string.continue_playing),color=White,fontWeight=FontWeight.SemiBold,fontSize=15.sp)
                Text(if(state.queueTitle.isNotBlank()) string(R.string.from_x, state.queueTitle) else if(state.album.isNotBlank()) string(R.string.from_x, state.album) else keyLabel(state.queueMessage).ifBlank {string(R.string.from_youtube_music)},color=White.copy(alpha=.6f),fontSize=11.sp,maxLines=1)
            }
            TextButton({uiHaptic(HapticCue.Selection);history=!history}) {Text(if(history) string(R.string.up_next) else string(R.string.history),color=White,fontSize=11.sp)}
        }
        // The list's own touch scrolling stays off during a drag and until the move lands: left on, the list ran on by about
        // the distance dragged when the song was let go, and the song left the screen (seen on the phone, 5 Nov).
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("queue_list").onGloballyPositioned {listCoordinates.value=it},state=listState,userScrollEnabled=dragOrder==null) {
            itemsIndexed(visible,key={_,e->rowKey(e.index,e.value)}) {_,entry->
                val index=entry.index;val item=entry.value;val key=rowKey(index,item)
                val latestIndex by rememberUpdatedState(index)
                val dragged=draggingKey==key
                val handleCoordinates=remember(key) {QueueCoordinates()}
                fun fingerInList(point:androidx.compose.ui.geometry.Offset):Float? {
                    val list=listCoordinates.value;val handle=handleCoordinates.value
                    return if(list?.isAttached==true && handle?.isAttached==true) list.localPositionOf(handle,point).y else null
                }
                var swipe by remember {mutableFloatStateOf(0f)}
                Row(Modifier.fillMaxWidth().height(54.dp).zIndex(if(dragged) 1f else 0f).then(if(motion.reduced || dragged) Modifier else Modifier.animateItem())
                    .graphicsLayer {val info=if(dragged) listState.layoutInfo.visibleItemsInfo.firstOrNull {it.key==key} else null;val view=listState.layoutInfo;translationY=if(info!=null) queueDraggedTop(fingerY-grabY,view.viewportStartOffset,view.viewportEndOffset,info.size)-info.offset else 0f;scaleX=if(dragged) 1.015f else 1f;scaleY=scaleX}
                    .background(if(dragged) White.copy(alpha=.1f) else Color.Transparent,RoundedCornerShape(8.dp))
                    .pointerInput(item.mediaId,index) {detectHorizontalDragGestures(onHorizontalDrag={change,x->val before=swipe;swipe+=x;if(before>= -70.dp.toPx() && swipe< -70.dp.toPx()) uiHaptic(HapticCue.Threshold);change.consume()},onDragCancel={swipe=0f},onDragEnd={if(swipe< -70.dp.toPx()) {playback.removeQueueItemWithUndo(latestIndex);uiHaptic(HapticCue.Confirm)};swipe=0f})}
                    .clickable { uiHaptic(); playback.seekQueueItem(index)}.semantics {
                        stateDescription=queuePositionLabel(index,state.index,state.queue.size)
                        customActions=queueRowActions(index,state.index,state.queue.size).map {action->CustomAccessibilityAction(action.label) {
                            when(action) {
                                QueueRowAction.MoveUp->playback.moveQueueItem(index,index-1)
                                QueueRowAction.MoveDown->playback.moveQueueItem(index,index+1)
                                QueueRowAction.Remove->playback.removeQueueItemWithUndo(index)
                            }
                            uiHaptic(if(action==QueueRowAction.Remove) HapticCue.Confirm else HapticCue.Selection);true
                        }}
                    },verticalAlignment=Alignment.CenterVertically) {
                    AsyncImage(item.artworkUrl,null,contentScale=ContentScale.Crop,modifier=Modifier.size(38.dp).clip(RoundedCornerShape(6.dp)))
                    Column(Modifier.weight(1f).padding(horizontal=10.dp)) {
                        Text(item.title,color=White,fontSize=13.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(playerArtistLabel(item.artist),color=White.copy(alpha=.6f),fontSize=11.sp,maxLines=1)
                    }
                    IconButton(onClick=hapticClick {playback.removeQueueItemWithUndo(latestIndex)},
                        modifier=Modifier.size(48.dp).testTag("queue_remove_$index")) {
                        Icon(Icons.Rounded.RemoveCircleOutline,string(R.string.remove_x_from_queue, item.title),tint=White.copy(alpha=.65f),modifier=Modifier.size(22.dp))
                    }
                    // Dragging is for touch only: a screen reader or a switch reorders with the row's own actions,
                    // so the handle is not a second thing to land on.
                    Icon(Icons.Rounded.DragHandle,null,tint=White.copy(alpha=.45f),modifier=Modifier.size(48.dp)
                         .clearAndSetSemantics {testTag="queue_drag_$index"}.onGloballyPositioned {handleCoordinates.value=it}.clickable {uiHaptic(HapticCue.Selection)}.pointerInput(key) {
                            detectVerticalDragGestures(onDragStart={point->
                                dragOrder=latestVisible;landed=null;draggingKey=key
                                val info=listState.layoutInfo.visibleItemsInfo.firstOrNull {it.key==key}
                                fingerY=fingerInList(point)?:((info?.offset?:0)+point.y+7.dp.toPx());grabY=fingerY-(info?.offset?:0)
                                uiHaptic(HapticCue.DragStart)
                                autoScroll=scope.launch {while(isActive) {
                                    val info=listState.layoutInfo;val edge=54.dp.toPx()
                                    val amount=queueEdgeScroll(fingerY,info.viewportStartOffset.toFloat(),info.viewportEndOffset.toFloat(),edge)
                                    if(amount!=0f) {listState.scrollBy(amount);reorder()};delay(16)
                                }}
                            },onDragCancel={finishDrag()},onDragEnd={finishDrag()},onVerticalDrag={change,amount->
                                change.consume();fingerInList(change.position)?.let {fingerY=it};reorder()
                            })
                        }.padding(4.dp))
                }
            }
            if(visible.isNotEmpty() && !history && state.queueLoading) item {Text(string(R.string.finding_more_music),color=White.copy(alpha=.6f),fontSize=RiffType.body,modifier=Modifier.padding(RiffSpace.rowGap).testTag("queue_loading_more"))}
            if(visible.isEmpty()) item {Text(if(state.queueLoading) string(R.string.finding_more_music) else string(R.string.you_re_at_the_end_of_this),color=White.copy(alpha=.6f),fontSize=13.sp,modifier=Modifier.padding(12.dp))}
        }
    }
}

/** The same glass pill, but something to press and not a mode: the whole queue again, around the song playing. */
@Composable private fun RowScope.QueueReshuffleButton(enabled:Boolean,glass:RiffBackdrop?,onClick:()->Unit) =
    QueueActionPill(enabled,glass,string(R.string.reshuffle_queue),Icons.Rounded.Autorenew,"queue_reshuffle",Modifier.weight(1f),onClick)

/** A glass pill in the queue's row of modes that does something once (Reshuffle; Riff+'s DJ order). */
@Composable internal fun QueueActionPill(enabled:Boolean,glass:RiffBackdrop?,label:String,icon:androidx.compose.ui.graphics.vector.ImageVector,tag:String,
    modifier:Modifier,onClick:()->Unit) {
    Box(modifier.height(RiffQueue.buttonHeight).riffGlass(GlassRole.Button,backdrop=glass).clip(RoundedCornerShape(50))
        .clickable(enabled=enabled,role=androidx.compose.ui.semantics.Role.Button,onClickLabel=label,onClick=onClick)
        .semantics {contentDescription=label}.testTag(tag),contentAlignment=Alignment.Center) {
        Icon(icon,null,tint=White.copy(alpha=if(enabled) 1f else .5f),modifier=Modifier.size(RiffQueue.buttonIcon))
    }
}

/** A queue mode (shuffle, repeat): one glass pill, pink while on, dimmed when it can't be changed. */
@Composable private fun RowScope.QueueModeButton(on:Boolean,enabled:Boolean,icon:androidx.compose.ui.graphics.vector.ImageVector,
    label:String,glass:RiffBackdrop?,onClick:()->Unit) {
    val shape=RoundedCornerShape(50)
    Box(Modifier.weight(1f).height(RiffQueue.buttonHeight).riffGlass(GlassRole.Button,backdrop=glass).clip(shape)
        .background(if(on) Pink.copy(alpha=.22f) else Color.Transparent)
        .toggleable(on,enabled=enabled,role=androidx.compose.ui.semantics.Role.Switch) {onClick()}
        .semantics {contentDescription=label},contentAlignment=Alignment.Center) {
        Icon(icon,null,tint=(if(on) Pink else White).copy(alpha=if(enabled) 1f else .5f),modifier=Modifier.size(RiffQueue.buttonIcon))
    }
}

/**
 * Where a dragged song goes when the row nearest the finger is [nearest]. While the list is not at its very top, the song
 * stops at the second row on screen, so the row above it, not the song, keeps the list's place.
 */
internal fun queueDragTarget(nearest:Int,from:Int,firstVisible:Int,firstOffset:Int):Int {
    val lowest=if(firstVisible==0 && firstOffset==0) 0 else firstVisible+1
    return if(nearest<lowest && from>=lowest) lowest else nearest
}

/** The dragged row stays whole inside the list at both edges, rather than mostly hidden above the top (seen on the phone, 6 Nov). */
internal fun queueDraggedTop(wanted:Float,viewportStart:Int,viewportEnd:Int,rowSize:Int):Float =
    if(viewportEnd-viewportStart<rowSize) wanted else wanted.coerceIn(viewportStart.toFloat(),(viewportEnd-rowSize).toFloat())
