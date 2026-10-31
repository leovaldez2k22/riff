package com.musicglass.personal

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.SystemClock
import android.text.format.DateUtils
import android.view.View
import android.widget.RemoteViews
import androidx.media3.common.C
import androidx.media3.common.Player
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/*
 * The home widgets in the app's glass (owner, 31 Oct 2026). A widget cannot blur what is under it, so the glass is the
 * cover itself, blurred and darkened, under a sheen and a hairline rim. [progress]: the preset shows where the song is.
 */
internal data class WidgetPreset(private val titleRes:Int,val receiver:Class<out AppWidgetProvider>,val layout:Int,val progress:Boolean=true) {val title:String get()=string(titleRes)}
internal val riffWidgetPresets=listOf(
    WidgetPreset(R.string.compact,CompactRiffWidget::class.java,R.layout.widget_compact,progress=false),
    WidgetPreset(R.string.wide,WideRiffWidget::class.java,R.layout.widget_wide),
    WidgetPreset(R.string.artwork,ArtworkRiffWidget::class.java,R.layout.widget_artwork),
    WidgetPreset(R.string.player,PlayerRiffWidget::class.java,R.layout.widget_player),
    WidgetPreset(R.string.large,LargeRiffWidget::class.java,R.layout.widget_large),
    WidgetPreset(R.string.circle,CircleRiffWidget::class.java,R.layout.widget_circle))
