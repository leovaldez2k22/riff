package com.musicglass.personal

import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

@OptIn(io.ktor.utils.io.InternalAPI::class)
class OriginalAudioEngineTest {
    private val body="""{"streamingData":{"adaptiveFormats":[{"itag":251,"mimeType":"audio/webm","audioTrack":{"id":"en.0","displayName":"English (original)"}},{"itag":251,"mimeType":"audio/webm","audioTrack":{"id":"hi.1","displayName":"Hindi","audioIsDefault":true}}]}}"""
    private class FixtureEngine(val fixture:String):HttpClientEngineBase("fixture") {
        override val config=HttpClientEngineConfig()
        override suspend fun execute(data:HttpRequestData)=HttpResponseData(HttpStatusCode.OK,io.ktor.util.date.GMTDate(),headersOf(HttpHeaders.ContentType,"application/json"),HttpProtocolVersion.HTTP_1_1,ByteReadChannel(fixture),coroutineContext)
    }
    @Test fun normalClientPipelineActuallyReceivesFilteredOriginalFormats()= runBlocking {
        val engine=OriginalAudioTransport(FixtureEngine(body));val client=HttpClient(engine)
        try {
            val response=client.get("https://www.youtube.com/youtubei/v1/player").bodyAsText()
            assertTrue(response.contains("English (original)"));assertFalse(response.contains("Hindi"))
        } finally {client.close();engine.close()}
    }
    @Test fun browseResponsesAndOtherHostsArePassedThrough()=runBlocking {
        val engine=OriginalAudioTransport(FixtureEngine(body));val client=HttpClient(engine)
        try {for(url in listOf("https://www.youtube.com/youtubei/v1/browse","https://example.test/player")) assertEquals(body,client.get(url).bodyAsText())}
        finally {client.close();engine.close()}
    }
}
