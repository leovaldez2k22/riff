package com.musicglass.desktop

import java.net.URI
import java.time.Clock
import java.util.Collections
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

internal const val UPDATE_MAX_SEQUENCE = 9007199254740991L
internal const val UPDATE_MAX_TIME = 253402300799L
internal data class UpdateScope(val appId: String, val channel: String, val target: String)
internal data class UpdateBootstrap(val sequence: Long, val version: String, val digest: String? = null)

/** Explicit local deployment configuration; no defaults are inferred from remote metadata. */
internal class UpdateLocalConfiguration(
    val scope: UpdateScope,
    val installedVersion: String,
    val isWindows: Boolean,
    val windowsBuild: Int,
    workerProtocols: Set<Int>,
    val installedDatabaseSchema: Int,
    migrationPaths: Set<Pair<Int, Int>>,
    artifactHosts: Set<String>,
    val bootstrap: UpdateBootstrap
) {
    val workerProtocols: Set<Int> = Collections.unmodifiableSet(workerProtocols.toSet())
    val migrationPaths: Set<Pair<Int, Int>> = Collections.unmodifiableSet(migrationPaths.toSet())
    val artifactHosts: Set<String> = Collections.unmodifiableSet(artifactHosts.toSet())
}

internal enum class UpdatePolicyError {
    CONFIGURATION_ERROR, WRONG_TARGET, INCOMPATIBLE, NOT_YET_VALID, EXPIRED, CLOCK_INVALID,
    STALE_RELEASE, RELEASE_CONFLICT, ROLLBACK_REJECTED, STATE_UNAVAILABLE
}
internal sealed interface UpdatePolicyResult {
    data class Rejected(val code: UpdatePolicyError) : UpdatePolicyResult
    data class AuthenticatedIneligible(val reason: UpdatePolicyError) : UpdatePolicyResult
    data object UpToDate : UpdatePolicyResult
}

/** An offer is a point-in-time metadata decision, never authority to install indefinitely. */
internal class EligibleUpdate private constructor(
    val manifest: ValidatedUpdateManifest, val scope: UpdateScope, val evaluatedAt: Long
) : UpdatePolicyResult {
    companion object {
        // The sole factory performs the complete policy transaction; there is no unchecked copy/factory.
        suspend fun evaluate(manifest: ValidatedUpdateManifest, config: UpdateLocalConfiguration,
            store: UpdateFreshnessStore, clock: Clock): UpdatePolicyResult {
            val installed = UpdateVersion.parse(config.installedVersion)
            val bootstrapVersion = UpdateVersion.parse(config.bootstrap.version)
            if (installed == null || bootstrapVersion == null || !validConfiguration(config))
                return UpdatePolicyResult.Rejected(UpdatePolicyError.CONFIGURATION_ERROR)
            val scope = config.scope
            if (manifest.appId != scope.appId || manifest.channel != scope.channel || manifest.target != scope.target ||
                !manifest.signer.active || scope.appId !in manifest.signer.appIds || scope.channel !in manifest.signer.channels ||
                scope.target !in manifest.signer.targets || config.artifactHosts.none { it.equals(URI(manifest.artifact.url).host, true) })
                return UpdatePolicyResult.AuthenticatedIneligible(UpdatePolicyError.WRONG_TARGET)
            return try {
                store.transaction(scope) { persisted, commit ->
                    val normalized = reconcile(config.bootstrap, bootstrapVersion, installed, persisted)
                    // Capture once under the decision lock, after state reconciliation.
                    val now = clock.instant().epochSecond
                    fun denied(code: UpdatePolicyError) = UpdatePolicyResult.AuthenticatedIneligible(code)
                    if (now !in 0..UPDATE_MAX_TIME || now < normalized.lastEvaluationTime - 300)
                        return@transaction denied(UpdatePolicyError.CLOCK_INVALID)
                    val effectiveNow = maxOf(now, normalized.lastEvaluationTime)
                    if (manifest.publishedAt > effectiveNow + 300) return@transaction denied(UpdatePolicyError.NOT_YET_VALID)
                    if (manifest.expiresAt <= effectiveNow) return@transaction denied(UpdatePolicyError.EXPIRED)
                    if (manifest.releaseSequence < normalized.sequence) return@transaction denied(UpdatePolicyError.STALE_RELEASE)
                    if (manifest.releaseSequence == normalized.sequence) {
                        if (normalized.digest != null && normalized.digest != manifest.payloadSha256)
                            return@transaction denied(UpdatePolicyError.RELEASE_CONFLICT)
                        if (manifest.version != normalized.sequenceVersion) {
                            val code = if (normalized.digest == null && manifest.version < normalized.sequenceVersion)
                                UpdatePolicyError.ROLLBACK_REJECTED else UpdatePolicyError.RELEASE_CONFLICT
                            return@transaction denied(code)
                        }
                    }
                    if (manifest.version < normalized.highestVersion) return@transaction denied(UpdatePolicyError.ROLLBACK_REJECTED)
                    val accepted = UpdateFreshnessRecord(manifest.releaseSequence, manifest.version, manifest.payloadSha256,
                        maxOf(manifest.version, normalized.highestVersion), effectiveNow)
                    coroutineContext.ensureActive()
                    // Normalization and acceptance are one durable commit. Rejected inputs never write normalization.
                    commit(accepted)
                    if (installed < manifest.minInstalledVersion || !config.isWindows || config.windowsBuild < manifest.minWindowsBuild ||
                        manifest.workerProtocolMajor !in config.workerProtocols ||
                        (config.installedDatabaseSchema to manifest.databaseSchemaVersion) !in config.migrationPaths)
                        return@transaction denied(UpdatePolicyError.INCOMPATIBLE)
                    if (manifest.version == installed) UpdatePolicyResult.UpToDate else EligibleUpdate(manifest,scope,effectiveNow)
                }
            } catch (_: FreshnessStateUnavailable) {
                UpdatePolicyResult.Rejected(UpdatePolicyError.STATE_UNAVAILABLE)
            }
        }
    }
}

