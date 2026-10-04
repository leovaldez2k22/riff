package com.musicglass.personal

import kotlin.math.roundToInt

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun SettingsScreen(model:ShellModel,bottom:Dp,provider:YouTubeMusicProvider,
    collections:LocalCollections,onNotice:(String)->Unit) {
    val preferences=LocalAccessibilitySettings.current
    val context=androidx.compose.ui.platform.LocalContext.current
    val audio=remember(context) {AudioBoostSettings(context)}
    var boostLevel by remember(audio) {mutableFloatStateOf(audio.percent.toFloat())}
    val crossfade=remember(context) {CrossfadeSettings(context)}
    var crossfadeLength by remember(crossfade) {mutableFloatStateOf(crossfade.seconds.toFloat())}
    val scope=rememberCoroutineScope()
    ScreenList(model,bottom) {
        item {BackHeading("Back",model::back);ScreenTitle("Settings")}
        item {
            Surface(Modifier.fillMaxWidth().padding(vertical=8.dp).testTag("settings_account")
                .clickable(onClick=hapticClick {model.push("account")}),shape=RoundedCornerShape(24.dp),
                color=White.copy(alpha=.06f)) {
                Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
                    val image=provider.accountProfile?.artworkUrl
                    if(image!=null) AsyncImage(image,null,contentScale=ContentScale.Crop,
                        modifier=Modifier.size(48.dp).clip(CircleShape))
                    else Icon(Icons.Rounded.AccountCircle,null,tint=Muted,modifier=Modifier.size(48.dp))
                    Column(Modifier.weight(1f).padding(horizontal=14.dp)) {
                        Text(provider.accountProfile?.name ?: if(provider.signedIn) "Your account" else "YouTube Music",
                            color=White,fontSize=17.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(if(provider.signedIn) "Manage account" else "Sign in",color=Muted,fontSize=12.sp)
                    }
                    Icon(Icons.Rounded.ChevronRight,null,tint=Muted)
                }
            }
        }
        item {SettingsSection("Interaction") {
            SettingsToggleRow("Haptic feedback",preferences.hapticFeedback,
                hapticToggle(preferences::updateHapticFeedback),Icons.Rounded.Vibration,"settings_haptics")
        }}
        item {SettingsSection("Audio") {
            SettingsToggleRow("Volume booster",audio.enabled,hapticToggle(audio::updateEnabled),
                Icons.AutoMirrored.Rounded.VolumeUp,"settings_volume_boost", "Amplify Riff playback up to 300%")
            if(audio.enabled) {
                SettingsDivider()
                Column(Modifier.padding(vertical=8.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text("Boost level",color=White,fontSize=15.sp,modifier=Modifier.weight(1f))
                        Text("${boostLevel.toInt()}%",color=White,fontSize=13.sp)
                    }
                    CapsuleSlider(boostLevel,{boostLevel=it},valueRange=100f..300f,
                        onValueChangeFinished={audio.updatePercent(boostLevel.toInt())},
                        haptics=SliderHaptics.Intensity,
                        modifier=Modifier.fillMaxWidth().testTag("settings_boost_level"))
                }
            }
            SettingsDivider()
            SettingsToggleRow("Crossfade",crossfade.enabled,hapticToggle(crossfade::updateEnabled),
                Icons.Rounded.Shuffle,"settings_crossfade","Blend the end of each song into the next")
            if(crossfade.enabled) {
                SettingsDivider()
                Column(Modifier.padding(vertical=8.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text("Crossfade length",color=White,fontSize=15.sp,modifier=Modifier.weight(1f))
                        Text("${crossfadeLength.roundToInt()} s",color=White,fontSize=13.sp)
                    }
                    CapsuleSlider(crossfadeLength,{crossfadeLength=it},valueRange=1f..12f,steps=10,
                        onValueChangeFinished={crossfade.updateSeconds(crossfadeLength.roundToInt())},
                        haptics=SliderHaptics.Intensity,
                        modifier=Modifier.fillMaxWidth().testTag("settings_crossfade_length"))
                }
            }
        }}
        item {SettingsSection("Appearance") {
            SettingsToggleRow("Frosted glass",preferences.frostedGlass,
                hapticToggle(preferences::updateFrostedGlass),Icons.Rounded.BlurOn,"settings_frosted_glass")
            SettingsDivider()
            Column(Modifier.padding(vertical=8.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text("Glass intensity",color=White,fontSize=15.sp,modifier=Modifier.weight(1f))
                    Text("${(preferences.glassIntensity*100).toInt()}%",color=Muted,fontSize=13.sp)
                }
                CapsuleSlider(preferences.glassIntensity,preferences::updateGlassIntensity,valueRange=0f..1.5f,
                    haptics=SliderHaptics.Intensity,modifier=Modifier.fillMaxWidth().testTag("settings_glass_intensity"))
            }
            SettingsDivider()
            SettingsToggleRow("Reduce motion",preferences.reduceMotion,
                hapticToggle(preferences::updateReduceMotion),Icons.Rounded.MotionPhotosOff,"settings_reduce_motion")
            SettingsDivider()
            SettingsToggleRow("High-contrast controls",preferences.solidControls,
                hapticToggle(preferences::updateSolidControls),Icons.Rounded.Contrast,"settings_solid_controls")
        }}
        item {SettingsSection("Downloads") {DownloadSettings(model,onNotice)}}
        item {SettingsSection("Widgets") {WidgetSettings(onNotice)}}
        item {SettingsSection("Library") {
            SettingsToggleRow("Save listening history",collections.historyEnabled,
                hapticToggle(collections::updateHistoryPreference),Icons.Rounded.History,"settings_history")
            SettingsDivider()
            val hidden=remember(context) {NotInterestedStore.get(context)}
            SettingsLinkRow("Not interested",hidden.entries.size.takeIf {it>0}?.toString(),Icons.Rounded.Block,"settings_not_interested") {
                model.push("not_interested")
            }
        }}
        item {SettingsSection("About") {
            SettingsLinkRow("Copy playback diagnostics",icon=Icons.Rounded.BugReport,tag="settings_copy_diagnostics") {
                scope.launch {
                    val report=withContext(kotlinx.coroutines.Dispatchers.IO) {
                        playbackDiagnosticsReport(PlaybackEventLog(context).recent(),BuildConfig.VERSION_NAME,
                            android.os.Build.VERSION.SDK_INT,"${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                    }
                    context.getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("Riff playback diagnostics",report))
                    onNotice("Playback diagnostics copied")
                }
            }
        }}
        item {Text("Riff \u00B7 ${BuildConfig.VERSION_NAME}",color=Muted,fontSize=12.sp,
            modifier=Modifier.fillMaxWidth().padding(vertical=20.dp),textAlign=androidx.compose.ui.text.style.TextAlign.Center)}
    }
}

@Composable internal fun SettingsSection(title:String,content:@Composable ColumnScope.()->Unit) {
    Column(Modifier.fillMaxWidth().padding(top=10.dp,bottom=4.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(),color=Muted,fontSize=12.sp,fontWeight=FontWeight.SemiBold,letterSpacing=.8.sp,
            modifier=Modifier.padding(start=8.dp).semantics {heading()})
        Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp),color=White.copy(alpha=.045f),
            border=BorderStroke(.6.dp,White.copy(alpha=.08f))) {
            Column(Modifier.padding(horizontal=16.dp,vertical=4.dp),content=content)
        }
    }
}
@Composable internal fun SettingsDivider() {HorizontalDivider(color=White.copy(alpha=.08f))}
@Composable internal fun SettingsToggleRow(title:String,checked:Boolean,onChange:(Boolean)->Unit,
    icon:ImageVector,tag:String,subtitle:String?=null) {
    Row(Modifier.fillMaxWidth().heightIn(min=58.dp).testTag(tag)
        .toggleable(checked,role=Role.Switch,onValueChange=onChange).padding(vertical=8.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Icon(icon,null,tint=Muted,modifier=Modifier.size(21.dp))
        Column(Modifier.weight(1f)) {
            Text(title,color=White,fontSize=15.sp)
            if(subtitle!=null) Text(subtitle,color=Muted,fontSize=12.sp,modifier=Modifier.padding(top=3.dp))
        }
        Switch(checked,onCheckedChange=null)
    }
}
@Composable internal fun SettingsLinkRow(title:String,value:String?=null,icon:ImageVector,tag:String,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=56.dp).testTag(tag).clickable(onClick=hapticClick(onClick))
        .padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Icon(icon,null,tint=Muted,modifier=Modifier.size(21.dp))
        Text(title,color=White,fontSize=15.sp,modifier=Modifier.weight(1f))
        if(value!=null) Text(value,color=Muted,fontSize=12.sp)
        Icon(Icons.Rounded.ChevronRight,null,tint=Muted,modifier=Modifier.size(18.dp))
    }
}

@Composable private fun WidgetSettings(onNotice:(String)->Unit) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val manager=remember(context) {android.appwidget.AppWidgetManager.getInstance(context)}
    riffWidgetPresets.forEach {preset->
        SettingsLinkRow(preset.title,icon=Icons.Rounded.Widgets,tag="widget_add_${preset.title.lowercase()}") {
            if(manager.isRequestPinAppWidgetSupported) manager.requestPinAppWidget(android.content.ComponentName(context,preset.receiver),null,null)
            else onNotice("Touch and hold your home screen, choose Widgets, then Riff.")
        }
    }
}
