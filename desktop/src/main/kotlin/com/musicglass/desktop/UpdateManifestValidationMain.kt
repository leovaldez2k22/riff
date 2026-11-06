package com.musicglass.desktop

import java.security.MessageDigest
import kotlin.system.exitProcess

/** Offline maintainer entry point. No UI, trust, manifest construction, state or network. */
internal object UpdateManifestValidationMain {
    @JvmStatic fun main(args: Array<String>) {
        if(args.isNotEmpty()) { println("RIFF_UPDATE_SYNTAX V1 CONFIGURATION_ERROR");exitProcess(2) }
        val bytes=try { System.`in`.readNBytes(VerifiedUpdatePayload.MAX_PAYLOAD_BYTES+1) }
            catch(_: java.io.IOException) { println("RIFF_UPDATE_SYNTAX V1 INVALID_PAYLOAD");exitProcess(2) }
        val error=ValidatedUpdateManifest.validateForSigning(bytes)
        if(error != null) { println("RIFF_UPDATE_SYNTAX V1 $error");exitProcess(2) }
        val digest=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        println("RIFF_UPDATE_SYNTAX V1 VALID $digest")
    }
}