package com.musicglass.personal

import androidx.core.net.toUri
import android.content.Intent

/** Let YouTube consume Google's selected-account handoff before opening Music. */
internal fun signInStartUrl(changingAccount: Boolean): String {
    val handoff = "https://music.youtube.com/signin".toUri().buildUpon()
        .appendQueryParameter("action_handle_signin", "true")
        .appendQueryParameter("app", "desktop")
        .appendQueryParameter("next", "/").build()
    return "https://accounts.google.com/${if (changingAccount) "AccountChooser" else "ServiceLogin"}"
        .toUri().buildUpon().appendQueryParameter("service", "youtube")
        .appendQueryParameter("continue", handoff.toString()).build().toString()
}

/** Only YouTube's handoff can supply the selected index; unrelated URLs cannot change it. */
internal fun signInSelectedAccount(url: String): String? {
    val uri = url.toUri()
    if (uri.scheme != "https" || uri.host !in setOf("music.youtube.com", "www.youtube.com")) return null
    return uri.getQueryParameter("authuser")?.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }
}

internal fun signInMusicUrl(accountIndex: String?): String = "https://music.youtube.com/".toUri()
    .buildUpon().apply { if (accountIndex != null) appendQueryParameter("authuser", accountIndex) }
    .build().toString()

internal fun signInAccountMatches(selectedIndex: String?, pageIndex: String?): Boolean =
    pageIndex != null && pageIndex.isNotEmpty() && pageIndex.all { it in '0'..'9' } &&
        (selectedIndex == null || selectedIndex == pageIndex)

/** A cached Google session must not silently complete the explicit change-account flow. */
internal fun shouldVerifyFinishedSignIn(url: String, changingAccount: Boolean): Boolean {
    val uri = url.toUri()
    return !changingAccount && uri.scheme == "https" && uri.host == "music.youtube.com" && uri.path in setOf("", "/")
}

/** Loading deadlines apply to navigation, never to a user completing a loaded Google form. */
internal const val SIGN_IN_SLOW_MS = 20_000L
internal const val SIGN_IN_TIMEOUT_MS = 45_000L
internal const val SIGN_IN_VERIFY_MS = 20_000L

internal enum class SignInStage { Ready, Loading, Waiting, Slow, Failed, Verifying, Connected }

/** A generation rejects callbacks from a WebView or verification request that was abandoned. */
internal class SignInAttempt {
    var generation: Int = 0; private set
    fun restart(): Int = ++generation
    fun accepts(value: Int) = value == generation
}

internal fun signInLoadingStage(elapsedMs: Long): SignInStage = when {
    elapsedMs >= SIGN_IN_TIMEOUT_MS -> SignInStage.Failed
    elapsedMs >= SIGN_IN_SLOW_MS -> SignInStage.Slow
    else -> SignInStage.Loading
}

internal data class WebSignInCandidate(val cookies: String, val visitor: String?, val syncId: String?,
    val accountIndex: String, val revision: Int)
internal data class VerifiedSignIn(val candidate: WebSignInCandidate, val profile: ProviderProfile?)

internal fun signInSyncId(value: String?): String? = value?.substringBefore("||")?.trim()
    ?.takeUnless { it.isBlank() || it == "null" }

internal fun signInCookieIdentity(cookies: String?): Map<String, String> = cookies.orEmpty().split(';')
    .mapNotNull { part ->
        val name = part.trim().substringBefore('=')
        if ('=' in part && name in setOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID"))
            name to part.trim().substringAfter('=') else null
    }.toMap()

/** Follow only an approved web destination from an Android app link; never execute the intent. */
internal fun signInWebFallback(url: String): String? {
    if (!url.startsWith("intent://")) return null
    val intent = runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return null
    val fallback = intent.getStringExtra("browser_fallback_url")
    if (fallback != null && allowedSignInUrl(fallback)) return fallback
    return intent.dataString?.takeIf(::allowedSignInUrl)
}
