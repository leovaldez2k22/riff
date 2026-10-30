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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings' opening page (owner, 30 Oct): the account, four quick switches, a search over the sections, Moods, and a
 * tile for each section saying what is set inside; a tile opens its section's page of glass tiles.
 */
@Composable internal fun SettingsScreen(model:ShellModel,bottom:Dp,provider:YouTubeMusicProvider,
    collections:LocalCollections,onNotice:(String)->Unit) {
    val preferences=LocalAccessibilitySettings.current
    val context=androidx.compose.ui.platform.LocalContext.current
    val audio=remember(context) {AudioBoostSettings(context)}
    val crossfade=remember(context) {CrossfadeSettings.get(context)}
    val dataSaver=remember(context) {DataSaverSettings(context)}
    val store=remember(context) {OfflineMusicStore.get(context)}
    val storeRevision by store.revision.collectAsStateWithLifecycle()
    val smartDownloads=remember(storeRevision) {store.smartEnabled}
    val hidden=remember(context) {NotInterestedStore.get(context)}
    val moods=remember(context) {MoodStore.get(context)}
    val equalizerOn=remember(context) {EqualizerSettings.read(EqualizerSettings.prefs(context)).enabled}
    var query by rememberSaveable {mutableStateOf("")}
    val mood=activeMood(builtInMoods+moods.kept,currentMoodValues(crossfade,audio,dataSaver))
    val automix=crossfade.enabled && crossfade.mode==BlendMode.Automix
    val tiles=listOf(
        SettingsTile(SettingsSection.Sound,"sound",R.string.settings_sound,Icons.Rounded.GraphicEq,Pink,R.string.settings_keywords_sound,
            listOf(if(!crossfade.enabled) string(R.string.summary_no_crossfade) else when(crossfade.mode) {
                BlendMode.Crossfade->string(R.string.summary_crossfade_x,crossfade.seconds); BlendMode.Smart->string(R.string.smart_crossfade)
                else->string(R.string.blend_automix_short)},
                if(audio.enabled) string(R.string.summary_boost_x,audio.percent) else string(if(equalizerOn) R.string.settings_eq_on else R.string.summary_no_boost)).joinToString(" · ")),
        SettingsTile(SettingsSection.Look,"look",R.string.settings_look_and_feel,Icons.Rounded.Opacity,Lavender,R.string.settings_keywords_look,
            string(R.string.summary_glass_x,(preferences.glassIntensity*100).toInt())),
        SettingsTile(SettingsSection.Lyrics,"lyrics",R.string.settings_lyrics,Icons.Rounded.Lyrics,Mint,R.string.settings_keywords_lyrics,
            string(if(preferences.playerLyricLine) R.string.summary_lyric_line_on else R.string.summary_lyric_line_off)),
        SettingsTile(SettingsSection.Downloads,"downloads",R.string.settings_downloads_and_data,Icons.Rounded.DownloadDone,Sky,R.string.settings_keywords_downloads,
            string(if(dataSaver.enabled) R.string.summary_data_saver_on else if(smartDownloads) R.string.summary_smart_downloads_on else R.string.summary_data_saver_off)),
        SettingsTile(SettingsSection.Library,"library",R.string.settings_library_and_privacy,Icons.Rounded.Lock,Coral,R.string.settings_keywords_library,
            hidden.entries.size.takeIf {it>0}?.let {string(R.string.summary_x_not_interested,it)} ?: string(R.string.summary_x_widgets,riffWidgetPresets.size)),
        SettingsTile(SettingsSection.About,"about",R.string.about,Icons.Rounded.Info,Stone,R.string.settings_keywords_about,
            BuildConfig.VERSION_NAME))
    fun matches(tile:SettingsTile)=query.isBlank() || listOf(string(tile.title),tile.summary,string(tile.keywords)).any {it.contains(query.trim(),ignoreCase=true)}
    ScreenList(model,bottom) {
        item {BackHeading(string(R.string.back),model::back);ScreenTitle(string(R.string.settings))}
        item {AccountCard(model,provider,Modifier.bentoEnter(0))}
        item {BentoHeading(string(R.string.settings_quick))}
        item {
            Row(Modifier.fillMaxWidth().bentoEnter(1),horizontalArrangement=Arrangement.spacedBy(RiffSpace.item)) {
                QuickTile(string(R.string.quick_automix),Icons.Rounded.Shuffle,automix,"settings_quick_automix",Modifier.weight(1f)) {on->
                    if(on) {crossfade.updateMode(BlendMode.Automix);crossfade.updateEnabled(true)} else crossfade.updateEnabled(false)}
                QuickTile(string(R.string.quick_boost),Icons.AutoMirrored.Rounded.VolumeUp,audio.enabled,"settings_quick_boost",Modifier.weight(1f),audio::updateEnabled)
                QuickTile(string(R.string.quick_saver),Icons.Rounded.DataSaverOn,dataSaver.enabled,"settings_quick_saver",Modifier.weight(1f),dataSaver::updateEnabled)
                QuickTile(string(R.string.quick_haptics),Icons.Rounded.Vibration,preferences.hapticFeedback,"settings_quick_haptics",Modifier.weight(1f),preferences::updateHapticFeedback)
            }
        }
        item {SettingsSearch(query,{query=it},Modifier.bentoEnter(2))}
        item {
            BentoTile(Modifier.fillMaxWidth().bentoEnter(3),lit=true,tint=Lavender,tag="settings_group_moods",
                onClick={model.push(Route.Settings(SettingsSection.Moods))}) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(RiffSpace.rowGap)) {
                    SettingIcon(Icons.Rounded.AutoAwesome,true,tint=Lavender)
                    Column(Modifier.weight(1f)) {
                        BentoTitle(string(R.string.settings_moods))
                        BentoHelp(mood?.let {string(R.string.mood_on_x,moodName(it))} ?: string(R.string.moods_detail))
                    }
                    Icon(Icons.Rounded.ChevronRight,null,tint=Muted)
                }
            }
        }
        tiles.chunked(2).forEachIndexed {row,pair->
            item {
                Box(Modifier.bentoEnter(4+row)) {
                    BentoPair({m->SectionTile(pair[0],matches(pair[0]),query.isNotBlank(),m) {model.push(Route.Settings(pair[0].section))}},
                        {m->SectionTile(pair[1],matches(pair[1]),query.isNotBlank(),m) {model.push(Route.Settings(pair[1].section))}})
                }
            }
        }
        item {Text(androidx.compose.ui.res.stringResource(R.string.about_build_identity, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildIdentity.current().shortId),color=Muted,fontSize=RiffType.detail,
            modifier=Modifier.fillMaxWidth().padding(vertical=RiffSpace.screenEdge),textAlign=androidx.compose.ui.text.style.TextAlign.Center)}
    }
}

