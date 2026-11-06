package com.musicglass.personal
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

internal const val SET_SLEEP_TIMER="riff.set_sleep_timer"
internal fun sleepDeadline(minutes:Int,now:Long):Long=if(minutes in 1..1440) now+minutes*60_000L else 0
internal fun sleepCountdown(deadline:Long,now:Long):String {
    val seconds=((deadline-now).coerceAtLeast(0)+999)/1000
    return "${seconds/60}:${(seconds%60).toString().padStart(2,'0')}"
}
/** What to do with a sleep timer saved before the process died (W6). */
internal sealed interface SleepRestore {
    data class Rearm(val deadline:Long):SleepRestore
    /** The deadline passed while Riff wasn't running: clear it; never start or stop playback for it. */
    data object Expired:SleepRestore
    data object None:SleepRestore
}
/**
 * [now] and [savedDeadline] are elapsedRealtime, which restarts from 0 at boot, so a timer saved
 * before a reboot is discarded: by boot count when Android provides it, and in any case when the
 * deadline is further ahead than a timer can be (24 hours).
 */
internal fun restoreSleepTimer(savedDeadline:Long,savedBoot:Int,now:Long,boot:Int):SleepRestore = when {
    savedDeadline<=0 -> SleepRestore.None
    savedBoot!=boot -> SleepRestore.None
    savedDeadline-now>1440*60_000L -> SleepRestore.None
    savedDeadline>now -> SleepRestore.Rearm(savedDeadline)
    else -> SleepRestore.Expired
}
internal fun sleepMinutesRemaining(deadline:Long,now:Long):Long=((deadline-now).coerceAtLeast(0)+59_999)/60_000
@Composable private fun sleepTimerNow(deadline:Long):Long {
    val now by produceState(SystemClock.elapsedRealtime(),deadline) {
        while(value<deadline) {delay(250);value=SystemClock.elapsedRealtime()}
    }
    return now
}
/** Keep countdown ticks inside this small subtree, independently of song position. */
@Composable internal fun SleepTimerCountdown(deadline:Long,onClick:()->Unit) {
    val now=sleepTimerNow(deadline)
    Row(Modifier.fillMaxWidth().testTag("player_sleep_countdown").clickable(onClick=onClick),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
        Icon(Icons.Rounded.Bedtime,null,tint=Muted,modifier=Modifier.size(13.dp));Spacer(Modifier.width(5.dp))
        Text(string(R.string.sleep_in_x, sleepCountdown(deadline,now)),color=Muted,fontSize=11.sp,maxLines=1)
    }
}
/** A scrollbar that stays, and the edge a row can scroll past fading out: a list that scrolls has to look like one. */
private fun Modifier.sleepListHints(list:LazyListState)=graphicsLayer {compositingStrategy=CompositingStrategy.Offscreen}.drawWithContent {
    drawContent()
    val edge=RiffSleep.edgeFade.toPx()
    if(list.canScrollForward) drawRect(Brush.verticalGradient(0f to Color.Black,1f to Color.Transparent,startY=size.height-edge,endY=size.height),
        topLeft=Offset(0f,size.height-edge),blendMode=BlendMode.DstIn)
    if(list.canScrollBackward) drawRect(Brush.verticalGradient(0f to Color.Transparent,1f to Color.Black,startY=0f,endY=edge),
        size=Size(size.width,edge),blendMode=BlendMode.DstIn)
    val info=list.layoutInfo
    val first=info.visibleItemsInfo.firstOrNull()
    if(first!=null && (list.canScrollForward || list.canScrollBackward) && info.totalItemsCount>0) {
        val row=first.size.toFloat().coerceAtLeast(1f)
        val track=size.height
        val length=(track*(track/(row*info.totalItemsCount))).coerceIn(RiffSleep.thumbMin.toPx(),track)
        val travel=(row*info.totalItemsCount-track).coerceAtLeast(1f)
        val top=((first.index*row-first.offset)/travel).coerceIn(0f,1f)*(track-length)
        val width=RiffSleep.thumbWidth.toPx()
        drawRoundRect(Color.White.copy(alpha=.5f),Offset(size.width-width,top),Size(width,length),CornerRadius(width/2))
    }
}
@Composable internal fun SleepTimerDialog(playback:PlaybackGateway,onDismiss:()->Unit) {
    var custom by remember {mutableStateOf("")}
    fun set(minutes:Int) {playback.setSleepTimer(minutes);uiHaptic(HapticCue.Confirm);onDismiss()}
    fun setSongs(songs:Int) {playback.setSleepTimerSongs(songs);uiHaptic(HapticCue.Confirm);onDismiss()}
    val context=androidx.compose.ui.platform.LocalContext.current
    FrostedAlertDialog(onDismissRequest=onDismiss,title={Text(string(R.string.sleep_timer))},text={Column(Modifier.testTag("sleep_timer")) {
        if(playback.state.sleepDeadlineMs>0) Text(string(R.string.x_minutes_remaining, sleepMinutesRemaining(playback.state.sleepDeadlineMs,sleepTimerNow(playback.state.sleepDeadlineMs))))
        val list=rememberLazyListState()
        LazyColumn(Modifier.heightIn(max=RiffSleep.listMax).sleepListHints(list),state=list) {
            listOf(5,10,15,30,45,60,90).forEach {minutes->item {
                TextButton({set(minutes)},modifier=Modifier.fillMaxWidth().testTag("sleep_timer_$minutes")) {Text(string(R.string.x_minutes, minutes))}
            }}
            // Feature 18: counted in songs; the one playing now is the first.
            val left=(playback.state.queue.size-playback.state.index).coerceAtLeast(1)
            item {TextButton({setSongs(1)},modifier=Modifier.fillMaxWidth().testTag("sleep_timer_end_of_song")) {Text(string(R.string.end_of_this_song))}}
            listOf(2,3,5).filter {it<left}.forEach {songs->item {
                TextButton({setSongs(songs)},modifier=Modifier.fillMaxWidth().testTag("sleep_timer_songs_$songs")) {Text(string(R.string.after_x_songs, songs))}
            }}
            if(left>1) item {TextButton({setSongs(left)},modifier=Modifier.fillMaxWidth().testTag("sleep_timer_end_of_queue")) {Text(string(R.string.end_of_queue))}}
        }
        // Feature 18: how long the music fades out before it stops.
        var fade by remember {mutableLongStateOf(sleepFadeMs(context))}
        Text(string(R.string.fade_out),modifier=Modifier.padding(top=RiffSpace.item))
        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(RiffSpace.tight)) {
            SLEEP_FADE_CHOICES_MS.forEach {ms->FilterChip(fade==ms,{uiHaptic(HapticCue.Selection);fade=ms;setSleepFadeMs(context,ms)},
                {Text(if(ms==0L) string(R.string.off) else string(R.string.x_seconds_short,"${ms/1000}"))},modifier=Modifier.testTag("sleep_fade_${ms/1000}"),shape=RoundedCornerShape(RiffSleep.chipCorner))}
        }
        OutlinedTextField(custom,{custom=it.filter(Char::isDigit).take(4)},singleLine=true,shape=RoundedCornerShape(RiffSleep.fieldCorner),label={Text(string(R.string.custom_minutes_1_1440))},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.testTag("sleep_timer_custom"))
    }},confirmButton={TextButton({set(custom.toIntOrNull()?:0)},enabled=custom.toIntOrNull() in 1..1440) {Text(string(R.string.set_timer))}},
        dismissButton={TextButton({set(0)}) {Text(if(playback.state.sleepDeadlineMs>0) string(R.string.cancel_timer) else string(R.string.close))}})
}

/** Feature 18: the fade-out length, kept on this phone. */
internal fun sleepFadeMs(context: android.content.Context): Long = context.applicationContext
    .getSharedPreferences("sleep_timer", android.content.Context.MODE_PRIVATE).getLong("fade_ms", SLEEP_FADE_DEFAULT_MS)
    .takeIf { it in SLEEP_FADE_CHOICES_MS } ?: SLEEP_FADE_DEFAULT_MS

internal fun setSleepFadeMs(context: android.content.Context, ms: Long) {
    context.applicationContext.getSharedPreferences("sleep_timer", android.content.Context.MODE_PRIVATE).edit().putLong("fade_ms", ms).apply()
}
