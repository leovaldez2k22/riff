package com.musicglass.personal

import androidx.core.net.toUri
import android.content.Intent

/**
 * Let YouTube consume Google's selected-account handoff before opening Music. With [email] (an account picked from
 * Riff's saved list) Google's page opens with that account already chosen.
 */
internal fun signInStartUrl(changingAccount: Boolean, email: String? = null): String {
    val handoff = "https://music.youtube.com/signin".toUri().buildUpon()
        .appendQueryParameter("action_handle_signin", "true")
        .appendQueryParameter("app", "desktop")
        .appendQueryParameter("next", "/").build()
    val page = if (email != null) "ServiceLogin" else if (changingAccount) "AccountChooser" else "ServiceLogin"
    return "https://accounts.google.com/$page".toUri().buildUpon().appendQueryParameter("service", "youtube")
        .apply { if (email != null) appendQueryParameter("Email", email).appendQueryParameter("login_hint", email) }
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

/**
 * Q18-013: Riff finishes by itself as soon as Google hands back to YouTube Music. When changing account, a cached
 * Google session must not complete it silently: only once the owner has been on Google's own page in this attempt
 * ([choseOnGoogle]) or picked the account in Riff's list. Otherwise Continue to Riff stays as the fallback.
 */
internal fun shouldVerifyFinishedSignIn(url: String, changingAccount: Boolean, choseOnGoogle: Boolean = false): Boolean {
    val uri = url.toUri()
    return (!changingAccount || choseOnGoogle) && uri.scheme == "https" && uri.host == "music.youtube.com" && uri.path in setOf("", "/")
}

/** True for Google's own account pages (any country host), where the owner chooses or signs in. */
internal fun googleAccountsPage(url: String): Boolean {
    val uri = url.toUri()
    return uri.scheme == "https" && (uri.host == "accounts.google.com" ||
        googleCountryDomains.any { uri.host == "accounts.$it" })
}

/** Q18-013: a link to somewhere else than Google's and YouTube's sign-in hosts opens outside Riff, https only. */
internal fun signInOutsideLink(url: String): Boolean = url.toUri().scheme == "https" && !allowedSignInUrl(url)

/** Loading deadlines apply to navigation, never to a user completing a loaded Google form. */
internal const val SIGN_IN_TIMEOUT_MS = 45_000L
internal const val SIGN_IN_VERIFY_MS = 20_000L

/** Q18-013: the agreed screens (docs/quality/sign-in-design.md); the stop sheet sits over [Google]. */
internal enum class SignInPhase { Manage, Welcome, AllAccounts, Google, NoLoad, Checking, Connected }

/** A generation rejects callbacks from a WebView or verification request that was abandoned. */
internal class SignInAttempt {
    var generation: Int = 0; private set
    fun restart(): Int = ++generation
    fun accepts(value: Int) = value == generation
}

/**
 * Q18-013 point 3: "Google's page didn't load" only when nothing loaded: the page's own request failed, or the
 * deadline passed before anything of this navigation was shown. A slow page that is still working gets no message.
 */
internal fun signInNothingLoaded(elapsedMs: Long, pageShown: Boolean, mainFrameFailed: Boolean): Boolean =
    mainFrameFailed || (!pageShown && elapsedMs >= SIGN_IN_TIMEOUT_MS)

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

/**
 * A one-way digest of the login cookies' values, kept to notice when the shared cookie store changes account
 * under the committed one (Q18-010). The cookies themselves are never stored by Riff.
 */
internal fun cookieIdentityDigest(cookies: String?): String? = signInCookieIdentity(cookies).takeIf { it.isNotEmpty() }
    ?.toSortedMap()?.entries?.joinToString(";") { "${it.key}=${it.value}" }
    ?.let { java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b.toInt() and 255) } }

/** Follow only an approved web destination from an Android app link; never execute the intent. */
internal fun signInWebFallback(url: String): String? {
    if (!url.startsWith("intent://")) return null
    val intent = runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return null
    val fallback = intent.getStringExtra("browser_fallback_url")
    if (fallback != null && allowedSignInUrl(fallback)) return fallback
    return intent.dataString?.takeIf(::allowedSignInUrl)
}