/** One of the opening page's section tiles: its icon in its colour, its name, and what is set inside. */
private class SettingsTile(val section:SettingsSection,val id:String,val title:Int,val icon:ImageVector,val tint:Color,val keywords:Int,val summary:String)

@Composable private fun SectionTile(tile:SettingsTile,match:Boolean,searching:Boolean,modifier:Modifier,onOpen:()->Unit) {
    val motion=LocalMotionPolicy.current
    // While searching, the sections that match stand out and the rest fall back.
    val shown by animateFloatAsState(if(match) 1f else .25f,GlassMotion.timed(GlassMotion.selectionMs,motion),label="section match")
    BentoTile(modifier.graphicsLayer {alpha=shown},lit=searching && match,tint=tile.tint,onClick=onOpen,tag="settings_group_${tile.id}") {
        Box(Modifier.size(RiffSettings.badge).clip(CircleShape).background(tile.tint.copy(alpha=.18f)),contentAlignment=Alignment.Center) {
            Icon(tile.icon,null,tint=tile.tint,modifier=Modifier.size(RiffSettings.badgeIcon))
        }
        Spacer(Modifier.weight(1f).heightIn(min=RiffSpace.item))
        BentoTitle(string(tile.title))
        BentoHelp(tile.summary)
    }
}

