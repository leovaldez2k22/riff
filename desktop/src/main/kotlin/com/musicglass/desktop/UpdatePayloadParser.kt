package com.musicglass.desktop

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal enum class UpdatePayloadError { INVALID_PAYLOAD, SIZE_LIMIT, UNSUPPORTED_FORMAT }
internal sealed interface UpdatePayloadParseResult {
    data class Valid(val manifest: ValidatedUpdateManifest) : UpdatePayloadParseResult
    data class Rejected(val code: UpdatePayloadError) : UpdatePayloadParseResult
}

/** Values are immutable; this object alone is not an authenticated manifest. */
internal class UpdateArtifact internal constructor(val type: String, val os: String, val arch: String,
    val url: String, val sizeBytes: Long, val sha256: String)

/** Only parse(VerifiedUpdatePayload) constructs this boundary. No copy()/unverified factory. */
internal class ValidatedUpdateManifest private constructor(
    val schemaVersion: Int, val appId: String, val channel: String, val version: UpdateVersion,
    val releaseSequence: Long, val publishedAt: Long, val expiresAt: Long, val target: String,
    val minWindowsBuild: Int, val minInstalledVersion: UpdateVersion, val workerProtocolMajor: Int,
    val databaseSchemaVersion: Int, val releaseNotes: String, val artifact: UpdateArtifact,
    val payloadSha256: String, val signer: UpdateTrustKey
) {
    companion object {
        fun parse(payload: VerifiedUpdatePayload): UpdatePayloadParseResult = read(payload.bytes(), payload)!!

        /** Maintainer syntax check only: raw bytes never produce a manifest or eligibility. */
        fun validateForSigning(bytes: ByteArray): UpdatePayloadError? =
            (read(bytes, null) as? UpdatePayloadParseResult.Rejected)?.code

        private fun read(bytes: ByteArray, payload: VerifiedUpdatePayload?): UpdatePayloadParseResult? = try {
            if (bytes.size > 65536) fail(UpdatePayloadError.SIZE_LIMIT)
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val root = PayloadJsonReader(text).read() as? JsonValue.Obj ?: invalidPayload()
            val schema = root.integer("schemaVersion", 0, 9999999999999999L)
            if (schema != 1L) fail(UpdatePayloadError.UNSUPPORTED_FORMAT)
            root.exactFields(setOf("schemaVersion","appId","channel","version","releaseSequence","publishedAt","expiresAt",
                "target","minWindowsBuild","minInstalledVersion","workerProtocolMajor","databaseSchemaVersion","releaseNotes","artifacts"))
            val appId = root.string("appId").also { if (!Regex("[a-z][a-z0-9.-]{0,127}").matches(it)) invalidPayload() }
            val channel = root.string("channel").also { if (it !in setOf("preview","stable")) invalidPayload() }
            val version = UpdateVersion.parse(root.string("version")) ?: invalidPayload()
            val minimum = UpdateVersion.parse(root.string("minInstalledVersion")) ?: invalidPayload()
            if (minimum > version) invalidPayload()
            val sequence = root.integer("releaseSequence",1,9007199254740991L)
            val published = root.integer("publishedAt",0,253402300799L)
            val expires = root.integer("expiresAt",0,253402300799L)
            if (expires <= published || expires - published > 2592000L) invalidPayload()
            val target = root.string("target")
            if (!Regex("[a-z][a-z0-9]{0,15}-[a-z][a-z0-9]{0,15}").matches(target)) invalidPayload()
            val build = root.integer("minWindowsBuild",22000,2147483647L).toInt()
            val worker = root.integer("workerProtocolMajor",1,65535).toInt()
            val database = root.integer("databaseSchemaVersion",1,2147483647L).toInt()
            val notes = root.string("releaseNotes")
            if (notes.any { Character.isISOControl(it) && it !in "\t\r\n" }) invalidPayload()
            val artifacts = root.fields["artifacts"] as? JsonValue.Arr ?: invalidPayload()
            if (artifacts.values.size != 1) invalidPayload()
            val artifact = artifacts.values[0] as? JsonValue.Obj ?: invalidPayload()
            artifact.exactFields(setOf("type","os","arch","url","sizeBytes","sha256"))
            val type = artifact.string("type"); if (type != "msi") invalidPayload()
            val os = artifact.string("os"); val arch = artifact.string("arch")
            if (!Regex("[a-z][a-z0-9]{0,15}").matches(os) || !Regex("[a-z][a-z0-9]{0,15}").matches(arch) || target != "$os-$arch") invalidPayload()
            val url = artifact.string("url"); validateArtifactUrl(url)
            val size = artifact.integer("sizeBytes",1,2147483648L)
            val hash = artifact.string("sha256"); if (!Regex("[0-9a-f]{64}").matches(hash)) invalidPayload()
            if (payload == null) null else UpdatePayloadParseResult.Valid(ValidatedUpdateManifest(1,appId,channel,version,sequence,published,expires,target,
                build,minimum,worker,database,notes,UpdateArtifact(type,os,arch,url,size,hash),payload.sha256Hex(),payload.signer))
        } catch (error: PayloadFailure) { UpdatePayloadParseResult.Rejected(error.code) }
          catch (_: java.nio.charset.CharacterCodingException) { UpdatePayloadParseResult.Rejected(UpdatePayloadError.INVALID_PAYLOAD) }
          catch (_: java.net.URISyntaxException) { UpdatePayloadParseResult.Rejected(UpdatePayloadError.INVALID_PAYLOAD) }
    }
}

