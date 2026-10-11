package com.musicglass.personal

import com.metrolist.innertubex.sabr.SabrRecoveryAction
import com.metrolist.innertubex.sabr.sabrRecoveryAction

/**
 * The song whose attestation should be refreshed after [error], or null when the failure gets no
 * SABR recovery: not a SABR failure, a live item, not a YouTube item, or already refreshed once
 * for this track (innertubex then wants the profile excluded, which normal error handling covers).
 */
internal fun sabrAttestationRetryId(error: Throwable?, mediaId: String?, live: Boolean,
                                    alreadyRefreshed: Set<String>): String? {
    // Bounded walk: cause chains are short, and this can't loop on a cyclic chain.
    val failure = generateSequence(error) { it.cause }.take(16)
        .filterIsInstance<SabrPlaybackFailure>().firstOrNull() ?: return null
    if (live) return null
    val videoId = mediaId?.takeIf { it.startsWith("yt:") }?.removePrefix("yt:")?.takeIf { it.isNotEmpty() } ?: return null
    return videoId.takeIf {
        sabrRecoveryAction(failure.kind, it in alreadyRefreshed) == SabrRecoveryAction.REFRESH_ATTESTATION
    }
}

/**
 * Stress-build fault injection: the next [pending] song streams fail as if YouTube answered
 * ATTESTATION_REQUIRED, through the same error path as a real failure. Only src/stress sets it,
 * so it is always 0 in release and debug builds.
 */
internal object SabrFaultInjection {
    private var pending = 0

    @Synchronized fun failNextAttestations(count: Int) { check(BuildConfig.DEBUG) {"Fault injection is limited to test builds"}; pending = count.coerceIn(0, 10) }

    @Synchronized fun consume(): Boolean = (pending > 0).also { if (it) pending-- }
}
