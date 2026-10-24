package com.musicglass.personal

import androidx.core.net.toUri
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
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
/** Q18-087: how long a direct switch may take to reach its check before Google's page is shown instead. */
internal const val SIGN_IN_DIRECT_MS = 15_000L

/** Q18-087: a saved account Riff can switch to without Google's page: signed in now, its scope and position known. */
internal fun directSwitchAccount(account: SavedAccount?, signedIn: Boolean): SavedAccount? =
    account?.takeIf { signedIn && it.scope != null && it.index != null }

/** Q18-087: a direct switch commits only when the account it reached is the one picked. */
internal fun directSwitchLanded(expectedScope: String?, reached: AccountScope): Boolean =
    expectedScope != null && (reached as? AccountScope.Provider)?.opaqueId == expectedScope

/**
 * Q18-110: an account picked from Riff's list is committed only when the check reached that account. Google continues
 * a sign-in whose account is already in the web session as the session's default, so a pick that fell back from the
 * direct switch used to end silently on the account in use. Judged by the picked account's scope digest; by its
 * address when no scope was saved and the page gave one (YouTube does not send the owner's, which never blocks);
 * nothing picked, nothing to match. Not applied once Google's own account page was drawn: that is the owner choosing.
 */
internal fun pickedAccountReached(pickedScope: String?, pickedEmail: String?, reached: AccountScope, reachedEmail: String?): Boolean = when {
    pickedScope != null -> (reached as? AccountScope.Provider)?.opaqueId == pickedScope
    pickedEmail != null && reachedEmail != null -> reachedEmail.equals(pickedEmail, ignoreCase = true)
    else -> true
}

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

/**
 * Follow only an approved web destination from an Android app link; never execute the intent. An app store page
 * is never a sign-in step (Q18-058): YouTube Music's handoff offers its app with the Play Store as the fallback, and
 * following it left the sign-in on the store page with no way on, so the link's own web address is taken instead.
 */
internal fun signInWebFallback(url: String): String? {
    if (!url.startsWith("intent://")) return null
    val intent = runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return null
    val fallback = intent.getStringExtra("browser_fallback_url")
    if (fallback != null && allowedSignInUrl(fallback) && !signInStorePage(fallback)) return fallback
    return intent.dataString?.takeIf { allowedSignInUrl(it) && !signInStorePage(it) }
}

/** Q18-058: an app store page (Google Play, or its market: links), where Google sends some sign-ins to offer an app. */
internal fun signInStorePage(url: String): Boolean {
    val uri = url.toUri()
    return uri.scheme == "market" || (uri.scheme == "https" && uri.host == "play.google.com")
}

/** Google's list of the accounts in this web session: the list Chrome reads for its account chooser. */
internal const val GOOGLE_SESSION_ACCOUNTS_URL = "https://accounts.google.com/ListAccounts?gpsia=1&source=ChromiumBrowser&json=standard"
internal const val GOOGLE_ACCOUNTS_ORIGIN = "https://accounts.google.com"

/**
 * Q18-100: Google answers this list only to a POST, as Chrome asks it (a one-space form body); a GET is refused with
 * HTTP 400, which is what rc8 got on the phone. The cookies go to Google's account host only.
 */
internal suspend fun requestGoogleSessionAccounts(http: HttpClient, cookies: String): HttpResponse =
    http.post(GOOGLE_SESSION_ACCOUNTS_URL) {
        header("Cookie", cookies)
        setBody(TextContent(" ", ContentType.Application.FormUrlEncoded))
    }

/** One account of Google's list. [stableId] is only turned into a scope digest and dropped; it is never kept or logged. */
internal data class GoogleSessionEntry(val email: String?, val index: String, val stableId: String?, val signedIn: Boolean)

/**
 * Q18-085, Q18-087, Q18-088: reads Google's account list, ["gaia.l.a.r", [["gaia.l.a", 1, name, email, photo, …,
 * valid at 9, id at 10, …, signed out at 14], …]]. Each signed-in account's position is its authuser index. Anything
 * unexpected gives an empty list, and Riff carries on with Google's own page.
 */
/**
 * Q18-100: what Google's answer looked like, for the diagnostic log: how many rows it held, how many gave an address,
 * how many are signed in, and a reason when none could be read. Never an address, id or any of the body's text.
 */
internal fun googleSessionAccountsShape(body: String, entries: List<GoogleSessionEntry>): String {
    val root = runCatching { org.json.JSONArray(body.trim().removePrefix(")]}'").trim()) }.getOrNull()
    val rows = root?.optJSONArray(1)
    val reason = when {
        body.isBlank() -> "empty"
        root == null -> "not_json_array"
        rows == null -> "no_rows"
        rows.length() > 0 && entries.none { it.email != null } -> "no_addresses"
        else -> "read"
    }
    return "rows=${rows?.length() ?: 0} parsed=${entries.count { it.email != null }} found=${entries.count { it.signedIn }} reason=$reason"
}

internal fun parseGoogleSessionAccounts(body: String): List<GoogleSessionEntry> = try {
    val root = org.json.JSONArray(body.trim().removePrefix(")]}'").trim())
    val rows = root.optJSONArray(1) ?: return emptyList()
    var index = 0
    (0 until rows.length()).mapNotNull { at ->
        val row = rows.optJSONArray(at) ?: return@mapNotNull null
        fun field(i: Int): Any? = if (i < row.length()) row.opt(i) else null
        fun on(value: Any?) = value == true || value == 1
        val email = (field(3) as? String)?.trim()
            ?.takeIf { it.length in 3..254 && it.count { c -> c == '@' } == 1 && ' ' !in it }
        val id = (field(10) as? String)?.takeIf { it.isNotBlank() && it.all(Char::isLetterOrDigit) }
        val signedIn = !on(field(14)) && (field(9) == null || on(field(9)))
        // Signed-out accounts are listed after the signed-in ones and have no position.
        if (signedIn) GoogleSessionEntry(email, (index++).toString(), id, true) else GoogleSessionEntry(email, "", id, false)
    }
} catch (_: org.json.JSONException) { emptyList() }
