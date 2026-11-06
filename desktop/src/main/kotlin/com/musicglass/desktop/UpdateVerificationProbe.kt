package com.musicglass.desktop

import java.io.File
import kotlin.system.exitProcess

/** Offline diagnostics only. Supplied test trust is never installed into production update configuration. */
internal object UpdateVerificationProbe {
    fun run(args: Array<String>) {
        if (args.size != 4 || !args[1].startsWith("test-")) {
            println("RIFF_UPDATE_VERIFY CONFIGURATION_ERROR"); exitProcess(2)
        }
        val result = try {
            val der = File(args[2]).inputStream().use { it.readNBytes(45) }
            val registry = UpdateTrustRegistry(listOf(UpdateTrustKey(args[1], der,
                setOf("com.musicglass.desktop"), setOf("preview"), setOf("windows-x64"))), allowTestKeys = true)
            File(args[3]).inputStream().use { VerifiedUpdatePayload.verify(it, registry) }
        } catch (_: Exception) { UpdateVerificationResult.Rejected(UpdateVerificationError.CONFIGURATION_ERROR) }
        when (result) {
            is UpdateVerificationResult.Verified -> println("RIFF_UPDATE_VERIFY VERIFIED_OPAQUE_PAYLOAD")
            is UpdateVerificationResult.Rejected -> { println("RIFF_UPDATE_VERIFY ${result.code}"); exitProcess(2) }
        }
    }
}
