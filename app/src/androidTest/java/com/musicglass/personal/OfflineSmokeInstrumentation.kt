package com.musicglass.personal
import android.app.Instrumentation
import android.app.Activity
import android.os.Bundle
import android.media.MediaExtractor
import android.media.MediaFormat
import org.json.JSONObject
import kotlinx.coroutines.*
import io.ktor.client.statement.bodyAsText
import io.ktor.client.plugins.plugin
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
/** Real full-file acceptance check; never packaged into the app. */
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class OfflineSmokeInstrumentation:Instrumentation() {
    private var mode="download"
    private var testActivity:Activity?=null
    override fun onCreate(arguments:Bundle?) {super.onCreate(arguments);mode=arguments?.getString("mode")?:"download";start()}
    override fun onDestroy() {runOnMainSync {testActivity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)};super.onDestroy()}
    override fun onStart() {
        val result=Bundle()
        try {
            if(mode!="navigation_fixture" && mode!="navigation_liquid" && mode!="queue_fixture" && mode!="stress_queue") {
                testActivity=startActivitySync(android.content.Intent(targetContext,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                runOnMainSync {testActivity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            }
            runOnMainSync {YouTubeMusicProvider.shared(targetContext)}
            if(mode=="stress_queue") {
                // Device check for the off-main-thread queue decode: ordinary, 2,000-track, racing and play-next
                // requests through the real gateway -> PLAY_CATALOG_QUEUE path. Needs no activity (works locked).
                val saved=targetContext.getSharedPreferences("last_playback",android.content.Context.MODE_PRIVATE)
                    .getString("snapshot",null)?.let(::decodePlaybackSnapshot)?:error("No saved listening session")
                val base=saved.queue.filter {it.mediaId.startsWith("yt:") && !it.live}.map {ProviderTrack(it.mediaId.removePrefix("yt:"),it.title,it.artist,it.artworkUrl,it.artistId,it.albumId,it.album,it.live,it.music)}
                check(base.size>=12) {"Need at least 12 saved tracks, have ${base.size}"}
                runBlocking {
                    val gateway=withContext(Dispatchers.Main) {MusicPlaybackGateway(targetContext)}
                    try {
                        withTimeout(12000) {while(!withContext(Dispatchers.Main) {gateway.state.ready}) delay(50)}
                        val player=withContext(Dispatchers.Main) {MusicPlaybackGateway::class.java.getDeclaredField("controller").apply {isAccessible=true}.get(gateway) as androidx.media3.session.MediaController}
                        suspend fun awaitQueue(size:Int,index:Int,id:String):Long {
                            val start=android.os.SystemClock.elapsedRealtime()
                            withTimeout(30000) {while(!withContext(Dispatchers.Main) {player.mediaItemCount==size && player.currentMediaItemIndex==index && player.currentMediaItem?.mediaId==id}) delay(50)}
                            return android.os.SystemClock.elapsedRealtime()-start
                        }
                        val small=base.take(40)
                        withContext(Dispatchers.Main) {gateway.playCollection(small,3,"Stress small")}
                        result.putLong("small_ms",awaitQueue(small.size,3,"yt:${small[3].videoId}"))
                        val big=List(2000) {base[it%base.size]}
                        withContext(Dispatchers.Main) {gateway.playCollection(big,1500,"Stress 2000")}
                        result.putLong("big_ms",awaitQueue(2000,1500,"yt:${big[1500].videoId}"))
                        val a=base.take(10);val b=base.drop(1).take(10)
                        withContext(Dispatchers.Main) {gateway.playCollection(big,10,"Race 1");gateway.playCollection(a,2,"Race 2");gateway.playCollection(b,5,"Race 3")}
                        result.putLong("race_ms",awaitQueue(b.size,5,"yt:${b[5].videoId}"))
                        delay(3000)
                        val stable=withContext(Dispatchers.Main) {player.mediaItemCount==b.size && player.currentMediaItem?.mediaId=="yt:${b[5].videoId}"}
                        result.putBoolean("race_stable",stable)
                        check(stable) {"An older queue request replaced the newest one"}
                        val next=base.last { it.videoId !in b.map(ProviderTrack::videoId) }
                        withContext(Dispatchers.Main) {gateway.enqueueSong(next,true)}
                        withTimeout(15000) {while(!withContext(Dispatchers.Main) {player.mediaItemCount==b.size+1 && player.getMediaItemAt(player.currentMediaItemIndex+1).mediaId=="yt:${next.videoId}"}) delay(50)}
                        result.putBoolean("play_next",true)
                        result.putBoolean("playing",withContext(Dispatchers.Main) {gateway.state.error==null})
                    } finally {withContext(Dispatchers.Main) {gateway.close()}}
                }
                finish(Activity.RESULT_OK,result);return
            }
            if(mode=="navigation_fixture" || mode=="navigation_liquid" || mode=="queue_fixture") {
                val queue=mode=="queue_fixture"
                val intent=android.content.Intent().setClassName("com.musicglass.personal","com.musicglass.personal.FeaturePreviewActivity").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("scene",if(queue) "queue" else if(mode=="navigation_liquid") "navigation_liquid" else "navigation")
                val activity=startActivitySync(intent) as FeaturePreviewActivity
                try {
                    Thread.sleep(800)
                    fun touch(action:Int,x:Float,y:Float,down:Long) {val event=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,x,y,0);uiAutomation.injectInputEvent(event,true);event.recycle()}
                    if(queue) {
                        val bounds=activity.queueBounds!!;val density=activity.resources.displayMetrics.density
                        // Header 62 + chip 46 + title 49 + status inset, then first row handle.
                        val x=bounds.right-40*density;val y=bounds.top+activity.window.decorView.rootWindowInsets.getInsets(android.view.WindowInsets.Type.statusBars()).top+195*density
                        val down=android.os.SystemClock.uptimeMillis();touch(android.view.MotionEvent.ACTION_DOWN,x,y,down)
                        for(i in 1..18) {touch(android.view.MotionEvent.ACTION_MOVE,x,y+i*8*density,down);Thread.sleep(22)}
                        touch(android.view.MotionEvent.ACTION_UP,x,y+144*density,down);Thread.sleep(500)
                        result.putString("moves",org.json.JSONArray(activity.fixture.moves.map {"${it.first}->${it.second}"}).toString())
                        check(activity.fixture.moves.isNotEmpty()) {"Immediate queue handle drag did not move a row"}
                        val selected=activity.fixture.state.queue.indexOfFirst {it.queueId=="fixture:1"}
                        result.putInt("dragged_index",selected)
                        check(selected in 3..6) {"Short handle drag skipped too many rows: $selected"}
                        // Drag the next first row to the bottom edge, hold, then reverse toward the top.
                        val edgeDown=android.os.SystemClock.uptimeMillis();touch(android.view.MotionEvent.ACTION_DOWN,x,y,edgeDown)
                        val bottom=bounds.bottom-30*density
                        for(i in 1..20) {touch(android.view.MotionEvent.ACTION_MOVE,x,y+(bottom-y)*i/20,edgeDown);Thread.sleep(20)}
                        val beforeEdge=activity.fixture.state.queue.indexOfFirst {it.queueId=="fixture:2"};result.putInt("before_edge",beforeEdge)
                        Thread.sleep(1800)
                        val edgeIndex=activity.fixture.state.queue.indexOfFirst {it.queueId=="fixture:2"};result.putInt("edge_index",edgeIndex)
                        check(edgeIndex>beforeEdge) {"Queue did not auto-scroll at the bottom edge: $beforeEdge -> $edgeIndex"}
                        for(i in 1..20) {touch(android.view.MotionEvent.ACTION_MOVE,x,bottom+(y-bottom)*i/20,edgeDown);Thread.sleep(20)}
                        Thread.sleep(700);touch(android.view.MotionEvent.ACTION_UP,x,y,edgeDown)
                        val reversed=activity.fixture.state.queue.indexOfFirst {it.queueId=="fixture:2"};result.putInt("reversed_index",reversed)
                        check(reversed<edgeIndex) {"Queue drag could not reverse direction"}
                        check(activity.fixture.state.queue.size==51 && activity.fixture.state.queue.map {it.queueId}.toSet().size==51)
                        check(activity.fixture.state.queue.first().mediaId=="yt:video000000") {"Dragging changed the playing item"}
                    } else {
                        val bounds=activity.navBounds!!;val x=bounds.left+60*activity.resources.displayMetrics.density;val y=bounds.center.y
                        val down=android.os.SystemClock.uptimeMillis();touch(android.view.MotionEvent.ACTION_DOWN,x,y,down);Thread.sleep(350)
                        val image=uiAutomation.takeScreenshot();val bytes=java.io.ByteArrayOutputStream();image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,bytes)
                        result.putString("image_base64",android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP))
                        touch(android.view.MotionEvent.ACTION_UP,x,y,down)
                        result.putString("refresh_rate",activity.display?.mode?.refreshRate.toString())
                    }
                } finally {runOnMainSync {activity.finish()}}
                finish(Activity.RESULT_OK,result);return
            }
            if(mode=="sleep_countdown") {
                runBlocking {
                    val gateway=withContext(Dispatchers.Main) {MusicPlaybackGateway(targetContext)}
                    try {
                        withTimeout(12000) {while(!withContext(Dispatchers.Main) {gateway.state.ready}) delay(50)}
                        withContext(Dispatchers.Main) {gateway.pause();gateway.setSleepTimer(1)}
                        delay(1000)
                        val metrics=targetContext.resources.displayMetrics;val down=android.os.SystemClock.uptimeMillis()
                        for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
                            val event=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,metrics.widthPixels*.2f,metrics.heightPixels*.876f,0)
                            uiAutomation.injectInputEvent(event,true);event.recycle()
                        }
                        delay(1200)
                        fun countdown():String? {
                            fun visit(node:android.view.accessibility.AccessibilityNodeInfo):String? {
                                node.text?.toString()?.takeIf {it.startsWith("Sleep in ")}?.let {return it}
                                for(i in 0 until node.childCount) node.getChild(i)?.let {visit(it)?.let {return it}}
                                return null
                            }
                            return uiAutomation.rootInActiveWindow?.let(::visit)
                        }
                        val first=countdown()?:error("No countdown in full player");delay(2500)
                        val next=countdown()?:error("Countdown disappeared")
                        check(first!=next) {"Countdown did not tick while paused: $first"}
                        check(!withContext(Dispatchers.Main) {gateway.state.playing})
                        result.putString("countdown",JSONObject().put("first",first).put("next",next).put("paused",true).toString())
                        val bitmap=uiAutomation.takeScreenshot();val reduced=android.graphics.Bitmap.createScaledBitmap(bitmap,480,(bitmap.height*480f/bitmap.width).toInt(),true);val bytes=java.io.ByteArrayOutputStream();reduced.compress(android.graphics.Bitmap.CompressFormat.JPEG,85,bytes)
                        result.putString("image_base64",android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP))
                    } finally {withContext(Dispatchers.Main) {gateway.setSleepTimer(0);gateway.close()}}
                }
                finish(Activity.RESULT_OK,result);return
            }
            if(mode=="rc15_native") {
                val backup=java.io.File(targetContext.filesDir,"rc15-verification-queue.json")
                val prefs=targetContext.getSharedPreferences("last_playback",android.content.Context.MODE_PRIVATE)
                if(!backup.exists()) backup.writeText(prefs.getString("snapshot",null)?:error("No saved listening session"))
                val saved=decodePlaybackSnapshot(backup.readText())?:error("Invalid session backup")
                check(saved.queue.all {it.mediaId.startsWith("yt:")})
                runBlocking {
                    val gateway=withContext(Dispatchers.Main) {MusicPlaybackGateway(targetContext)}
                    var controller:androidx.media3.session.MediaController?=null
                    try {
                        withTimeout(12000) {while(!withContext(Dispatchers.Main) {gateway.state.ready}) delay(50)}
                        controller=withContext(Dispatchers.Main) {MusicPlaybackGateway::class.java.getDeclaredField("controller").apply {isAccessible=true}.get(gateway) as androidx.media3.session.MediaController}
                        val player=controller!!
                        suspend fun waitPlaying(video:Boolean=false) {withTimeout(45000) {while(true) {
                            val ready=withContext(Dispatchers.Main) {check(gateway.state.error==null) {gateway.state.error.orEmpty()};gateway.state.playing && (!video || player.videoSize.height>0)}
                            if(ready) break;delay(150)
                        }}}
                        val provider=YouTubeMusicProvider.shared(targetContext)
                        val track=ProviderTrack("5qZQEq_C3vc","Numb","Linkin Park")
                        val pair=provider.videoFor(track).getOrThrow()?:error("No official counterpart")
                        val stream=provider.resolve(pair,video=true).getOrThrow()
                        check(pairedVideoTitleAllowed(track,stream.resolvedTitle))
                        withContext(Dispatchers.Main) {gateway.playProvider(track,stream)}
                        waitPlaying()
                        val metrics=targetContext.resources.displayMetrics;val down=android.os.SystemClock.uptimeMillis()
                        for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
                            val event=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,metrics.widthPixels*.2f,metrics.heightPixels*.876f,0)
                            uiAutomation.injectInputEvent(event,true);event.recycle()
                        }
                        sendStatus(5,Bundle().apply {putString("stage","official_video_playing");putString("video_id",pair)})
                        delay(15000);waitPlaying(true)
                        val position=withContext(Dispatchers.Main) {player.currentPosition}
                        withContext(Dispatchers.Main) {gateway.seekTo(30000)};delay(2000)
                        check(withContext(Dispatchers.Main) {player.currentPosition>=30000 && gateway.state.playing})
                        result.putString("video",JSONObject().put("id",pair).put("position_before_seek",position).put("seek_playing",true).toString())
                        val card=provider.liveStations().getOrThrow().flatMap {it.cards}.first {it.videoId!=null}
                        val live=ProviderTrack(card.videoId!!,card.title,card.subtitle,card.artworkUrl,isLive=true)
                        val radio=provider.resolve(live.videoId,live=true,video=true).getOrThrow()
                        withContext(Dispatchers.Main) {gateway.playProvider(live,radio)}
                        waitPlaying()
                        sendStatus(6,Bundle().apply {putString("stage","radio_native_playing");putString("title",live.title)})
                        delay(12000);waitPlaying(true)
                        withContext(Dispatchers.Main) {testActivity?.moveTaskToBack(true)}
                        delay(5000)
                        check(withContext(Dispatchers.Main) {gateway.state.playing && gateway.state.live}) {"Radio stopped in background"}
                        withContext(Dispatchers.Main) {targetContext.startActivity(android.content.Intent(targetContext,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));gateway.setSleepTimer(1)}
                        withTimeout(5000) {while(withContext(Dispatchers.Main) {gateway.state.sleepDeadlineMs==0L}) delay(50)}
                        sendStatus(7,Bundle().apply {putString("stage","sleep_timer_started")})
                        val volumes=mutableListOf<Float>()
                        val baseline=withContext(Dispatchers.Main) {player.volume}
                        while(withContext(Dispatchers.Main) {gateway.state.sleepDeadlineMs>0L}) {
                            val deadline=withContext(Dispatchers.Main) {gateway.state.sleepDeadlineMs}
                            val remaining=deadline-android.os.SystemClock.elapsedRealtime()
                            if(remaining in 1..14500) volumes+=withContext(Dispatchers.Main) {player.volume}
                            delay(500)
                        }
                        delay(700)
                        check(withContext(Dispatchers.Main) {!gateway.state.playing && !gateway.state.wantsPlayback})
                        check(volumes.size>=25 && volumes.zipWithNext().all {it.second<=it.first+.001f} && volumes.last()<.05f) {"Physical player fade failed: $volumes"}
                        check(withContext(Dispatchers.Main) {kotlin.math.abs(player.volume-baseline)<.001f})
                        result.putString("radio",JSONObject().put("title",live.title).put("background_playing",true).toString())
                        result.putString("sleep",JSONObject().put("samples",volumes.size).put("first",volumes.first()).put("last",volumes.last()).put("paused",true).put("volume_restored",true).toString())
                    } finally {
                        controller?.let {player->
                            val file=java.io.File(targetContext.cacheDir,"queue-request-${java.util.UUID.randomUUID()}.json").apply {writeText(encodePlaybackSnapshot(saved))}
                            val command=withContext(Dispatchers.Main) {player.sendCustomCommand(androidx.media3.session.SessionCommand(PLAY_CATALOG_QUEUE,Bundle.EMPTY),Bundle().apply {putString("queueFile",file.name);putBoolean("autoplay",false)})}
                            check(command.get(10,java.util.concurrent.TimeUnit.SECONDS).resultCode==androidx.media3.session.SessionResult.RESULT_SUCCESS)
                            withContext(Dispatchers.Main) {player.seekTo(saved.index,saved.positionMs);player.repeatMode=saved.repeat;gateway.setSleepTimer(0);player.pause()}
                            delay(700)
                            check(withContext(Dispatchers.Main) {player.mediaItemCount==saved.queue.size && player.currentMediaItemIndex==saved.index && !player.playWhenReady})
                            prefs.edit().putString("snapshot",encodePlaybackSnapshot(saved)).commit();file.delete();backup.delete()
                            result.putString("restored","Original queue and position restored, paused")
                        }
                        withContext(Dispatchers.Main) {gateway.close()}
                    }
                }
                finish(Activity.RESULT_OK,result);return
            }
            if(mode=="photo_network") {
                val client=okhttp3.OkHttpClient.Builder().callTimeout(20,java.util.concurrent.TimeUnit.SECONDS).build()
                val checks=org.json.JSONArray()
                val url="https://commons.wikimedia.org/w/api.php?action=query&format=json&list=search&srsearch=mountain%20lake&srnamespace=6&srlimit=2"
                for(agent in listOf("Riff/${BuildConfig.VERSION_NAME} (Android music app; daily photo discovery)","Mozilla/5.0","Riff/${BuildConfig.VERSION_NAME}")) {
                    try {client.newCall(okhttp3.Request.Builder().url(url).header("User-Agent",agent).build()).execute().use {r->
                        checks.put(JSONObject().put("agent",agent).put("status",r.code).put("body",r.body?.string()?.take(450)))
                    }} catch(e:Exception) {checks.put(JSONObject().put("agent",agent).put("error",e.javaClass.simpleName+":"+e.message))}
                }
                result.putString("network",checks.toString());finish(Activity.RESULT_OK,result);return
            }
            if(mode=="rc16_lyrics_probe") {
                val provider=YouTubeMusicProvider.shared(targetContext)
                val tube=YouTubeMusicProvider::class.java.getDeclaredField("innerTube").apply {isAccessible=true}.get(provider) as com.metrolist.innertubex.InnerTube
                val http=YouTubeMusicProvider::class.java.getDeclaredField("http").apply {isAccessible=true}.get(provider) as io.ktor.client.HttpClient
                val summaries=org.json.JSONArray()
                http.plugin(io.ktor.client.plugins.HttpSend).intercept {request->
                    val body=request.body as? io.ktor.http.content.TextContent
                    val data=body?.text?.let {runCatching {Json.parseToJsonElement(it)}.getOrNull()}
                    val call=execute(request);val response=call.response
                    if(request.url.build().encodedPath.endsWith("/browse") && catalogValue(data,"browseId")?.startsWith("MPLY")==true) {
                        val raw=Json.parseToJsonElement(response.bodyAsText());val paths=org.json.JSONArray()
                        fun visit(node:JsonElement,path:String="",depth:Int=0) {if(depth>24)return
                            when(node) {is JsonObject->node.forEach {(k,v)->if(k.contains("lyric",true) || k.contains("cue",true)) paths.put(path+"/"+k+":"+v.javaClass.simpleName);visit(v,path+"/"+k,depth+1)}
                            is JsonArray->node.take(2).forEach {visit(it,path+"[]",depth+1)};else->Unit}}
                        visit(raw)
                        summaries.put(JSONObject().put("host",request.url.host).put("client",catalogValue(data?.jsonObject?.get("context"),"clientName")).put("version",catalogValue(data,"clientVersion")).put("header_id",request.headers["X-YouTube-Client-Name"]).put("status",response.status.value).put("paths",paths).put("error",catalogValue(raw.jsonObject["error"],"message")).put("client_fields",org.json.JSONArray(data?.jsonObject?.get("context")?.jsonObject?.get("client")?.jsonObject?.keys?.toList())).put("timed",parseYoutubeLyrics(raw,300000)?.timed==true))
                    }
                    call
                }
                runBlocking {withTimeout(90000) {
                    provider.lyricsFor("5qZQEq_C3vc",187000)
                    val next=Json.parseToJsonElement(tube.next(com.metrolist.innertubex.models.YouTubeClient.WEB_REMIX,videoId="5qZQEq_C3vc").bodyAsText())
                    fun id(node:JsonElement):String?=when(node) {is JsonObject->(node["browseId"] as? JsonPrimitive)?.contentOrNull?.takeIf {it.startsWith("MPLY")}?:node.values.firstNotNullOfOrNull(::id);is JsonArray->node.firstNotNullOfOrNull(::id);else->null}
                    val browse=id(next)?:error("No lyrics ID")
                    val body=buildJsonObject {put("browseId",browse);putJsonObject("context") {putJsonObject("client") {put("clientName","ANDROID_MUSIC");put("clientVersion","7.21.50");put("hl","en");put("gl","IN")}}}
                    http.post("https://music.youtube.com/youtubei/v1/browse") {
                        contentType(ContentType.Application.Json)
                        setBody(io.ktor.http.content.TextContent(body.toString(),ContentType.Application.Json))
                    }.bodyAsText()
                }}
                result.putString("probe",summaries.toString());finish(Activity.RESULT_OK,result);return
            }
            if(mode=="rc16_provider") {
                val provider=YouTubeMusicProvider.shared(targetContext)
                runBlocking {withTimeout(180_000) {
                    val home=provider.homeSections().getOrThrow();check(home===provider.homeSections().getOrThrow())
                    val speed=homeSpeedDialCandidates(emptyList(),home)
                    val listen=home.filter {it.title.contains("listen again",true)}.flatMap {it.cards}.filter {it.kind==CatalogKind.Song && it.videoId!=null}
                    val overlap=speed.count {c->listen.any {it.key()==c.key()}}
                    check(speed.size==speed.map {it.key()}.toSet().size)
                    check(speed.count {it.kind!=CatalogKind.Song}<=3)
                    check(speed.none {it.kind==CatalogKind.Podcast || it.kind==CatalogKind.Episode})
                    check(overlap<=6);if(listen.isNotEmpty()) check(overlap>0)
                    result.putString("home",JSONObject().put("shelves",home.size).put("speed",speed.size).put("listen_songs",listen.size).put("overlap",overlap).put("songs",speed.count {it.kind==CatalogKind.Song}).put("collections",speed.count {it.kind!=CatalogKind.Song}).toString())
                    sendStatus(1,Bundle(result))
                    val lyrics=org.json.JSONArray()
                    for((id,duration) in listOf("5qZQEq_C3vc" to 187000L,"7CTJcHjkq0E" to 294000L,"WKZO-CWeOVA" to 210000L,"4NRXx6U8ABQ" to 200000L)) {
                        val doc=provider.lyricsFor(id,duration)
                        lyrics.put(JSONObject().put("id",id).put("timed",doc?.timed==true).put("lines",doc?.lines?.size?:0).put("first_start_ms",doc?.lines?.firstOrNull()?.startMs?:-1).put("source",doc?.source?:"none"))
                        sendStatus(2,Bundle().apply {putString("lyrics",lyrics.toString())})
                    }
                    check((0 until lyrics.length()).any {lyrics.getJSONObject(it).optBoolean("timed")}) {"No native timed lyrics received"}
                    result.putString("lyrics",lyrics.toString())
                }}
                finish(Activity.RESULT_OK,result);return
            }
            if(mode=="rc15_provider") {
                val provider=YouTubeMusicProvider.shared(targetContext)
                runBlocking {withTimeout(240_000) {
                    val home=provider.homeSections().getOrThrow();check(home===provider.homeSections().getOrThrow())
                    val speed=homeSpeedDialCandidates(emptyList(),home);val listen=home.filter {it.title.contains("listen again",true)}.flatMap {it.cards}
                    check(speed.count {c->listen.any {it.key()==c.key()}}<=6)
                    result.putString("home",JSONObject().put("shelves",home.size).put("speed_dial",speed.size).put("listen_again",listen.size).toString());sendStatus(1,Bundle(result))
                    val photos=MoodPhotos.get(targetContext);val mood=provider.homeMoods.firstOrNull {it.title.contains("relax",true)}?.title?:"Relax"
                    val photo=photos.refresh(mood);check(photo!=null && photo.file.length()>0) {"No daily photograph was downloaded for $mood: ${photos.lastStatus}"}
                    check(photo===photos.refresh(mood)) {"A current day's photo was fetched twice"}
                    result.putString("photo",JSONObject().put("mood",mood).put("day",photo.day).put("id",photo.id).put("bytes",photo.file.length()).put("author",photo.author).put("license",photo.license).put("page",photo.page).toString());sendStatus(2,Bundle(result))
                    val radio=provider.liveStations("All").getOrThrow();check(radio.isNotEmpty());check(radio===provider.liveStations("All").getOrThrow())
                    val ids=radio.flatMap {it.cards}.map {it.videoId};check(ids.size==ids.toSet().size)
                    result.putString("radio",JSONObject().put("channels",radio.size).put("streams",ids.size).put("names",org.json.JSONArray(radio.map {it.title})).toString());sendStatus(3,Bundle(result))
                    val pairs=org.json.JSONArray()
                    for(query in listOf("Numb Linkin Park","Billie Jean Michael Jackson","Birds of a Feather Billie Eilish")) {
                        val track=provider.search(query).getOrThrow().firstOrNull()?:continue
                        val count=provider.musicPairingRequests;val pair=provider.videoFor(track).getOrThrow()
                        check(provider.musicPairingRequests>count) {"The provider request did not include the YouTube Music switcher configuration"}
                        pairs.put(JSONObject().put("title",track.title).put("artist",track.artist).put("song_id",track.videoId).put("video_id",pair?:"none"));sendStatus(4,Bundle().apply {putString("video_pairs",pairs.toString())})
                    }
                    check(pairs.length()>0);result.putString("video_pairs",pairs.toString())
                }}
                finish(Activity.RESULT_OK,result);return
            }
            if(mode=="playlist_edits" || mode=="playlist_cleanup" || mode=="playlist_resume") {
                val provider=YouTubeMusicProvider.shared(targetContext);check(provider.signedIn)
                val tube=YouTubeMusicProvider::class.java.getDeclaredField("innerTube").apply {isAccessible=true}.get(provider) as com.metrolist.innertubex.InnerTube
                val client=com.metrolist.innertubex.models.YouTubeClient.WEB_REMIX
                val prefs=targetContext.getSharedPreferences("riff-test-playlists",android.content.Context.MODE_PRIVATE)
                val created=prefs.getStringSet("created",emptySet()).orEmpty().toMutableSet()
                fun remember() {prefs.edit().putStringSet("created",created.toSet()).commit()}
                Thread.sleep(8000)
                var activeStage="starting"
                val watchdog=Thread {try {Thread.sleep(25000);sendStatus(4,Bundle().apply {putString("diagnostic_stage",activeStage);putString("threads",Thread.getAllStackTraces().filter {it.key.name.contains("DefaultDispatcher") || it.key.name.contains("Instr")}.map {it.key.name+":"+it.value.take(8).joinToString(" <- "){e->e.className+"."+e.methodName}}.joinToString("\n"))})} catch(_:InterruptedException) {}}.apply {isDaemon=true;start()}
                fun stage(name:String) {activeStage=name;sendStatus(3,Bundle().apply {putString("stage",name)})}
                runBlocking {
                    if(mode=="playlist_cleanup") {val old=provider.scanLibraryPlaylists().getOrThrow().firstOrNull {it.browseId=="VLPLSEOzjmDhbNI" && it.title=="Riff verification 1790916827713 2"};old?.browseId?.let {created+=it;remember()}}
                    try {withTimeout(150_000) {
                        if(mode=="playlist_edits") {
                            check(created.isEmpty()) {"Earlier test playlists require cleanup"}
                            val before=provider.scanLibraryPlaylists().getOrThrow().map {it.key()}.toSet()
                            repeat(2) {index->
                                val response=tube.createPlaylist(client,"Riff verification ${System.currentTimeMillis()} ${index+1}")
                                check(response.status.value<400) {"Create ${index+1} HTTP ${response.status.value}"}
                                val id=Json.parseToJsonElement(response.bodyAsText()).jsonObject["playlistId"]?.jsonPrimitive?.contentOrNull?:error("Create returned no playlist ID")
                                check(id.matches(Regex("[A-Za-z0-9_-]{2,200}")))
                                val browse="VL"+id.removePrefix("VL");check(browse !in before) {"Create ${index+1} returned an existing library ID $browse"};check(browse !in created) {"Create ${index+1} returned the same ID $browse"};created+=browse;remember()
                                sendStatus(1,Bundle().apply {putString("created_playlist",browse)})
                                delay(1200)
                            }
                            stage("Scan new playlist membership");val index=provider.scanLibraryPlaylists().getOrThrow();check(created.all {id->index.any {it.browseId==id}}) {"Created IDs not visible in refreshed Library; titles="+index.filter {it.title.startsWith("Riff verification ")}.map {it.title+":"+it.browseId}}
                            }
                        if(mode=="playlist_edits" || mode=="playlist_resume") {
                            check(created.size==2) {"Exactly two persisted temporary IDs are required"}
                            stage("Bulk save to two playlists");val batch=addToSelectedPlaylists(created.toSet()) {provider.addPlaylistSong(it,"5qZQEq_C3vc")}
                            check(batch.completed.size==2 && batch.failures.isEmpty()) {"Bulk save failed: ${batch.failures.values.firstOrNull()}"}
                            created.forEach {id->
                                stage("Read saved playlist $id");val page=provider.completeCollection(id).getOrThrow();check(page.editable) {"Owned playlist was not editable"};check(page===provider.completeCollection(id).getOrThrow())
                                val song=page.albumTracks.single();check(song.videoId=="5qZQEq_C3vc" && song.setVideoId!=null)
                                stage("Remove song from $id");provider.removePlaylistSong(id,song).getOrThrow();stage("Removal acknowledged for $id")
                                stage("Read empty playlist $id");check(provider.completeCollection(id).getOrThrow().albumTracks.isEmpty())
                            }
                            result.putString("playlist_edits","Two private playlists appeared in Library, bulk save acknowledged, exact song entries removed, affected content cache refreshed")
                        }
                    }} finally {withContext(NonCancellable) {
                        created.toList().forEach {id->runCatching {
                            stage("Delete temporary playlist $id");val deleted=tube.deletePlaylist(client,id);val body=deleted.bodyAsText()
                            check(deleted.status.value<400)
                            if(body.isNotBlank()) {val root=Json.parseToJsonElement(body).jsonObject;check(root["error"]==null && root["status"]?.jsonPrimitive?.contentOrNull!="STATUS_FAILED")}
                            created-=id;remember()
                        }}
                        result.putString("cleanup_remaining",org.json.JSONArray(created.toList()).toString())
                        sendStatus(2,Bundle(result))
                        if(created.isEmpty()) {targetContext.deleteSharedPreferences("riff-test-playlists");stage("Confirm temporary playlist deletion");val after=provider.scanLibraryPlaylists().getOrThrow();result.putString("remaining_test_playlists",org.json.JSONArray(after.filter {it.title.startsWith("Riff verification ")}.map {it.title+":"+it.browseId}).toString())}
                    }}
                }
                watchdog.interrupt();check(created.isEmpty()) {"Temporary playlists still require cleanup"};finish(Activity.RESULT_OK,result);return
            }
            if(mode=="widgets") {
                val checks=org.json.JSONArray()
                runOnMainSync {
                    riffWidgetPresets.forEachIndexed {index,preset->
                        val views=RiffWidgets.views(targetContext,preset.layout,index,1000+index,RiffWidgets.WidgetState("A long song title to validate truncation","Original artist"),null)
                        val root=views.apply(targetContext,android.widget.FrameLayout(targetContext))
                        val density=targetContext.resources.displayMetrics.density
                        root.measure(android.view.View.MeasureSpec.makeMeasureSpec((if(index==0) 180 else 330)*density.toInt(),android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec((if(index<2) 90 else 300)*density.toInt(),android.view.View.MeasureSpec.EXACTLY))
                        root.layout(0,0,root.measuredWidth,root.measuredHeight)
                        check(root.findViewById<android.view.View>(R.id.widget_toggle)!=null)
                        checks.put(preset.title)
                    }
                }
                result.putString("widgets",checks.toString());finish(Activity.RESULT_OK,result);return
            }
            if(mode=="page_cache") {
                val provider=YouTubeMusicProvider.shared(targetContext)
                runBlocking {withTimeout(150_000) {
                    val home=provider.homeSections().getOrThrow();check(home===provider.homeSections().getOrThrow())
                    val radio=provider.liveStations().getOrThrow();check(radio===provider.liveStations().getOrThrow())
                    val shows=provider.podcastSections().getOrThrow();check(shows===provider.podcastSections().getOrThrow())
                    result.putString("cache",JSONObject().put("home_shelves",home.size).put("radio_shelves",radio.size).put("podcast_shelves",shows.size).put("moods",org.json.JSONArray(provider.homeMoods.map {it.title})).toString())
                    sendStatus(1,Bundle(result))
                    provider.homeMoods.firstOrNull()?.let {mood->val feed=provider.homeSections(mood.title).getOrThrow();check(feed===provider.homeSections(mood.title).getOrThrow());result.putString("mood",JSONObject().put("title",mood.title).put("shelves",feed.size).toString())}
                    if(provider.signedIn) {
                        val playlists=provider.scanLibraryPlaylists().getOrThrow()
                        result.putInt("playlists",playlists.size)
                        playlists.firstOrNull {it.browseId!="VLLM" && it.browseId!=null}?.let {c->val page=provider.completeCollection(c.browseId!!).getOrThrow();check(page===provider.completeCollection(c.browseId!!).getOrThrow());provider.scanLibraryPlaylists().getOrThrow();check(page===provider.completeCollection(c.browseId!!).getOrThrow());result.putString("playlist_detail",JSONObject().put("title",page.title).put("songs",page.albumTracks.size).put("editable",page.editable).put("removal_ids",page.albumTracks.count {it.setVideoId!=null}).toString())}
                    }
                }}
                finish(Activity.RESULT_OK,result);return
            }
            if(mode=="offline_inventory") {
                val store=OfflineMusicStore.get(targetContext)
                val db=android.database.sqlite.SQLiteDatabase.openDatabase(targetContext.getDatabasePath("offline-music.db").path,null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
                val scopes=mutableListOf<String>();try {db.rawQuery("SELECT DISTINCT scope FROM songs",null).use {c->while(c.moveToNext()) scopes+=c.getString(0)}} finally {db.close()}
                result.putString("inventory",org.json.JSONArray(scopes.map {key->JSONObject().put("current_account",key==store.currentScope).put("metadata_rows",store.rows(key).size).put("ready_audio",store.rows(key).count {store.audio(key,it.track.videoId)!=null})}).toString())
                finish(Activity.RESULT_OK,result);return
            }
            if(mode=="settings") {
                val store=OfflineMusicStore.get(targetContext)
                result.putString("settings",JSONObject().put("smart",store.smartEnabled).put("mobile",store.allowMobileData)
                    .put("budget_bytes",store.budgetBytes).put("haptics",AccessibilitySettings(targetContext).hapticFeedback).toString())
                result.putString("download_order",org.json.JSONArray(store.rows().filter {store.audio(it.scope,it.track.videoId)!=null}
                    .sortedByDescending {it.downloadedAt}.map {JSONObject().put("title",it.track.title).put("downloaded_at",it.downloadedAt)}).toString())
                finish(Activity.RESULT_OK,result);return
            }
            if(mode=="mobile_policy") {
                val store=OfflineMusicStore.get(targetContext);check(!store.smartEnabled) {"Automatic downloads must remain off during verification"}
                val original=store.allowMobileData
                val connectivity=targetContext.getSystemService(android.net.ConnectivityManager::class.java)
                val caps=connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                check(caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)==true) {"Mobile network not active"}
                check(caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {"Mobile network not validated"}
                try {
                    store.setAllowMobileData(false);val without=OfflineDownloadEngine.automaticConditionsAllowed(targetContext,store)
                    store.setAllowMobileData(true);val with=OfflineDownloadEngine.automaticConditionsAllowed(targetContext,store)
                    check(!without && with) {"Mobile data permission did not control download eligibility"}
                    result.putString("mobile_policy","Validated cellular network blocked by default and allowed with opt-in; no automatic downloads started")
                } finally {store.setAllowMobileData(original)}
                finish(Activity.RESULT_OK,result);return
            }
            if(mode=="offline_seek") {
                val store=OfflineMusicStore.get(targetContext)
                val rows=store.rows().filter {store.audio(it.scope,it.track.videoId)!=null}.take(12)
                check(rows.isNotEmpty()) {"No existing downloads to check"}
                val checks=org.json.JSONArray()
                runBlocking {withContext(Dispatchers.Main) {
                    for(row in rows) {
                        val factory=androidx.media3.exoplayer.source.DefaultMediaSourceFactory(targetContext)
                        val localFactory=object:androidx.media3.exoplayer.source.MediaSource.Factory by factory {
                            override fun createMediaSource(item:androidx.media3.common.MediaItem)=offlineMediaSource(targetContext,item)
                        }
                        val player=androidx.media3.exoplayer.ExoPlayer.Builder(targetContext).setMediaSourceFactory(localFactory).build()
                        try {
                            player.volume=0f
                            player.setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(store.audio(row.scope,row.track.videoId)!!)))
                            player.prepare()
                            withTimeout(15000) {while(player.playbackState!=androidx.media3.common.Player.STATE_READY) {player.playerError?.let {throw it};delay(30)}}
                            check(player.isCurrentMediaItemSeekable) {"Downloaded container is not seekable: ${row.track.title}"}
                            val duration=player.duration
                            for(fraction in listOf(.7,.2)) {
                                val target=(duration*fraction).toLong();player.seekTo(target)
                                player.play()
                                withTimeout(15000) {while(!player.isPlaying) {player.playerError?.let {throw it};delay(30)}}
                                delay(500);player.playerError?.let {throw it}
                                check(kotlin.math.abs(player.currentPosition-target)<2000 && player.isPlaying) {"Offline seek did not resume: target=$target actual=${player.currentPosition} duration=$duration playing=${player.isPlaying} state=${player.playbackState}"}
                                player.pause()
                                checks.put(JSONObject().put("title",row.track.title).put("target_ms",target).put("actual_ms",player.currentPosition).put("seekable",true))
                            }
                        } finally {player.release()}
                    }
                }}
                result.putString("offline_seek",checks.toString());finish(Activity.RESULT_OK,result);return
            }
            if(mode=="cache") {
                val cache=MusicDiskCache.get(targetContext);val key="verification:"+java.util.UUID.randomUUID()
                val expected=ByteArray(256*1024) {(it%251).toByte()};var upstreamOpens=0
                val upstream=androidx.media3.datasource.DataSource.Factory {object:androidx.media3.datasource.DataSource {
                    var position=0;var openedUri:android.net.Uri?=null
                    override fun open(spec:androidx.media3.datasource.DataSpec):Long {upstreamOpens++;check(upstreamOpens==1) {"Cache reuse unexpectedly fetched source"};position=spec.position.toInt();openedUri=spec.uri;return androidx.media3.common.C.LENGTH_UNSET.toLong()}
                    override fun read(buffer:ByteArray,offset:Int,length:Int):Int {if(position>=expected.size) return androidx.media3.common.C.RESULT_END_OF_INPUT;val n=minOf(length,expected.size-position);expected.copyInto(buffer,offset,position,position+n);position+=n;return n}
                    override fun getUri()=openedUri
                    override fun addTransferListener(listener:androidx.media3.datasource.TransferListener) {}
                    override fun close() {}
                }}
                try {
                    for(attempt in 1..2) {
                        val source=MusicDiskCache.audioFactory(targetContext,upstream).createDataSource()
                        val output=java.io.ByteArrayOutputStream()
                        try {source.open(androidx.media3.datasource.DataSpec.Builder().setUri("sabr://fresh-session-$attempt").setKey(key).setFlags(androidx.media3.datasource.DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN).build())
                            val buffer=ByteArray(8192);while(true) {val n=source.read(buffer,0,buffer.size);if(n<0) break;output.write(buffer,0,n)}
                            check(output.toByteArray().contentEquals(expected))
                        } finally {source.close()}
                    }
                    result.putString("cache","Finite unknown-length audio cached intact across two session URIs; upstream opened once")
                    result.putLong("cached_bytes",cache.getCachedBytes(key,0,expected.size.toLong()))
                } finally {cache.removeResource(key)}
                finish(Activity.RESULT_OK,result);return
            }
            if(mode=="recovery") {
                val store=OfflineMusicStore.get(targetContext);val id="5qZQEq_C3vc";val account=store.currentScope
                val original=checkNotNull(store.song(account,id));val file=checkNotNull(store.audio(account,id));val bytes=file.length()
                check(original.downloadedAt>0) {"Existing bundle download date was not migrated"}
                try {
                    store.update(account,id,"state" to "downloading");store.recover()
                    check(store.song(account,id)?.state=="queued")
                    store.update(account,id,"state" to "complete");store.enqueue(original.track)
                    check(store.song(account,id)?.state=="queued") {"Complete bundle could not retry missing assets"}
                    check(store.audio(account,id)?.length()==bytes)
                    check(store.song(account,id)?.downloadedAt==original.downloadedAt) {"Asset retry changed the download date"}
                    result.putString("recovery","Interrupted jobs requeued; existing audio retained; asset retry queued")
                    result.putBoolean("smart_enabled",store.smartEnabled)
                } finally {store.update(account,id,"state" to original.state,"pinned" to if(original.pinned) 1 else 0)}
                finish(Activity.RESULT_OK,result);return
            }
            if(mode=="video") {
                val provider=YouTubeMusicProvider.shared(targetContext);val track=ProviderTrack("5qZQEq_C3vc","Numb","Linkin Park")
                runBlocking {withTimeout(90_000) {
                    val cards=provider.discover("Numb Linkin Park official music video",filter="Videos").getOrThrow().cards
                    result.putString("candidates",org.json.JSONArray(cards.take(6).map {org.json.JSONObject().put("id",it.videoId).put("title",it.title).put("owner",it.subtitle).put("official",it.officialMusicVideo)}).toString())
                    sendStatus(1,Bundle(result));val id=provider.videoFor(track).getOrThrow();result.putString("matched_id",id?:"none");sendStatus(2,Bundle(result))
                }}
                finish(Activity.RESULT_OK,result);return
            }
            val store=OfflineMusicStore.get(targetContext)
            val track=ProviderTrack("5qZQEq_C3vc","Numb","Linkin Park")
            store.enqueue(track)
            val start=System.currentTimeMillis()
            runBlocking {withTimeout(240_000) {OfflineDownloadEngine.run(targetContext,false) {}}}
            val row=store.song(store.currentScope,track.videoId)!!
            val file=store.audio(store.currentScope,track.videoId)
            val measure=JSONObject().put("state",row.state).put("quality",row.quality).put("bytes",file?.length()?:0).put("artwork",row.artworkState).put("lyrics",row.lyricsState).put("elapsed_ms",System.currentTimeMillis()-start)
            result.putString("measurement",measure.toString())
            check(file!=null) {"Audio was not committed"}
            val extractor=MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath);val index=(0 until extractor.trackCount).first {extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true};extractor.selectTrack(index)
                extractor.seekTo(row.durationMs*700,MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                check(extractor.sampleTime>=row.durationMs*500) {"Offline seeking did not reach requested region"}
                check(extractor.readSampleData(java.nio.ByteBuffer.allocate(2*1024*1024),0)>0)
                measure.put("seek_ms",extractor.sampleTime/1000)
                val lyrics=store.lyrics(store.currentScope,track.videoId)
                measure.put("lyrics_word_timed",lyrics?.wordTimed==true).put("artwork_bytes",store.artwork(store.currentScope,track.videoId)?.length()?:0)
                result.putString("measurement",measure.toString())
            } finally {extractor.release()}
            finish(Activity.RESULT_OK,result)
        } catch(e:Exception) {result.putString("error",e::class.simpleName+":"+e.message?.takeIf {!it.contains("https")});finish(Activity.RESULT_CANCELED,result)}
    }
}
