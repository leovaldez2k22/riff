package com.musicglass.desktop

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow

internal abstract class CachedByteSource(val durationMs: Long) : AutoCloseable {
    var loudnessDb:Double?=null;internal set
    abstract fun readAt(position: Long, target: ByteArray, offset: Int, count: Int): Int
    abstract fun length(): Long
    /** Copy only already-acquired complete bytes. This API must never fetch a missing range. */
    open fun cachedEncodedSnapshot(destination:File):Boolean=false
}

/** Only validated complete ranges are exposed to AVIO. No missing range is read as sparse zeroes. */
internal class HttpRangeByteSource(
    private var url: URI, private var headers: Map<String, String>, directory: File,
    durationMs: Long = 0,
    private val requestBudget: Duration = Duration.ofSeconds(25),
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build(),
    private val representationKey:String?=null,
    private val refreshLease:(()->MediaLease)?=null
) : CachedByteSource(durationMs) {
    private val file: File
    private val channel: FileChannel
    private val complete = mutableSetOf<Long>()
    var persistenceWarning:String?=null;private set
    private val persistent=representationKey?.let{try{PersistentAudioBlocks(File(directory,"verified-blocks"),it)}catch(error:IOException){persistenceWarning="Persistent audio cache unavailable: ${error.javaClass.simpleName}";null}}
    private var size = -1L
    private var validator: String? = null
    private var closed = false
    var networkBytes = 0L; private set
    var cacheHits = 0L; private set
    init {
        require(url.scheme in setOf("http", "https") && url.host != null && url.userInfo == null)
        check(directory.mkdirs() || directory.isDirectory)
        file = File.createTempFile("riff-range-", ".media", directory)
        channel = FileChannel.open(file.toPath(), READ, WRITE)
        try { fetch(0) } catch (t: Exception) { close(); throw t }
    }
    private fun fetch(block: Long,renewed:Boolean=false) {
        persistent?.read(block)?.let{cached->
            if(size>=0)check(size==cached.total){"Cached representation size changed"}
            size=cached.total;var at=block*262144L;val bytes=ByteBuffer.wrap(cached.bytes);while(bytes.hasRemaining())at+=channel.write(bytes,at);complete+=block;cacheHits++;return
        }
        val start = block * 262144L
        val end = if (size >= 0) minOf(start + 262143, size - 1) else start + 262143
        val request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(20)).header("Range", "bytes=$start-$end").header("Accept-Encoding", "identity")
        validator?.let { request.header("If-Range", it) }
        headers.filterKeys { it.lowercase() !in setOf("range", "accept-encoding", "if-range", "host", "content-length") }.forEach { (k,v) -> request.header(k,v) }
        val handler=HttpResponse.BodyHandler<ByteArray> { info ->
            val limit=if(info.statusCode()==200 && start==0L)8388608L else 262144L
            BoundedMediaBody(limit)
        }
        val exchange=client.sendAsync(request.GET().build(),handler)
        val response=try {exchange.get(requestBudget.toMillis(),java.util.concurrent.TimeUnit.MILLISECONDS)}
            catch(error:Exception){exchange.cancel(true);throw IOException("Media request did not complete within its bounded budget",error)}
        if(response.statusCode() in setOf(401,403)&&!renewed&&refreshLease!=null){
            val fresh=refreshLease.invoke()
            if(representationKey!=null&&fresh.representation!=representationKey)throw IOException("Media representation changed; reopen at content time")
            if(representationKey==null&&validator==null)throw IOException("Media representation changed or cannot be proven; reopen at content time")
            require(fresh.url.scheme=="https"||(fresh.url.host in setOf("127.0.0.1","localhost")&&fresh.url.scheme=="http"))
            url=fresh.url;headers=fresh.headers;fetch(block,true);return
        }
        response.body().inputStream().use { body ->
            check(response.headers().firstValue("Content-Encoding").orElse("identity") == "identity") { "Encoded byte ranges are unsupported" }
            val tag = response.headers().firstValue("ETag").orElse(null)
            if (validator != null && validator != tag) throw IOException("Media representation changed")
            if (tag != null && !tag.startsWith("W/")) validator = tag
            val bytes: ByteArray
            if (response.statusCode() == 206) {
                val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.headers().firstValue("Content-Range").orElse(""))
                    ?: throw IOException("Invalid media Content-Range")
                val actualStart = range.groupValues[1].toLong(); val actualEnd=range.groupValues[2].toLong();val total=range.groupValues[3].toLong()
                require(actualStart == start && actualEnd == minOf(end,total-1) && total in 1..134217728L && actualEnd < total)
                if (size >= 0 && size != total) throw IOException("Media representation changed size"); size=total
                bytes=body.readNBytes((actualEnd-start+2).toInt())
                check(bytes.size.toLong()==actualEnd-start+1) { "Truncated or oversized media range" }
            } else if (response.statusCode() == 200 && start == 0L) {
                bytes=body.readNBytes(8388609);check(bytes.size <= 8388608) { "Media exceeds cache limit" };size=bytes.size.toLong()
            } else throw IOException("Media range HTTP ${response.statusCode()}")
            var at = start; val buffer=ByteBuffer.wrap(bytes)
            while(buffer.hasRemaining()) at += channel.write(buffer,at)
            try{
                if(response.statusCode()==206)persistent?.write(block,size,bytes)
                else if(persistent!=null)for(start in bytes.indices step 262144)persistent.write(start/262144L,size,bytes.copyOfRange(start,minOf(start+262144,bytes.size)))
            }catch(error:IOException){persistenceWarning="Persistent audio cache write failed: ${error.javaClass.simpleName}"}
            networkBytes += bytes.size
            if(response.statusCode()==200) for(b in 0..((size-1)/262144))complete+=b else complete+=block
        }
    }
    @Synchronized override fun cachedEncodedSnapshot(destination:File):Boolean {
        if(closed||size<=0||size>134217728L||(0..((size-1)/262144)).any{it !in complete})return false
        copyAcquiredAudio(file,size,destination);return true
    }
    @Synchronized override fun length() = size
    @Synchronized override fun readAt(position: Long, target: ByteArray, offset: Int, count: Int): Int {
        check(!closed);require(position>=0 && offset>=0 && count>=0 && offset+count<=target.size)
        if(Thread.currentThread().isInterrupted)throw InterruptedException("Media read cancelled")
        if(count==0)return 0
        if(position>=size)return -1
        val block=position/262144
        if(block !in complete)fetch(block) else cacheHits++
        val amount=minOf(count.toLong(),size-position,262144-(position%262144)).toInt()
        val buffer=ByteBuffer.wrap(target,offset,amount)
        var at=position
        while(buffer.hasRemaining()){val n=channel.read(buffer,at);if(n<=0)throw IOException("Validated cache range is unavailable");at+=n}
        return amount
    }
    @Synchronized override fun close(){if(!closed){closed=true;try{channel.close()}finally{file.delete();persistent?.close()}}}
}

