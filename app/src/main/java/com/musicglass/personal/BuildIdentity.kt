package com.musicglass.personal

/** Identifies source content without a compiled timestamp or personal machine information. */
internal data class BuildIdentity(
    val revision: String,
    val sourceState: String,
    val sourceDigest: String,
    val variant: String,
    val versionCode: Int,
) {
    val shortId: String
        get() = "${if (revision == "local-unknown") revision else revision.take(12)} · $sourceState · ${sourceDigest.take(12)} · $variant"

    val diagnosticLine: String
        get() = "Build revision=$revision state=$sourceState source=$sourceDigest variant=$variant code=$versionCode"

    companion object {
        fun current() = BuildIdentity(
            BuildConfig.SOURCE_REVISION, BuildConfig.SOURCE_STATE,
            BuildConfig.SOURCE_DIGEST, BuildConfig.BUILD_TYPE, BuildConfig.VERSION_CODE,
        )
    }
}
