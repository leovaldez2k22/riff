package com.musicglass.personal

import kotlin.math.roundToInt

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun SettingsScreen(model:ShellModel,bottom:Dp,provider:YouTubeMusicProvider,
    collections:LocalCollections,onNotice:(String)->Unit) {
    val preferences=LocalAccessibilitySettings.current
    val context=androidx.compose.ui.platform.LocalContext.current
    val audio=remember(context) {AudioBoostSettings(context)}
    var boostLevel by remember(audio) {mutableFloatStateOf(audio.percent.toFloat())}
    val crossfade=remember(context) {CrossfadeSettings.get(context)}
    var crossfadeLength by remember(crossfade) {mutableFloatStateOf(crossfade.seconds.toFloat())}
    val dataSaver=remember(context) {DataSaverSettings(context)}
    val equalizerOn=remember(context) {EqualizerSettings.read(EqualizerSettings.prefs(context)).enabled}
    val scope=rememberCoroutineScope()
    val store=remember(context) {OfflineMusicStore.get(context)}
    val storeRevision by store.revision.collectAsStateWithLifecycle()
    val smartDownloads=remember(storeRevision) {store.smartEnabled}
    val hidden=remember(context) {NotInterestedStore.get(context)}
    val updates=remember(context) {AppContainer.get(context).updates}
    // F5: the settings sit in compartments, one open at a time; a closed one says in a line what is set inside.
    var open by rememberSaveable {mutableStateOf<String?>(null)}
    fun toggle(id:String) {open=if(open==id) null else id}
    ScreenList(model,bottom) {
        item {BackHeading(string(R.string.back),model::back);ScreenTitle(string(R.string.settings))}
        item {
            Surface(Modifier.fillMaxWidth().padding(vertical=RiffSpace.item).testTag("settings_account")
                .clickable(onClick=hapticClick {model.push(Route.Account)}),shape=RoundedCornerShape(24.dp),
                color=White.copy(alpha=.06f)) {
                Row(Modifier.padding(RiffSpace.block),verticalAlignment=Alignment.CenterVertically) {
                    val image=provider.accountProfile?.artworkUrl
                    if(image!=null) AsyncImage(image,null,contentScale=ContentScale.Crop,
                        modifier=Modifier.size(48.dp).clip(CircleShape))
                    else Icon(Icons.Rounded.AccountCircle,null,tint=Muted,modifier=Modifier.size(48.dp))
                    Column(Modifier.weight(1f).padding(horizontal=14.dp)) {
                        Text(provider.accountProfile?.name ?: if(provider.signedIn) string(R.string.your_account) else "YouTube Music",
                            color=White,fontSize=17.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(if(provider.signedIn) string(R.string.manage_account) else string(R.string.sign_in),color=Muted,fontSize=RiffType.detail)
                    }
                    Icon(Icons.Rounded.ChevronRight,null,tint=Muted)
                }
            }
        }
        item {SettingsCompartment("sound",string(R.string.settings_sound),Icons.AutoMirrored.Rounded.VolumeUp,
            listOf(if(audio.enabled) string(R.string.summary_boost_x,boostLevel.toInt()) else string(R.string.summary_no_boost),
                if(crossfade.enabled) string(R.string.summary_crossfade_x,crossfadeLength.roundToInt()) else string(R.string.summary_no_crossfade)).joinToString(" · "),
            open=="sound",{toggle("sound")}) {
            SettingsToggleRow(string(R.string.volume_booster),audio.enabled,hapticToggle(audio::updateEnabled),
                Icons.AutoMirrored.Rounded.VolumeUp,"settings_volume_boost", string(R.string.amplify_riff_playback_up_to_300))
            if(audio.enabled) {
                SettingsDivider()
                Column(Modifier.padding(vertical=RiffSpace.item)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text(string(R.string.boost_level),color=White,fontSize=15.sp,modifier=Modifier.weight(1f))
                        Text("${boostLevel.toInt()}%",color=White,fontSize=13.sp)
                    }
                    CapsuleSlider(boostLevel,{boostLevel=it},valueRange=100f..300f,
                        onValueChangeFinished={audio.updatePercent(boostLevel.toInt())},
                        haptics=SliderHaptics.Boost,label=string(R.string.boost_level),spokenValue=quantity(R.plurals.n_percent,boostLevel.toInt()),
                        modifier=Modifier.fillMaxWidth().testTag("settings_boost_level"))
                }
            }
            SettingsDivider()
            SettingsToggleRow(string(R.string.normalize_volume),audio.normalize,hapticToggle(audio::updateNormalize),
                Icons.Rounded.GraphicEq,"settings_normalize_volume",string(R.string.turns_down_louder_songs_from_youtube_music))
            SettingsDivider()
            SettingsToggleRow(string(R.string.skip_end_silence),audio.skipEndSilence,hapticToggle(audio::updateSkipEndSilence),
                Icons.Rounded.SkipNext,"settings_skip_end_silence",string(R.string.skip_end_silence_detail))
            SettingsDivider()
            SettingsToggleRow(string(R.string.skip_all_silence),audio.skipAllSilence,hapticToggle(audio::updateSkipAllSilence),
                Icons.Rounded.ContentCut,"settings_skip_all_silence",string(R.string.skip_all_silence_detail))
            SettingsDivider()
            // Feature 10: Riff's own equalizer has a screen of its own.
            SettingsLinkRow(string(R.string.equalizer),string(if(equalizerOn) R.string.on else R.string.off),Icons.Rounded.Equalizer,"settings_equalizer") {
                model.push(Route.Settings(SettingsSection.Equalizer))
            }
            SettingsDivider()
            SettingsToggleRow(string(R.string.crossfade),crossfade.enabled,hapticToggle(crossfade::updateEnabled),
                Icons.Rounded.Shuffle,"settings_crossfade",string(R.string.blend_the_end_of_each_song_into))
            if(crossfade.enabled) {
                SettingsDivider()
                Column(Modifier.padding(vertical=RiffSpace.item)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text(string(R.string.crossfade_length),color=White,fontSize=15.sp,modifier=Modifier.weight(1f))
                        Text("${crossfadeLength.roundToInt()} s",color=White,fontSize=13.sp)
                    }
                    CapsuleSlider(crossfadeLength,{crossfadeLength=it},valueRange=1f..12f,steps=10,
                        onValueChangeFinished={crossfade.updateSeconds(crossfadeLength.roundToInt())},
                        haptics=SliderHaptics.Seconds,label=string(R.string.crossfade_length),spokenValue=quantity(R.plurals.n_seconds,crossfadeLength.roundToInt()),
                        modifier=Modifier.fillMaxWidth().testTag("settings_crossfade_length"))
                }
                SettingsDivider()
                // Automix stage 1: blends placed by each song's own end and start, with the bass swapped halfway.
                SettingsToggleRow(string(R.string.smart_crossfade),crossfade.smart,hapticToggle(crossfade::updateSmart),
                    Icons.Rounded.AutoAwesome,"settings_smart_crossfade",string(R.string.smart_crossfade_detail))
            }
        }}
        item {SettingsCompartment("look",string(R.string.settings_look_and_feel),Icons.Rounded.BlurOn,
            listOf(string(if(preferences.frostedGlass) R.string.summary_frosted_glass_on else R.string.summary_frosted_glass_off),
                string(R.string.summary_glass_x,(preferences.glassIntensity*100).toInt())).joinToString(" · "),
            open=="look",{toggle("look")}) {
            SettingsToggleRow(string(R.string.frosted_glass),preferences.frostedGlass,
                hapticToggle(preferences::updateFrostedGlass),Icons.Rounded.BlurOn,"settings_frosted_glass")
            SettingsDivider()
            Column(Modifier.padding(vertical=RiffSpace.item)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text(string(R.string.glass_intensity),color=White,fontSize=15.sp,modifier=Modifier.weight(1f))
                    Text("${(preferences.glassIntensity*100).toInt()}%",color=Muted,fontSize=13.sp)
                }
                CapsuleSlider(preferences.glassIntensity,preferences::updateGlassIntensity,valueRange=0f..1.5f,
                    haptics=SliderHaptics.Intensity,label=string(R.string.glass_intensity),spokenValue=quantity(R.plurals.n_percent,(preferences.glassIntensity*100).toInt()),
                    modifier=Modifier.fillMaxWidth().testTag("settings_glass_intensity"))
            }
            SettingsDivider()
            Column(Modifier.padding(vertical=RiffSpace.item)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text(string(R.string.frosted_glass_intensity),color=White,fontSize=15.sp,modifier=Modifier.weight(1f))
                    Text("${(preferences.frostedIntensity*100).toInt()}%",color=Muted,fontSize=13.sp)
                }
                CapsuleSlider(preferences.frostedIntensity,preferences::updateFrostedIntensity,valueRange=0f..1.5f,
                    haptics=SliderHaptics.Intensity,label=string(R.string.frosted_glass_intensity),spokenValue=quantity(R.plurals.n_percent,(preferences.frostedIntensity*100).toInt()),
                    modifier=Modifier.fillMaxWidth().testTag("settings_frosted_intensity"))
            }
            SettingsDivider()
            SettingsToggleRow(string(R.string.calm_transitions),preferences.motionPace==MotionPace.Calm,
                hapticToggle { calm -> preferences.updateMotionPace(if(calm) MotionPace.Calm else MotionPace.Quick) },
                Icons.Rounded.SlowMotionVideo,"settings_calm_transitions",subtitle=string(R.string.calm_transitions_detail))
            SettingsDivider()
            SettingsToggleRow(string(R.string.blur_other_lyric_lines),preferences.lyricsBlur,
                hapticToggle(preferences::updateLyricsBlur),Icons.Rounded.BlurOn,"settings_lyrics_blur",
                subtitle=string(R.string.blur_other_lyric_lines_detail))
            SettingsDivider()
            SettingsToggleRow(string(R.string.player_lyric_line),preferences.playerLyricLine,
                hapticToggle(preferences::updatePlayerLyricLine),Icons.Rounded.Subtitles,"settings_player_lyric_line")
            SettingsDivider()
            SettingsToggleRow(string(R.string.show_stats_for_nerds),preferences.playerStats,
                hapticToggle(preferences::updatePlayerStats),Icons.Rounded.Analytics,"settings_player_stats",
                subtitle=string(R.string.show_stats_for_nerds_detail))
            SettingsDivider()
            SettingsToggleRow(string(R.string.reduce_motion),preferences.reduceMotion,
                hapticToggle(preferences::updateReduceMotion),Icons.Rounded.MotionPhotosOff,"settings_reduce_motion")
            SettingsDivider()
            SettingsToggleRow(string(R.string.high_contrast_controls),preferences.solidControls,
                hapticToggle(preferences::updateSolidControls),Icons.Rounded.Contrast,"settings_solid_controls")
            SettingsDivider()
            SettingsToggleRow(string(R.string.haptic_feedback),preferences.hapticFeedback,
                hapticToggle(preferences::updateHapticFeedback),Icons.Rounded.Vibration,"settings_haptics")
        }}
        item {SettingsCompartment("downloads",string(R.string.settings_downloads_and_data),Icons.Rounded.DownloadDone,
            listOf(string(if(smartDownloads) R.string.summary_smart_downloads_on else R.string.summary_smart_downloads_off),
                string(if(dataSaver.enabled) R.string.summary_data_saver_on else R.string.summary_data_saver_off)).joinToString(" · "),
            open=="downloads",{toggle("downloads")}) {
            SettingsToggleRow(string(R.string.data_saver),dataSaver.enabled,hapticToggle(dataSaver::updateEnabled),
                Icons.Rounded.DataSaverOn,"settings_data_saver",
                string(R.string.lower_bitrate_audio_and_up_to_360p))
            SettingsDivider()
            SongQualityRow(dataSaver)
            SettingsDivider()
            // Feature 13: Android opens these links in Riff only once allowed here (Riff cannot verify YouTube's domains).
            SettingsLinkRow(string(R.string.open_links_in_riff),null,Icons.Rounded.Link,"settings_open_links") {
                runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
                    android.net.Uri.parse("package:${context.packageName}"))) }
            }
            Text(string(R.string.open_links_in_riff_detail),color=Muted,fontSize=RiffType.detail,modifier=Modifier.padding(bottom=RiffSpace.item))
            SettingsDivider()
            DownloadSettings(model,onNotice)
        }}
        item {SettingsCompartment("library",string(R.string.settings_library_and_privacy),Icons.Rounded.Lock,
            listOfNotNull(string(R.string.privacy_and_data),hidden.entries.size.takeIf {it>0}?.let {string(R.string.summary_x_not_interested,it)}).joinToString(" · "),
            open=="library",{toggle("library")}) {
            SettingsLinkRow(string(R.string.privacy_and_data),null,Icons.Rounded.Lock,"settings_privacy_data") {model.push(Route.Settings(SettingsSection.PrivacyData))}
            SettingsDivider()
            SettingsLinkRow(string(R.string.not_interested),hidden.entries.size.takeIf {it>0}?.toString(),Icons.Rounded.Block,"settings_not_interested") {
                model.push(Route.Settings(SettingsSection.NotInterested))
            }
        }}
        item {SettingsCompartment("widgets",string(R.string.widgets),Icons.Rounded.Widgets,
            string(R.string.summary_x_widgets,riffWidgetPresets.size),open=="widgets",{toggle("widgets")}) {WidgetSettings(onNotice)}}
        item {SettingsCompartment("about",string(R.string.about),Icons.Rounded.Info,
            listOfNotNull(string(R.string.summary_updates).takeIf {updates.configured},string(R.string.diagnostics)).joinToString(" · "),
            open=="about",{toggle("about")}) {
            // Q12A: shown only in a build that has a release feed. Off sends nothing, the manual check included.
            if(updates.configured) {
                SettingsToggleRow(string(R.string.check_for_updates),updates.enabled,hapticToggle(updates::updateEnabled),
                    Icons.Rounded.SystemUpdate,"settings_check_updates",string(R.string.check_for_updates_detail,updates.feedHost.orEmpty()))
                if(updates.enabled) {
                    SettingsDivider()
                    val newer=updates.available
                    SettingsLinkRow(newer?.let {string(R.string.riff_version_is_available,it.version.toString())} ?: string(R.string.check_for_updates_now),
                        icon=Icons.Rounded.Update,tag="settings_update_row") {
                        if(newer!=null) openWebPage(context,newer.page,updates.pageHosts)
                        else scope.launch {updateResultText(updates.checkNow())?.let(onNotice)}
                    }
                }
                SettingsDivider()
            }
            // Q15A: last exits, errors, Share diagnostics report and Copy playback diagnostics are on their own screen.
            SettingsLinkRow(string(R.string.diagnostics),icon=Icons.Rounded.BugReport,tag="settings_diagnostics") {
                model.push(Route.Settings(SettingsSection.Diagnostics))
            }
        }}
        item {Text(androidx.compose.ui.res.stringResource(R.string.about_build_identity, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildIdentity.current().shortId),color=Muted,fontSize=RiffType.detail,
            modifier=Modifier.fillMaxWidth().padding(vertical=20.dp),textAlign=androidx.compose.ui.text.style.TextAlign.Center)}
    }
}

