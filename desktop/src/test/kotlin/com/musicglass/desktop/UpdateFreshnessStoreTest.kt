package com.musicglass.desktop

import java.io.IOException
import java.net.URLClassLoader
import java.nio.file.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class UpdateFreshnessStoreTest {
    @get:Rule val temporary=TemporaryFolder()
    private val f=UpdatePolicyFixture
    private fun root()=temporary.newFolder().toPath()
    private suspend fun load(store: UpdateFreshnessStore, scope: UpdateScope=f.scope) = store.transaction(scope) { record, _ -> record }
    private suspend fun put(store: UpdateFreshnessStore, record: UpdateFreshnessRecord) = store.transaction(f.scope) { _, commit -> commit(record) }
    private fun state(root: Path): Path = Files.list(root).use { list -> list.filter { it.toString().endsWith(".state") }.findFirst().orElseThrow() }
    @Test fun absentStateRoundTripsAndReplacesWithScopeSeparation() = runBlocking<Unit> {
        val path=root();val store=FileUpdateFreshnessStore(path)
        assertNull(load(store));val a=f.record(10,"1.0.0");put(store,a);assertEquals(a,load(FileUpdateFreshnessStore(path)))
        val b=f.record(20,"2.0.0");put(store,b);assertEquals(b,load(store))
        assertNull(load(store,f.scope.copy(channel="stable")))
        assertNull(load(store,f.scope.copy(appId="other.product")))
        assertTrue(Files.exists(state(path)))
    }
    @Test fun emptyTruncatedOversizedChecksumAndMalformedStateFailWithoutReset() = runBlocking<Unit> {
        val path=root();val store=FileUpdateFreshnessStore(path);put(store,f.record(2,"0.2.0"));val file=state(path)
        val valid=Files.readAllBytes(file)
        for(bytes in listOf(byteArrayOf(),valid.copyOf(20),ByteArray(1025),valid.copyOf().also{it[12]=(it[12].toInt() xor 1).toByte()})) {
            Files.write(file,bytes)
            assertFailsWith<FreshnessStateUnavailable> { load(store) }
            assertContentEquals(bytes,Files.readAllBytes(file))
        }
    }
    @Test fun orphanInterruptedTemporaryFileDoesNotReplacePriorState() = runBlocking<Unit> {
        val path=root();val store=FileUpdateFreshnessStore(path);val original=f.record(10,"1.0.0");put(store,original)
        Files.write(path.resolve("interrupted.pending"),byteArrayOf(1,2,3))
        assertEquals(original,load(store))
    }
    @Test fun failedAtomicReplacementPreservesPreviousRecordAndDoesNotOffer() = runBlocking<Unit> {
        val path=root();val original=f.record(1,"0.1.0",null);put(FileUpdateFreshnessStore(path),original)
        var sawForcedTemporary=false
        val failed=FileUpdateFreshnessStore(path,UpdateStatePublisher { temp, _ ->
            sawForcedTemporary=Files.size(temp)>32;throw AtomicMoveNotSupportedException("local-test","local-test","simulated")
        })
        val result=UpdateEligibilityPolicy.evaluate(f.manifest(),f.config(),failed,f.clock())
        assertEquals(UpdatePolicyResult.Rejected(UpdatePolicyError.STATE_UNAVAILABLE),result)
        assertTrue(sawForcedTemporary);assertEquals(original,load(FileUpdateFreshnessStore(path)))
        assertEquals(0,Files.list(path).use { it.filter{p->p.toString().endsWith(".pending")}.count() })
    }
    @Test fun accessDeniedReadAndWriteFailClosed() = runBlocking<Unit> {
        val path=root();val store=FileUpdateFreshnessStore(path);put(store,f.record(1,"0.1.0",null));val file=state(path)
        Files.setAttribute(file,"dos:readonly",true)
        try {
            val result=UpdateEligibilityPolicy.evaluate(f.manifest(),f.config(),store,f.clock())
            assertEquals(UpdatePolicyResult.Rejected(UpdatePolicyError.STATE_UNAVAILABLE),result)
            assertEquals(1,load(store)!!.sequence)
        } finally { Files.setAttribute(file,"dos:readonly",false) }
        // A non-regular destination must not be confused with absent state.
        Files.delete(file);Files.createDirectory(file)
        assertFailsWith<FreshnessStateUnavailable> { load(store) }
    }
    @Test fun invalidPersistedRecordNeverPublishes() = runBlocking<Unit> {
        val path=root();val store=FileUpdateFreshnessStore(path)
        for(record in listOf(f.record(0,"0.1.0"),f.record(1,"1.0.0",highest="0.1.0"),f.record(1,"0.1.0",digest="bad"),f.record(1,"0.1.0",time=-1))) {
            assertFailsWith<FreshnessStateUnavailable> { put(store,record) }
            assertNull(load(store))
        }
    }
    @Test fun concurrentStoreInstancesSerializeReadEvaluateAndCommit() = runBlocking<Unit> {
        val path=root()
        coroutineScope { (1L..30L).map { seq -> async(Dispatchers.Default) {
            FileUpdateFreshnessStore(path).transaction(f.scope) { current,commit ->
                delay(2)
                if(current == null || current.sequence < seq) commit(f.record(seq,"1.0.0"))
            }
        } }.awaitAll() }
        assertEquals(30,load(FileUpdateFreshnessStore(path))!!.sequence)
    }
    @Test fun lockWaitCancellationAndTimeoutDoNotWrite() = runBlocking<Unit> {
        val path=root();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val holder=async(Dispatchers.IO) { FileUpdateFreshnessStore(path).transaction(f.scope) { _,_ -> entered.complete(Unit);release.await() } }
        entered.await()
        try {
            val waiter=async { load(FileUpdateFreshnessStore(path)) }
            delay(30);waiter.cancel();assertFailsWith<CancellationException> { waiter.await() }
            assertFailsWith<FreshnessStateUnavailable> { load(FileUpdateFreshnessStore(path,lockTimeoutMs=20)) }
        } finally { release.complete(Unit);holder.await() }
        assertNull(load(FileUpdateFreshnessStore(path)))
    }
    @Test fun ioRunsOutsideCallingUiThread() = runBlocking<Unit> {
        val caller=Thread.currentThread().threadId()
        FileUpdateFreshnessStore(root()).transaction(f.scope) { _,_ -> assertNotEquals(caller,Thread.currentThread().threadId()) }
    }
    private fun spawn(root: Path,ready: Path,release: Path,seq: Long,mode: String): Process {
        val urls=generateSequence(javaClass.classLoader) { it.parent }.filterIsInstance<URLClassLoader>()
            .flatMap { it.urLs.asSequence() }.map { Path.of(it.toURI()).toString() }.toList()
        val classpath=(urls+System.getProperty("java.class.path").split(java.io.File.pathSeparator)).distinct().joinToString(java.io.File.pathSeparator)
        return ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-cp",classpath,
            "com.musicglass.desktop.UpdateFreshnessProcessProbe",root.toString(),ready.toString(),release.toString(),seq.toString(),mode)
            .redirectErrorStream(true).redirectOutput(root.resolve("probe-$seq.log").toFile()).start()
    }
    private suspend fun awaitFile(path: Path, process: Process) = withTimeout(15000) {
        while(!Files.exists(path)) { check(process.isAlive) { "Owned test subprocess exited before barrier; inspect probe log" };delay(10) }
    }
    @Test fun separateProcessesShareTheWholeTransactionLock() = runBlocking<Unit> {
        val path=root();val ready=path.resolve("ready");val release=path.resolve("release")
        val first=spawn(path,ready,release,20,"hold")
        var second: Process?=null
        try {
            awaitFile(ready,first)
            second=spawn(path,path.resolve("ready-low"),path.resolve("unused"),10,"normal")
            delay(100);assertTrue(second.isAlive)
            Files.writeString(release,"go")
            assertTrue(first.waitFor(20,TimeUnit.SECONDS));assertEquals(0,first.exitValue())
            assertTrue(second.waitFor(20,TimeUnit.SECONDS));assertEquals(0,second.exitValue())
            assertEquals(20,load(FileUpdateFreshnessStore(path))!!.sequence)
        } finally {
            if(first.isAlive) {first.destroyForcibly();first.waitFor(10,TimeUnit.SECONDS)}
            second?.let { if(it.isAlive) {it.destroyForcibly();it.waitFor(10,TimeUnit.SECONDS)} }
        }
    }
    @Test fun processTerminationBeforeReplacementPreservesDurableState() = runBlocking<Unit> {
        val path=root();val original=f.record(10,"1.0.0");put(FileUpdateFreshnessStore(path),original)
        val ready=path.resolve("ready");val process=spawn(path,ready,path.resolve("never-release"),20,"interrupt")
        try {
            awaitFile(ready,process)
            process.destroyForcibly();assertTrue(process.waitFor(10,TimeUnit.SECONDS))
            assertEquals(original,load(FileUpdateFreshnessStore(path)))
            assertTrue(Files.list(path).use { it.anyMatch { p -> p.toString().endsWith(".pending") } })
        } finally { if(process.isAlive) {process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS)} }
    }
    @Test fun boundedStateReaderRejectsSelfConsistentInvalidFormatAndScope() = runBlocking<Unit> {
        val path=root();val store=FileUpdateFreshnessStore(path);put(store,f.record(2,"0.2.0"));val file=state(path)
        val valid=Files.readAllBytes(file);val body=valid.copyOfRange(0,valid.size-32)
        val variants=listOf(body.copyOf().also { it[0]=0 },body.copyOf().also { it[8]=0x7f },
            body.copyOf().also { it[10]='X'.code.toByte() },body+byteArrayOf(0),body.copyOf(body.size-1))
        for(bad in variants) {
            val bytes=bad+java.security.MessageDigest.getInstance("SHA-256").digest(bad)
            Files.write(file,bytes)
            assertFailsWith<FreshnessStateUnavailable> { load(store) }
            assertContentEquals(bytes,Files.readAllBytes(file))
        }
    }
    @Test fun mandatoryWindowsReadLockIsReportedAsUnavailable() = runBlocking<Unit> {
        val path=root();val store=FileUpdateFreshnessStore(path);put(store,f.record(2,"0.2.0"));val file=state(path)
        java.nio.channels.FileChannel.open(file,StandardOpenOption.READ,StandardOpenOption.WRITE).use { channel ->
            channel.lock().use { assertFailsWith<FreshnessStateUnavailable> { load(store) } }
        }
        assertEquals(2L,load(store)!!.sequence)
    }
    @Test fun separateProcessCannotAcquireLockWhileParentTransactionIsOpen() = runBlocking<Unit> {
        val path=root();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val holder=async(Dispatchers.IO) {
            FileUpdateFreshnessStore(path).transaction(f.scope) { _,_ -> entered.complete(Unit);release.await() }
        }
        entered.await()
        var child: Process?=null
        try {
            child=spawn(path,path.resolve("unused-ready"),path.resolve("unused-release"),99,"timeout")
            assertTrue(child.waitFor(20,TimeUnit.SECONDS));assertEquals(0,child.exitValue())
            assertEquals("LOCK_TIMEOUT",Files.readString(path.resolve("probe-99.log")).trim())
        } finally {
            if(child?.isAlive == true) { child.destroyForcibly();child.waitFor(10,TimeUnit.SECONDS) }
            release.complete(Unit);holder.await()
        }
        assertNull(load(FileUpdateFreshnessStore(path)))
    }
}
