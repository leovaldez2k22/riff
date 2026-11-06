package com.musicglass.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*

/** TEST-ONLY subprocess entry point; never included in production resources or Main. */
internal object UpdateFreshnessProcessProbe {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val root=Path.of(args[0]);val ready=Path.of(args[1]);val release=Path.of(args[2]);val sequence=args[3].toLong()
        val mode=args[4]
        suspend fun barrier() {
            Files.writeString(ready,"ready")
            withTimeout(20000) { while(!Files.exists(release)) delay(10) }
        }
        val publisher=if(mode=="interrupt") UpdateStatePublisher { temp,dest ->
            runBlocking { barrier() }
            WindowsUpdateStatePublisher.replace(temp,dest)
        } else WindowsUpdateStatePublisher
        if(mode=="timeout") {
            try {
                FileUpdateFreshnessStore(root,lockTimeoutMs=100).transaction(UpdatePolicyFixture.scope) { _,_ ->
                    error("Owned subprocess unexpectedly acquired a held cross-process lock")
                }
                error("Missing lock timeout")
            } catch (_: FreshnessStateUnavailable) { println("LOCK_TIMEOUT") }
            return@runBlocking
        }
        FileUpdateFreshnessStore(root,publisher).transaction(UpdatePolicyFixture.scope) { current, commit ->
            if(mode=="hold") barrier()
            if(current == null || current.sequence < sequence)
                commit(UpdatePolicyFixture.record(sequence,"$sequence.0.0"))
        }
        println("DONE")
    }
}