/**
 * F5: one settings compartment. The header names it and, while closed, sums up what is set inside; a tap opens or
 * closes it. Frosted like Riff's other glass: a light sheen over a faint fill, a thin edge that turns pink when open.
 */
@Composable internal fun SettingsCompartment(id:String,title:String,icon:ImageVector,summary:String,open:Boolean,
    onToggle:()->Unit,content:@Composable ColumnScope.()->Unit) {
    val motion=LocalMotionPolicy.current
    val edge by animateColorAsState(if(open) Pink.copy(alpha=.5f) else White.copy(alpha=.12f),
        GlassMotion.timed(GlassMotion.selectionMs,motion),label="compartment edge")
    val turn by animateFloatAsState(if(open) 180f else 0f,GlassMotion.timed(GlassMotion.screenMs,motion),label="compartment chevron")
    val openText=string(R.string.settings_group_open);val closedText=string(R.string.settings_group_closed)
    val shape=RoundedCornerShape(24.dp)
    Column(Modifier.fillMaxWidth().padding(vertical=RiffSpace.tight).clip(shape)
        .background(Brush.linearGradient(listOf(White.copy(alpha=.10f),White.copy(alpha=.035f))))
        .border(.6.dp,edge,shape)) {
        Row(Modifier.fillMaxWidth().heightIn(min=64.dp).testTag("settings_group_$id")
            .clickable(role=Role.Button,onClick=hapticClick(onToggle))
            .semantics {heading();stateDescription=if(open) openText else closedText}
            .padding(horizontal=RiffSpace.block,vertical=RiffSpace.rowGap),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(RiffSpace.rowGap)) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(Pink.copy(alpha=.14f)).border(.6.dp,Pink.copy(alpha=.35f),CircleShape),
                contentAlignment=Alignment.Center) {Icon(icon,null,tint=Pink,modifier=Modifier.size(19.dp))}
            Column(Modifier.weight(1f)) {
                Text(title,color=White,fontSize=RiffType.message,fontWeight=FontWeight.SemiBold)
                AnimatedVisibility(!open,enter=fadeIn(GlassMotion.timed(GlassMotion.selectionMs,motion)),
                    exit=fadeOut(GlassMotion.exit(GlassMotion.selectionMs,motion))) {
                    Text(summary,color=Muted,fontSize=RiffType.detail,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=2.dp))
                }
            }
            Icon(Icons.Rounded.ExpandMore,null,tint=if(open) Pink else Muted,modifier=Modifier.rotate(turn))
        }
        AnimatedVisibility(open,enter=expandVertically(GlassMotion.timed(GlassMotion.screenMs,motion))+fadeIn(GlassMotion.timed(GlassMotion.screenMs,motion)),
            exit=shrinkVertically(GlassMotion.exit(GlassMotion.screenMs,motion))+fadeOut(GlassMotion.exit(GlassMotion.selectionMs,motion))) {
            Column(Modifier.padding(start=RiffSpace.block,end=RiffSpace.block,bottom=RiffSpace.item)) {SettingsDivider();content()}
        }
    }
}

