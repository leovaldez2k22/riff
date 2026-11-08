package com.musicglass.personal

import java.io.File

// The shapes of a local-data file and of its import and export, apart from LocalDataTransfer's reading and writing.

/** The only schema this build writes. A file with a higher number is refused whole. */
internal const val LOCAL_DATA_SCHEMA = 1
/** Ceiling for a file, written or read. */
internal const val LOCAL_DATA_MAX_BYTES = 128L * 1024 * 1024
internal const val LOCAL_DATA_MAX_LINE = 16 * 1024
/** Most records of one list that are read from one section. The stores keep their own, lower limits. */
internal const val LOCAL_DATA_LIST_CAP = 2_000

internal enum class OwnerKind(val word: String) { Device("device"), Guest("guest"), Provider("provider") }

/** How much of each kind of data. The same shape counts what is kept, what a file holds and what was added. */
internal data class SectionCounts(val settings: Int = 0, val hidden: Int = 0, val saved: Int = 0, val recents: Int = 0,
    val searches: Int = 0, val plays: Int = 0, val downloads: Int = 0, val pins: Int = 0,
    /** 0.30.0: the never-play list, and playlists on this phone with their songs. */
    val neverPlay: Int = 0, val phoneLists: Int = 0, val phoneSongs: Int = 0) {
    val total get() = settings + hidden + saved + recents + searches + plays + downloads + pins + neverPlay + phoneLists + phoneSongs
    operator fun plus(o: SectionCounts) = SectionCounts(settings + o.settings, hidden + o.hidden, saved + o.saved, recents + o.recents,
        searches + o.searches, plays + o.plays, downloads + o.downloads, pins + o.pins,
        neverPlay + o.neverPlay, phoneLists + o.phoneLists, phoneSongs + o.phoneSongs)
}

/** One owner whose data can be put in a file. [key] is where the stores keep it; it is not written to the file. */
internal data class ExportOwner(val key: String, val kind: OwnerKind, val counts: SectionCounts)

internal sealed interface ExportOutcome {
    data class Done(val records: Int, val bytes: Long) : ExportOutcome
    /** Stopped at the user's request. The chosen document holds nothing usable. */
    data object Cancelled : ExportOutcome
    /** The file would pass the ceiling. Fewer owners must be chosen. */
    data object TooLarge : ExportOutcome
    /** [kind] is a fixed word or a class name, never content. */
    data class Failed(val kind: String) : ExportOutcome
}

internal enum class RejectReason { NotRiff, Newer, Truncated, TooLarge, Unreadable, Storage }

/** Where a section of a file would go on this phone. */
internal enum class Destination {
    /** The device's own data, or signed-out listening: the same owner on every phone. */
    Direct,
    /** The account signed in here is the one the section was exported from. */
    Account,
    /** Another account's data, or nobody verified is signed in. Imported only if the user says so. */
    Other
}

internal data class ImportSection(val index: Int, val kind: OwnerKind, val counts: SectionCounts, val destination: Destination)

/** A file that was read through and can be imported. [file] is a private copy, removed by [LocalDataTransfer.discard]. */
internal data class ImportPlan(val file: File, val exportedAt: Long, val sections: List<ImportSection>, val malformed: Int,
    val overLimit: Int, /** A verified account is signed in, so another account's section can be added to it. */ val canAdopt: Boolean) {
    val total get() = sections.sumOf { it.counts.total }
}

internal sealed interface ImportOpen {
    data class Ready(val plan: ImportPlan) : ImportOpen
    data class Rejected(val reason: RejectReason) : ImportOpen
    data object Cancelled : ImportOpen
}

/**
 * What an import did. [added] is new on this phone; [present] was already here; [malformed] records
 * could not be read; [overLimit] did not fit a limit; [expired] plays are older than the history
 * period; [settingsKept] settings were left as they are; [sectionsLeft] sections were not imported;
 * [notStored] names the kinds of data that could not be written.
 */
internal data class ImportReport(val added: SectionCounts = SectionCounts(), val present: Int = 0, val malformed: Int = 0,
    val overLimit: Int = 0, val expired: Int = 0, val settingsKept: Int = 0, val sectionsLeft: Int = 0,
    val notStored: List<String> = emptyList())

internal sealed interface ImportOutcome {
    data class Finished(val report: ImportReport) : ImportOutcome
    /** Stopped before anything was written. */
    data object Cancelled : ImportOutcome
    /** The file was refused at the second reading. Nothing was changed. */
    data class Rejected(val reason: RejectReason) : ImportOutcome
    /** Nothing was changed. [kind] is a fixed word or a class name, never content. */
    data class Failed(val kind: String) : ImportOutcome
}

/** The owner active now. [digests] is empty unless a verified account is signed in. */
internal data class CurrentOwner(val key: String, val digests: Set<String> = emptySet()) {
    val verified get() = digests.isNotEmpty() && key != UNVERIFIED_SCOPE_KEY && key != GUEST_SCOPE_KEY
}

internal sealed interface DataRecord {
    data class Setting(val name: String, val value: Any) : DataRecord
    data class Hidden(val entry: NotInterestedEntry) : DataRecord
    /** [list] is the name the collection store uses. */
    data class Saved(val list: String, val track: ProviderTrack) : DataRecord
    data class Search(val query: String) : DataRecord
    data class Play(val entry: HistoryEntry) : DataRecord
    data class Download(val track: ProviderTrack) : DataRecord
    data class Pin(val card: ProviderHomeCard) : DataRecord // A speed-dial pin: the device's, like hidden items.
    /** 0.30.0: an owner's never-play entry. */
    data class NeverPlay(val entry: NeverPlayEntry) : DataRecord
    /** 0.30.0: a playlist on this phone, then each of its songs in order, in records of their own (a line is short). */
    data class PhoneList(val id: String, val name: String, val createdAt: Long) : DataRecord
    data class PhoneSong(val listId: String, val track: ProviderTrack) : DataRecord
}

