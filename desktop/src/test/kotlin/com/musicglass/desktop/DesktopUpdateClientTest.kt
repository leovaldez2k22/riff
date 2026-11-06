package com.musicglass.desktop

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class DesktopUpdateClientTest {
    @get:Rule val temporary=TemporaryFolder()
    private val f=UpdatePolicyFixture
    private fun registry()=UpdateTrustRegistry(listOf(UpdateTrustKey("test-rfc8032-01",
        javaClass.getResourceAsStream("/update-v1/public-key.der")!!.use { it.readBytes() },
        setOf(f.scope.appId),setOf("preview"),setOf("windows-x64"))),allowTestKeys=true)
    private fun bytes(sequence: Long=2, version: String="0.2.0", notes: String="Fixture notes", transform:(String)->String={it}) =
        SignedUpdatePayloadFixture.envelope(transform(SignedUpdatePayloadFixture.validJson(notes)
            .replace("\"releaseSequence\":2","\"releaseSequence\":$sequence")
            .replace("\"version\":\"0.2.0\"","\"version\":\"$version\"")).toByteArray())
    private fun client(store: UpdateFreshnessStore=MemoryUpdateFreshnessStore(), config: UpdateLocalConfiguration=f.config(), clock: Clock=f.clock()) =
        DesktopUpdateClient(registry(),config,store,clock)
    private fun badSignature(input: ByteArray): ByteArray = input.toString(Charsets.UTF_8).replace(
        Regex("(\"signatureBase64\":\")([A-Za-z0-9])")) { it.groupValues[1]+if(it.groupValues[2]=="A") "B" else "A" }.toByteArray()
    private class SpyStore : UpdateFreshnessStore {
        val memory=MemoryUpdateFreshnessStore()
        var transactions=0
        override suspend fun <T> transaction(scope: UpdateScope, action: suspend (UpdateFreshnessRecord?,(UpdateFreshnessRecord)->Unit)->T): T {
            transactions++;return memory.transaction(scope,action)
        }
    }
    @Test fun signedCompatibleFreshMetadataCommitsBeforeEligible() = runBlocking<Unit> {
        val store=SpyStore();val result=assertIs<UpdateCheckResult.Eligible>(client(store).check(bytes()))
        assertEquals(result.offer.manifest.payloadSha256,store.memory.record!!.digest)
        assertEquals(2L,store.memory.record!!.sequence);assertEquals(f.scope,result.offer.scope)
        assertEquals(1,store.memory.commits)
    }
    @Test fun equalPackagedVersionIsUpToDateOnlyAfterCommit() = runBlocking<Unit> {
        val store=SpyStore();assertEquals(UpdateCheckResult.UpToDate,client(store).check(bytes(version="0.1.0")))
        assertEquals(1,store.memory.commits)
    }
    @Test fun defaultsAndMissingDeploymentAreDisabledWithoutReadingInput() = runBlocking<Unit> {
        val input=object: InputStream() { override fun read():Int=error("Disabled updater must not read caller input") }
        assertEquals(UpdateCheckResult.Disabled,DesktopUpdateClient().check(input))
        for(c in listOf(DesktopUpdateClient(registry(),f.config()),DesktopUpdateClient(configuration=f.config(),store=SpyStore()),
            DesktopUpdateClient(registry=UpdateTrustRegistry(emptyList()),configuration=f.config(),store=SpyStore())))
            assertEquals(UpdateCheckResult.Disabled,c.check(input))
    }
    @Test fun installedVersionMustMatchGeneratedNativePackageIdentity() = runBlocking<Unit> {
        assertEquals(System.getProperty("riff.test.desktopVersion"),DesktopBuildIdentity.version().toString())
        for(version in listOf("0.20.0","bad","")) {
            val store=SpyStore();assertEquals(UpdateCheckResult.Disabled,client(store,f.config(installed=version)).check(bytes()));assertEquals(0,store.transactions)
        }
    }
    @Test fun missingMalformedOrOversizedIdentityCannotBecomeVersionZero() {
        for(text in listOf("","version=bad\n","version=v0.1.0\n","version=0.1.0", "version=0.1.0\nextra=x\n","x".repeat(65)))
            assertNull(DesktopBuildIdentity.read(ByteArrayInputStream(text.toByteArray())))
        assertEquals(f.version("0.1.0"),DesktopBuildIdentity.read(ByteArrayInputStream("version=0.1.0\n".toByteArray())))
    }
    @Test fun unsignedLegacyAndEnvelopeSuppliedPublicKeysAreRejected() = runBlocking<Unit> {
        val store=SpyStore();val c=client(store)
        val legacy="""{"version":"9.0.0","msiUrl":"https://example.invalid/a.msi","sha256Hex":"short","mandatory":"true"}"""
        assertEquals(UpdateCheckResult.Rejected(DesktopUpdateError.INVALID_ENVELOPE),c.check(legacy.toByteArray()))
        val extra=bytes().toString(Charsets.UTF_8).replaceFirst("{","{\"publicKey\":\"remote\",")
        assertEquals(UpdateCheckResult.Rejected(DesktopUpdateError.INVALID_ENVELOPE),c.check(extra.toByteArray()))
        assertEquals(0,store.transactions)
    }
    @Test fun invalidSignatureOnMalformedPayloadNeverReachesParserOrStore() = runBlocking<Unit> {
        val store=SpyStore();val c=client(store)
        val malformed=SignedUpdatePayloadFixture.envelope("not-json".toByteArray())
        assertEquals(UpdateCheckResult.Rejected(DesktopUpdateError.INVALID_SIGNATURE),c.check(badSignature(malformed)))
        assertEquals(0,store.transactions)
        assertEquals(UpdateCheckResult.Rejected(DesktopUpdateError.INVALID_PAYLOAD),c.check(malformed))
        assertEquals(0,store.transactions)
    }
    @Test fun authenticatedPayloadFailuresAndFutureSchemaPreserveCodes() = runBlocking<Unit> {
        val store=SpyStore();val c=client(store)
        assertEquals(UpdateCheckResult.Rejected(DesktopUpdateError.UNSUPPORTED_FORMAT),c.check(bytes(transform={it.replace("\"schemaVersion\":1","\"schemaVersion\":2")})))
        assertEquals(UpdateCheckResult.Rejected(DesktopUpdateError.INVALID_PAYLOAD),c.check(bytes(transform={it.replace("0".repeat(64),"short")})))
        assertEquals(0,store.transactions)
    }
    @Test fun wrongTargetDoesNotOpenStoreAndFailedCommitDoesNotOffer() = runBlocking<Unit> {
        val store=SpyStore();val c=client(store)
        assertEquals(UpdateCheckResult.AuthenticatedIneligible(DesktopUpdateError.WRONG_TARGET),c.check(bytes(transform={it.replace("com.musicglass.desktop","other.product")})))
        assertEquals(0,store.transactions)
        store.memory.failCommit=true
        assertEquals(UpdateCheckResult.Rejected(DesktopUpdateError.STATE_UNAVAILABLE),c.check(bytes()))
    }
    @Test fun incompatibleMetadataCommitsButNeverBecomesOffer() = runBlocking<Unit> {
        val store=SpyStore();assertEquals(UpdateCheckResult.AuthenticatedIneligible(DesktopUpdateError.INCOMPATIBLE),client(store,f.config(workers=setOf(2))).check(bytes()))
        assertEquals(2L,store.memory.record!!.sequence)
    }
    @Test fun callerStreamIsBoundedAndRemainsCallerOwned() = runBlocking<Unit> {
        var consumed=0;var closed=false
        val input=object: InputStream() { override fun read():Int {consumed++;return 32};override fun close(){closed=true} }
        val store=SpyStore();assertEquals(UpdateCheckResult.Rejected(DesktopUpdateError.SIZE_LIMIT),client(store).check(input))
        assertEquals(VerifiedUpdatePayload.MAX_ENVELOPE_BYTES+1,consumed);assertFalse(closed);assertEquals(0,store.transactions)
        assertEquals(UpdateCheckResult.Rejected(DesktopUpdateError.SIZE_LIMIT),client(store).check(ByteArray(VerifiedUpdatePayload.MAX_ENVELOPE_BYTES+1)))
    }
    @Test fun streamAndByteArrayUseTheSameAuthenticatedPipeline() = runBlocking<Unit> {
        val envelope=bytes();assertIs<UpdateCheckResult.Eligible>(client().check(ByteArrayInputStream(envelope)))
        val c=client();val offer=assertIs<UpdateCheckResult.Eligible>(c.check(envelope)).offer
        envelope.fill(0)
        assertIs<UpdateCheckResult.Eligible>(c.revalidate(offer))
    }
    @Test fun cancellationPropagatesAcrossCoordinatorAndDirectVerifier() = runBlocking<Unit> {
        val cancelled=object: InputStream() { override fun read():Int {throw CancellationException("test") } }
        assertFailsWith<CancellationException> { client().check(cancelled) }
        assertFailsWith<CancellationException> { VerifiedUpdatePayload.verify(cancelled,registry()) }
    }
    @Test fun ioFailuresExposeStableCodesWithoutRemoteText() = runBlocking<Unit> {
        val input=object: InputStream() { override fun read():Int {throw IOException("DO_NOT_LOG?token=secret") } }
        val result=client().check(input)
        assertEquals("Rejected(code=INVALID_ENVELOPE)",result.toString())
    }
    @Test fun heldOfferIsRejectedAfterHigherSequenceAndCannotCrossClientIdentity() = runBlocking<Unit> {
        val store=MemoryUpdateFreshnessStore();val c=client(store)
        val old=assertIs<UpdateCheckResult.Eligible>(c.check(bytes())).offer
        assertIs<UpdateCheckResult.Eligible>(client(store).check(bytes(sequence=3)))
        assertEquals(UpdateCheckResult.AuthenticatedIneligible(DesktopUpdateError.STALE_RELEASE),c.revalidate(old))
        assertEquals(UpdateCheckResult.Rejected(DesktopUpdateError.STALE_RELEASE),client(store).revalidate(old))
    }
    @Test fun supersededLocalOfferCannotBeReused() = runBlocking<Unit> {
        val c=client();val old=assertIs<UpdateCheckResult.Eligible>(c.check(bytes())).offer
        val next=assertIs<UpdateCheckResult.Eligible>(c.check(bytes(sequence=3))).offer
        assertEquals(UpdateCheckResult.Rejected(DesktopUpdateError.STALE_RELEASE),c.revalidate(old))
        assertIs<UpdateCheckResult.Eligible>(c.revalidate(next))
    }
    @Test fun heldOfferExpiresAtExactBoundary() = runBlocking<Unit> {
        var now=UpdatePolicyFixture.NOW
        val clock=object:Clock() { override fun getZone()=java.time.ZoneOffset.UTC;override fun withZone(zone: ZoneId)=this;override fun instant()=Instant.ofEpochSecond(now) }
        val c=client(clock=clock);val old=assertIs<UpdateCheckResult.Eligible>(c.check(bytes())).offer
        now=old.manifest.expiresAt
        assertEquals(UpdateCheckResult.AuthenticatedIneligible(DesktopUpdateError.EXPIRED),c.revalidate(old))
    }
    @Test fun fullCoordinatorUsesRealTemporaryDurableStorage() = runBlocking<Unit> {
        val store=FileUpdateFreshnessStore(temporary.newFolder().toPath())
        val c=client(store);assertIs<UpdateCheckResult.Eligible>(c.check(bytes()))
        assertEquals(2L,store.transaction(f.scope) { record,_ -> record!!.sequence })
    }
}