@Composable internal fun SettingsSection(title:String,content:@Composable ColumnScope.()->Unit) {
    Column(Modifier.fillMaxWidth().padding(top=10.dp,bottom=RiffSpace.tight),verticalArrangement=Arrangement.spacedBy(RiffSpace.item)) {
        Text(title.uppercase(),color=Muted,fontSize=RiffType.detail,fontWeight=FontWeight.SemiBold,letterSpacing=.8.sp,
            modifier=Modifier.padding(start=RiffSpace.item).semantics {heading()})
        Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp),color=White.copy(alpha=.045f),
            border=BorderStroke(.6.dp,White.copy(alpha=.08f))) {
            Column(Modifier.padding(horizontal=RiffSpace.block,vertical=RiffSpace.tight),content=content)
        }
    }
}
/** Q18-099: High, Medium or Low for streamed songs; Data saver wins while it is on, and the row says so. */
@Composable private fun SongQualityRow(dataSaver:DataSaverSettings) {
    Column(Modifier.fillMaxWidth().padding(vertical=RiffSpace.item).testTag("settings_song_quality")) {
        Text(string(R.string.song_quality),color=White,fontSize=RiffType.rowTitle)
        // Feature 17: one choice for Wi-Fi (any unmetered network) and one for mobile data.
        for (metered in listOf(false, true)) {
            Text(string(if (metered) R.string.on_mobile_data else R.string.on_wifi),color=Muted,fontSize=RiffType.detail,modifier=Modifier.padding(top=RiffSpace.item))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top=RiffSpace.tight),horizontalArrangement=Arrangement.spacedBy(RiffSpace.item)) {
                SongQuality.entries.forEach {choice->
                    val chosen=if(metered) dataSaver.meteredQuality else dataSaver.unmeteredQuality
                    FilterChip(chosen==choice,{ uiHaptic(HapticCue.Selection); dataSaver.updateNetworkQuality(metered,choice)},{Text(songQualityLabel(choice))},
                        modifier=Modifier.testTag(if(metered) "settings_song_quality_mobile_${choice.key}" else "settings_song_quality_${choice.key}"))
                }
            }
        }
        Text(string(if(dataSaver.enabled) R.string.song_quality_data_saver_on else R.string.song_quality_note),
            color=Muted,fontSize=RiffType.detail,modifier=Modifier.padding(top=RiffSpace.tight).testTag("settings_song_quality_note"))
    }
}
internal fun songQualityLabel(choice:SongQuality) = string(when(choice) {
    SongQuality.High -> R.string.song_quality_high; SongQuality.Medium -> R.string.song_quality_medium; SongQuality.Low -> R.string.song_quality_low })
