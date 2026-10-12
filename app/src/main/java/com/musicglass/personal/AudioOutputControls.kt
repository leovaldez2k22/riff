package com.musicglass.personal

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.MediaRouter2
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.Airplay
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Earbuds
import androidx.compose.material.icons.rounded.SpeakerGroup
import androidx.compose.material.icons.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.SpeakerNotes
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The audio device type of the selected system route, or -1 when it cannot be told. MediaRoute2Info.getType
 * arrived in API 34: an older phone has no such method, and asking for it ends the app (found on API 31, Q14).
 */
@android.annotation.SuppressLint("InlinedApi") // the constants are plain numbers; only the call needs API 34
internal fun routeDeviceType(sdk: Int, hasRoute: Boolean, routeType: () -> Int): Int = when {
    !hasRoute -> AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    sdk < 34 -> -1
    else -> when(routeType()) {
        android.media.MediaRoute2Info.TYPE_BUILTIN_SPEAKER->AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        android.media.MediaRoute2Info.TYPE_WIRED_HEADSET->AudioDeviceInfo.TYPE_WIRED_HEADSET
        android.media.MediaRoute2Info.TYPE_WIRED_HEADPHONES->AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        android.media.MediaRoute2Info.TYPE_BLUETOOTH_A2DP->AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        android.media.MediaRoute2Info.TYPE_BLE_HEADSET->AudioDeviceInfo.TYPE_BLE_HEADSET
        else->-1
    }
}

/** Uses the system music stream and the platform's own phone/Bluetooth output picker. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun AudioOutputControls(onLyrics: () -> Unit, onQueue: () -> Unit) {
    val context = LocalContext.current
    val largeText = LocalDensity.current.fontScale >= 1.6f
    val audio = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val router = remember(context) { MediaRouter2.getInstance(context) }
    val maxVolume = remember(audio) { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember(audio) { mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    var routeName by remember { mutableStateOf(string(R.string.this_phone)) }
    var routeKind by remember { mutableStateOf(OutputKind.Phone) }
    val mediaAttributes = remember { AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build() }
    LaunchedEffect(audio, router) {
        while (true) {
            volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val routed = if(Build.VERSION.SDK_INT>=33) runCatching {audio.getAudioDevicesForAttributes(mediaAttributes)}.getOrDefault(emptyList()) else emptyList()
            val route=router.systemController.selectedRoutes.firstOrNull()
            val device=routed.firstOrNull()
            val type=device?.type ?: routeDeviceType(Build.VERSION.SDK_INT,route!=null) {route!!.type}
            routeName=if(type in setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)) string(R.string.this_phone)
                else device?.productName?.toString()?.takeIf(String::isNotBlank) ?:route?.name?.toString()?:string(R.string.audio_output)
            routeKind=outputKind(type,routeName)
            delay(500)
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (volume == 0) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeDown,
                contentDescription = null, tint = White, modifier = Modifier.size(20.dp))
            CapsuleSlider(value = volume.toFloat().coerceIn(0f,maxVolume.toFloat()),
                onValueChange={ next -> volume=next.roundToInt().coerceIn(0,maxVolume)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC,volume,0) },valueRange=0f..maxVolume.toFloat(),haptics=SliderHaptics.Volume,
                modifier=Modifier.weight(1f).padding(horizontal=8.dp).testTag("player_volume").semantics {
                    contentDescription=string(R.string.volume)
                    stateDescription=if(volume==0) string(R.string.muted) else "${volume*100/maxVolume.coerceAtLeast(1)} percent"
                })
            Icon(Icons.Rounded.VolumeUp, contentDescription = null, tint = White,
                modifier = Modifier.size(23.dp))
        }
        Row(Modifier.fillMaxWidth().height(62.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).height(62.dp).clickable(onClick = hapticClick(onLyrics))
                .testTag("player_lyrics"), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Icon(Icons.Rounded.SpeakerNotes, contentDescription = string(R.string.open_lyrics), tint = White,
                    modifier = Modifier.size(27.dp))
            }
            Column(Modifier.weight(1f).height(62.dp).clickable { uiHaptic(); 
                if (Build.VERSION.SDK_INT >= 34) router.showSystemOutputSwitcher()
                else context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }.semantics { contentDescription = string(R.string.change_audio_output_x_x, routeKind.label, routeName) }
                .testTag("player_output"), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Icon(when(routeKind) {OutputKind.Phone->Icons.Rounded.Smartphone;OutputKind.Earphones->Icons.Rounded.Earbuds;OutputKind.Headphones->Icons.Rounded.Headphones;OutputKind.Speaker->Icons.Rounded.SpeakerGroup}, contentDescription = null, tint = White,
                    modifier = Modifier.size(28.dp))
                if (!largeText) {
                    Text(routeName, color = Muted, fontSize = 10.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
            Column(Modifier.weight(1f).height(62.dp).clickable(onClick = hapticClick(onQueue))
                .testTag("player_queue_bottom"), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Icon(Icons.Rounded.FormatListBulleted, contentDescription = string(R.string.open_queue), tint = White,
                    modifier = Modifier.size(28.dp))
            }
        }
    }
}
