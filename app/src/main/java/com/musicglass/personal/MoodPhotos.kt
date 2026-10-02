package com.musicglass.personal

import android.app.job.*
import android.content.*
import android.graphics.BitmapFactory
import android.text.Html
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.serialization.json.*
import java.io.File
import java.time.*
import java.util.concurrent.TimeUnit

internal data class MoodPhoto(val mood:String,val id:String,val day:String,val file:File,val author:String,val license:String,val page:String,val licenseUrl:String)
/** Independent daily cache: photos never block feeds, audio parsing or UI navigation. */
internal class MoodPhotos private constructor(context:Context) {
    private val app=context.applicationContext
    private val prefs=app.getSharedPreferences("daily_mood_photos",Context.MODE_PRIVATE)
    private val directory=File(app.filesDir,"mood_photos")
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val gate=Mutex()
    private val http=OkHttpClient.Builder().connectTimeout(12,TimeUnit.SECONDS).readTimeout(18,TimeUnit.SECONDS).callTimeout(25,TimeUnit.SECONDS).build()
    private val json=Json {ignoreUnknownKeys=true}
    val photos=MutableStateFlow<Map<String,MoodPhoto>>(emptyMap())
    internal var lastStatus="idle"
        private set
    private val attempted=mutableMapOf<String,Long>()
    init {scope.launch {gate.withLock {load()}}}
    private fun key(mood:String)=java.security.MessageDigest.getInstance("SHA-256").digest(mood.toByteArray()).take(12).joinToString("") {"%02x".format(it)}
    private fun JsonObject.str(key:String)=(get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun load() {
        directory.mkdirs()
        photos.value=prefs.all.mapNotNull {(_,value)->runCatching {
            val o=json.parseToJsonElement(value as String).jsonObject;val f=File(directory,o.str("file"))
            if(f.exists() && f.parentFile==directory) MoodPhoto(o.str("mood"),o.str("id"),o.str("day"),f,o.str("author"),o.str("license"),o.str("page"),o.str("licenseUrl")) else null
        }.getOrNull()}.associateBy {it.mood}
    }
    fun foreground() {schedule(app);scope.launch {refreshKnown()}}
    fun ensure(mood:String) {if(mood.isNotBlank()) scope.launch {refresh(mood)}}
    suspend fun refreshKnown() {val names=gate.withLock {if(photos.value.isEmpty()) load();photos.value.keys.toList()};names.forEach {refresh(it)}}
    internal suspend fun refresh(mood:String):MoodPhoto?=withContext(Dispatchers.IO) {gate.withLock {
        if(photos.value.isEmpty()) load()
        val old=photos.value[mood];val day=photoDay()
        if(old?.day==day && old.file.exists()) return@withLock old
        val now=System.currentTimeMillis()
        if(now-(attempted[mood]?:0)<3_600_000) return@withLock old
        attempted[mood]=now;lastStatus="searching"
        try {
            val candidates=discoverPhotos(mood).associateBy {it.id}
            lastStatus="${candidates.size} photo candidates"
            for(id in photoChoiceOrder(candidates.keys.toList(),old?.id,mood,day).take(6)) {
                currentCoroutineContext().ensureActive()
                val candidate=candidates.getValue(id);val imageUrl=candidate.url
                val bytes=try {http.newCall(Request.Builder().url(imageUrl).header("User-Agent","Riff/${BuildConfig.VERSION_NAME}").build()).execute().use {r->
                    lastStatus="photo HTTP ${r.code}"
                    if(!r.isSuccessful || (r.body?.contentLength()?:0)>2_000_000) null else r.body?.byteStream()?.use {input->
                        val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                        while(true) {val n=input.read(buffer);if(n<0) break;out.write(buffer,0,n);if(out.size()>2_000_000) break}
                        out.toByteArray().takeIf {it.size<=2_000_000}
                    }
                }} catch(e:CancellationException) {throw e} catch(_:Exception) {null} ?:continue
                val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                lastStatus="decoded ${bounds.outWidth}x${bounds.outHeight}"
                if(bounds.outWidth<600 || bounds.outHeight<400) continue
                val file=File(directory,"${key(mood)}-$id-$day.jpg");val temp=File(directory,file.name+".partial")
                temp.writeBytes(bytes);check(temp.renameTo(file))
                val author=Html.fromHtml(candidate.author,Html.FROM_HTML_MODE_LEGACY).toString().trim().take(200)
                val photo=MoodPhoto(mood,id,day,file,author,candidate.license,candidate.page,candidate.licenseUrl)
                val data=buildJsonObject {put("mood",mood);put("id",id);put("day",day);put("file",file.name);put("author",author);put("license",candidate.license);put("page",candidate.page);put("licenseUrl",candidate.licenseUrl)}
                prefs.edit().putString(key(mood),data.toString()).apply()
                photos.value=photos.value+(mood to photo)
                if(old?.file!=file) old?.file?.delete()
                val excess=photos.value.values.sortedBy {it.file.lastModified()}.dropLast(64)
                if(excess.isNotEmpty()) {
                    val remaining=photos.value-excess.map {it.mood}.toSet()
                    val edit=prefs.edit();excess.forEach {edit.remove(key(it.mood));it.file.delete()};edit.apply();photos.value=remaining
                }
                directory.listFiles()?.filter {it.name.endsWith(".partial")}?.forEach {it.delete()}
                lastStatus="ready";return@withLock photo
            }
        } catch(e:CancellationException) {throw e} catch(e:Exception) {lastStatus="${e.javaClass.simpleName}: ${e.message?.take(200)}" /* Keep the last successful photograph. */ }
        old
    }}
    private fun searchJson(url:okhttp3.HttpUrl):JsonObject? = http.newCall(Request.Builder().url(url)
        .header("User-Agent","Riff/${BuildConfig.VERSION_NAME} (Android music app; daily photo discovery)").build()).execute().use {r->
        lastStatus="search HTTP ${r.code}"
        if(!r.isSuccessful || (r.body?.contentLength()?:0)>2_000_000) null else r.body?.byteStream()?.use {input->
            val bytes=input.readBytesLimited(2_000_000)?:return@use null
            runCatching {json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject}.getOrNull()
        }
    }
    private fun java.io.InputStream.readBytesLimited(limit:Int):ByteArray? {
        val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true) {val n=read(buffer);if(n<0) break;if(out.size()+n>limit) return null;out.write(buffer,0,n)}
        return out.toByteArray()
    }
    private fun discoverPhotos(mood:String):List<PhotoCandidate> {
        // Openverse indexes licensed photographs from Flickr and other public collections.
        val openverse="https://api.openverse.org/v1/images/".toHttpUrl().newBuilder()
            .addQueryParameter("q",moodPhotoQuery(mood)).addQueryParameter("license","cc0,pdm,by,by-sa")
            .addQueryParameter("extension","jpg").addQueryParameter("category","photograph")
            .addQueryParameter("aspect_ratio","wide").addQueryParameter("page_size","20").build()
        runCatching {searchJson(openverse)?.let(::parseOpenversePhotos)}.getOrNull()?.takeIf {it.isNotEmpty()}?.let {return it}
        val url="https://commons.wikimedia.org/w/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("action","query").addQueryParameter("format","json").addQueryParameter("generator","search")
            .addQueryParameter("gsrsearch","${moodPhotoQuery(mood)} filetype:bitmap -drawing -illustration -painting -logo -map -diagram")
            .addQueryParameter("gsrnamespace","6").addQueryParameter("gsrlimit","12").addQueryParameter("prop","imageinfo")
            .addQueryParameter("iiprop","url|size|mime|extmetadata").addQueryParameter("iiurlwidth","1280")
            .addQueryParameter("iiextmetadatafilter","Artist|LicenseShortName|LicenseUrl|ImageDescription").build()
        val root=searchJson(url)?:return emptyList()
        val pages=(root["query"] as? JsonObject)?.get("pages") as? JsonObject ?:return emptyList()
        return pages.values.mapNotNull {v->
            val page=v as? JsonObject?:return@mapNotNull null
            val info=(page["imageinfo"] as? JsonArray)?.firstOrNull() as? JsonObject?:return@mapNotNull null
            val meta=info["extmetadata"] as? JsonObject?:return@mapNotNull null
            fun metadata(k:String)=((meta[k] as? JsonObject)?.get("value") as? JsonPrimitive)?.contentOrNull.orEmpty()
            val title=page.str("title")+" "+metadata("ImageDescription");val license=metadata("LicenseShortName")
            val width=info.str("width").toIntOrNull()?:0;val height=info.str("height").toIntOrNull()?:1
            val imageUrl=info.str("thumburl");val host=runCatching {imageUrl.toHttpUrl().host}.getOrNull()
            if(info.str("mime")!="image/jpeg" || width<1000 || height<600 || width.toFloat()/height !in .9f..2.6f ||
                host !in setOf("upload.wikimedia.org","thumb.wikimedia.org") ||
                Regex("drawing|illustration|painting|diagram|map of|logo|AI.generated",RegexOption.IGNORE_CASE).containsMatchIn(title) ||
                !Regex("^(CC BY(?:-SA)?(?: [0-9.]+)?(?: [a-z]{2})?|CC0|Public domain)$",RegexOption.IGNORE_CASE).matches(license)) return@mapNotNull null
            PhotoCandidate("wc-${page.str("pageid")}",imageUrl,metadata("Artist"),license,info.str("descriptionurl"),metadata("LicenseUrl"))
        }
    }
    companion object {
        @Volatile private var instance:MoodPhotos?=null
        fun get(context:Context)=instance?:synchronized(this) {instance?:MoodPhotos(context).also {instance=it}}
        private const val JOB=15201
        fun schedule(context:Context,replace:Boolean=false) {
            val scheduler=context.getSystemService(JobScheduler::class.java)
            if(!replace && scheduler.getPendingJob(JOB)!=null) return
            val next=LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            scheduler.schedule(JobInfo.Builder(JOB,ComponentName(context,MoodPhotoRefreshJob::class.java))
                .setMinimumLatency((next-System.currentTimeMillis()).coerceAtLeast(60_000))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).build())
        }
    }
}
class MoodPhotoRefreshJob:JobService() {
    private var work:Job?=null
    override fun onStartJob(params:JobParameters):Boolean {
        work=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate).launch {
            try {MoodPhotos.get(this@MoodPhotoRefreshJob).refreshKnown();ensureActive();jobFinished(params,false);MoodPhotos.schedule(this@MoodPhotoRefreshJob,replace=true)} catch(e:CancellationException) {throw e} catch(_:Exception) {jobFinished(params,true)}
        };return true
    }
    override fun onStopJob(params:JobParameters):Boolean {work?.cancel();return true}
}
