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
internal enum class DownloadFailure(val wire: String, val message: String) {
    Storage("storage", "Not enough free space. Free space or raise the download budget, then restart."),
    Budget("budget", "Download budget reached. Raise the budget or remove downloads, then restart."),
    Rejected("rejected", "YouTube Music refused the audio. Restart the download later."),
    Incomplete("incomplete", "The audio did not arrive in full. Check the connection, then restart."),
    Damaged("damaged", "The audio received could not be used. Restart the download."),
    Unavailable("unavailable", "No downloadable audio is available for this song."),
    Commit("commit", "Could not save the download. Check storage, then restart."),
    /** A finished download whose files are gone or no longer match. */
    Integrity("integrity", "This download is damaged or missing. Download it again."),
    Unrecognised("unrecognised", "This download was left in a state Riff does not know. Download it again."),
    Other("other", "Download incomplete. Check storage and connection, then restart.");
}

/** How many runs try a failed download again by themselves. After that it waits for the user. */
internal const val DOWNLOAD_AUTOMATIC_RETRIES = 3
