package com.musicglass.personal

import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Q18-100: Google's account list is asked the way Chrome asks it; a GET was refused with HTTP 400 on the phone. */
@OptIn(io.ktor.utils.io.InternalAPI::class)
class GoogleSessionAccountsRequestTest {
    private class RecordingEngine(val answer: String) : HttpClientEngineBase("recording") {
        override val config = HttpClientEngineConfig()
        var seen: HttpRequestData? = null
        override suspend fun execute(data: HttpRequestData): HttpResponseData {
            seen = data
            // Google's own answer to a GET, so the test fails the way the phone did.
            val status = if (data.method == HttpMethod.Post) HttpStatusCode.OK else HttpStatusCode.BadRequest
            return HttpResponseData(status, io.ktor.util.date.GMTDate(), headersOf(HttpHeaders.ContentType, "application/json"),
                HttpProtocolVersion.HTTP_1_1, ByteReadChannel(answer), coroutineContext)
        }
    }

    @Test(timeout = 30_000) fun theListIsAskedForWithAPostAsChromeDoes() = runBlocking {
        val answer = """["gaia.l.a.r",[["gaia.l.a",1,"Owner","owner@example.com","https://p/1",1,1,0,null,1,"111222333"]]]"""
        // Behind Riff's own transport, as the session's client is.
        val recorder = RecordingEngine(answer); val engine = OriginalAudioTransport(recorder); val client = HttpClient(engine)
        try {
            val response = requestGoogleSessionAccounts(client, "SID=test")
            assertEquals(200, response.status.value)
            assertEquals("the list reaches the parser (SignInDesignTest reads it)", answer, response.bodyAsText())
            val request = recorder.seen!!
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("accounts.google.com", request.url.host)
            assertEquals("/ListAccounts", request.url.encodedPath)
            assertEquals("standard", request.url.parameters["json"])
            assertEquals("SID=test", request.headers[HttpHeaders.Cookie])
            val body = request.body as OutgoingContent.ByteArrayContent
            assertEquals(" ", body.bytes().decodeToString())
            assertEquals(ContentType.Application.FormUrlEncoded, body.contentType?.withoutParameters())
        } finally { client.close(); engine.close() }
    }
}
