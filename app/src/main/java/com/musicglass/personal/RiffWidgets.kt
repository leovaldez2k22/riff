package com.musicglass.personal

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.widget.RemoteViews
import androidx.media3.common.Player
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal data class WidgetPreset(val title:String,val receiver:Class<out AppWidgetProvider>,val layout:Int)
internal val riffWidgetPresets=listOf(
    WidgetPreset("Compact",CompactRiffWidget::class.java,R.layout.widget_compact),
    WidgetPreset("Wide",WideRiffWidget::class.java,R.layout.widget_wide),
    WidgetPreset("Artwork",ArtworkRiffWidget::class.java,R.layout.widget_artwork),
    WidgetPreset("Player",PlayerRiffWidget::class.java,R.layout.widget_player),
    WidgetPreset("Large",LargeRiffWidget::class.java,R.layout.widget_large),
    WidgetPreset("Circle",CircleRiffWidget::class.java,R.layout.widget_circle))
internal const val WIDGET_CONTROL="com.musicglass.personal.WIDGET_CONTROL"
internal fun widgetCommandAllowed(command:String)=command in setOf("toggle","previous","next")
internal object RiffWidgets {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val generation=AtomicLong()
    private var current:WidgetState?=null
    internal data class WidgetState(val title:String="Riff",val artist:String="Ready when you are",val art:String?=null,val playing:Boolean=false,val next:String="",val available:Boolean=false)
    fun token(context:Context):String {
        val prefs=context.getSharedPreferences("widget_control",Context.MODE_PRIVATE)
        synchronized(this) {
            return prefs.getString("token",null)?:UUID.randomUUID().toString().also {prefs.edit().putString("token",it).apply()}
        }
    }
    fun publish(context:Context,player:Player) {
        val item=player.currentMediaItem;val meta=item?.mediaMetadata
        val next=if(player.hasNextMediaItem()) player.getMediaItemAt(player.nextMediaItemIndex).mediaMetadata.title?.toString().orEmpty() else ""
        val state=WidgetState(meta?.title?.toString()?:"Riff",meta?.artist?.toString()?:"Ready when you are",meta?.artworkUri?.toString(),player.isPlaying,next,item!=null)
        if(state==current) return
        current=state;refresh(context)
    }
    fun refresh(context:Context,finished:()->Unit={}) {
        val app=context.applicationContext;val ticket=generation.incrementAndGet()
        scope.launch {try {
            val manager=AppWidgetManager.getInstance(app)
            if(riffWidgetPresets.none {manager.getAppWidgetIds(ComponentName(app,it.receiver)).isNotEmpty()}) return@launch
            val saved=app.getSharedPreferences("last_playback",Context.MODE_PRIVATE).getString("snapshot",null)?.let(::decodePlaybackSnapshot)
            val state=current?:saved?.let {s->val q=s.queue[s.index];WidgetState(q.title,q.artist,q.artworkUrl,false,s.queue.getOrNull(s.index+1)?.title.orEmpty(),true)}?:WidgetState()
            configureArtworkLoader(app)
            val bitmap=state.art?.let {url->withTimeoutOrNull(3500) {runCatching {
                SingletonImageLoader.get(app).execute(ImageRequest.Builder(app).data(url).size(256).allowHardware(false).build()).image?.toBitmap()
            }.getOrNull()}}
            if(ticket!=generation.get()) return@launch
            riffWidgetPresets.forEachIndexed {index,preset->
                manager.getAppWidgetIds(ComponentName(app,preset.receiver)).forEach {id->manager.updateAppWidget(id,views(app,preset.layout,index,id,state,bitmap))}
            }
        } finally {finished()}}
    }
    internal fun views(context:Context,layout:Int,preset:Int,id:Int,state:WidgetState,bitmap:Bitmap?):RemoteViews {
        val views=RemoteViews(context.packageName,layout)
        views.setTextViewText(R.id.widget_title,state.title)
        if(preset!=5) views.setTextViewText(R.id.widget_artist,state.artist)
        if(preset==4) views.setTextViewText(R.id.widget_up_next,if(state.next.isBlank()) "" else "Up next: ${state.next}")
        if(bitmap!=null) views.setImageViewBitmap(R.id.widget_art,rounded(bitmap,preset==5))
        else views.setImageViewResource(R.id.widget_art,R.drawable.ic_launcher_foreground)
        views.setImageViewResource(R.id.widget_toggle,if(state.playing) R.drawable.widget_pause else R.drawable.widget_toggle)
        val open=PendingIntent.getActivity(context,id,Intent(context,MainActivity::class.java).apply {flags=Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP},PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        views.setOnClickPendingIntent(R.id.widget_root,open)
        listOf("toggle" to R.id.widget_toggle,"previous" to R.id.widget_previous,"next" to R.id.widget_next).forEachIndexed {offset,(command,viewId)->
            if(preset==0 && command!="toggle") return@forEachIndexed
            val intent=Intent(context,MusicPlaybackService::class.java).setAction(WIDGET_CONTROL).putExtra("widgetToken",token(context)).putExtra("command",command)
            if(!state.available) {views.setOnClickPendingIntent(viewId,open);return@forEachIndexed}
            views.setOnClickPendingIntent(viewId,PendingIntent.getForegroundService(context,id*4+offset,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        return views
    }
    private fun rounded(source:Bitmap,circle:Boolean):Bitmap {
        val output=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888)
        val scale=256f/minOf(source.width,source.height)
        val shader=BitmapShader(source,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP)
        shader.setLocalMatrix(Matrix().apply {setScale(scale,scale);postTranslate((256-source.width*scale)/2,(256-source.height*scale)/2)})
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {this.shader=shader}
        val canvas=Canvas(output)
        if(circle) canvas.drawCircle(128f,128f,128f,paint) else canvas.drawRoundRect(0f,0f,256f,256f,28f,28f,paint)
        return output
    }
}
abstract class RiffWidgetProvider:AppWidgetProvider() {
    override fun onUpdate(context:Context,manager:AppWidgetManager,ids:IntArray) {val pending=goAsync();RiffWidgets.refresh(context) {pending.finish()}}
    override fun onAppWidgetOptionsChanged(context:Context,manager:AppWidgetManager,id:Int,options:android.os.Bundle) {val pending=goAsync();RiffWidgets.refresh(context) {pending.finish()}}
}
class CompactRiffWidget:RiffWidgetProvider()
class WideRiffWidget:RiffWidgetProvider()
class ArtworkRiffWidget:RiffWidgetProvider()
class PlayerRiffWidget:RiffWidgetProvider()
class LargeRiffWidget:RiffWidgetProvider()
class CircleRiffWidget:RiffWidgetProvider()
