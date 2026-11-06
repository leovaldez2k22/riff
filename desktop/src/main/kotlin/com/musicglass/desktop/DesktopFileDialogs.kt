package com.musicglass.desktop
import androidx.compose.runtime.staticCompositionLocalOf
import java.awt.*
import java.io.File

internal val LocalDesktopWindow=staticCompositionLocalOf<Window?>{null}
/** A real native save dialog owned by this Compose window, not Swing's unrelated shared hidden frame. */
internal fun chooseDesktopSaveFile(owner:Window?,title:String,filename:String):File?{
    check(EventQueue.isDispatchThread()){"File picker must run on the AWT event thread"}
    val dialog=when(owner){
        is Frame->FileDialog(owner,title,FileDialog.SAVE)
        is Dialog->FileDialog(owner,title,FileDialog.SAVE)
        else->error("The application window is unavailable")
    }
    return try{dialog.file=filename;dialog.isAlwaysOnTop=true;dialog.isVisible=true;dialog.files.firstOrNull()}finally{dialog.dispose()}
}
