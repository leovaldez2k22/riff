package com.musicglass.desktop

import androidx.compose.runtime.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.Role
import com.musicglass.data.*

internal val LocalDesktopHost=staticCompositionLocalOf<DesktopHost?>{null}

@Composable internal fun LibraryTools(font:FontFamily){
    val host=LocalDesktopHost.current?:return
    val dialogOwner=LocalDesktopWindow.current
    val state by host.state.collectAsState()
    var creating by remember{mutableStateOf(false)}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
            DataAction("Add music folder",font){javax.swing.SwingUtilities.invokeLater{val chooser=javax.swing.JFileChooser().apply{fileSelectionMode=javax.swing.JFileChooser.DIRECTORIES_ONLY;dialogTitle="Add music folder"};if(chooser.showOpenDialog(dialogOwner)==javax.swing.JFileChooser.APPROVE_OPTION)host.dispatch(DesktopCommand.RegisterLibraryRoot(chooser.selectedFile))}}
            DataAction("New playlist",font){creating=true}
            if(state.libraryOffset>0)DataAction("Previous 500",font){host.dispatch(DesktopCommand.PreviousLibraryPage)}
            if(state.libraryOffset+state.libraryTracks.size<state.libraryCount)DataAction("Next 500 (${state.libraryOffset+state.libraryTracks.size}/${state.libraryCount})",font){host.dispatch(DesktopCommand.LoadMoreLibrary)}
        }
        state.scanStatus?.let{Label(it,font,RiffDesign.muted,size=12)}
        state.scanIssues.take(10).forEach{(path,reason)->Label("${java.io.File(path).name}: $reason",font,RiffDesign.muted,size=12)}
    }
    if(creating)NameDialog("New local playlist","",font,{creating=false}){host.dispatch(DesktopCommand.CreatePlaylist(it));creating=false}
}
@Composable internal fun DataAction(text:String,font:FontFamily,onClick:()->Unit){
    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha=.08f)).clickable(role=Role.Button,onClick=onClick).padding(horizontal=12.dp,vertical=8.dp)){Label(text,font,RiffDesign.pink,size=13)}
}
@Composable internal fun NameDialog(title:String,initial:String,font:FontFamily,onDismiss:()->Unit,onSave:(String)->Unit){
    var text by remember{mutableStateOf(initial)}
    val focus=remember{FocusRequester()}
    Popup(alignment=Alignment.Center,onDismissRequest=onDismiss,properties=PopupProperties(focusable=true)){
        LaunchedEffect(Unit){withFrameNanos{};focus.requestFocus()}
        Column(Modifier.width(420.dp).onPreviewKeyEvent{event->
            if(event.type!=KeyEventType.KeyDown)false else when(event.key){
                Key.Enter,Key.NumPadEnter->{if(text.isNotBlank())onSave(text.trim());true}
                Key.Escape->{onDismiss();true}
                else->false
            }
        }.clip(RoundedCornerShape(16.dp)).background(RiffDesign.ink).border(1.dp,Color.White.copy(alpha=.12f),RoundedCornerShape(16.dp)).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            Label(title,font,Color.White,size=22)
            BasicTextField(text,{text=it.take(300)},singleLine=true,textStyle=TextStyle(color=Color.White,fontFamily=font,fontSize=16.sp),cursorBrush=SolidColor(RiffDesign.pink),modifier=Modifier.fillMaxWidth().focusRequester(focus).background(Color.White.copy(alpha=.06f)).padding(12.dp))
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){DataAction("Cancel",font,onDismiss);if(text.isNotBlank())DataAction("Save",font){onSave(text.trim())}}
        }
    }
}
@Composable internal fun PlaylistEditTools(playlist:PlaylistItem,font:FontFamily){
    val host=LocalDesktopHost.current?:return
    var rename by remember{mutableStateOf(false)}
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
        DataAction("Rename",font){rename=true}
        DataAction("Delete local playlist",font){host.dispatch(DesktopCommand.DeletePlaylist(playlist.id));host.dispatch(DesktopCommand.SetRoute(WorkspaceRoute.Library))}
    }
    if(rename)NameDialog("Rename playlist",playlist.name,font,{rename=false}){host.dispatch(DesktopCommand.RenamePlaylist(playlist.id,it));rename=false}
}
@Composable internal fun TrackPlaylistAction(track:SongItem,font:FontFamily){
    val host=LocalDesktopHost.current?:return
    val state by host.state.collectAsState()
    var selecting by remember{mutableStateOf(false)}
    DataAction("+",font){selecting=!selecting}
    if(selecting)Popup(alignment=Alignment.Center,onDismissRequest={selecting=false},properties=PopupProperties(focusable=true)){
        androidx.compose.foundation.lazy.LazyColumn(Modifier.width(420.dp).heightIn(max=480.dp).clip(RoundedCornerShape(16.dp)).background(RiffDesign.ink).padding(24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            item{Label("Add to local playlist",font,Color.White,size=22)}
            if(track.scope in setOf(Scopes.LOCAL,Scopes.GUEST)&&track.playbackSource.startsWith("youtube:"))item{DataAction("Save offline",font){host.dispatch(DesktopCommand.DownloadTrack(track));selecting=false}}
            if(state.libraryPlaylists.isEmpty())item{Label("Create a playlist in Your Library first.",font,RiffDesign.muted)}
            items(state.libraryPlaylists.size){index->val p=state.libraryPlaylists[index];DataAction(p.name,font){host.dispatch(DesktopCommand.AddToPlaylist(p.id,track));selecting=false}}
        }
    }
}
@Composable internal fun HistoryPrivacyTools(font:FontFamily){
    val host=LocalDesktopHost.current?:return
    val state by host.state.collectAsState();val prefs=state.historyPrivacy
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Label("LISTENING HISTORY & PRIVACY",font,RiffDesign.muted,size=13)
        DataAction("Public search suggestions: ${if(state.searchSuggestionsEnabled)"On"else"Off"}",font){host.dispatch(DesktopCommand.SetSearchSuggestions(!state.searchSuggestionsEnabled))}
        DataAction(if(prefs.saveHistory)"Save listening history: On"else"Save listening history: Off",font){host.dispatch(DesktopCommand.SetHistoryPrivacy(prefs.copy(saveHistory=!prefs.saveHistory)))}
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){for(days in listOf(30,90,365,0))DataAction(if(days==0)"Forever"else"$days days",font){host.dispatch(DesktopCommand.SetHistoryPrivacy(prefs.copy(retentionDays=days)))}}
        Label("Retention: ${if(prefs.retentionDays==0)"Forever"else "${prefs.retentionDays} days"}. Genres: ${if(prefs.genreLookup)"On"else"Off"}.",font,RiffDesign.muted,size=12)
        DataAction("Find genres for your artists: ${if(prefs.genreLookup)"On"else"Off"}",font){host.dispatch(DesktopCommand.SetHistoryPrivacy(prefs.copy(genreLookup=!prefs.genreLookup)))}
        Label("When enabled, sends artist names to MusicBrainz on unmetered connections; cached for 90 days.",font,RiffDesign.muted,size=12)
        DataAction("Preserve totals after retention: ${if(prefs.keepTotals)"On"else"Off"}",font){host.dispatch(DesktopCommand.SetHistoryPrivacy(prefs.copy(keepTotals=!prefs.keepTotals)))}
        DataAction("Clear history and Encore",font){host.dispatch(DesktopCommand.ClearHistory)}
        if(host.isAuthenticationConfigured)DataAction("Sign in to YouTube (read-only)",font){host.dispatch(DesktopCommand.SignIn)}
        DataAction("Sign out",font){host.dispatch(DesktopCommand.SignOut)}
        Label("Account sign-in needs a configured Desktop OAuth client. Public discovery and local music work without sign-in.",font,RiffDesign.muted,size=13)
    }
}

