package com.musicglass.personal

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Loopback only, in-memory routes, bounded requests/headers/body/time. Never reads arbitrary paths. */
internal class FixtureHttpServer(routes:Map<String,Reply>,private val maxRequests:Int=16):Closeable {
    data class Reply(val body:ByteArray,val status:Int=200,val contentType:String="audio/wav")
    private val routes=routes.also {
        require(maxRequests in 1..64)
        require(it.size in 1..32 && it.all {(path,reply)->path.startsWith("/") && reply.body.size<=16*1024*1024})
    }.mapValues {(_,reply)->reply.copy(body=reply.body.copyOf())}
    private val socket=ServerSocket(0,8,InetAddress.getByName("127.0.0.1")).apply {soTimeout=500}
    private val executor=Executors.newSingleThreadExecutor {r->Thread(r,"riff-fixture-http").apply {isDaemon=true}}
    private val count=AtomicInteger()
    private val activeClient=AtomicReference<Socket?>()
    @Volatile private var closed=false
    val baseUrl="http://127.0.0.1:${socket.localPort}"
    init {
        executor.submit {
            while(!closed && count.get()<maxRequests) {
                try {socket.accept().use {client->
                    activeClient.set(client)
                    try {count.incrementAndGet();if(!closed) serve(client)} finally {activeClient.compareAndSet(client,null)}
                }}
                catch(_:java.net.SocketTimeoutException) {}
                catch(_:IllegalArgumentException) {} // Malformed/oversized header consumes one bounded request.
                catch(_:java.io.IOException) {if(closed) break}
            }
            socket.close()
        }
    }
    private fun serve(client:Socket) {
        client.soTimeout=2000
        val input=client.getInputStream();val header=StringBuilder()
        while(!header.endsWith("\r\n\r\n")) {
            require(header.length<8192) {"Fixture header limit"}
            val next=input.read();if(next<0) return
            header.append(next.toChar())
        }
        val lines=header.toString().split("\r\n");val request=lines.first().split(" ")
        val method=request.getOrNull(0);val path=request.getOrNull(1)
        val reply=if(method in setOf("GET","HEAD")) routes[path] else null
        var status=reply?.status?:404;var body=reply?.body?:ByteArray(0)
        val fullSize=body.size;var contentRange:String?=null
        val range=lines.firstOrNull {it.startsWith("Range:",true)}?.substringAfter(":")?.trim()
        if(range!=null && status==200) {
            val match=Regex("bytes=(\\d+)-(\\d*)").matchEntire(range)
            val start=match?.groupValues?.get(1)?.toLongOrNull()
            val end=match?.groupValues?.get(2)?.takeIf {it.isNotEmpty()}?.toLongOrNull()?:fullSize.toLong()-1
            if(start==null || start !in 0 until fullSize.toLong() || end<start || end>=fullSize) {
                status=416;body=ByteArray(0);contentRange="bytes */$fullSize"
            } else {
                status=206;body=body.copyOfRange(start.toInt(),end.toInt()+1)
                contentRange="bytes $start-$end/$fullSize"
            }
        }
        val response=buildString {
            append("HTTP/1.1 $status Fixture\r\nContent-Length: ${body.size}\r\n")
            append("Content-Type: ${reply?.contentType?:"text/plain"}\r\nAccept-Ranges: bytes\r\nConnection: close\r\n")
            contentRange?.let {append("Content-Range: $it\r\n")}
            append("\r\n")
        }
        client.getOutputStream().apply {write(response.toByteArray(Charsets.US_ASCII));if(method!="HEAD") write(body);flush()}
    }
    override fun close() {closed=true;socket.close();activeClient.getAndSet(null)?.close();executor.shutdownNow();check(executor.awaitTermination(3,TimeUnit.SECONDS)) {"Fixture server did not stop"}}
}
