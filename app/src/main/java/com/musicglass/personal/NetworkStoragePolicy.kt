package com.musicglass.personal

/**
 * When a download may start and continue (Q09): space on the device, the app's own budget, and for
 * Smart Downloads the network and the battery. Pure rules; the numbers come from the caller.
 */
internal object NetworkStoragePolicy {
    /** Artwork is refused above 8 MB and lyrics above 1 MB; this is kept free for them. */
    const val ASSET_ALLOWANCE = 9_000_000L
    /** One song is never larger than this. */
    const val SONG_LIMIT = 512L * 1024 * 1024
    private const val GIB = 1_073_741_824L

    /** Left free on the device whatever the budget says, "Unlimited" included: 1 GB or a tenth, whichever is more. */
    fun deviceReserve(totalBytes: Long) = maxOf(GIB, totalBytes / 10)

    enum class Verdict { Fits, OverBudget, DeviceFull, Invalid }

    /**
     * Before a transfer. The budget counts what the song will occupy when done: audio and assets. The
     * device must also hold what exists only while it is made: the source as received next to the rebuilt copy.
     */
    fun admit(usedBytes: Long, expectedBytes: Long, budgetBytes: Long, availableBytes: Long, totalBytes: Long): Verdict {
        if (usedBytes < 0 || expectedBytes <= 0 || expectedBytes > SONG_LIMIT) return Verdict.Invalid
        val kept = expectedBytes + ASSET_ALLOWANCE
        if (kept > budgetBytes || usedBytes > budgetBytes - kept) return Verdict.OverBudget
        return if (roomFor(2 * expectedBytes + ASSET_ALLOWANCE, availableBytes, totalBytes)) Verdict.Fits else Verdict.DeviceFull
    }

    /** While the source arrives: the rest of it, the rebuilt copy and the assets must still fit above the reserve. */
    fun transferMayContinue(receivedBytes: Long, expectedBytes: Long, availableBytes: Long, totalBytes: Long): Boolean =
        roomFor((expectedBytes - receivedBytes).coerceAtLeast(0) + maxOf(expectedBytes, receivedBytes) + ASSET_ALLOWANCE, availableBytes, totalBytes)

    /** Before the rebuild: a copy the size of the source, and the assets. */
    fun rebuildMayStart(sourceBytes: Long, availableBytes: Long, totalBytes: Long): Boolean =
        roomFor(sourceBytes + ASSET_ALLOWANCE, availableBytes, totalBytes)

    private fun roomFor(neededBytes: Long, availableBytes: Long, totalBytes: Long) =
        neededBytes >= 0 && neededBytes <= availableBytes && availableBytes - neededBytes >= deviceReserve(totalBytes)

    /**
     * Smart Downloads only. A download the user asked for by hand runs on any validated or unvalidated
     * connection and at any battery level; it is the user's request and Android's job constraints do not apply to it.
     */
    fun automaticAllowed(allowMobileData: Boolean, network: NetworkConditions, batteryPercent: Int): Boolean =
        batteryPercent !in 0..19 && smartDownloadNetworkAllowed(allowMobileData, network.wifi, network.unmetered, network.validated)
}
