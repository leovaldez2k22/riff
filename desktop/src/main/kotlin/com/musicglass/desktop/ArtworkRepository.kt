package com.musicglass.desktop

import com.musicglass.data.TrackRecord
import androidx.compose.ui.graphics.ImageBitmap
import java.io.File
import java.io.ByteArrayInputStream
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import java.net.URI
import java.net.http.*
import java.time.Duration
import javax.imageio.ImageIO
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

data class CachedArtwork(val key:String,val path:String,val width:Int,val height:Int,val dominant:Int,val mesh:IntArray)

/** Real bounded image acquisition; immutable content keys keep palettes and bitmaps paired during rapid changes. */
internal class ArtworkRepository(private val directory:File,private val incoming:File) {
    private val dispatcher=Dispatchers.IO.limitedParallelism(2)
    private val stripes=Array(32){kotlinx.coroutines.sync.Mutex()}
    private val bitmaps=LinkedHashMap<String,Pair<ImageBitmap,Long>>(16,.75f,true)
    private var memoryBytes=0L
    val networkFetches=java.util.concurrent.atomic.AtomicLong()
    private val http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()
    init{check(directory.mkdirs()||directory.isDirectory)}
    suspend fun obtain(track:TrackRecord):TrackRecord=withContext(dispatcher){
        track.artworkKey?.let{if(metadata(it)!=null)return@withContext track}
        val artworkUrl=track.artworkUrl
        if(artworkUrl?.startsWith("https://")==true){
            val ref=File(directory,com.musicglass.data.SqliteLibraryRepository.digest("art-url-v1:$artworkUrl")+".ref")
            if(ref.isFile&&ref.length()<=128){
                val key=ref.readText().trim()
                if(key.matches(Regex("[0-9a-f]{64}"))&&metadata(key)!=null)return@withContext track.copy(artworkKey=key)
            }
        }
        val raw=when {
            artworkUrl?.startsWith("incoming:")==true->{val file=File(checkNotNull(artworkUrl).removePrefix("incoming:")).canonicalFile;require(file.toPath().startsWith(incoming.canonicalFile.toPath()));boundedFile(file)}
            artworkUrl?.startsWith("https://")==true->download(URI(artworkUrl))
            track.sourcePath!=null->{val parent=File(track.sourcePath).parentFile;listOf("cover.jpg","cover.png","folder.jpg","folder.png","front.jpg").map{File(parent,it)}.firstOrNull{it.isFile}?.let(::boundedFile)}
            else->null
        }?:return@withContext track
        val result=ingest(raw)
        if(artworkUrl?.startsWith("https://")==true){
            val ref=File(directory,com.musicglass.data.SqliteLibraryRepository.digest("art-url-v1:$artworkUrl")+".ref")
            val temp=File(directory,"${ref.name}.${java.util.UUID.randomUUID()}.tmp")
            try{temp.writeText(result.key);Files.move(temp.toPath(),ref.toPath(),ATOMIC_MOVE,REPLACE_EXISTING)}finally{temp.delete()}
            directory.listFiles{file->file.name.endsWith(".ref")}.orEmpty().sortedByDescending{it.lastModified()}.drop(4096).forEach{it.delete()}
        }
        track.copy(artworkKey=result.key,artworkUrl=track.artworkUrl?.takeUnless{it.startsWith("incoming:")})
    }
    suspend fun ingest(encoded:ByteArray):CachedArtwork=withContext(dispatcher){
        require(encoded.size in 1..16*1024*1024)
        val key=com.musicglass.data.SqliteLibraryRepository.digest("riff-art-v1:")+":"+java.security.MessageDigest.getInstance("SHA-256").digest(encoded).joinToString(""){"%02x".format(it)}
        val digest=com.musicglass.data.SqliteLibraryRepository.digest(key)
        val mutex=stripes[(digest.hashCode() and Int.MAX_VALUE)%stripes.size]
        mutex.lock()
        try {
            metadata(digest)?.let{return@withContext it}
            val image=ImageIO.createImageInputStream(ByteArrayInputStream(encoded)).use{stream->
                val readers=ImageIO.getImageReaders(stream);require(readers.hasNext()){"Unsupported cover image"}
                val reader=readers.next()
                try{reader.input=stream;val w=reader.getWidth(0);val h=reader.getHeight(0);require(w>0&&h>0&&w.toLong()*h<=32_000_000){"Cover image dimensions exceed limit"}
                    val parameter=reader.defaultReadParam;val sample=maxOf(1,(maxOf(w,h)+2047)/2048);parameter.setSourceSubsampling(sample,sample,0,0);reader.read(0,parameter)
                }finally{reader.dispose()}
            }
            val opaque=BufferedImage(image.width,image.height,BufferedImage.TYPE_INT_RGB)
            opaque.createGraphics().useGraphics{g->g.color=java.awt.Color(0x181A22);g.fillRect(0,0,opaque.width,opaque.height);g.drawImage(image,0,0,null)}
            val sample=BufferedImage(128,128,BufferedImage.TYPE_INT_RGB)
            sample.createGraphics().useGraphics{g->g.drawImage(opaque,0,0,128,128,null)}
            val pixels=sample.getRGB(0,0,128,128,null,0,128)
            val result=CachedArtwork(digest,File(directory,"$digest.png").path,opaque.width,opaque.height,dominantArtworkRgb(pixels),deckMesh(meshColors(pixels,128,128)))
            val temporary=File(directory,"$digest.${java.util.UUID.randomUUID()}.tmp")
            try{check(ImageIO.write(opaque,"png",temporary));Files.move(temporary.toPath(),File(result.path).toPath(),ATOMIC_MOVE,REPLACE_EXISTING)}finally{temporary.delete()}
            val json=buildJsonObject{put("version",1);put("width",result.width);put("height",result.height);put("dominant",result.dominant);put("mesh",JsonArray(result.mesh.map(::JsonPrimitive)))}
            val metaTemp=File(directory,"$digest.meta.tmp")
            try{metaTemp.writeText(json.toString());Files.move(metaTemp.toPath(),File(directory,"$digest.json").toPath(),ATOMIC_MOVE,REPLACE_EXISTING)}finally{metaTemp.delete()}
            evictDisk(digest);result
        }finally{mutex.unlock()}
    }
    fun metadata(key:String):CachedArtwork? {
        require(key.matches(Regex("[0-9a-f]{64}")))
        val image=File(directory,"$key.png");val metadata=File(directory,"$key.json")
        if(!image.isFile||!metadata.isFile||metadata.length()>8192)return null
        return try{val json=Json.parseToJsonElement(metadata.readText()).jsonObject;require(json.getValue("version").jsonPrimitive.int==1)
            val w=json.getValue("width").jsonPrimitive.int;val h=json.getValue("height").jsonPrimitive.int;require(w in 1..2048&&h in 1..2048)
            val mesh=json.getValue("mesh").jsonArray.map{it.jsonPrimitive.int}.toIntArray();require(mesh.size==36)
            CachedArtwork(key,image.path,w,h,json.getValue("dominant").jsonPrimitive.int,mesh)
        }catch(_:Exception){null}
    }
    /** Memory-only fast path for moving the same artwork between desktop surfaces. */
    fun cachedBitmap(key:String):ImageBitmap?=synchronized(bitmaps){bitmaps[key]?.first}
    suspend fun bitmap(key:String):ImageBitmap?=withContext(dispatcher){
        synchronized(bitmaps){bitmaps[key]?.let{return@withContext it.first}}
        val metadata=metadata(key)?:return@withContext null
        val image=ImageIO.createImageInputStream(File(metadata.path)).use{stream->
            val readers=ImageIO.getImageReaders(stream);if(!readers.hasNext())return@withContext null
            val reader=readers.next();try{reader.input=stream;require(reader.getWidth(0)==metadata.width&&reader.getHeight(0)==metadata.height){"Cached cover dimensions changed"};reader.read(0)}finally{reader.dispose()}
        }
        require(image.width==metadata.width&&image.height==metadata.height)
        val pixels=image.getRGB(0,0,image.width,image.height,null,0,image.width).also{values->values.indices.forEach{values[it]=values[it] or 0xff000000.toInt()}}
        val bitmap=opaqueArtworkBitmap(pixels,image.width,image.height);val bytes=image.width.toLong()*image.height*4
        synchronized(bitmaps){while(memoryBytes+bytes>32L*1024*1024&&bitmaps.isNotEmpty()){val first=bitmaps.keys.first();memoryBytes-=bitmaps.remove(first)!!.second};bitmaps.put(key,bitmap to bytes)?.let{memoryBytes-=it.second};memoryBytes+=bytes}
        File(metadata.path).setLastModified(System.currentTimeMillis());bitmap
    }
    private fun boundedFile(file:File):ByteArray{require(file.length() in 1..16L*1024*1024);return file.inputStream().use{it.readNBytes(16*1024*1024+1)}.also{require(it.size<=16*1024*1024)}}
    private fun download(initial:URI):ByteArray {
        networkFetches.incrementAndGet()
        var uri=initial
        repeat(5){
            val host=uri.host?.lowercase().orEmpty();require(uri.scheme=="https"&&uri.userInfo==null&&listOf("ytimg.com","ggpht.com","googleusercontent.com").any{host==it||host.endsWith(".$it")}){"Unsupported artwork host"}
            val exchange=http.sendAsync(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build(),HttpResponse.BodyHandler<ByteArray>{BoundedMediaBody(16L*1024*1024)})
            val response=try{exchange.get(25,java.util.concurrent.TimeUnit.SECONDS)}catch(error:Exception){exchange.cancel(true);throw error}
            if(response.statusCode() in 300..399){uri=uri.resolve(response.headers().firstValue("Location").orElseThrow());return@repeat}
            check(response.statusCode()==200){"Cover request failed (${response.statusCode()})"};return response.body()
        }
        error("Cover redirect limit exceeded")
    }
    @Synchronized private fun evictDisk(keep:String){
        val images=directory.listFiles{file->file.name.matches(Regex("[0-9a-f]{64}\\.png"))}.orEmpty().sortedBy{it.lastModified()}
        var total=images.sumOf{it.length()}
        for(image in images){if(total<=128L*1024*1024)break;if(image.nameWithoutExtension==keep)continue;val size=image.length();if(image.delete()){total-=size;File(directory,image.nameWithoutExtension+".json").delete()}}
    }
}
private inline fun java.awt.Graphics2D.useGraphics(action:(java.awt.Graphics2D)->Unit){try{action(this)}finally{dispose()}}