/** SABR media chunks are real provider bytes; AVIO waits on missing bytes on the decoder thread only. */
internal class GrowingMediaByteSource(directory: File, durationMs: Long, bytes: Flow<ByteArray>,private val reconnect:(suspend()->Flow<ByteArray>)?=null) : CachedByteSource(durationMs) {
    private val file: File
    private val channel: FileChannel
    private val guard=java.lang.Object()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    @Volatile private var available=0L
    @Volatile private var done=false
    @Volatile private var closed=false
    @Volatile private var failure:Throwable?=null
    init {
        check(directory.mkdirs()||directory.isDirectory);file=File.createTempFile("riff-sabr-",".media",directory)
        channel=FileChannel.open(file.toPath(),READ,WRITE)
        scope.launch {
            var stream=bytes;var attempt=0
            while(isActive){
                val prefix=available;var position=0L
                try{
                    stream.collect{chunk->
                        ensureActive();check(position+chunk.size<=134217728L){"Media exceeds cache limit"}
                        var compared=0
                        while(position+compared<prefix&&compared<chunk.size){
                            val n=minOf(32768L,prefix-position-compared,(chunk.size-compared).toLong()).toInt()
                            val previous=ByteBuffer.allocate(n);var at=position+compared
                            while(previous.hasRemaining()){val count=channel.read(previous,at);if(count<=0)throw IOException("Cached prefix is missing");at+=count}
                            val data=previous.array();for(index in data.indices)if(data[index]!=chunk[compared+index])throw IOException("Media representation changed during reconnect; reopen at content time")
                            compared+=n
                        }
                        if(compared<chunk.size){val payload=ByteBuffer.wrap(chunk,compared,chunk.size-compared);var at=position+compared;while(payload.hasRemaining())at+=channel.write(payload,at);synchronized(guard){available=at;guard.notifyAll()}}
                        position+=chunk.size
                    }
                    check(position>=prefix){"Refreshed media ended before its validated prefix"}
                    synchronized(guard){done=true;guard.notifyAll()};break
                }catch(cancel:CancellationException){throw cancel}
                catch(error:Exception){
                    if(reconnect==null||attempt>=2||error.message.orEmpty().contains("representation changed")){synchronized(guard){failure=error;guard.notifyAll()};break}
                    delay(500L shl attempt);attempt++
                    try{stream=reconnect.invoke()}catch(cancel:CancellationException){throw cancel}catch(failed:Exception){synchronized(guard){failure=failed;guard.notifyAll()};break}
                }
            }
        }
    }
    override fun cachedEncodedSnapshot(destination:File):Boolean=synchronized(guard){
        if(!done||closed||failure!=null||available<=0)return@synchronized false
        copyAcquiredAudio(file,available,destination);true
    }
    override fun length():Long=if(done)available else -1
    override fun readAt(position:Long,target:ByteArray,offset:Int,count:Int):Int=synchronized(guard){
        require(position>=0 && offset>=0 && count>=0 && offset+count<=target.size)
        if(count==0)return@synchronized 0
        val deadline=System.nanoTime()+30_000_000_000L
        while(position>=available && !done && !closed && failure==null){
            if(System.nanoTime()>=deadline)throw IOException("Provider audio stalled")
            guard.wait(200)
        }
        check(!closed){"Media source closed"};if(position>=available)failure?.let{throw IOException("Provider audio acquisition failed",it)}
        if(position>=available)return@synchronized -1
        val n=minOf(count.toLong(),available-position).toInt();val b=ByteBuffer.wrap(target,offset,n);var at=position
        while(b.hasRemaining()){val read=channel.read(b,at);if(read<=0)throw IOException("Cached media read failed");at+=read};n
    }
    override fun close(){synchronized(guard){closed=true;guard.notifyAll()};scope.cancel();channel.close();file.delete()}
}
/** Enforces a body cap before the JDK byte-array subscriber allocates unbounded storage. */
internal class BoundedMediaBody(private val maximum:Long):HttpResponse.BodySubscriber<ByteArray> {
    private val delegate=HttpResponse.BodySubscribers.ofByteArray()
    private var subscription:java.util.concurrent.Flow.Subscription?=null
    private var received=0L
    private var failed=false
    override fun getBody():java.util.concurrent.CompletionStage<ByteArray> = delegate.body
    override fun onSubscribe(value:java.util.concurrent.Flow.Subscription){subscription=value;delegate.onSubscribe(value)}
    override fun onNext(items:List<ByteBuffer>){
        if(failed)return
        received+=items.sumOf{it.remaining().toLong()}
        if(received>maximum){failed=true;subscription?.cancel();delegate.onError(IOException("Media response exceeds its bounded byte limit"))}
        else delegate.onNext(items)
    }
    override fun onError(error:Throwable){if(!failed){failed=true;delegate.onError(error)}}
    override fun onComplete(){if(!failed)delegate.onComplete()}
}

internal data class MediaLease(val url:URI,val headers:Map<String,String>,val representation:String?)

/** A forced owned snapshot for analysis; caller owns and removes the new file. No network or sparse holes. */
private fun copyAcquiredAudio(source:File,length:Long,destination:File){
    require(length in 1..134217728L)
    var created=false
    try{
        // An interrupted FileChannel read closes that channel. Never borrow the playback channel
        // for cancellable background analysis: own a separate read handle for this copy.
        FileChannel.open(source.toPath(),READ).use{input->
        FileChannel.open(destination.toPath(),CREATE_NEW,WRITE).use{output->
            created=true
            val bytes=ByteBuffer.allocate(65536);var at=0L
            while(at<length){
                if(Thread.currentThread().isInterrupted)throw java.io.InterruptedIOException("Analysis snapshot cancelled")
                bytes.clear();bytes.limit(minOf(bytes.capacity().toLong(),length-at).toInt())
                val n=input.read(bytes,at);check(n>0){"Acquired audio snapshot is incomplete"};bytes.flip()
                while(bytes.hasRemaining())output.write(bytes)
                at+=n
            }
            output.force(true)
        }}
    }catch(error:Throwable){if(created)java.nio.file.Files.deleteIfExists(destination.toPath());throw error}
}
