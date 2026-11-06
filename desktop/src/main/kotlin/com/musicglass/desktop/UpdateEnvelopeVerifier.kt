package com.musicglass.desktop

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.SignatureException
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Collections

internal enum class UpdateVerificationError {
    CONFIGURATION_ERROR, SIZE_LIMIT, INVALID_ENVELOPE, UNSUPPORTED_FORMAT, UNKNOWN_KEY, INVALID_SIGNATURE
}

internal sealed interface UpdateVerificationResult {
    data class Verified(val payload: VerifiedUpdatePayload) : UpdateVerificationResult
    data class Rejected(val code: UpdateVerificationError) : UpdateVerificationResult
}

internal class UpdateTrustKey(
    val keyId: String,
    publicKeyDer: ByteArray,
    appIds: Set<String>, channels: Set<String>, targets: Set<String>,
    val active: Boolean = true
) {
    companion object {
        fun fromBase64(keyId: String, encoded: String, appIds: Set<String>, channels: Set<String>, targets: Set<String>, active: Boolean = true): UpdateTrustKey {
            require(encoded.length == 60)
            val bytes = Base64.getDecoder().decode(encoded)
            require(bytes.size == 44 && Base64.getEncoder().encodeToString(bytes) == encoded)
            return UpdateTrustKey(keyId, bytes, appIds, channels, targets, active)
        }
    }
    private val encoded = publicKeyDer.copyOf()
    val appIds: Set<String> = Collections.unmodifiableSet(appIds.toSet())
    val channels: Set<String> = Collections.unmodifiableSet(channels.toSet())
    val targets: Set<String> = Collections.unmodifiableSet(targets.toSet())
    internal fun key(): PublicKey {
        require(KEY_ID.matches(keyId))
        require(appIds.isNotEmpty() && channels.isNotEmpty() && targets.isNotEmpty())
        // RFC 8410 canonical Ed25519 SPKI with absent parameters and a 32-byte key.
        val prefix = byteArrayOf(0x30,0x2a,0x30,0x05,0x06,0x03,0x2b,0x65,0x70,0x03,0x21,0x00)
        require(encoded.size == 44 && encoded.take(12).toByteArray().contentEquals(prefix))
        val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(encoded))
        require(key.encoded.contentEquals(encoded))
        return key
    }
}

private val KEY_ID = Regex("[a-z0-9][a-z0-9._-]{0,63}")
private class VerificationFailure(val code: UpdateVerificationError) : RuntimeException()
private fun reject(code: UpdateVerificationError): Nothing = throw VerificationFailure(code)

/** Registry is application configuration, never populated from a received envelope. */
internal class UpdateTrustRegistry(keys: List<UpdateTrustKey>, allowTestKeys: Boolean = false) {
    private val entries: Map<String, Pair<UpdateTrustKey, PublicKey>>
    internal val valid: Boolean
    init {
        var built = emptyMap<String, Pair<UpdateTrustKey, PublicKey>>()
        valid = try {
            require(keys.size <= 32 && keys.map { it.keyId }.distinct().size == keys.size)
            require(allowTestKeys || keys.none { it.keyId.startsWith("test-") })
            built = keys.associate { it.keyId to (it to it.key()) }
            true
        } catch (_: Exception) { false }
        entries = built
    }
    internal val configured: Boolean get() = valid && entries.values.any { it.first.active }
    internal fun lookup(id: String): Pair<UpdateTrustKey, PublicKey>? = entries[id]?.takeIf { it.first.active }
}