/** A quick switch: solid pink while on, its icon popping as it turns. */
@Composable private fun QuickTile(label:String,icon:ImageVector,on:Boolean,tag:String,modifier:Modifier,onChange:(Boolean)->Unit) {
    val motion=LocalMotionPolicy.current
    val fill by animateColorAsState(if(on) Pink else White.copy(alpha=.06f),GlassMotion.timed(GlassMotion.selectionMs,motion),label="quick fill")
    val ink by animateColorAsState(if(on) PinkInk else White,GlassMotion.timed(GlassMotion.selectionMs,motion),label="quick ink")
    val shape=RoundedCornerShape(RiffRadius.hero)
    Column(modifier.heightIn(min=RiffSettings.quick).riffGlass(GlassRole.Card,RiffRadius.hero,backdrop=LocalPageBackdrop.current).clip(shape)
        .background(fill).border(RiffSettings.hairline,if(on) Pink else White.copy(alpha=.12f),shape)
        .toggleable(on,role=Role.Switch,onValueChange=hapticToggle(onChange)).testTag(tag).padding(vertical=RiffSpace.item),
        horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
        QuickIcon(icon,on,ink)
        Text(label,color=ink,fontSize=RiffType.detail,fontWeight=FontWeight.SemiBold,maxLines=1,modifier=Modifier.padding(top=RiffSpace.tight))
    }
}

/** The quick switch's icon pops past full size and tips as it turns on, dips as it turns off. */
@Composable private fun QuickIcon(icon:ImageVector,on:Boolean,ink:Color) {
    val motion=LocalMotionPolicy.current
    val scale=remember {androidx.compose.animation.core.Animatable(1f)}
    var settled by remember {mutableStateOf(false)}
    LaunchedEffect(on) {
        if(!settled) {settled=true;return@LaunchedEffect}
        if(motion.reduced) return@LaunchedEffect
        scale.animateTo(if(on) 1.35f else .75f,GlassMotion.timed(GlassMotion.pressMs,motion))
        scale.animateTo(1f,GlassMotion.pop(motion))
    }
    Icon(icon,null,tint=ink,modifier=Modifier.size(RiffSettings.quickIcon).graphicsLayer {scaleX=scale.value;scaleY=scale.value;rotationZ=(scale.value-1f)*-40f})
}

/** Search over the sections: their names, what is set in them, and the words for what is inside. */
@Composable private fun SettingsSearch(query:String,onQuery:(String)->Unit,modifier:Modifier) {
    val shape=RoundedCornerShape(RiffRadius.pill)
    Row(modifier.fillMaxWidth().height(RiffSettings.search).riffGlass(GlassRole.Card,RiffRadius.pill,backdrop=LocalPageBackdrop.current).clip(shape)
        .border(RiffSettings.hairline,White.copy(alpha=.12f),shape).padding(horizontal=RiffSpace.block),verticalAlignment=Alignment.CenterVertically,
        horizontalArrangement=Arrangement.spacedBy(RiffSpace.item)) {
        Icon(Icons.Rounded.Search,null,tint=Muted,modifier=Modifier.size(RiffSettings.badgeIcon))
        Box(Modifier.weight(1f)) {
            if(query.isEmpty()) Text(string(R.string.settings_search),color=Muted,fontSize=RiffType.body)
            androidx.compose.foundation.text.BasicTextField(query,onQuery,singleLine=true,
                textStyle=androidx.compose.ui.text.TextStyle(color=White,fontSize=RiffType.body),
                cursorBrush=androidx.compose.ui.graphics.SolidColor(Pink),
                modifier=Modifier.fillMaxWidth().testTag("settings_search").semantics {contentDescription=string(R.string.settings_search)})
        }
        if(query.isNotEmpty()) IconButton({onQuery("")},Modifier.size(RiffSpace.touch)) {Icon(Icons.Rounded.Close,string(R.string.settings_search_clear),tint=Muted)}
    }
}

