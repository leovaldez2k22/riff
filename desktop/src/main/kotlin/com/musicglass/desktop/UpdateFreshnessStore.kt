package com.musicglass.desktop

import com.sun.jna.Native
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import java.io.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.*
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.*

internal class FreshnessStateUnavailable : IOException("STATE_UNAVAILABLE")
internal data class UpdateFreshnessRecord(val sequence: Long, val sequenceVersion: UpdateVersion, val digest: String?,
    val highestVersion: UpdateVersion, val lastEvaluationTime: Long) {
    fun validate() {
        if (sequence !in 1..UPDATE_MAX_SEQUENCE || highestVersion < sequenceVersion ||
            lastEvaluationTime !in 0..UPDATE_MAX_TIME || (digest != null && !validUpdateDigest(digest))) throw FreshnessStateUnavailable()
    }
}

internal interface UpdateFreshnessStore {
    /** Callback and commit execute while the exact scope's cross-process decision lock is held. */
    suspend fun <T> transaction(scope: UpdateScope, action: suspend (UpdateFreshnessRecord?, (UpdateFreshnessRecord) -> Unit) -> T): T
}

/** Atomic publisher seam supports deterministic failure injection without changing decision policy. */
internal fun interface UpdateStatePublisher { fun replace(temporary: Path, destination: Path) }

/** Windows 11 local NTFS only. Never falls back to a copy/delete or non-durable replacement. */
internal object WindowsUpdateStatePublisher : UpdateStatePublisher {
    private interface Kernel : StdCallLibrary {
        fun MoveFileExW(source: WString, destination: WString, flags: Int): Boolean
        fun GetDriveTypeW(root: WString): Int
    }
    private val kernel by lazy { Native.load("kernel32", Kernel::class.java) }
    override fun replace(temporary: Path, destination: Path) {
        try {
            if (!System.getProperty("os.name").startsWith("Windows") || temporary.parent != destination.parent ||
                !Files.getFileStore(temporary).type().equals("NTFS",true) ||
                kernel.GetDriveTypeW(WString(temporary.root.toString())) != 3) throw FreshnessStateUnavailable()
            // Same-directory local-volume rename; COPY_ALLOWED is deliberately absent. Temp content was forced first.
            if (!kernel.MoveFileExW(WString(temporary.toString()),WString(destination.toString()),0x1 or 0x8)) throw FreshnessStateUnavailable()
        } catch (_: LinkageError) { throw FreshnessStateUnavailable() }
    }
}

/** Caller injects an owned app-data root; no user-home state is opened implicitly. */
internal class FileUpdateFreshnessStore(private val root: Path,
    private val publisher: UpdateStatePublisher = WindowsUpdateStatePublisher,
    private val lockTimeoutMs: Long = 10000
) : UpdateFreshnessStore {
    override suspend fun <T> transaction(scope: UpdateScope,
        action: suspend (UpdateFreshnessRecord?, (UpdateFreshnessRecord) -> Unit) -> T): T = withContext(Dispatchers.IO) {
        try {
            Files.createDirectories(root)
            val directory = root.toRealPath()
            val id = MessageDigest.getInstance("SHA-256").digest("${scope.appId}\u0000${scope.channel}\u0000${scope.target}".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val destination = directory.resolve("$id.state")
            val lockPath = directory.resolve("$id.lock") // Stable inode: never delete the decision lock file.
            FileChannel.open(lockPath,CREATE,WRITE,LinkOption.NOFOLLOW_LINKS).use { channel ->
                val started = System.nanoTime()
                var lock: java.nio.channels.FileLock? = null
                while(lock == null) {
                    coroutineContext.ensureActive()
                    lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                    if (lock == null) {
                        if ((System.nanoTime()-started)/1000000 >= lockTimeoutMs) throw FreshnessStateUnavailable()
                        delay(10)
                    }
                }
                lock.use {
                    val decisionContext = coroutineContext
                    val current = read(destination,scope)
                    action(current) { next ->
                        decisionContext.ensureActive()
                        next.validate()
                        val bytes = encode(scope,next)
                        val temporary = Files.createTempFile(directory,"$id-", ".pending")
                        var primaryFailure: Throwable? = null
                        try {
                            FileChannel.open(temporary,WRITE,LinkOption.NOFOLLOW_LINKS).use { output ->
                                val buffer = ByteBuffer.wrap(bytes)
                                while(buffer.hasRemaining()) output.write(buffer)
                                output.force(true)
                            }
                            decisionContext.ensureActive()
                            // The replace operation is an indivisible publication boundary.
                            publisher.replace(temporary,destination)
                        } catch (error: Throwable) {
                            primaryFailure = error
                            throw error
                        } finally {
                            try { Files.deleteIfExists(temporary) }
                            catch (cleanup: IOException) { if (primaryFailure != null) primaryFailure.addSuppressed(cleanup) else throw cleanup }
                        }
                    }
                }
            }
        } catch (error: CancellationException) { throw error }
          catch (_: IOException) { throw FreshnessStateUnavailable() }
          catch (_: SecurityException) { throw FreshnessStateUnavailable() }
    }

    private fun read(path: Path, scope: UpdateScope): UpdateFreshnessRecord? {
        val bytes = try {
            Files.newInputStream(path,READ,LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(1025) }
        } catch (_: NoSuchFileException) { return null }
        if (bytes.size !in 33..1024) throw FreshnessStateUnavailable()
        val body = bytes.copyOfRange(0,bytes.size-32)
        if (!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(body),bytes.copyOfRange(body.size,bytes.size))) throw FreshnessStateUnavailable()
        DataInputStream(ByteArrayInputStream(body)).use { input ->
            fun text(maximum: Int): String {
                val size = input.readUnsignedShort()
                if (size > maximum) throw FreshnessStateUnavailable()
                val value = input.readNBytes(size)
                if (value.size != size || value.any { (it.toInt() and 255) !in 33..126 }) throw FreshnessStateUnavailable()
                return value.toString(Charsets.US_ASCII)
            }
            if (input.readLong() != 0x5249464655504431L || UpdateScope(text(128),text(7),text(33)) != scope) throw FreshnessStateUnavailable()
            val sequence = input.readLong()
            val version = UpdateVersion.parse(text(32)) ?: throw FreshnessStateUnavailable()
            val digest = when(input.readUnsignedByte()) { 0 -> null; 1 -> text(64); else -> throw FreshnessStateUnavailable() }
            val highest = UpdateVersion.parse(text(32)) ?: throw FreshnessStateUnavailable()
            val result = UpdateFreshnessRecord(sequence,version,digest,highest,input.readLong())
            if (input.available() != 0) throw FreshnessStateUnavailable()
            result.validate()
            return result
        }
    }

    private fun encode(scope: UpdateScope, record: UpdateFreshnessRecord): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            fun text(value: String) { val bytes=value.toByteArray(Charsets.US_ASCII);data.writeShort(bytes.size);data.write(bytes) }
            data.writeLong(0x5249464655504431L)
            text(scope.appId);text(scope.channel);text(scope.target)
            data.writeLong(record.sequence);text(record.sequenceVersion.toString())
            data.writeByte(if(record.digest == null) 0 else 1);record.digest?.let { text(it) }
            text(record.highestVersion.toString());data.writeLong(record.lastEvaluationTime)
        }
        val body = output.toByteArray()
        return body + MessageDigest.getInstance("SHA-256").digest(body)
    }
}