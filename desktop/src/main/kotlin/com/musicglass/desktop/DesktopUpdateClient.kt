package com.musicglass.desktop

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

internal enum class DesktopUpdateError {
    CONFIGURATION_ERROR, SIZE_LIMIT, INVALID_ENVELOPE, UNSUPPORTED_FORMAT, UNKNOWN_KEY, INVALID_SIGNATURE,
    INVALID_PAYLOAD, WRONG_TARGET, INCOMPATIBLE, NOT_YET_VALID, EXPIRED, CLOCK_INVALID,
    STALE_RELEASE, RELEASE_CONFLICT, ROLLBACK_REJECTED, STATE_UNAVAILABLE
}
internal sealed interface UpdateCheckResult {
    data object Disabled : UpdateCheckResult { val code = DesktopUpdateError.CONFIGURATION_ERROR }
    data class Rejected(val code: DesktopUpdateError) : UpdateCheckResult
    data class AuthenticatedIneligible(val reason: DesktopUpdateError) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data class Eligible(val offer: EligibleUpdate) : UpdateCheckResult
}

/** Offline metadata coordinator. No feed, artifact access or installation capability. Defaults disabled. */
internal class DesktopUpdateClient(
    private val registry: UpdateTrustRegistry? = null,
    private val configuration: UpdateLocalConfiguration? = null,
    private val store: UpdateFreshnessStore? = null,
    private val clock: Clock = Clock.systemUTC()
) {
    private val decisions = Mutex()
    // At most one bounded envelope retained. Offer identity is reference identity, plus policy's scope/sequence/digest.
    private var retained: Pair<EligibleUpdate,ByteArray>? = null

    suspend fun check(envelope: ByteArray): UpdateCheckResult = withContext(Dispatchers.IO) {
        decisions.withLock {
            if (!configured()) return@withLock UpdateCheckResult.Disabled
            if (envelope.size > VerifiedUpdatePayload.MAX_ENVELOPE_BYTES) return@withLock rejected(DesktopUpdateError.SIZE_LIMIT)
            evaluate(envelope.copyOf())
        }
    }

    /** Bounded caller-owned stream; transport encodings/timeouts belong to a future collector. Does not close it. */
    suspend fun check(envelope: InputStream): UpdateCheckResult = withContext(Dispatchers.IO) {
        decisions.withLock {
            if (!configured()) return@withLock UpdateCheckResult.Disabled
            val bytes = try {
                val output=ByteArrayOutputStream()
                val buffer=ByteArray(4096)
                while(true) {
                    coroutineContext.ensureActive()
                    val read=envelope.read(buffer,0,minOf(buffer.size,VerifiedUpdatePayload.MAX_ENVELOPE_BYTES+1-output.size()))
                    if(read < 0) break
                    if(read == 0) { val one=envelope.read();if(one < 0) break;output.write(one) }
                    else output.write(buffer,0,read)
                    if(output.size() > VerifiedUpdatePayload.MAX_ENVELOPE_BYTES) return@withLock rejected(DesktopUpdateError.SIZE_LIMIT)
                }
                output.toByteArray()
            } catch(error: CancellationException) { throw error }
              catch(_: IOException) { coroutineContext.ensureActive();return@withLock rejected(DesktopUpdateError.INVALID_ENVELOPE) }
            evaluate(bytes)
        }
    }

    /** Only this client's retained offer can be rechecked; reverify against current local trust before policy. */
    suspend fun revalidate(offer: EligibleUpdate): UpdateCheckResult = withContext(Dispatchers.IO) {
        decisions.withLock {
            if (!configured()) return@withLock UpdateCheckResult.Disabled
            val source=retained
            if(source == null || source.first !== offer) return@withLock rejected(DesktopUpdateError.STALE_RELEASE)
            evaluate(source.second)
        }
    }

    private fun configured(): Boolean {
        val version=DesktopBuildIdentity.version() ?: return false
        return registry?.configured == true && configuration != null && store != null &&
            UpdateVersion.parse(configuration.installedVersion) == version
    }
    private suspend fun evaluate(bytes: ByteArray): UpdateCheckResult {
        coroutineContext.ensureActive()
        val verified=when(val result=VerifiedUpdatePayload.verify(bytes,registry!!)) {
            is UpdateVerificationResult.Rejected -> return rejected(DesktopUpdateError.valueOf(result.code.name))
            is UpdateVerificationResult.Verified -> result.payload
        }
        coroutineContext.ensureActive()
        val manifest=when(val result=ValidatedUpdateManifest.parse(verified)) {
            is UpdatePayloadParseResult.Rejected -> return rejected(DesktopUpdateError.valueOf(result.code.name))
            is UpdatePayloadParseResult.Valid -> result.manifest
        }
        val result=UpdateEligibilityPolicy.evaluate(manifest,configuration!!,store!!,clock)
        coroutineContext.ensureActive()
        return when(result) {
            is EligibleUpdate -> {
                retained=result to bytes.copyOf()
                UpdateCheckResult.Eligible(result)
            }
            UpdatePolicyResult.UpToDate -> { retained=null;UpdateCheckResult.UpToDate }
            is UpdatePolicyResult.Rejected -> rejected(DesktopUpdateError.valueOf(result.code.name))
            is UpdatePolicyResult.AuthenticatedIneligible -> UpdateCheckResult.AuthenticatedIneligible(DesktopUpdateError.valueOf(result.reason.name))
        }
    }
    private fun rejected(code: DesktopUpdateError) = UpdateCheckResult.Rejected(code)
}