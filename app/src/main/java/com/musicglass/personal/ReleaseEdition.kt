package com.musicglass.personal

/** Exact public asset names; the two APKs share package, version and signing identity. */
internal enum class ReleaseEdition(private val prefix: String, val marker: String, val title: String) {
    Riff("riff", "riff", "Riff"), Plus("riff-plus", "plus", "Riff+");
    val other: ReleaseEdition get() = if (this == Riff) Plus else Riff
    fun assetName(version: ReleaseVersion): String = "$prefix-$version.apk"
    companion object {
        val current: ReleaseEdition get() = if (Edition.plus) Plus else Riff
        /** Feature 3: the manifest marker an APK carries (com.musicglass.personal.EDITION), which the signer cannot tell apart. */
        fun ofMarker(marker: String?): ReleaseEdition? = entries.firstOrNull { it.marker == marker }
    }
}

/**
 * One APK of a release. [url] is the feed's download address, kept only when it is the expected GitHub release-asset
 * path for this name; [sha256] is the feed's digest when GitHub lists one. Without [url] the release page is the way.
 */
internal data class ReleaseAsset(val name: String, val bytes: Long, val url: String? = null, val sha256: String? = null)
internal data class EditionSwitch(val release: PublishedRelease, val edition: ReleaseEdition, val bytes: Long)
