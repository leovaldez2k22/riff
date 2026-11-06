package com.musicglass.desktop

import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlin.test.assertIs

/** TEST-ONLY signing through the actual envelope verifier; no unverified parser construction. */
internal object SignedUpdatePayloadFixture {
    private fun resource(name: String) = javaClass.getResourceAsStream("/update-v1/$name")!!.use { it.readBytes() }
    fun envelope(payload: ByteArray, keyId: String = "test-rfc8032-01"): ByteArray {
        val id=keyId.toByteArray(); val algorithm="Ed25519".toByteArray()
        val domain="RIFF-DESKTOP-UPDATE\u0000".toByteArray()
        val message=ByteBuffer.allocate(domain.size+4+2+id.size+2+algorithm.size+4+payload.size)
            .put(domain).putInt(1).putShort(id.size.toShort()).put(id).putShort(algorithm.size.toShort()).put(algorithm)
            .putInt(payload.size).put(payload).array()
        val der="302e020100300506032b6570042204209d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val key=KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(der))
        val signer=Signature.getInstance("Ed25519");signer.initSign(key);signer.update(message)
        val p=Base64.getEncoder().encodeToString(payload);val s=Base64.getEncoder().encodeToString(signer.sign())
        return """{"envelopeVersion":1,"keyId":"$keyId","algorithm":"Ed25519","payloadBase64":"$p","signatureBase64":"$s"}""".toByteArray()
    }
    fun verify(payload: ByteArray): UpdateVerificationResult {
        val registry=UpdateTrustRegistry(listOf(UpdateTrustKey("test-rfc8032-01",resource("public-key.der"),
            setOf("com.musicglass.desktop"),setOf("preview"),setOf("windows-x64"))),allowTestKeys=true)
        return VerifiedUpdatePayload.verify(envelope(payload),registry)
    }
    fun parse(text: String): UpdatePayloadParseResult = parse(text.toByteArray(Charsets.UTF_8))
    fun parse(bytes: ByteArray): UpdatePayloadParseResult = ValidatedUpdateManifest.parse(
        assertIs<UpdateVerificationResult.Verified>(verify(bytes)).payload)

    fun validJson(notes: String="Fixture notes"): String = """{
      "schemaVersion":1,"appId":"com.musicglass.desktop","channel":"preview","version":"0.2.0",
      "releaseSequence":2,"publishedAt":1793577600,"expiresAt":1794182400,"target":"windows-x64",
      "minWindowsBuild":22000,"minInstalledVersion":"0.1.0","workerProtocolMajor":1,"databaseSchemaVersion":1,
      "releaseNotes":"$notes","artifacts":[{"type":"msi","os":"windows","arch":"x64",
      "url":"https://releases.example.invalid/a.msi?opaque=keep%20this","sizeBytes":123456,
      "sha256":"${"0".repeat(64)}"}]}"""
}
