package com.musicglass.personal

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.models.YouTubeClient
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Fixed-origin authenticated preference request, using one immutable account session. */
internal suspend fun applyPlaylistSort(http:HttpClient,json:Json,session:InnerTube.SessionSnapshot,edit:PlaylistSortEdit) {
    require(edit.validFor("VL${edit.playlistId}")) {"Invalid playlist sort preference"}
    check(!session.cookie.isNullOrBlank() && !session.sapisid.isNullOrBlank()) {"Sign in again to sort this playlist"}
    val client=YouTubeClient.WEB_REMIX
    val origin=YouTubeClient.ORIGIN_YOUTUBE_MUSIC
    val timestamp=System.currentTimeMillis()/1000
    val hash=MessageDigest.getInstance("SHA-1").digest("$timestamp ${session.sapisid} $origin".toByteArray())
        .joinToString("") {"%02x".format(it)}
    val body=buildJsonObject {
        put("context",json.encodeToJsonElement(client.toContext(session.locale,session.visitorData,session.dataSyncId)))
        put("playlistId",edit.playlistId)
        put("actions",JsonArray(listOf(edit.action)))
        edit.params?.let {put("params",it)}
    }
    val response=http.post("$origin/youtubei/v1/browse/edit_playlist") {
        header("User-Agent",client.userAgent)
        header("X-Goog-Api-Format-Version","1")
        header("X-YouTube-Client-Name",client.clientId)
        header("X-YouTube-Client-Version",client.clientVersion)
        header("Origin",origin);header("X-Origin",origin);header("Referer","$origin/")
        header("X-Goog-AuthUser",session.authUser)
        session.visitorData?.let {header("X-Goog-Visitor-Id",it)}
        header("Cookie",session.cookie)
        header("Authorization","SAPISIDHASH ${timestamp}_$hash")
        setBody(TextContent(body.toString(),ContentType.Application.Json))
    }
    check(response.status.value in 200..299) {"YouTube Music could not sort this playlist (HTTP ${response.status.value})"}
    val result=json.parseToJsonElement(response.bodyAsText())
    check(catalogRenderer(result,"error")==null && catalogValue(result,"status")=="STATUS_SUCCEEDED") {
        "YouTube Music could not apply this sort preference. Please reload and retry."
    }
}
