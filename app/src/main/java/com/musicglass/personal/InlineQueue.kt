package com.musicglass.personal

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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

@Composable internal fun InlineQueue(playback:PlaybackGateway,provider:YouTubeMusicProvider,
    onClose:()->Unit,onLike:()->Unit,onMore:()->Unit,artworkModifier:Modifier=Modifier,onMoreBounds:(IntRect)->Unit={}) {
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
    var dragIndex by remember {mutableIntStateOf(-1)}
    var fingerY by remember {mutableFloatStateOf(0f)}
    var grabY by remember {mutableFloatStateOf(0f)}
    var autoScroll by remember {mutableStateOf<Job?>(null)}
    fun rowKey(index:Int,item:QueueEntry)=item.queueId.ifBlank {"${item.mediaId}:${latestQueue.take(index).count {it.mediaId==item.mediaId}}"}
    val visible=state.queue.withIndex().filter {history || it.index>state.index}
    val latestVisible by rememberUpdatedState(visible)
    fun reorder() {
        val layout=listState.layoutInfo.visibleItemsInfo
        val target=layout.minByOrNull {kotlin.math.abs(fingerY-(it.offset+it.size/2f))}?:return
        val to=latestVisible.getOrNull(target.index)?.index?:return
        if(dragIndex in latestQueue.indices && to!=dragIndex) {val from=dragIndex;dragIndex=to;val anchor=listState.firstVisibleItemIndex;val offset=listState.firstVisibleItemScrollOffset
            playback.moveQueueItem(from,to);listState.requestScrollToItem(anchor,offset);uiHaptic(HapticCue.Selection)}
    }
    fun finishDrag() {autoScroll?.cancel();autoScroll=null;draggingKey=null;dragIndex=-1;uiHaptic(HapticCue.DragEnd)}
    DisposableEffect(Unit) {onDispose {autoScroll?.cancel()}}
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal=20.dp).testTag("inline_queue")) {
        Row(Modifier.fillMaxWidth().height(62.dp),verticalAlignment=Alignment.CenterVertically) {
            // Keep the hit target outside the shared artwork layer: its animated overlay can
            // otherwise leave touch coordinates at the full-size hero's position.
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))
                .clickable(onClick=hapticClick(onClose)).semantics {contentDescription="Back to player"}.testTag("queue_artwork_back")) {
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
            IconButton(onLike) {Icon(if(provider.songLikes[state.mediaId.removePrefix("yt:")]==true) Icons.Rounded.Star else Icons.Rounded.StarBorder,"Like on YouTube Music",tint=White)}
            IconButton(hapticClick(onMore),modifier=Modifier.testTag("queue_more").onGloballyPositioned {val r=it.boundsInWindow();onMoreBounds(IntRect(r.topLeft.round(),r.bottomRight.round()))}) {Icon(Icons.Rounded.MoreHoriz,"Song options",tint=White)}
            IconButton(hapticClick(onClose),modifier=Modifier.size(36.dp).testTag("queue_back")) {Icon(Icons.Rounded.ExpandMore,"Close queue",tint=White)}
        }
        Row(Modifier.fillMaxWidth().height(46.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(state.shuffle,hapticClick(playback::toggleShuffle),label={Icon(Icons.Rounded.Shuffle,"Shuffle",modifier=Modifier.size(18.dp))},modifier=Modifier.weight(1f),shape=RoundedCornerShape(50))
            FilterChip(state.repeat!=0,hapticClick(playback::cycleRepeat),label={Icon(if(state.repeat==1) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,"Repeat",modifier=Modifier.size(18.dp))},modifier=Modifier.weight(1f),shape=RoundedCornerShape(50))
            FilterChip(state.queueMessage !in setOf("Album order","Collection order"),{},enabled=false,label={Icon(Icons.Rounded.AllInclusive,"YouTube Music recommendations",modifier=Modifier.size(18.dp))},modifier=Modifier.weight(1f),shape=RoundedCornerShape(50))
        }
        Row(Modifier.fillMaxWidth().padding(top=6.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Continue Playing",color=White,fontWeight=FontWeight.SemiBold,fontSize=15.sp)
                Text(if(state.queueTitle.isNotBlank()) "From ${state.queueTitle}" else if(state.album.isNotBlank()) "From ${state.album}" else state.queueMessage.ifBlank {"From YouTube Music"},color=White.copy(alpha=.55f),fontSize=11.sp,maxLines=1)
            }
            TextButton({uiHaptic(HapticCue.Selection);history=!history}) {Text(if(history) "Up next" else "History",color=White,fontSize=11.sp)}
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("queue_list").onGloballyPositioned {listCoordinates.value=it},state=listState) {
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
                    .graphicsLayer {val info=if(dragged) listState.layoutInfo.visibleItemsInfo.firstOrNull {it.key==key} else null;translationY=if(info!=null) fingerY-grabY-info.offset else 0f;scaleX=if(dragged) 1.015f else 1f;scaleY=scaleX}
                    .background(if(dragged) White.copy(alpha=.1f) else Color.Transparent,RoundedCornerShape(8.dp))
                    .pointerInput(item.mediaId,index) {detectHorizontalDragGestures(onHorizontalDrag={change,x->val before=swipe;swipe+=x;if(before>= -70.dp.toPx() && swipe< -70.dp.toPx()) uiHaptic(HapticCue.Threshold);change.consume()},onDragCancel={swipe=0f},onDragEnd={if(swipe< -70.dp.toPx()) {playback.removeQueueItem(latestIndex);uiHaptic(HapticCue.Confirm)};swipe=0f})}
                    .clickable { uiHaptic(); playback.seekQueueItem(index)}.semantics {
                        customActions=listOf(CustomAccessibilityAction("Move up"){playback.moveQueueItem(index,index-1);uiHaptic(HapticCue.Selection);true},
                            CustomAccessibilityAction("Move down"){playback.moveQueueItem(index,index+1);uiHaptic(HapticCue.Selection);true},
                            CustomAccessibilityAction("Remove from queue"){playback.removeQueueItem(index);uiHaptic(HapticCue.Confirm);true})
                    },verticalAlignment=Alignment.CenterVertically) {
                    AsyncImage(item.artworkUrl,null,contentScale=ContentScale.Crop,modifier=Modifier.size(38.dp).clip(RoundedCornerShape(6.dp)))
                    Column(Modifier.weight(1f).padding(horizontal=10.dp)) {
                        Text(item.title,color=White,fontSize=13.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(playerArtistLabel(item.artist),color=White.copy(alpha=.55f),fontSize=11.sp,maxLines=1)
                    }
                    IconButton(onClick=hapticClick {playback.removeQueueItem(latestIndex)},
                        modifier=Modifier.size(48.dp).testTag("queue_remove_$index")) {
                        Icon(Icons.Rounded.RemoveCircleOutline,"Remove ${item.title} from queue",tint=White.copy(alpha=.65f),modifier=Modifier.size(22.dp))
                    }
                    Icon(Icons.Rounded.DragHandle,"Reorder ${item.title}",tint=White.copy(alpha=.45f),modifier=Modifier.size(40.dp)
                         .testTag("queue_drag_$index").onGloballyPositioned {handleCoordinates.value=it}.clickable {uiHaptic(HapticCue.Selection)}.pointerInput(key) {
                            detectVerticalDragGestures(onDragStart={point->
                                dragIndex=latestIndex;draggingKey=key
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
                        })
                }
            }
            if(visible.isEmpty()) item {Text(if(state.queueLoading) "Finding more music\u2026" else "You're at the end of this queue.",color=White.copy(alpha=.6f),fontSize=13.sp,modifier=Modifier.padding(12.dp))}
        }
    }
}