@Composable internal fun PlaylistOccurrenceTools(playlist:PlaylistItem,index:Int,font:FontFamily){
    val host=LocalDesktopHost.current?:return
    val entry=playlist.entryIds.getOrNull(index)?:return
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
        if(playlist.entryOffset+index>0)DataAction("Move up",font){host.dispatch(DesktopCommand.MovePlaylistOccurrence(playlist.id,entry,true))}
        if(playlist.entryOffset+index<playlist.trackCount-1)DataAction("Move down",font){host.dispatch(DesktopCommand.MovePlaylistOccurrence(playlist.id,entry,false))}
        DataAction("Remove occurrence",font){host.dispatch(DesktopCommand.RemovePlaylistEntry(playlist.id,entry))}
    }
}
@Composable internal fun LocalCollectionTools(font:FontFamily){
    val host=LocalDesktopHost.current?:return
    val state by host.state.collectAsState()
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
        Label("${state.collectionOffset+state.collectionTracks.size}/${state.collectionCount} tracks",font,RiffDesign.muted,size=12)
        if(state.collectionOffset>0)DataAction("Previous 500",font){host.dispatch(DesktopCommand.LocalCollectionPage((state.collectionOffset-500).coerceAtLeast(0)))}
        if(state.collectionOffset+state.collectionTracks.size<state.collectionCount)DataAction("Next 500",font){host.dispatch(DesktopCommand.LocalCollectionPage(state.collectionOffset+500))}
    }
}
@Composable internal fun PlaylistPagingTools(playlist:PlaylistItem,font:FontFamily){
    val host=LocalDesktopHost.current?:return
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
        Label("${playlist.entryOffset+playlist.tracks.size}/${playlist.trackCount} tracks",font,RiffDesign.muted,size=12)
        if(playlist.entryOffset>0)DataAction("Previous 500",font){host.dispatch(DesktopCommand.PlaylistPage(playlist.id,(playlist.entryOffset-500).coerceAtLeast(0)))}
        if(playlist.entryOffset+playlist.tracks.size<playlist.trackCount)DataAction("Next 500",font){host.dispatch(DesktopCommand.PlaylistPage(playlist.id,playlist.entryOffset+500))}
    }
}