/** Authentication only: payload bytes have NOT passed the payload schema or eligibility policy. */
internal class VerifiedUpdatePayload private constructor(
    bytes: ByteArray, val signer: UpdateTrustKey
) {
    private val authenticatedBytes = bytes.copyOf()
    fun bytes(): ByteArray = authenticatedBytes.copyOf()
    fun sha256Hex(): String = MessageDigest.getInstance("SHA-256").digest(authenticatedBytes)
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_ENVELOPE_BYTES = 131072
        const val MAX_PAYLOAD_BYTES = 65536

        fun verify(bytes: ByteArray, registry: UpdateTrustRegistry): UpdateVerificationResult {
            if (bytes.size > MAX_ENVELOPE_BYTES) return UpdateVerificationResult.Rejected(UpdateVerificationError.SIZE_LIMIT)
            return verify(ByteArrayInputStream(bytes.copyOf()), registry)
        }

        /** Does not close caller-owned streams. Collector is responsible for transport timeouts. */
        fun verify(input: InputStream, registry: UpdateTrustRegistry): UpdateVerificationResult {
            return try {
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer, 0, minOf(buffer.size, MAX_ENVELOPE_BYTES + 1 - out.size()))
                    if (count < 0) break
                    if (count == 0) {
                        val single = input.read()
                        if (single < 0) break
                        out.write(single)
                    } else out.write(buffer, 0, count)
                    if (out.size() > MAX_ENVELOPE_BYTES) reject(UpdateVerificationError.SIZE_LIMIT)
                }
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                val text = decoder.decode(ByteBuffer.wrap(out.toByteArray())).toString()
                val envelope = EnvelopeReader(text).read()
                if (envelope.version != "1" || envelope.algorithm != "Ed25519") reject(UpdateVerificationError.UNSUPPORTED_FORMAT)
                if (!registry.valid) reject(UpdateVerificationError.CONFIGURATION_ERROR)
                val (entry, key) = registry.lookup(envelope.keyId) ?: reject(UpdateVerificationError.UNKNOWN_KEY)
                val payload = decode64(envelope.payload, MAX_PAYLOAD_BYTES)
                if (payload.isEmpty()) reject(UpdateVerificationError.INVALID_ENVELOPE)
                if (envelope.signature.length != 88 || !envelope.signature.endsWith("=="))
                    reject(UpdateVerificationError.INVALID_ENVELOPE)
                val sig = decode64(envelope.signature, 64)
                if (sig.size != 64)
                    reject(UpdateVerificationError.INVALID_ENVELOPE)
                val verifier = Signature.getInstance("Ed25519")
                verifier.initVerify(key)
                verifier.update(preimage(envelope.keyId, payload))
                val verified = try { verifier.verify(sig) } catch (_: SignatureException) { false }
                if (!verified) reject(UpdateVerificationError.INVALID_SIGNATURE)
                UpdateVerificationResult.Verified(VerifiedUpdatePayload(payload, entry))
            } catch (failure: VerificationFailure) {
                UpdateVerificationResult.Rejected(failure.code)
            } catch (_: java.security.GeneralSecurityException) {
                UpdateVerificationResult.Rejected(UpdateVerificationError.CONFIGURATION_ERROR)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                UpdateVerificationResult.Rejected(UpdateVerificationError.INVALID_ENVELOPE)
            }
        }

        private fun preimage(id: String, payload: ByteArray): ByteArray {
            val key = id.toByteArray(Charsets.US_ASCII)
            val algorithm = "Ed25519".toByteArray(Charsets.US_ASCII)
            val domain = "RIFF-DESKTOP-UPDATE\u0000".toByteArray(Charsets.US_ASCII)
            return ByteBuffer.allocate(domain.size + 4 + 2 + key.size + 2 + algorithm.size + 4 + payload.size)
                .put(domain).putInt(1).putShort(key.size.toShort()).put(key)
                .putShort(algorithm.size.toShort()).put(algorithm).putInt(payload.size).put(payload).array()
        }

        private fun decode64(value: String, max: Int): ByteArray {
            if (value.length % 4 != 0 || value.isEmpty()) reject(UpdateVerificationError.INVALID_ENVELOPE)
            val padding = if (value.endsWith("==")) 2 else if (value.endsWith("=")) 1 else 0
            val predicted = value.length / 4 * 3 - padding
            if (predicted > max) reject(UpdateVerificationError.SIZE_LIMIT)
            val decoded = try { Base64.getDecoder().decode(value) } catch (_: IllegalArgumentException) {
                reject(UpdateVerificationError.INVALID_ENVELOPE)
            }
            if (decoded.size > max) reject(UpdateVerificationError.SIZE_LIMIT)
            if (Base64.getEncoder().encodeToString(decoded) != value) reject(UpdateVerificationError.INVALID_ENVELOPE)
            return decoded
        }
    }
}

private data class UpdateEnvelope(val version: String, val keyId: String, val algorithm: String, val payload: String, val signature: String)

