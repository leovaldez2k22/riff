package com.musicglass.desktop

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlin.test.*

class UpdateEnvelopeVerifierTest {
    private fun resource(name: String) = javaClass.getResourceAsStream("/update-v1/$name")!!.use { it.readBytes() }
    private fun text() = resource("envelope.json").toString(Charsets.UTF_8)
    private fun key(id: String = "test-rfc8032-01", der: ByteArray = resource("public-key.der"), active: Boolean = true) =
        UpdateTrustKey(id, der, setOf("com.musicglass.desktop"), setOf("preview"), setOf("windows-x64"), active)
    private fun registry(vararg keys: UpdateTrustKey) = UpdateTrustRegistry(keys.toList().ifEmpty { listOf(key()) }, allowTestKeys = true)
    private fun verify(s: String, reg: UpdateTrustRegistry = registry()) = VerifiedUpdatePayload.verify(s.toByteArray(), reg)
    private fun failure(s: String, code: UpdateVerificationError, reg: UpdateTrustRegistry = registry()) {
        assertEquals(UpdateVerificationResult.Rejected(code), verify(s, reg))
    }

    @Test fun opensslVectorVerifiesAndOwnsItsBytes() {
        val result = verify(text()) as UpdateVerificationResult.Verified
        assertContentEquals(resource("payload.bin"), result.payload.bytes())
        val returned = result.payload.bytes(); returned.fill(0)
        assertContentEquals(resource("payload.bin"), result.payload.bytes())
        assertFailsWith<UnsupportedOperationException> { (result.payload.signer.channels as MutableSet).add("stable") }
    }
    @Test fun javaReproducesIndependentOpenSslSignature() {
        // Public RFC test seed, never a release key. Node generator is a separate preimage implementation.
        val seed = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
        fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val pk = KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(hex("302e020100300506032b657004220420$seed")))
        val signature = Signature.getInstance("Ed25519"); signature.initSign(pk); signature.update(resource("preimage.bin"))
        assertContentEquals(resource("signature.bin"), signature.sign())
        assertEquals(resource("preimage.hex").toString(Charsets.UTF_8).trim(), resource("preimage.bin").joinToString("") { "%02x".format(it) })
    }
    @Test fun alteredPayloadOrSignatureFails() {
        for (field in listOf("payloadBase64", "signatureBase64")) {
            val changed = text().replace(Regex("(\"$field\":\")([A-Za-z0-9+/])")) { it.groupValues[1] + if(it.groupValues[2]=="A") "B" else "A" }
            failure(changed, UpdateVerificationError.INVALID_SIGNATURE)
        }
    }
    @Test fun keyIdIsAuthenticatedEvenForAliasOfSameKey() {
        failure(text().replace("test-rfc8032-01", "test-alias"), UpdateVerificationError.INVALID_SIGNATURE, registry(key("test-alias")))
    }
    @Test fun unknownInactiveAndInvalidLocalKeysFailClosed() {
        failure(text(), UpdateVerificationError.UNKNOWN_KEY, registry(key("test-other")))
        failure(text(), UpdateVerificationError.UNKNOWN_KEY, registry(key(active=false)))
        failure(text(), UpdateVerificationError.CONFIGURATION_ERROR, registry(key(der=ByteArray(44))))
        failure(text(), UpdateVerificationError.CONFIGURATION_ERROR, registry(key(), key()))
        failure(text(), UpdateVerificationError.CONFIGURATION_ERROR, UpdateTrustRegistry(listOf(key())))
    }
    @Test fun wrongValidPublicKeyFailsSignature() {
        val wrong = resource("public-key.der"); wrong[wrong.lastIndex]=(wrong.last().toInt() xor 1).toByte()
        // Point may be invalid; either way it must never authenticate.
        assertIs<UpdateVerificationResult.Rejected>(verify(text(),registry(key(der=wrong))))
    }
    @Test fun supportedFormatDoesNotNegotiate() {
        failure(text().replace("Ed25519","Ed448"), UpdateVerificationError.UNSUPPORTED_FORMAT)
        failure(text().replace("\"envelopeVersion\":1", "\"envelopeVersion\":2"), UpdateVerificationError.UNSUPPORTED_FORMAT)
    }
    @Test fun duplicateEscapedEquivalentAndUnknownFieldsFail() {
        val original = text().trim()
        failure(original.replaceFirst("{", "{\"keyId\":\"test-other\","), UpdateVerificationError.INVALID_ENVELOPE)
        failure(original.replace("\"keyId\"", "\"keyId\":\"test-other\",\"\\u006beyId\""), UpdateVerificationError.INVALID_ENVELOPE)
        failure(original.replace("\"algorithm\"", "\"publicKey\""), UpdateVerificationError.INVALID_ENVELOPE)
    }
    @Test fun outerWhitespaceAndFieldOrderDoNotAlterMessage() {
        assertIs<UpdateVerificationResult.Verified>(verify(" \n"+text()+"\t"))
        val reordered = text().trim().removePrefix("{").removeSuffix("}").split(',').reversed().joinToString(",","{","}")
        assertIs<UpdateVerificationResult.Verified>(verify(reordered))
    }
    @Test fun invalidJsonUnicodeAndTypesFail() {
        for (s in listOf("\uFEFF"+text(), text()+"{}", text().replaceFirst("{", "{/*x*/"),
            text().trim().dropLast(1)+",}", text().replace("\"keyId\":\"test-rfc8032-01\"", "\"keyId\":null"),
            text().replace("\"algorithm\":\"Ed25519\"", "\"algorithm\":\"\\ud800\""),
            text().replace("\"envelopeVersion\":1", "\"envelopeVersion\":1.0"),
            text().replace("\"envelopeVersion\":1", "\"envelopeVersion\":01"))) {
            failure(s,UpdateVerificationError.INVALID_ENVELOPE)
        }
        assertEquals(UpdateVerificationResult.Rejected(UpdateVerificationError.INVALID_ENVELOPE),
            VerifiedUpdatePayload.verify(byteArrayOf(0xc3.toByte(),0x28),registry()))
    }
    @Test fun canonicalBase64AndSignatureLengthAreMandatory() {
        val sig = Base64.getEncoder().encodeToString(resource("signature.bin"))
        for (bad in listOf(sig.dropLast(1),sig+"=",sig.replace('+','-').replace('/','_'),sig.dropLast(3)+"B==",
            Base64.getEncoder().encodeToString(ByteArray(63)),Base64.getEncoder().encodeToString(ByteArray(65)))) {
            if(bad==sig) continue
            assertIs<UpdateVerificationResult.Rejected>(verify(text().replace(sig,bad)))
        }
        failure(text().replace(sig,("\\u" + sig.first().code.toString(16).padStart(4, '0') + sig.drop(1))),UpdateVerificationError.INVALID_ENVELOPE)
    }
    @Test fun boundedInputDoesNotConsumeUnboundedStream() {
        var count = 0
        val endless = object : InputStream() { override fun read(): Int { count++; return 32 } }
        assertEquals(UpdateVerificationResult.Rejected(UpdateVerificationError.SIZE_LIMIT),VerifiedUpdatePayload.verify(endless,registry()))
        assertEquals(131073,count)
        assertIs<UpdateVerificationResult.Verified>(VerifiedUpdatePayload.verify(ByteArrayInputStream(resource("envelope.json")),registry()))
    }
    @Test fun limitsRejectOversizedTokensAndNestedInput() {
        failure(text().replace("test-rfc8032-01", "x".repeat(65)),UpdateVerificationError.INVALID_ENVELOPE)
        failure(text().replace("\"algorithm\":\"Ed25519\"", "\"algorithm\":\""+"x".repeat(8193)+"\""),UpdateVerificationError.SIZE_LIMIT)
        failure(text().replace("\"envelopeVersion\":1", "\"envelopeVersion\":12345678901234567"),UpdateVerificationError.SIZE_LIMIT)
        failure(text().replace("\"envelopeVersion\":1", "\"envelopeVersion\":{}"),UpdateVerificationError.INVALID_ENVELOPE)
    }
    @Test fun payloadAndEnvelopeBoundariesAreEnforced() {
        val originalPayload = resource("payload.bin")
        val originalMessage = resource("preimage.bin")
        val prefix = originalMessage.copyOf(originalMessage.size - originalPayload.size - 4)
        val seedHex = "302e020100300506032b6570042204209d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
        val pk = KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(seedHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()))
        val payload = ByteArray(65536) { 32 }
        val message = prefix + java.nio.ByteBuffer.allocate(4).putInt(payload.size).array() + payload
        val signer = Signature.getInstance("Ed25519"); signer.initSign(pk); signer.update(message)
        val encoded = Base64.getEncoder().encodeToString(payload)
        val signature = Base64.getEncoder().encodeToString(signer.sign())
        val envelope = """{"envelopeVersion":1,"keyId":"test-rfc8032-01","algorithm":"Ed25519","payloadBase64":"$encoded","signatureBase64":"$signature"}"""
        assertIs<UpdateVerificationResult.Verified>(verify(envelope))
        assertIs<UpdateVerificationResult.Verified>(verify(envelope + " ".repeat(131072 - envelope.length)))
        failure(envelope + " ".repeat(131073 - envelope.length),UpdateVerificationError.SIZE_LIMIT)
        failure(envelope.replace(encoded, Base64.getEncoder().encodeToString(ByteArray(65537))),UpdateVerificationError.SIZE_LIMIT)
        // Non-ASCII source bytes must count toward raw token limits, not just UTF-16 length.
        failure(text().replace("Ed25519", "é".repeat(4097)),UpdateVerificationError.SIZE_LIMIT)
    }

    @Test fun publicKeyConfigurationHasCanonicalBase64AndOwnsDer() {
        val der = resource("public-key.der")
        val encoded = Base64.getEncoder().encodeToString(der)
        val trusted = UpdateTrustKey.fromBase64("test-rfc8032-01",encoded,setOf("com.musicglass.desktop"),setOf("preview"),setOf("windows-x64"))
        assertIs<UpdateVerificationResult.Verified>(verify(text(),registry(trusted)))
        assertFailsWith<IllegalArgumentException> { UpdateTrustKey.fromBase64("test-rfc8032-01",encoded.dropLast(1),setOf("x"),setOf("preview"),setOf("windows-x64")) }
        val copied = key(der=der); der.fill(0)
        assertIs<UpdateVerificationResult.Verified>(verify(text(),registry(copied)))
    }

    @Test fun trailingUnknownAndDuplicateMembersKeepEnvelopeErrorCategory() {
        val body=text().trim().dropLast(1)
        failure(body+",\"publicKey\":\"untrusted\"}",UpdateVerificationError.INVALID_ENVELOPE)
        failure(body+",\"keyId\":\"test-duplicate\"}",UpdateVerificationError.INVALID_ENVELOPE)
    }
}