private class PayloadFailure(val code: UpdatePayloadError) : RuntimeException()
private fun fail(code: UpdatePayloadError): Nothing = throw PayloadFailure(code)
private fun invalidPayload(): Nothing = fail(UpdatePayloadError.INVALID_PAYLOAD)

private fun validateArtifactUrl(value: String) {
    if (value.length !in 1..2048 || value.any { it.code !in 33..126 || it == '\\' }) invalidPayload()
    val uri = URI(value)
    val host = uri.host ?: invalidPayload()
    if (uri.scheme != "https" || uri.isOpaque || uri.rawUserInfo != null || uri.rawFragment != null || uri.port !in setOf(-1,443)) invalidPayload()
    if (host.length > 253 || '.' !in host || host.endsWith('.') || host.split('.').last().all { it in '0'..'9' }) invalidPayload()
    val label = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")
    if (host.split('.').any { !label.matches(it) }) invalidPayload()
    val authority = uri.rawAuthority ?: invalidPayload()
    if (!authority.equals(host,ignoreCase=true) && !authority.equals("$host:443",ignoreCase=true)) invalidPayload()
    val path = uri.rawPath ?: invalidPayload()
    if (!path.startsWith('/')) invalidPayload()
    if (Regex("%(?:2f|5c|00|0d|0a)",RegexOption.IGNORE_CASE).containsMatchIn(path)) invalidPayload()
    val dotDecoded = path.replace(Regex("%2e",RegexOption.IGNORE_CASE),".")
    if (dotDecoded.split('/').any { it == "." || it == ".." }) invalidPayload()
    // Host allowlists, DNS resolution, redirects and artifact access are deliberately deferred.
}

private sealed interface JsonValue {
    class Str(val value: String) : JsonValue
    class Num(val value: Long) : JsonValue
    class Bool(val value: Boolean) : JsonValue
    class Arr(val values: List<JsonValue>) : JsonValue
    class Obj(val fields: Map<String,JsonValue>) : JsonValue {
        fun exactFields(names: Set<String>) { if (fields.keys != names) invalidPayload() }
        fun string(name: String): String = (fields[name] as? Str)?.value ?: invalidPayload()
        fun integer(name: String, min: Long, max: Long): Long = ((fields[name] as? Num)?.value ?: invalidPayload()).also {
            if (it !in min..max) invalidPayload()
        }
    }
}

