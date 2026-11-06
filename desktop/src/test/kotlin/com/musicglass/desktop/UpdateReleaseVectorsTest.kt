package com.musicglass.desktop

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class UpdateReleaseVectorsTest {
    private val root="/update-v1/release-corpus/"
    private fun resource(name: String)=javaClass.getResourceAsStream(root+name)!!.use {it.readBytes()}
    private fun cases()=resource("expectations.tsv").toString(Charsets.UTF_8).trim().lines().map {it.split('\t')}
    private fun registry()=UpdateTrustRegistry(listOf("test-rfc8032-01","test-rfc8032-rotated").map { id ->
        UpdateTrustKey(id,resource(if(id=="test-rfc8032-rotated") "rotated-public-key.der" else "public-key.der"),setOf("com.musicglass.desktop"),setOf("preview"),setOf("windows-x64")) },allowTestKeys=true)
    @Test fun completeCorpusPassesJcaAndFullCoordinatorAtIntendedPhase() = runBlocking<Unit> {
        val f=UpdatePolicyFixture;val registry=registry()
        val verified=assertIs<UpdateVerificationResult.Verified>(VerifiedUpdatePayload.verify(resource("valid/envelope.json"),registry)).payload
        val base=assertIs<UpdatePayloadParseResult.Valid>(ValidatedUpdateManifest.parse(verified)).manifest
        val all=cases();assertEquals(27,all.size)
        for((name,expected,stored) in all) {
            val state=if(stored=="valid") f.record(20,"0.2.0",base.payloadSha256) else null
            val store=MemoryUpdateFreshnessStore(state)
            val config=f.config(bootstrap=UpdateBootstrap(10,"0.1.0"))
            val result=DesktopUpdateClient(registry,config,store,f.clock()).check(resource("$name/envelope.json"))
                        if(name=="key-overlap") {
                val oldOnly=UpdateTrustRegistry(listOf(UpdateTrustKey("test-rfc8032-01",resource("public-key.der"),setOf(f.scope.appId),setOf("preview"),setOf("windows-x64"))),allowTestKeys=true)
                assertEquals(UpdateVerificationResult.Rejected(UpdateVerificationError.UNKNOWN_KEY),VerifiedUpdatePayload.verify(resource("$name/envelope.json"),oldOnly))
                assertFalse(resource("public-key.der").contentEquals(resource("rotated-public-key.der")))
            }
            val outcome=when(result) {
                is UpdateCheckResult.Eligible -> "ELIGIBLE"
                is UpdateCheckResult.Rejected -> result.code.name
                is UpdateCheckResult.AuthenticatedIneligible -> result.reason.name
                UpdateCheckResult.UpToDate -> "UP_TO_DATE"
                UpdateCheckResult.Disabled -> "DISABLED"
            }
            assertEquals(expected,outcome,name)
            if(expected in setOf("ELIGIBLE","INCOMPATIBLE")) assertEquals(1,store.commits,name)
            else assertEquals(0,store.commits,name)
        }
    }
    @Test fun rawSyntaxBridgeMatchesAuthenticatedParserAcrossEveryCorpusPayload() {
        for((name,_,_) in cases()) {
            val bytes=resource("$name/payload.bin")
            val syntax=ValidatedUpdateManifest.validateForSigning(bytes)
            val verified=SignedUpdatePayloadFixture.verify(bytes)
            if(verified is UpdateVerificationResult.Rejected) {
                assertEquals(UpdateVerificationError.SIZE_LIMIT,verified.code,name);assertEquals(UpdatePayloadError.SIZE_LIMIT,syntax,name)
            } else {
                val parsed=ValidatedUpdateManifest.parse((verified as UpdateVerificationResult.Verified).payload)
                assertEquals((parsed as? UpdatePayloadParseResult.Rejected)?.code,syntax,name)
            }
        }
    }
    @Test fun allIndependentPreimagesAndSignaturesVerifyInJca() {
        val publicKey=KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(resource("public-key.der")))
        for((name,_,_) in cases()) {
            val message=resource("$name/preimage.bin");val input=ByteBuffer.wrap(message)
            val domain=ByteArray(20);input.get(domain);assertContentEquals("RIFF-DESKTOP-UPDATE\u0000".toByteArray(),domain,name)
            assertEquals(1,input.int,name)
            val id=ByteArray(input.short.toInt());input.get(id)
            val algorithm=ByteArray(input.short.toInt());input.get(algorithm);assertEquals("Ed25519",algorithm.toString(Charsets.US_ASCII))
            val payload=ByteArray(input.int);input.get(payload);assertFalse(input.hasRemaining());assertContentEquals(resource("$name/payload.bin"),payload,name)
            val hex=message.joinToString("") {"%02x".format(it)}+"\n";assertEquals(hex,resource("$name/preimage.hex").toString(Charsets.US_ASCII),name)
            val selected=if(id.toString(Charsets.US_ASCII)=="test-rfc8032-rotated") KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(resource("rotated-public-key.der"))) else publicKey
            val verifier=Signature.getInstance("Ed25519");verifier.initVerify(selected);verifier.update(message)
            assertTrue(verifier.verify(resource("$name/signature.bin")),name)
        }
    }
    @Test fun corpusHashInventoryHasEveryExpectedFileAndMatchesBytes() {
        // This regex reads controlled test hash metadata only, never untrusted update JSON.
        val entries=Regex("\"([^\"]+)\": \"([0-9a-f]{64})\"").findAll(resource("sha256.json").toString(Charsets.US_ASCII)).toList()
        assertEquals(168,entries.size)
        for(entry in entries) {
            val actual=MessageDigest.getInstance("SHA-256").digest(resource(entry.groupValues[1])).joinToString("") {"%02x".format(it)}
            assertEquals(entry.groupValues[2],actual,entry.groupValues[1])
        }
    }
}