package com.musicglass.desktop

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.test.assertIs

internal object UpdatePolicyFixture {
    const val NOW = 1793578000L
    val scope = UpdateScope("com.musicglass.desktop","preview","windows-x64")
    fun version(value: String) = UpdateVersion.parse(value)!!
    fun config(installed: String = "0.1.0", bootstrap: UpdateBootstrap = UpdateBootstrap(1,"0.1.0"),
        workers: Set<Int> = setOf(1), migrations: Set<Pair<Int,Int>> = setOf(1 to 1),
        hosts: Set<String> = setOf("releases.example.invalid"), windowsBuild: Int = 26100,
        scope: UpdateScope = this.scope, isWindows: Boolean = true) =
        UpdateLocalConfiguration(scope,installed,isWindows,windowsBuild,workers,1,migrations,hosts,bootstrap)
    fun manifest(sequence: Long = 2, version: String = "0.2.0", notes: String = "Fixture notes",
        published: Long = 1793577600, expires: Long = 1794182400, transform: (String)->String = {it}): ValidatedUpdateManifest {
        val json = SignedUpdatePayloadFixture.validJson(notes).replace("\"releaseSequence\":2","\"releaseSequence\":$sequence")
            .replace("\"version\":\"0.2.0\"","\"version\":\"$version\"")
            .replace("1793577600",published.toString()).replace("1794182400",expires.toString())
        return assertIs<UpdatePayloadParseResult.Valid>(SignedUpdatePayloadFixture.parse(transform(json))).manifest
    }
    fun clock(time: Long = NOW) = Clock.fixed(Instant.ofEpochSecond(time),ZoneOffset.UTC)
    fun record(sequence: Long, version: String, digest: String? = "a".repeat(64), highest: String = version,
        time: Long = NOW) = UpdateFreshnessRecord(sequence,version(version),digest,version(highest),time)
}
internal class MemoryUpdateFreshnessStore(var record: UpdateFreshnessRecord? = null) : UpdateFreshnessStore {
    private val mutex = Mutex()
    var commits = 0
    var failCommit = false
    override suspend fun <T> transaction(scope: UpdateScope,
        action: suspend (UpdateFreshnessRecord?, (UpdateFreshnessRecord)->Unit)->T): T = mutex.withLock {
        action(record) { next -> if(failCommit) throw FreshnessStateUnavailable(); record=next; commits++ }
    }
}