/** Dedicated bounded JSON tokenizer/parser for the five scalar envelope fields, not a general payload parser. */
private class EnvelopeReader(private val text: String) {
    private var at = 0
    private val names = setOf("envelopeVersion", "keyId", "algorithm", "payloadBase64", "signatureBase64")
    private fun invalid(): Nothing = reject(UpdateVerificationError.INVALID_ENVELOPE)
    private fun space() { while (at < text.length && text[at] in " \t\r\n") at++ }
    private fun expect(char: Char) { space(); if (at >= text.length || text[at++] != char) invalid() }
    fun read(): UpdateEnvelope {
        expect('{')
        val values = linkedMapOf<String, String>()
        while (true) {
            space()
            if (at < text.length && text[at] == '}') { at++; break }

            val name = string(32, 128, 32768).first
            // Unknown/duplicate names are structural errors, including after all five allowed fields.
            if (name !in names || name in values) invalid()
            if (values.size >= 5) reject(UpdateVerificationError.SIZE_LIMIT)
            expect(':'); space()
            val value = if (name == "envelopeVersion") number() else {
                val base64 = name == "payloadBase64" || name == "signatureBase64"
                val token = string(if (name == "payloadBase64") 87384 else 4096,
                    if (name == "payloadBase64") 87384 else 16384,
                    if (name == "payloadBase64") 87386 else 32768)
                if (base64 && (token.second || token.first.any { it.code > 127 })) invalid()
                token.first
            }
            values[name] = value
            space()
            if (at >= text.length) invalid()
            when (text[at++]) {
                '}' -> break
                ',' -> { space(); if (at >= text.length || text[at] == '}') invalid() }
                else -> invalid()
            }
        }
        space()
        if (at != text.length || values.keys != names) invalid()
        val id = values.getValue("keyId")
        if (!KEY_ID.matches(id)) invalid()
        return UpdateEnvelope(values.getValue("envelopeVersion"), id, values.getValue("algorithm"),
            values.getValue("payloadBase64"), values.getValue("signatureBase64"))
    }
    private fun number(): String {
        val start = at
        while (at < text.length && text[at] in '0'..'9') {
            at++
            if (at - start > 16) reject(UpdateVerificationError.SIZE_LIMIT)
        }
        if (at == start || (at - start > 1 && text[start] == '0')) invalid()
        return text.substring(start, at)
    }
    private fun string(maxScalars: Int, maxBytes: Int, maxRaw: Int): Pair<String, Boolean> {
        expect('"')
        val start = at - 1
        var rawBytes = 1
        var accounted = at
        fun accountRaw() {
            for (i in accounted until at) {
                val c = text[i]
                rawBytes += when { c.code < 128 -> 1; c.code < 2048 -> 2; c.isSurrogate() -> 2; else -> 3 }
            }
            accounted = at
            if (rawBytes > maxRaw) reject(UpdateVerificationError.SIZE_LIMIT)
        }
        val out = StringBuilder()
        var decodedScalars = 0
        var decodedBytes = 0
        fun appendChar(ch: Char) {
            if (out.isNotEmpty() && out.last().isHighSurrogate()) {
                if (!ch.isLowSurrogate()) invalid()
            } else {
                if (ch.isLowSurrogate()) invalid()
                decodedScalars++
            }
            decodedBytes += when { ch.code < 128 -> 1; ch.code < 2048 -> 2; ch.isSurrogate() -> 2; else -> 3 }
            if (decodedScalars > maxScalars || decodedBytes > maxBytes) reject(UpdateVerificationError.SIZE_LIMIT)
            out.append(ch)
        }
        var escaped = false
        while (at < text.length) {
            val c = text[at++]
            if (at - start > maxRaw) reject(UpdateVerificationError.SIZE_LIMIT)
            if (c == '"') {
                accountRaw()
                val value = out.toString()
                var i = 0; var scalars = 0
                while (i < value.length) {
                    val ch = value[i++]
                    if (ch.isHighSurrogate()) {
                        if (i >= value.length || !value[i++].isLowSurrogate()) invalid()
                    } else if (ch.isLowSurrogate()) invalid()
                    scalars++
                }
                if (scalars > maxScalars || value.toByteArray(Charsets.UTF_8).size > maxBytes)
                    reject(UpdateVerificationError.SIZE_LIMIT)
                return value to escaped
            }
            if (c.code < 32) invalid()
            if (c == '\\') {
                escaped = true
                if (at >= text.length) invalid()
                when (val e = text[at++]) {
                    '"', '\\', '/' -> appendChar(e)
                    'b' -> appendChar('\b'); 'f' -> appendChar('\u000c')
                    'n' -> appendChar('\n'); 'r' -> appendChar('\r'); 't' -> appendChar('\t')
                    'u' -> {
                        if (text.length - at < 4) invalid()
                        val code = text.substring(at, at + 4).toIntOrNull(16) ?: invalid()
                        if (text.substring(at, at + 4).any { it !in "0123456789abcdefABCDEF" }) invalid()
                        at += 4; appendChar(code.toChar())
                    }
                    else -> invalid()
                }
            } else appendChar(c)
            accountRaw()
            if (out.length > maxScalars * 2 || at - start > maxRaw) reject(UpdateVerificationError.SIZE_LIMIT)
        }
        invalid()
    }
}
