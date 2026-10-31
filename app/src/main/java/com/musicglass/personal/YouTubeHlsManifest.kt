package com.musicglass.personal
import com.metrolist.innertubex.models.response.PlayerResponse.StreamingData.Format
import okhttp3.HttpUrl.Companion.toHttpUrl

/** A live manifest's challenge address as a format, so the solver treats it as it treats a song's. */
internal fun hlsChallengeFormat(challengeUrl: String) = Format(itag = 0, url = challengeUrl, mimeType = "application/x-mpegURL")

/** YouTube HLS authentication and n challenges live in the path, before the playlist suffix. */
internal suspend fun prepareYoutubeHlsManifest(raw: String, solveN: suspend (String) -> String?): String {
    val url=raw.toHttpUrl()
    require(url.isHttps && (url.host=="googlevideo.com" || url.host.endsWith(".googlevideo.com")))
    var path=url.encodedPath
    Regex("/n/([^/]+)").find(path)?.let { match ->
        val challenge=match.groupValues[1]
        val solved=solveN(challenge)?.takeIf {it.matches(Regex("[A-Za-z0-9_-]{1,512}"))}
            ?: error("Live manifest challenge could not be solved")
        path=path.replaceRange(match.range,"/n/$solved")
    }
    val token=url.queryParameter("pot")
    if(!token.isNullOrBlank() && !path.contains("/pot/")) {
        require(token.matches(Regex("[A-Za-z0-9_=-]{1,8192}")))
        val suffix=Regex("/(?:file|playlist)/index\\.m3u8$").find(path)?.value.orEmpty()
        path=path.removeSuffix(suffix).trimEnd('/')+"/pot/$token"+suffix
    }
    return url.newBuilder().encodedPath(path).removeAllQueryParameters("pot").build().toString()
}
