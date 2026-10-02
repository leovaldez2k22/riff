package com.musicglass.personal

import io.ktor.client.engine.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.io.readByteArray

/** Only YouTube player JSON is filtered. Search, accounts, media bytes and live endpoints pass through. */
internal object OriginalAudioEngine:HttpClientEngineFactory<OkHttpConfig> {
    override fun create(block:OkHttpConfig.()->Unit):HttpClientEngine = OriginalAudioTransport(OkHttp.create(block))
}
@OptIn(io.ktor.utils.io.InternalAPI::class)
internal class OriginalAudioTransport(private val delegate:HttpClientEngine):HttpClientEngineBase("riff-original-audio") {
    override val config get()=delegate.config
    override val dispatcher get()=delegate.dispatcher
    override val supportedCapabilities get()=delegate.supportedCapabilities
    override fun close() {super.close();delegate.close()}
    override suspend fun execute(data:HttpRequestData):HttpResponseData {
        val response=delegate.execute(data)
        val host=data.url.host
        if(!(host=="youtube.com" || host.endsWith(".youtube.com") || host=="youtubei.googleapis.com") ||
            !data.url.encodedPath.endsWith("/player") || response.statusCode.value !in 200..299) return response
        val channel=response.body as? ByteReadChannel ?: return response
        val bytes=channel.readRemaining().readByteArray()
        val raw=bytes.toString(Charsets.UTF_8)
        val filtered=originalAudioPlayerResponse(raw).toByteArray(Charsets.UTF_8)
        val headers=Headers.build {
            response.headers.forEach {name,values->if(!name.equals(HttpHeaders.ContentLength,true)) appendAll(name,values)}
            append(HttpHeaders.ContentLength,filtered.size.toString())
        }
        return HttpResponseData(response.statusCode,response.requestTime,headers,response.version,ByteReadChannel(filtered),response.callContext)
    }
}
