package com.musicglass.personal
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun SongActionMenu(expanded:Boolean,onDismissRequest:()->Unit,modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit) {
    val panel=modifier.widthIn(min=220.dp,max=290.dp).clip(RoundedCornerShape(18.dp))
    DropdownMenu(expanded,onDismissRequest,if(expanded) panel.frostedPopupBackground() else panel,shape=RoundedCornerShape(18.dp),
        containerColor=androidx.compose.ui.graphics.Color.Transparent,tonalElevation=0.dp,shadowElevation=16.dp,
        border=BorderStroke(.6.dp,White.copy(alpha=.16f)),content=content)
}
@Composable internal fun SongActionItem(label:String,onClick:()->Unit) {
    val icon=when(label) {
        "View artist"->Icons.Rounded.Person;"View album"->Icons.Rounded.Album
        "Play next"->Icons.Rounded.PlaylistPlay;"Add to queue"->Icons.Rounded.QueueMusic
        "Add to playlist"->Icons.Rounded.PlaylistAdd;"Share song","Share"->Icons.Rounded.Share
        "Sleep timer"->Icons.Rounded.Bedtime;"View credits"->Icons.Rounded.Info;"Favorite"->Icons.Rounded.StarBorder
        "Download details"->Icons.Rounded.Info;"Resume download","Retry missing assets"->Icons.Rounded.Refresh
        "Keep downloaded"->Icons.Rounded.DownloadDone;"Pause download"->Icons.Rounded.Pause;"Remove download"->Icons.Rounded.DeleteOutline
        "Download song"->Icons.Rounded.Download;"Not interested"->Icons.Rounded.Block;"Open"->Icons.Rounded.OpenInNew;"Remove from queue"->Icons.Rounded.DeleteOutline;else->Icons.Rounded.LibraryMusic
    }
    DropdownMenuItem(text={Text(label,color=if(label.startsWith("Remove")) Pink else White,fontSize=14.sp)},onClick = if(label in setOf("Favorite","Play next","Add to queue","Add to playlist")) onClick else hapticClick(onClick),
        trailingIcon={Icon(icon,null,tint=if(label.startsWith("Remove")) Pink else White.copy(alpha=.7f),modifier=Modifier.size(18.dp))},
        modifier=Modifier.heightIn(min=44.dp),contentPadding=PaddingValues(horizontal=14.dp))
    HorizontalDivider(color=White.copy(alpha=.08f),modifier=Modifier.padding(horizontal=12.dp))
}
