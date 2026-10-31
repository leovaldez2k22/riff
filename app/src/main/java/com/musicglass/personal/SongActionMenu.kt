package com.musicglass.personal
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable internal fun SongActionMenu(expanded:Boolean,onDismissRequest:()->Unit,modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit) {
    // The pop-out look (RiffMenu); the glass draws its own rim.
    val panel=modifier.widthIn(min=RiffMenu.minWidth,max=RiffMenu.maxWidth).clip(RoundedCornerShape(RiffMenu.corner))
    DropdownMenu(expanded,onDismissRequest,if(expanded) panel.frostedPopupBackground(RiffMenu.corner) else panel,shape=RoundedCornerShape(RiffMenu.corner),
        containerColor=androidx.compose.ui.graphics.Color.Transparent,tonalElevation=0.dp,shadowElevation=16.dp,content=content)
}
@Composable internal fun SongActionItem(label:String,onClick:()->Unit) {
    val icon=when(label) {
        "View artist"->Icons.Rounded.Person;"View album"->Icons.Rounded.Album
        "Play next"->Icons.Rounded.PlaylistPlay;"Add to queue"->Icons.Rounded.QueueMusic
        "Add to playlist"->Icons.Rounded.PlaylistAdd;"Share song","Share"->Icons.Rounded.Share
        "Sleep timer"->Icons.Rounded.Bedtime;"Playback speed"->Icons.Rounded.Speed;"Stats for nerds"->Icons.Rounded.Analytics;"Equalizer"->Icons.Rounded.Equalizer;"View credits"->Icons.Rounded.Info;"Favorite"->Icons.Rounded.StarBorder
        "Download details"->Icons.Rounded.Info;"Resume download","Retry missing assets"->Icons.Rounded.Refresh
        "Keep downloaded"->Icons.Rounded.DownloadDone;"Pause download"->Icons.Rounded.Pause;"Remove download"->Icons.Rounded.DeleteOutline
        "Download song"->Icons.Rounded.Download;"Not interested"->Icons.Rounded.Block;"Open"->Icons.Rounded.OpenInNew;"Remove from queue"->Icons.Rounded.DeleteOutline
        "Your history with this"->Icons.Rounded.History;"Play"->Icons.Rounded.PlayArrow;"Pin to speed dial"->Icons.Rounded.PushPin;"Unpin from speed dial"->Icons.Rounded.PushPin;else->Icons.Rounded.LibraryMusic
    }
    // The pop-out look (RiffMenu): the icon leads, the words are regular, and rows are not ruled one from the next.
    val tint=if(label.startsWith("Remove")) Pink else White
    DropdownMenuItem(text={Text(keyLabel(label),color=tint,style=RiffMenu.text)},onClick = if(label in setOf("Favorite","Play next","Add to queue","Add to playlist")) onClick else hapticClick(onClick),
        // Material leaves 12 dp after the icon; the reference has 16.
        leadingIcon={Icon(icon,null,tint=tint,modifier=Modifier.padding(end=RiffMenu.iconGap-12.dp).size(RiffMenu.icon))},
        modifier=Modifier.heightIn(min=RiffMenu.row),contentPadding=PaddingValues(horizontal=RiffMenu.edge))
}

/** A hairline between groups of rows, as the reference has. */
@Composable internal fun SongActionDivider() =
    HorizontalDivider(color=RiffMenu.dividerColor,modifier=Modifier.padding(horizontal=RiffMenu.dividerInset,vertical=4.dp))