internal object UpdateEligibilityPolicy {
    suspend fun evaluate(manifest: ValidatedUpdateManifest, config: UpdateLocalConfiguration,
        store: UpdateFreshnessStore, clock: Clock): UpdatePolicyResult = EligibleUpdate.evaluate(manifest,config,store,clock)

    /** Module 5 must re-evaluate immediately before activation and serialize activation with later checks. */
    suspend fun revalidate(offer: EligibleUpdate, config: UpdateLocalConfiguration,
        store: UpdateFreshnessStore, clock: Clock): UpdatePolicyResult = evaluate(offer.manifest,config,store,clock)
}

private fun validConfiguration(c: UpdateLocalConfiguration): Boolean {
    if (c.scope.appId != "com.musicglass.desktop" || c.scope.channel !in setOf("preview","stable") ||
        c.scope.target != "windows-x64" || c.windowsBuild < 0 || c.installedDatabaseSchema < 1 ||
        c.bootstrap.sequence !in 1..UPDATE_MAX_SEQUENCE || (c.bootstrap.digest != null && !validUpdateDigest(c.bootstrap.digest))) return false
    if (c.workerProtocols.isEmpty() || c.workerProtocols.any { it !in 1..65535 } || c.migrationPaths.isEmpty() ||
        c.migrationPaths.any { it.first < 1 || it.second < 1 } || c.artifactHosts.isEmpty() || c.artifactHosts.size > 32) return false
    val label = "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
    return c.artifactHosts.all { host -> host.length <= 253 && Regex("$label(?:\\.$label)+").matches(host) &&
        !host.substringAfterLast('.').all { it in '0'..'9' } }
}

internal fun validUpdateDigest(value: String) = Regex("[0-9a-f]{64}").matches(value)

private fun reconcile(bootstrap: UpdateBootstrap, version: UpdateVersion, installed: UpdateVersion,
    persisted: UpdateFreshnessRecord?): UpdateFreshnessRecord {
    if (persisted != null) persisted.validate()
    val base = UpdateFreshnessRecord(bootstrap.sequence,version,bootstrap.digest,version,0)
    val binding = when {
        persisted == null || bootstrap.sequence > persisted.sequence -> base
        bootstrap.sequence < persisted.sequence -> persisted
        else -> {
            if (version != persisted.sequenceVersion || (bootstrap.digest != null && persisted.digest != null && bootstrap.digest != persisted.digest))
                throw FreshnessStateUnavailable()
            base.copy(digest = bootstrap.digest ?: persisted.digest)
        }
    }
    return binding.copy(highestVersion = maxOf(installed,version,persisted?.highestVersion ?: version),
        lastEvaluationTime = persisted?.lastEvaluationTime ?: 0)
}