internal const val WIDGET_CONTROL="com.musicglass.personal.WIDGET_CONTROL"
/** While playing with the screen on, the widgets' progress is moved on this often; seeks and song changes move it at once. */
internal const val WIDGET_TICK_MS=10_000L
internal fun widgetCommandAllowed(command:String)=command in setOf("toggle","previous","next","like","shuffle","queue")
/** The item [ahead] songs on in the order the player will play them, or null when the queue ends first. */
internal fun Player.indexAhead(ahead:Int):Int? {
    val timeline=currentTimeline
    if(timeline.isEmpty) return null
    var index=currentMediaItemIndex
    repeat(ahead) {
        index=timeline.getNextWindowIndex(index,if(repeatMode==Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else repeatMode,shuffleModeEnabled)
        if(index==C.INDEX_UNSET) return null
    }
    return index
}
/** Where the song was at [atMs] (elapsed realtime). */
internal data class WidgetProgress(val positionMs:Long=0,val durationMs:Long=0,val playing:Boolean=false,val atMs:Long=SystemClock.elapsedRealtime()) {
    fun positionAt(now:Long)=(positionMs+if(playing) now-atMs else 0).coerceIn(0,durationMs.coerceAtLeast(0))
    /** Thousandths of the song at [now]; none when its length is not known (a live stream). */
    fun permille(now:Long=SystemClock.elapsedRealtime())=if(durationMs<=0) 0 else (positionAt(now)*1000/durationMs).toInt()
}
internal object RiffWidgets {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val generation=AtomicLong()
    private var current:WidgetState?=null
    @Volatile private var progress=WidgetProgress()
    /** Whether a widget that shows progress is on the home screen, as of the last full update. */
    @Volatile private var progressShown=false
    /** [liked]: null when the song cannot be liked (not signed in, not a YouTube Music song, live). [later]: the song after [next]. */
    internal data class WidgetState(val title:String="Riff",val artist:String=string(R.string.ready_when_you_are),val art:String?=null,val playing:Boolean=false,val next:String="",val available:Boolean=false,
        val liked:Boolean?=null,val shuffle:Boolean=false,val later:String="")
    fun token(context:Context):String {
        val prefs=context.getSharedPreferences("widget_control",Context.MODE_PRIVATE)
        synchronized(this) {
            return prefs.getString("token",null)?:UUID.randomUUID().toString().also {prefs.edit().putString("token",it).apply()}
        }
    }
    /** [seeked]: the position jumped, so the progress is sent even when nothing else changed. */
    fun publish(context:Context,player:Player,liked:Boolean?,seeked:Boolean=false) {
        val item=player.currentMediaItem;val meta=item?.mediaMetadata
        fun titleAhead(ahead:Int)=player.indexAhead(ahead)?.let {player.getMediaItemAt(it).mediaMetadata.title?.toString()}.orEmpty()
        val state=WidgetState(meta?.title?.toString()?:"Riff",meta?.artist?.toString()?:string(R.string.ready_when_you_are),meta?.artworkUri?.toString(),player.isPlaying,titleAhead(1),item!=null,
            liked,player.shuffleModeEnabled,titleAhead(2))
        val moved=progressOf(player).let {it.playing!=progress.playing || it.durationMs!=progress.durationMs}
        progress=progressOf(player)
        if(state==current) {if(seeked || moved) tick(context);return}
        current=state;refresh(context)
    }
    /** Moves the progress on without sending the cover again. */
    fun tick(context:Context,player:Player) {progress=progressOf(player);tick(context)}
    // A live stream reports its replay window as a length, with the position at its end; it has no length to show.
    private fun progressOf(player:Player)=WidgetProgress(player.currentPosition,player.duration.takeIf {it!=C.TIME_UNSET && !player.isCurrentMediaItemLive}?:0,player.isPlaying)
    private fun tick(context:Context) {
        if(!progressShown) return
        val app=context.applicationContext;val now=progress
        scope.launch {
            val manager=AppWidgetManager.getInstance(app)
            riffWidgetPresets.forEachIndexed {index,preset->
                if(!preset.progress) return@forEachIndexed
                val ids=manager.getAppWidgetIds(ComponentName(app,preset.receiver)).takeIf {it.isNotEmpty()}?:return@forEachIndexed
                manager.partiallyUpdateAppWidget(ids,RemoteViews(app.packageName,preset.layout).also {showProgress(it,index,now)})
            }
        }
    }
    fun refresh(context:Context,finished:()->Unit={}) {
        val app=context.applicationContext;val ticket=generation.incrementAndGet()
        scope.launch {try {
            val manager=AppWidgetManager.getInstance(app)
            if(riffWidgetPresets.none {manager.getAppWidgetIds(ComponentName(app,it.receiver)).isNotEmpty()}) {progressShown=false;return@launch}
            // The active owner's saved queue or the device's, never one from before owners existed.
            val saved=suspendCatching {AppContainer.get(app).let {process->process.stateReady.await()
                process.snapshots.newest(listOfNotNull(OfflineMusicStore.get(app).currentScope.takeIf {it!=UNVERIFIED_SCOPE_KEY},DEVICE_LOCAL_SCOPE_KEY))?.snapshot}}.getOrNull()
            val state=current?:saved?.let {s->val q=s.queue[s.index];WidgetState(q.title,q.artist,q.artworkUrl,false,s.queue.getOrNull(s.index+1)?.title.orEmpty(),true,
                later=s.queue.getOrNull(s.index+2)?.title.orEmpty())}?:WidgetState()
            configureArtworkLoader(app)
            val bitmap=state.art?.let {url->withTimeoutOrNull(3500) {suspendCatching {
                SingletonImageLoader.get(app).execute(ImageRequest.Builder(app).data(url).size(256).allowHardware(false).build()).image?.toBitmap()
            }.getOrNull()}}
            val backdrop=bitmap?.let(::glassBackdrop)
            if(ticket!=generation.get()) return@launch
            progressShown=riffWidgetPresets.any {it.progress && manager.getAppWidgetIds(ComponentName(app,it.receiver)).isNotEmpty()}
            riffWidgetPresets.forEachIndexed {index,preset->
                manager.getAppWidgetIds(ComponentName(app,preset.receiver)).forEach {id->manager.updateAppWidget(id,views(app,preset.layout,index,id,state,bitmap,backdrop,progress))}
            }
        } finally {finished()}}
    }
    internal fun views(context:Context,layout:Int,preset:Int,id:Int,state:WidgetState,bitmap:Bitmap?,backdrop:Bitmap?=null,progress:WidgetProgress=WidgetProgress()):RemoteViews {
        val views=RemoteViews(context.packageName,layout)
        views.setTextViewText(R.id.widget_title,state.title)
        if(preset!=5) views.setTextViewText(R.id.widget_artist,state.artist)
        if(preset==4) {
            views.setTextViewText(R.id.widget_up_next_1,state.next);views.setTextViewText(R.id.widget_up_next_2,state.later)
            views.setViewVisibility(R.id.widget_up_next,if(state.next.isBlank()) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.widget_up_next_2,if(state.later.isBlank()) View.GONE else View.VISIBLE)
        }
        // Compact, Wide and Circle show a round cover; Artwork fills the widget with it, which the widget's corners round.
        if(bitmap!=null) views.setImageViewBitmap(R.id.widget_art,cover(bitmap,circle=preset in setOf(0,1,5),radius=if(preset==2) 0f else 28f))
        else views.setImageViewResource(R.id.widget_art,R.drawable.ic_launcher_foreground)
        if(backdrop!=null && preset!=2) views.setImageViewBitmap(R.id.widget_backdrop,backdrop)
        views.setImageViewResource(R.id.widget_toggle,if(state.playing) R.drawable.widget_pause else R.drawable.widget_toggle)
        // Named for what a tap does now, in the same update that swaps the icon (Q18-032).
        views.setContentDescription(R.id.widget_toggle,string(if(state.playing) R.string.pause else R.string.play))
        val liking=preset in setOf(1,3,4);val shuffling=preset in setOf(3,4)
        if(liking) {
            // Kept in place but unseen when the song cannot be liked, so the other buttons do not move.
            views.setViewVisibility(R.id.widget_like,if(state.liked==null) View.INVISIBLE else View.VISIBLE)
            views.setImageViewResource(R.id.widget_like,if(state.liked==true) R.drawable.widget_heart_filled else R.drawable.widget_heart)
            views.setContentDescription(R.id.widget_like,string(if(state.liked==true) R.string.liked else R.string.like_on_youtube_music))
        }
        if(shuffling) {
            views.setImageViewResource(R.id.widget_shuffle,if(state.shuffle) R.drawable.widget_shuffle_on else R.drawable.widget_shuffle)
            views.setContentDescription(R.id.widget_shuffle,string(if(state.shuffle) R.string.shuffle_on else R.string.shuffle_off))
        }
        if(riffWidgetPresets[preset].progress) showProgress(views,preset,progress)
        val open=PendingIntent.getActivity(context,id,Intent(context,MainActivity::class.java).apply {flags=Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP},PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        views.setOnClickPendingIntent(R.id.widget_root,open)
        val controls=buildList {
            add(Triple("toggle",R.id.widget_toggle,0))
            if(preset in setOf(3,4,5)) add(Triple("previous",R.id.widget_previous,0))
            if(preset in setOf(1,3,4,5)) add(Triple("next",R.id.widget_next,0))
            if(liking && state.liked!=null) add(Triple("like",R.id.widget_like,0))
            if(shuffling) add(Triple("shuffle",R.id.widget_shuffle,0))
            if(preset==4) {add(Triple("queue",R.id.widget_up_next_1,1));add(Triple("queue",R.id.widget_up_next_2,2))}
        }
        controls.forEachIndexed {offset,(command,viewId,ahead)->
            if(!state.available) {views.setOnClickPendingIntent(viewId,open);return@forEachIndexed}
            val intent=Intent(context,MusicPlaybackService::class.java).setAction(WIDGET_CONTROL).putExtra("widgetToken",token(context)).putExtra("command",command).putExtra("ahead",ahead)
            views.setOnClickPendingIntent(viewId,PendingIntent.getForegroundService(context,id*8+offset,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        return views
    }
    private fun showProgress(views:RemoteViews,preset:Int,progress:WidgetProgress) {
        val now=SystemClock.elapsedRealtime()
        views.setProgressBar(R.id.widget_progress,1000,progress.permille(now),false)
        if(preset!=4) return
        // The elapsed time counts by itself while playing; the bar moves on with each tick.
        views.setChronometer(R.id.widget_elapsed,now-progress.positionAt(now),null,progress.playing)
        views.setTextViewText(R.id.widget_duration,if(progress.durationMs>0) DateUtils.formatElapsedTime(progress.durationMs/1000) else "")
    }
    private fun cover(source:Bitmap,circle:Boolean,radius:Float):Bitmap {
        val output=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888)
        val scale=256f/minOf(source.width,source.height)
        val shader=BitmapShader(source,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP)
        shader.setLocalMatrix(Matrix().apply {setScale(scale,scale);postTranslate((256-source.width*scale)/2,(256-source.height*scale)/2)})
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {this.shader=shader}
        val canvas=Canvas(output)
        if(circle) canvas.drawCircle(128f,128f,128f,paint) else canvas.drawRoundRect(0f,0f,256f,256f,radius,radius,paint)
        return output
    }
    /** The cover shrunk to a few pixels and grown back, which blurs it, then darkened so white text reads on any cover. */
    internal fun glassBackdrop(source:Bitmap):Bitmap {
        val tiny=Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(source,32,32,true),8,8,true)
        val out=Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(tiny,24,24,true),96,96,true).copy(Bitmap.Config.ARGB_8888,true)
        Canvas(out).drawColor(0x8C0B0B10.toInt())
        return out
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
