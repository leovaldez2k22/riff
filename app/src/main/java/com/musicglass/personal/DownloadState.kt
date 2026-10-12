package com.musicglass.personal

/**
 * Where a download is (Q09). [db] is what the songs table holds. Builds before Q09 wrote "downloading"
 * for everything between the start of a transfer and the artwork; it is read as [Transferring].
 */
internal enum class DownloadState(val db: String) {
    /** A name and listening totals only. Nothing was asked for and nothing is playable from this row. */
    Metadata("metadata"),
    Queued("queued"),
    /** Asking the provider where the audio is. */
    Resolving("resolving"),
    Transferring("transferring"),
    /** The audio has arrived and is being rebuilt and checked. */
    Validating("validating"),
    /** The audio is committed and playable; artwork and lyrics are being fetched. */
    Assets("assets"),
    Complete("complete"),
    Paused("paused"),
    Failed("failed"),
    /** A removal that has begun: the files are going and the row returns to [Metadata]. */
    Cancelled("cancelled");

    /** A transfer holds the row. Only that transfer may move it forward. */
    val inFlight get() = this == Resolving || this == Transferring || this == Validating || this == Assets
    /** Wanted and not finished: waiting, or with a transfer. */
    val pending get() = this == Queued || inFlight

    companion object {
        /** Null for a string no build of Riff writes. Such a row is kept and treated as not downloaded. */
        fun decode(text: String?): DownloadState? = when (text) {
            "downloading" -> Transferring
            else -> entries.firstOrNull { it.db == text }
        }
    }
}

/** Why a download stopped, as the user is told. Nothing from a response or an address is kept. */
internal enum class DownloadFailure(val wire: String, private val messageRes: Int) {
    Storage("storage", R.string.not_enough_free_space_free_space_or),
    Budget("budget", R.string.download_budget_reached_raise_the_budget_or),
    Rejected("rejected", R.string.youtube_music_refused_the_audio_restart_the),
    Incomplete("incomplete", R.string.the_audio_did_not_arrive_in_full),
    Damaged("damaged", R.string.the_audio_received_could_not_be_used),
    Unavailable("unavailable", R.string.no_downloadable_audio_is_available_for_this),
    Commit("commit", R.string.could_not_save_the_download_check_storage),
    /** A finished download whose files are gone or no longer match. */
    Integrity("integrity", R.string.this_download_is_damaged_or_missing_download),
    Unrecognised("unrecognised", R.string.this_download_was_left_in_a_state),
    Other("other", R.string.download_incomplete_check_storage_and_connec);
    val message: String get() = string(messageRes)
}

/** How many runs try a failed download again by themselves. After that it waits for the user. */
internal const val DOWNLOAD_AUTOMATIC_RETRIES = 3
