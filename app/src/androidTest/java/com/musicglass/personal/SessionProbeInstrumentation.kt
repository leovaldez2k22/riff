package com.musicglass.personal

import android.app.Instrumentation
import android.os.Bundle
import android.webkit.CookieManager
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.models.YouTubeClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * W5.1 spike: what YouTube Music answers when the session cookies are no longer valid, without
 * signing the phone out. Sends the same requests with the real cookies, with none, with every value
 * scrambled, and with only the session cookies scrambled (closest to a server-side revocation).
 *
 *   adb shell am instrument -w com.musicglass.personal.debug.test/com.musicglass.personal.SessionProbeInstrumentation
 *   (test APK of a debug build made with -PriffSideBySide=true, as the lanes make it; otherwise com.musicglass.personal.test)
 *
 * Reports status codes, Google error codes, the loggedOut flag and yes/no markers only: never cookie
 * values, visitor data, sync IDs or response text.
 */
class SessionProbeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    private val sessionCookies = setOf("SID", "HSID", "SSID", "__Secure-1PSID", "__Secure-3PSID", "__Secure-1PSIDTS",
        "__Secure-3PSIDTS", "__Secure-1PSIDCC", "__Secure-3PSIDCC", "SIDCC", "LOGIN_INFO")
    private val markers = listOf("activeAccountHeaderRenderer", "LOGIN_REQUIRED", "UNAUTHENTICATED", "signInEndpoint",
        "musicSignInRenderer", "\"loggedOut\":true", "accountSigninTokenUrl", "playlistVideoListRenderer", "musicShelfRenderer",
        "gridRenderer", "musicResponsiveListItemRenderer", "messageRenderer", "backgroundPromoRenderer")

    private fun scramble(value: String) = String(CharArray(value.length) { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"[(Math.random() * 62).toInt()] })

    private fun variant(cookies: List<Pair<String, String>>, which: (String) -> Boolean): String =
        cookies.joinToString("; ") { (k, v) -> if (which(k)) "$k=${scramble(v)}" else "$k=$v" }

    private suspend fun summarize(call: suspend () -> HttpResponse): String = try {
        val response = call()
        val text = response.bodyAsText()
        val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
        val error = json?.get("error") as? JsonObject
        val loggedOut = ((json?.get("responseContext") as? JsonObject)?.get("mainAppWebResponseContext") as? JsonObject)
            ?.get("loggedOut")?.let { (it as? JsonPrimitive)?.content }
        val found = markers.filter { text.contains(it) }
        "status=${response.status.value} errorCode=${(error?.get("code") as? JsonPrimitive)?.content} " +
            "errorStatus=${(error?.get("status") as? JsonPrimitive)?.content} loggedOut=$loggedOut " +
            "keys=${json?.keys?.sorted()?.joinToString("|")} markers=${found.joinToString("|")} bytes=${text.length}"
    } catch (e: Exception) {
        // Exception messages can carry URLs; keep the type and any HTTP status only.
        "exception=${e.javaClass.simpleName} status=${Regex("\\b[45]\\d\\d\\b").find(e.message.orEmpty())?.value} " +
            "message=${e.message.orEmpty().replace(Regex("https?://\\S+"), "<url>").take(100)}"
    }

    override fun onStart() {
        val result = Bundle()
        // Same setup as YouTubeMusicProvider: innertubex needs JSON content negotiation for its request bodies.
        val http = HttpClient(OriginalAudioEngine) {
            install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
            }
            install(HttpTimeout) { connectTimeoutMillis = 12_000; requestTimeoutMillis = 30_000 }
        }
        fun step(name: String) = android.util.Log.i("SessionProbe", name)
        try {
            step("cookies")
            // WebView's CookieManager works from a background thread once created; avoid blocking main.
            val raw: String? = CookieManager.getInstance().getCookie("https://music.youtube.com")
            step("cookies read: ${raw != null}")
            val cookies = raw.orEmpty().split(';').mapNotNull { part ->
                val t = part.trim(); val i = t.indexOf('='); if (i <= 0) null else t.substring(0, i) to t.substring(i + 1)
            }
            result.putString("cookie_names", cookies.map { it.first }.sorted().joinToString(","))
            val prefs = targetContext.getSharedPreferences("provider_session", 0)
            val visitor = prefs.getString("visitor", null); val sync = prefs.getString("sync", null)
            val account = prefs.getString("account", "0").orEmpty()
            check(cookies.any { it.first == "SAPISID" || it.first.endsWith("PAPISID") }) { "Phone is not signed in" }

            val variants = linkedMapOf(
                "real" to raw,
                "none" to null,
                "all_scrambled" to variant(cookies) { true },
                "session_scrambled" to variant(cookies) { it in sessionCookies },
            )
            for ((name, cookie) in variants) {
                val tube = InnerTube(http)
                tube.replaceSession(cookie = cookie, visitorData = visitor, dataSyncId = sync, authUser = account,
                    useLoginForBrowse = cookie != null)
                step("variant $name")
                fun record(key: String, value: String) { result.putString(key, value); android.util.Log.i("SessionProbe", "$key: $value") }
                runBlocking {
                    var t = System.currentTimeMillis()
                    record("${name}_account_menu", summarize { tube.accountMenu(YouTubeClient.WEB_REMIX) } + " ms=${System.currentTimeMillis() - t}")
                    t = System.currentTimeMillis()
                    record("${name}_liked", summarize { tube.browse(YouTubeClient.WEB_REMIX, browseId = "FEmusic_liked_videos", setLogin = cookie != null) } + " ms=${System.currentTimeMillis() - t}")
                    t = System.currentTimeMillis()
                    record("${name}_library", summarize { tube.browse(YouTubeClient.WEB_REMIX, browseId = "FEmusic_library_landing", setLogin = cookie != null) } + " ms=${System.currentTimeMillis() - t}")
                }
            }
            result.putString("result", "done")
            finish(-1, result)
        } catch (e: Throwable) {
            result.putString("result", "fail"); result.putString("error", "${e.javaClass.simpleName}: ${e.message}")
            finish(0, result)
        } finally {
            http.close()
        }
    }
}
