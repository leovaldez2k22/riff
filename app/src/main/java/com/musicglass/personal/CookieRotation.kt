package com.musicglass.personal

import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess

/**
 * Cookies Google rotates during a session. The HTTP client sends cookies from a string built
 * from WebView's CookieManager, so rotated values in `Set-Cookie` were dropped and the saved
 * session aged faster (W5 finding). Only these names are written back: never the long-lived
 * sign-in cookies, and never a deletion, so a bad response can't sign the user out.
 */
internal val rotatingSessionCookies = setOf("__Secure-1PSIDTS", "__Secure-3PSIDTS", "SIDCC", "__Secure-1PSIDCC", "__Secure-3PSIDCC")

/** The `Set-Cookie` values worth persisting, unchanged, with their names. */
internal fun rotatedSessionCookies(setCookieHeaders: List<String>): List<Pair<String, String>> =
    setCookieHeaders.mapNotNull { header ->
        val pair = header.substringBefore(';').trim()
        val name = pair.substringBefore('=', "").trim()
        val value = pair.substringAfter('=', "").trim()
        val attributes = header.split(';').drop(1).map { it.trim().lowercase() }
        val deletes = attributes.any { it.startsWith("max-age=") && (it.removePrefix("max-age=").toLongOrNull() ?: 0) <= 0 } ||
            attributes.any { it.startsWith("expires=") && it.contains("1970") }
        if (name in rotatingSessionCookies && value.isNotEmpty() && !deletes) name to header.trim() else null
    }

internal fun isYouTubeHost(host: String): Boolean = host == "youtube.com" || host.endsWith(".youtube.com")

/**
 * Hands rotated session cookies from successful YouTube responses to [persist]. [enabled] is
 * checked per response, so a signed-out or simulated-expiry session writes nothing.
 */
internal fun cookieRotationPlugin(enabled: () -> Boolean, persist: (host: String, cookies: List<Pair<String, String>>) -> Unit) =
    createClientPlugin("RiffCookieRotation") {
        onResponse { response ->
            if (!response.status.isSuccess() || !enabled()) return@onResponse
            val host = response.call.request.url.host
            if (!isYouTubeHost(host)) return@onResponse
            val rotated = rotatedSessionCookies(response.headers.getAll(HttpHeaders.SetCookie).orEmpty())
            if (rotated.isNotEmpty()) runCatching { persist(host, rotated) }
        }
    }
