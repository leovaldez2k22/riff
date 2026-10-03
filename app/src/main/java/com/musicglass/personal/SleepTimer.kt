package com.musicglass.personal
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.clickable
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
        Text("Sleep in ${sleepCountdown(deadline,now)}",color=Muted,fontSize=11.sp,maxLines=1)
    }
}
@Composable internal fun SleepTimerDialog(playback:PlaybackGateway,onDismiss:()->Unit) {
    var custom by remember {mutableStateOf("")}
    fun set(minutes:Int) {playback.setSleepTimer(minutes);uiHaptic(HapticCue.Confirm);onDismiss()}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Sleep timer")},text={Column(Modifier.testTag("sleep_timer")) {
        if(playback.state.sleepDeadlineMs>0) Text("${sleepMinutesRemaining(playback.state.sleepDeadlineMs,sleepTimerNow(playback.state.sleepDeadlineMs))} minutes remaining")
        LazyColumn(Modifier.heightIn(max=250.dp)) {listOf(5,10,15,30,45,60,90).forEach {minutes->item {
            TextButton({set(minutes)},modifier=Modifier.fillMaxWidth().testTag("sleep_timer_$minutes")) {Text("$minutes minutes")}
        }}}
        OutlinedTextField(custom,{custom=it.filter(Char::isDigit).take(4)},singleLine=true,label={Text("Custom minutes (1–1440)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.testTag("sleep_timer_custom"))
    }},confirmButton={TextButton({set(custom.toIntOrNull()?:0)},enabled=custom.toIntOrNull() in 1..1440) {Text("Set timer")}},
        dismissButton={TextButton({set(0)}) {Text(if(playback.state.sleepDeadlineMs>0) "Cancel timer" else "Close")}})
}
