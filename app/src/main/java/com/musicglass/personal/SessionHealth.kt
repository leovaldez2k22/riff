package com.musicglass.personal

import com.metrolist.innertubex.InnerTubeHttpException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Whether YouTube Music still accepts the saved sign-in (plan W5). Cookies existing on the phone
 * only means the app *was* signed in; Google can expire or revoke the session at any time.
 *
 * Unverified means "couldn't tell" (offline, server error, bot check) and never shows the
 * expired banner: a false "session expired" is worse than a late one.
 */
internal enum class SessionState { SignedOut, Checking, Valid, Expired, Unverified }

/**
 * Classifies an account_menu response. Shapes captured on device on 2026-10-03 (W5.1,
 * SessionProbeInstrumentation): a valid session answers 200 with an activeAccountHeaderRenderer;
 * missing, scrambled or revoked cookies answer 401 with error.status UNAUTHENTICATED.
 * Google's responseContext loggedOut flag never appeared, so it isn't used.
 */
internal fun classifySessionProbe(status: Int?, body: JsonObject?, bodyText: String?, networkError: Boolean): SessionState {
    if (networkError || status == null) return SessionState.Unverified
    val errorStatus = ((body?.get("error") as? JsonObject)?.get("status") as? JsonPrimitive)?.content
    if (status == 401 || errorStatus == "UNAUTHENTICATED") return SessionState.Expired
    if (status in 200..299 && bodyText?.contains("activeAccountHeaderRenderer") == true) return SessionState.Valid
    return SessionState.Unverified // 403 bot checks, 5xx, or a 200 without an account header
}

/** An authenticated request was refused with 401: the session is very likely expired. */
internal fun isAuthFailure(error: Throwable?): Boolean = generateSequence(error) { it.cause }.take(16)
    .any { it is InnerTubeHttpException && it.status.value == 401 }

/**
 * When to ask Google again: at most one probe every [minIntervalMs] on resume, but at once (after
 * [forcedGapMs]) when a request just failed with 401 or the account changed.
 */
internal class SessionCheckSchedule(private val clock: () -> Long, private val minIntervalMs: Long = 6 * 60 * 60_000L,
                                    private val forcedGapMs: Long = 30_000L) {
    private var lastCheck = Long.MIN_VALUE / 2

    @Synchronized fun due(forced: Boolean): Boolean {
        val gap = clock() - lastCheck
        return if (forced) gap >= forcedGapMs else gap >= minIntervalMs
    }

    /** How long until a check of this kind is due (0 when due now). */
    @Synchronized fun waitMs(forced: Boolean): Long =
        ((if (forced) forcedGapMs else minIntervalMs) - (clock() - lastCheck)).coerceAtLeast(0)

    @Synchronized fun checked() { lastCheck = clock() }

    @Synchronized fun reset() { lastCheck = Long.MIN_VALUE / 2 }
}

internal const val SESSION_EXPIRED_MESSAGE = "Your YouTube Music session has expired. Sign in again from Account."