/** Whole body is capped at 64 KiB before this bounded structural parser allocates any nodes. */
private class PayloadJsonReader(private val text: String) {
    private var at = 0
    private var nodes = 0
    private fun space() { while(at < text.length && text[at] in " \t\r\n") at++ }
    private fun take(c: Char): Boolean { space(); return if(at < text.length && text[at] == c) { at++; true } else false }
    private fun expect(c: Char) { if (!take(c)) invalidPayload() }
    fun read(): JsonValue { val value = value(0); space(); if(at != text.length) invalidPayload(); return value }
    private fun value(parentDepth: Int): JsonValue {
        space(); if(at >= text.length) invalidPayload()
        // Intrinsic body-derived cap: any valid 64 KiB JSON document has fewer than 65536 values.
        if (++nodes > 65536) fail(UpdatePayloadError.SIZE_LIMIT)
        return when(text[at]) {
            '{' -> {
                if(parentDepth >= 3) fail(UpdatePayloadError.SIZE_LIMIT)
                at++; val map = linkedMapOf<String,JsonValue>()
                if (!take('}')) {
                    do {
                        val key = string(true)
                        if(key in map) invalidPayload()
                        // A member needs at least 5 source bytes, so this is above the intrinsic body cap.
                        if(map.size >= 16384) fail(UpdatePayloadError.SIZE_LIMIT)
                        expect(':'); map[key] = value(parentDepth+1)
                        if(take('}')) return JsonValue.Obj(map)
                    } while(take(','))
                    invalidPayload()
                }
                JsonValue.Obj(map)
            }
            '[' -> {
                if(parentDepth >= 3) fail(UpdatePayloadError.SIZE_LIMIT)
                at++; val list = mutableListOf<JsonValue>()
                if(!take(']')) {
                    do {
                        if(list.size >= 32768) fail(UpdatePayloadError.SIZE_LIMIT)
                        list += value(parentDepth+1)
                        if(take(']')) return JsonValue.Arr(list)
                    } while(take(','))
                    invalidPayload()
                }
                JsonValue.Arr(list)
            }
            '"' -> JsonValue.Str(string(false))
            in '0'..'9' -> {
                val start = at
                while(at < text.length && text[at] in '0'..'9') { at++; if(at-start>16) fail(UpdatePayloadError.SIZE_LIMIT) }
                if(at-start>1 && text[start]=='0') invalidPayload()
                JsonValue.Num(text.substring(start,at).toLongOrNull() ?: invalidPayload())
            }
            't' -> { literal("true"); JsonValue.Bool(true) }
            'f' -> { literal("false"); JsonValue.Bool(false) }
            else -> invalidPayload()
        }
    }
    private fun literal(word: String) { if(!text.startsWith(word,at)) invalidPayload(); at += word.length }
    private fun string(key: Boolean): String {
        expect('"'); var rawBytes = 1; var scalars = 0; var bytes = 0
        val out = StringBuilder()
        fun add(c: Char) {
            if(out.isNotEmpty() && out.last().isHighSurrogate()) { if(!c.isLowSurrogate()) invalidPayload() }
            else { if(c.isLowSurrogate()) invalidPayload(); scalars++ }
            bytes += utf8Size(c)
            if(scalars > (if(key) 32 else 4096) || bytes > (if(key) 128 else 16384)) fail(UpdatePayloadError.SIZE_LIMIT)
            out.append(c)
        }
        while(at < text.length) {
            val start = at; val c = text[at++]
            if(c == '"') {
                rawBytes++
                if(rawBytes>32768) fail(UpdatePayloadError.SIZE_LIMIT)
                if(out.isNotEmpty() && out.last().isHighSurrogate()) invalidPayload()
                return out.toString()
            }
            if(c.code<32) invalidPayload()
            if(c=='\\') {
                if(at>=text.length) invalidPayload()
                when(val e=text[at++]) {
                    '"','\\','/' -> add(e)
                    'b' -> add('\b'); 'f' -> add('\u000c'); 'n' -> add('\n'); 'r' -> add('\r'); 't' -> add('\t')
                    'u' -> {
                        if(text.length-at<4) invalidPayload()
                        val hex=text.substring(at,at+4)
                        if(hex.any { it !in "0123456789abcdefABCDEF" }) invalidPayload()
                        at+=4; add(hex.toInt(16).toChar())
                    }
                    else -> invalidPayload()
                }
            } else add(c)
            for(i in start until at) rawBytes += utf8Size(text[i])
            if(rawBytes>32768) fail(UpdatePayloadError.SIZE_LIMIT)
        }
        invalidPayload()
    }
    private fun utf8Size(c: Char): Int = when { c.code<128 -> 1; c.code<2048 -> 2; c.isSurrogate() -> 2; else -> 3 }
}
