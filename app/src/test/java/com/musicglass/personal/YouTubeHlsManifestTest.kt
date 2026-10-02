package com.musicglass.personal
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
class YouTubeHlsManifestTest {
    @Test fun solvesPathChallengeAndMovesTokenBeforePlaylistSuffix() = runBlocking {
        val url=prepareYoutubeHlsManifest("https://manifest.googlevideo.com/api/manifest/n/challenge/playlist/index.m3u8?pot=token&cpn=nonce") {value->assertEquals("challenge",value);"solved"}.toHttpUrl()
        assertEquals("/api/manifest/n/solved/pot/token/playlist/index.m3u8",url.encodedPath)
        assertNull(url.queryParameter("pot"));assertEquals("nonce",url.queryParameter("cpn"))
    }
    @Test fun preservesSignedPathAndExistingPathToken() = runBlocking {
        val raw="https://manifest.googlevideo.com/api/sig/signature/pot/existing/file/index.m3u8?other=value"
        assertEquals(raw,prepareYoutubeHlsManifest(raw) {error("No challenge")})
    }
    @Test fun refusesUnsolvedChallengesInsteadOfHandingThePlayerARejectedUrl() = runBlocking {
        assertTrue(runCatching {prepareYoutubeHlsManifest("https://manifest.googlevideo.com/api/n/challenge/playlist/index.m3u8") {null}}.isFailure)
    }
}
