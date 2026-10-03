package com.musicglass.personal

/**
 * Playback that stopped because the network went away resumes by itself once it is back
 * (found 2026-10-04: a Wi-Fi drop left background playback stopped for 55 minutes until Play
 * was tapped). Only network failures qualify, only while the user still wants sound
 * (playWhenReady), and only a few times per failure, so a dead stream can't loop.
 */
internal object NetworkRecovery {
    const val MAX_ATTEMPTS = 5
    /** Delays after the error; a network becoming available also triggers an attempt. */
    val retryDelaysMs = listOf(5_000L, 20_000L, 60_000L)

    private val networkCauses = listOf("UnknownHostException", "ConnectException", "SocketTimeoutException",
        "NoRouteToHostException", "SocketException", "SSLException", "HttpRequestTimeoutException", "EOFException")

    /** A network failure, from the error code or anywhere in the cause chain (SABR wraps its cause in the message). */
    fun isNetworkFailure(errorCodeName: String, causes: List<Throwable>): Boolean =
        errorCodeName == "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED" || errorCodeName == "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT" ||
            causes.any { cause -> networkCauses.any { name -> cause.javaClass.simpleName == name || cause.message.orEmpty().contains(name) } ||
                cause.message == OFFLINE_MESSAGE || cause.message == TIMEOUT_MESSAGE ||
                // innertubex's StreamResolveException.Reason.NETWORK, as YouTubeMusicProvider.resolve reports it.
                cause.message == "Track unavailable: NETWORK" }

    fun shouldRetry(networkFailure: Boolean, playWhenReady: Boolean, hasError: Boolean, attempts: Int): Boolean =
        networkFailure && playWhenReady && hasError && attempts < MAX_ATTEMPTS
}