@Composable internal fun LocalCollectionStatus(id:String,font:FontFamily):Boolean {
    val host=LocalDesktopHost.current?:return false
    val state by host.state.collectAsState()
    if(state.collectionId!=id)return false
    when(val page=state.collectionState){
        PageState.Loading->{Label("Loading collection…",font,RiffDesign.muted);return true}
        PageState.NotFound->{Label("Collection not found",font,RiffDesign.muted);return true}
        is PageState.Failed->{Column{Label(page.message,font,RiffDesign.muted);DataAction("Retry",font){host.dispatch(DesktopCommand.LocalCollectionPage(state.collectionOffset))}};return true}
        else->return false
    }
}

@Composable internal fun SleepTimerTools(font:FontFamily){
    val host=LocalDesktopHost.current?:return
    val state by host.state.collectAsState()
    var fade by remember{mutableLongStateOf(15000)}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Label("SLEEP TIMER",font,RiffDesign.muted,size=13)
        Label(when(state.sleep.mode){SleepMode.Off->"Off";SleepMode.Deadline->"Stops at ${java.time.Instant.ofEpochMilli(state.sleep.deadlineMs).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withNano(0)}";SleepMode.Tracks->"${state.sleep.songsLeft} songs left";SleepMode.Queue->"Stops at the end of this queue"},font,Color.White,size=14)
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
            for(minutes in listOf(15,30,60))DataAction("${minutes}m",font){host.dispatch(DesktopCommand.SetSleepTimer(SleepPlan(SleepMode.Deadline,System.currentTimeMillis()+minutes*60000L,fadeMs=fade)))}
            DataAction("Cancel",font){host.dispatch(DesktopCommand.SetSleepTimer(SleepPlan()))}
        }
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
            for(count in listOf(1,2,3,5))DataAction(if(count==1)"This song"else"$count songs",font){host.dispatch(DesktopCommand.SetSleepTimer(SleepPlan(SleepMode.Tracks,songsLeft=count,fadeMs=fade)))}
            DataAction("Queue end",font){host.dispatch(DesktopCommand.SetSleepTimer(SleepPlan(SleepMode.Queue,fadeMs=fade)))}
        }
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
            for(seconds in listOf(0,5,15,30,60))DataAction("Fade ${seconds}s${if(fade==seconds*1000L)" ✓"else""}",font){fade=seconds*1000L;if(state.sleep.mode!=SleepMode.Off)host.dispatch(DesktopCommand.SetSleepTimer(state.sleep.copy(fadeMs=fade)))}
        }
    }
}

@Composable internal fun DownloadLibraryRow(item:DesktopDownloadItem,font:FontFamily,onPlay:(SongItem)->Unit){
    val host=LocalDesktopHost.current?:return
    val job=item.job
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha=.04f)).padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Label(item.song.title,font,Color.White,size=15)
        Label("${item.song.artist} · ${downloadStatus(job)}",font,RiffDesign.muted,size=12)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            if(job.state==DownloadState.Complete)DataAction("Play offline",font){onPlay(item.song)}
            if(job.state in setOf(DownloadState.Paused,DownloadState.Failed))DataAction(if(job.state==DownloadState.Failed)"Retry"else"Resume",font){host.dispatch(DesktopCommand.PauseDownload(job.scope,job.trackId,false))}
            if(job.state in setOf(DownloadState.Queued,DownloadState.Resolving,DownloadState.Transferring,DownloadState.Verifying))DataAction("Pause",font){host.dispatch(DesktopCommand.PauseDownload(job.scope,job.trackId,true))}
            if(job.state!=DownloadState.Removing)DataAction("Remove",font){host.dispatch(DesktopCommand.RemoveDownload(job.scope,job.trackId))}
        }
    }
}
private fun downloadStatus(job:DownloadJob):String=when(job.state){
    DownloadState.Queued->"Queued"
    DownloadState.Resolving->"Finding audio"
    DownloadState.Transferring->if(job.total>0)"${job.bytes*100/job.total}% saved"else"${job.bytes/1024} KB saved"
    DownloadState.Paused->"Paused"
    DownloadState.Verifying->"Checking audio"
    DownloadState.Complete->"Available offline · ${job.bytes/1024} KB"
    DownloadState.Removing->"Waiting to remove files in use"
    DownloadState.Failed->when(job.errorCode){"STORAGE_LIMIT"->"Free some disk space, then retry";"TRACK_TOO_LARGE","DURATION_LIMIT"->"Track exceeds the download limit";"REPRESENTATION_CHANGED"->"The source changed. Retry to start a fresh copy";"INTEGRITY_FAILED","CORRUPT_AUDIO"->"Saved audio failed its check. Retry to replace it";else->"Download stopped. Check the connection and retry"}
}