@Composable internal fun SettingsDivider() {HorizontalDivider(color=White.copy(alpha=.08f))}
@Composable internal fun SettingsToggleRow(title:String,checked:Boolean,onChange:(Boolean)->Unit,
    icon:ImageVector,tag:String,subtitle:String?=null) {
    Row(Modifier.fillMaxWidth().heightIn(min=58.dp).testTag(tag)
        .toggleable(checked,role=Role.Switch,onValueChange=onChange).padding(vertical=RiffSpace.item),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(RiffSpace.rowGap)) {
        Icon(icon,null,tint=Muted,modifier=Modifier.size(21.dp))
        Column(Modifier.weight(1f)) {
            Text(title,color=White,fontSize=15.sp)
            if(subtitle!=null) Text(subtitle,color=Muted,fontSize=RiffType.detail,modifier=Modifier.padding(top=3.dp))
        }
        Switch(checked,onCheckedChange=null)
    }
}
@Composable internal fun SettingsLinkRow(title:String,value:String?=null,icon:ImageVector,tag:String,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=56.dp).testTag(tag).clickable(onClick=hapticClick(onClick))
        .padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(RiffSpace.rowGap)) {
        Icon(icon,null,tint=Muted,modifier=Modifier.size(21.dp))
        Text(title,color=White,fontSize=15.sp,modifier=Modifier.weight(1f))
        if(value!=null) Text(value,color=Muted,fontSize=RiffType.detail)
        Icon(Icons.Rounded.ChevronRight,null,tint=Muted,modifier=Modifier.size(18.dp))
    }
}

@Composable private fun WidgetSettings(onNotice:(String)->Unit) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val manager=remember(context) {android.appwidget.AppWidgetManager.getInstance(context)}
    riffWidgetPresets.forEach {preset->
        SettingsLinkRow(preset.title,icon=Icons.Rounded.Widgets,tag="widget_add_${preset.title.lowercase()}") {
            if(manager.isRequestPinAppWidgetSupported) manager.requestPinAppWidget(android.content.ComponentName(context,preset.receiver),null,null)
            else onNotice(string(R.string.touch_and_hold_your_home_screen_choose))
        }
    }
}