/** The account at the top of Settings. */
@Composable private fun AccountCard(model:ShellModel,provider:YouTubeMusicProvider,modifier:Modifier) {
    BentoTile(modifier.fillMaxWidth(),lit=true,onClick={model.push(Route.Account)},tag="settings_account") {
        Row(verticalAlignment=Alignment.CenterVertically) {
            val image=provider.accountProfile?.artworkUrl
            if(image!=null) AsyncImage(image,null,contentScale=ContentScale.Crop,modifier=Modifier.size(RiffSettings.avatar).clip(CircleShape))
            else Icon(Icons.Rounded.AccountCircle,null,tint=Pink,modifier=Modifier.size(RiffSettings.avatar))
            Column(Modifier.weight(1f).padding(horizontal=RiffSpace.rowGap)) {
                Text(provider.accountProfile?.name ?: if(provider.signedIn) string(R.string.your_account) else "YouTube Music",
                    color=White,fontSize=RiffSettings.title,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(if(provider.signedIn) string(R.string.manage_account) else string(R.string.sign_in),color=Muted,fontSize=RiffType.detail)
            }
            Icon(Icons.Rounded.ChevronRight,null,tint=Muted)
        }
    }
}

@Composable internal fun SettingsSection(title:String,content:@Composable ColumnScope.()->Unit) {
    Column(Modifier.fillMaxWidth().padding(top=10.dp,bottom=RiffSpace.tight),verticalArrangement=Arrangement.spacedBy(RiffSpace.item)) {
        Text(title.uppercase(),color=Muted,fontSize=RiffType.detail,fontWeight=FontWeight.SemiBold,letterSpacing=.8.sp,
            modifier=Modifier.padding(start=RiffSpace.item).semantics {heading()})
        // Feature 2: a glass card over the screen's artwork-coloured background.
        Surface(Modifier.fillMaxWidth().riffGlass(GlassRole.Card,RiffRadius.hero,backdrop=LocalPageBackdrop.current),
            shape=RoundedCornerShape(RiffRadius.hero),color=Color.Transparent) {
            Column(Modifier.padding(horizontal=RiffSpace.block,vertical=RiffSpace.tight),content=content)
        }
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
        SettingIcon(icon,checked,badge=false)
        Column(Modifier.weight(1f)) {
            Text(title,color=White,style=RiffText.settingsRow)
            if(subtitle!=null) Text(subtitle,color=White.copy(alpha=.55f),style=RiffText.settingsHelp,modifier=Modifier.padding(top=3.dp))
        }
        GlassSwitch(checked)
    }
}
@Composable internal fun SettingsLinkRow(title:String,value:String?=null,icon:ImageVector,tag:String,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=56.dp).testTag(tag).clickable(onClick=hapticClick(onClick))
        .padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(RiffSpace.rowGap)) {
        Icon(icon,null,tint=Muted,modifier=Modifier.size(21.dp))
        Text(title,color=White,style=RiffText.settingsRow,modifier=Modifier.weight(1f))
        if(value!=null) Text(value,color=White.copy(alpha=.55f),style=RiffText.settingsHelp)
        Icon(Icons.Rounded.ChevronRight,null,tint=Muted,modifier=Modifier.size(18.dp))
    }
}

@Composable internal fun WidgetSettings(onNotice:(String)->Unit) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val manager=remember(context) {android.appwidget.AppWidgetManager.getInstance(context)}
    riffWidgetPresets.forEach {preset->
        SettingsLinkRow(preset.title,icon=Icons.Rounded.Widgets,tag="widget_add_${preset.title.lowercase()}") {
            if(manager.isRequestPinAppWidgetSupported) manager.requestPinAppWidget(android.content.ComponentName(context,preset.receiver),null,null)
            else onNotice(string(R.string.touch_and_hold_your_home_screen_choose))
        }
    }
}
