package com.musicglass.desktop

import java.io.InputStream

/** Generated from the same Gradle value as nativeDistributions.packageVersion. No Android version fallback. */
internal object DesktopBuildIdentity {
    fun version(): UpdateVersion? = try {
        javaClass.getResourceAsStream("/META-INF/riff-desktop.properties")?.use { read(it) }
    } catch (_: java.io.IOException) { null }
      catch (_: SecurityException) { null }
    internal fun read(input: InputStream): UpdateVersion? {
        val bytes=input.readNBytes(65)
        if(bytes.size > 64 || bytes.any { it.toInt() !in 10..126 }) return null
        val text=bytes.toString(Charsets.US_ASCII)
        if(!text.startsWith("version=") || !text.endsWith('\n')) return null
        val version=UpdateVersion.parse(text.removePrefix("version=").removeSuffix("\n")) ?: return null
        return version.takeIf { text == "version=$it\n" }
    }
}