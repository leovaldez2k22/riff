package com.musicglass.personal

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import java.io.File

/**
 * Why Riff's processes ended (Q15A). The diagnostic ring records what the app did; this records how each
 * process died, as Android reports it afterwards, and what was thrown when one fell over on its own.
 *
 * Kept: which process, the reason as a word, the importance it had, the hour, the version code. Dropped: the
 * system's free-text description and any trace file. A crash keeps exception class names and the app's own
 * stack frames, never a message. Both live in their own small files, apart from the 200-line ring, so a crash
 * loop that fills the ring cannot push them out, and a loop of the same crash adds to a count instead of lines.
 */

/** Which of the app's processes: the player and screens, or the stream-code solver. */
internal enum class ProcessRole(val word: String) { Main("main"), Solver("solver"), Other("other") }

internal fun processRole(processName: String?, packageName: String): ProcessRole = when (processName) {
    packageName -> ProcessRole.Main
    "$packageName:solver" -> ProcessRole.Solver
    else -> ProcessRole.Other
}

/** One exit, reduced. [hour] is the start of the hour it happened in, in epoch milliseconds. */
internal data class ExitRecord(val hour: Long, val role: ProcessRole, val reason: String, val importance: Int, val versionCode: Int) {
    /** The stored and reported form; it passes [DiagnosticEvent.parse] unchanged. */
    val line get() = "process_exit at=$hour process=${role.word} reason=$reason importance=$importance code=$versionCode"
    /** A failure is an exit the user did not ask for and Android did not do in the ordinary course of things. */
    val failure get() = reason in FAILURE_REASONS
}

/** Reasons shown as a failure. A stop from Settings, a swipe from recent apps (Android files it under other)
 *  and an update are not. */
internal val FAILURE_REASONS = setOf("crash", "crash_native", "anr", "low_memory", "excessive_resource_usage", "init_failure", "dependency_died")

/** The package the app's code is in. Lite and benchmark builds have another application id, not another package. */
internal val CODE_PACKAGE: String = ExitStore::class.java.name.substringBeforeLast('.')

internal const val HOUR_MS = 60 * 60 * 1000L
internal fun coarseHour(millis: Long) = if (millis <= 0) 0L else millis - millis % HOUR_MS

/** Most exit records kept, and most crash records per process. */
internal const val MAX_EXIT_RECORDS = 12
internal const val MAX_CRASH_RECORDS = 4
/** Largest a record file may be on disk; anything larger is read as empty. */
internal const val MAX_RECORD_FILE_BYTES = 8 * 1024

/** A raw exit as the platform gives it, before reduction; what a test scripts. */
internal data class PlatformExit(val timestamp: Long, val reason: Int, val importance: Int, val processName: String?)

/**
 * Reduces [exits] newer than [lastSeen], oldest first. The description and trace the platform also offers are
 * not even read. [versionCode] is the version that was running when they happened.
 */
internal fun reduceExits(exits: List<PlatformExit>, lastSeen: Long, packageName: String, versionCode: Int): List<ExitRecord> =
    exits.filter { it.timestamp > lastSeen }.sortedBy { it.timestamp }.map {
        ExitRecord(coarseHour(it.timestamp), processRole(it.processName, packageName), exitReasonName(it.reason), it.importance, versionCode)
    }

private val exitLine = Regex("process_exit at=([0-9]{1,15}) process=(main|solver|other) reason=([a-z][a-z0-9_]{0,31}) importance=(-?[0-9]{1,6}) code=([0-9]{1,9})")

internal fun parseExitRecord(line: String): ExitRecord? = exitLine.matchEntire(line)?.groupValues?.let { g ->
    ExitRecord(g[1].toLong(), ProcessRole.entries.first { it.word == g[2] }, g[3], g[4].toInt(), g[5].toInt())
}

/** One uncaught exception: class names outermost first, the app's own frames, how often, the hour of the last. */
internal data class CrashRecord(val hour: Long, val role: ProcessRole, val causes: List<String>, val frames: List<String>, val count: Int, val versionCode: Int) {
    val signature get() = causes.joinToString(">") + "|" + frames.joinToString(",")
    /** The event form; frames go on lines of their own, see [frameLines]. */
    val line get() = "uncaught at=$hour process=${role.word} causes=${causes.joinToString(">")} count=$count code=$versionCode"
    val frameLines get() = frames.map { "  at $it" }
    val stored get() = "$line frames=${frames.joinToString(",")}"
}

private const val MAX_CAUSES = 5
private const val MAX_FRAMES = 6
private val causeName = Regex("[A-Za-z_$][A-Za-z0-9_$]{0,47}")
/** A frame as kept: the class inside the app's package, a method and a line, from what the compiler wrote. */
internal val appFrame = Regex("[A-Za-z_$][A-Za-z0-9_$.]{0,95}\\.[A-Za-z_$<][A-Za-z0-9_$<>-]{0,63}:[0-9]{1,6}")
private val crashLine = Regex("uncaught at=([0-9]{1,15}) process=(main|solver|other) causes=([A-Za-z0-9_$>]{1,250}) count=([0-9]{1,6}) code=([0-9]{1,9}) frames=(.{0,1200})")

/**
 * [error] reduced to class names and the app's own frames. A message is never read. Frames come from the
 * deepest cause that has any in the app, which is where it went wrong; library and platform frames are left
 * out, so a frame cannot carry anything but this app's code positions.
 */
internal fun reduceCrash(error: Throwable, role: ProcessRole, now: Long, versionCode: Int, appPackage: String): CrashRecord {
    val chain = generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }.take(MAX_CAUSES).toList()
    val causes = chain.map { (it.javaClass.simpleName.ifEmpty { it.javaClass.name.substringAfterLast('.') }).takeIf(causeName::matches) ?: "Unnamed" }
    val prefix = "$appPackage."
    fun framesOf(t: Throwable) = t.stackTrace.filter { it.className.startsWith(prefix) && it.lineNumber >= 0 }
        .map { "${it.className.removePrefix(prefix)}.${it.methodName}:${it.lineNumber}" }.filter(appFrame::matches).take(MAX_FRAMES)
    val frames = chain.asReversed().map(::framesOf).firstOrNull { it.isNotEmpty() }.orEmpty()
    return CrashRecord(coarseHour(now), role, causes, frames, 1, versionCode)
}

internal fun parseCrashRecord(line: String): CrashRecord? {
    val g = crashLine.matchEntire(line)?.groupValues ?: return null
    val causes = g[3].split('>')
    if (causes.size > MAX_CAUSES || !causes.all(causeName::matches)) return null
    val frames = if (g[6].isEmpty()) emptyList() else g[6].split(',')
    if (frames.size > MAX_FRAMES || !frames.all(appFrame::matches)) return null
    return CrashRecord(g[1].toLong(), ProcessRole.entries.first { it.word == g[2] }, causes, frames, g[4].toInt(), g[5].toInt())
}

/** [records] with [crash] added: the same crash again raises the count and moves to the end; at most [MAX_CRASH_RECORDS]. */
internal fun addCrash(records: List<CrashRecord>, crash: CrashRecord): List<CrashRecord> {
    val same = records.firstOrNull { it.signature == crash.signature }
    val merged = same?.copy(hour = crash.hour, count = (same.count + 1).coerceAtMost(999_999), versionCode = crash.versionCode) ?: crash
    return (records.filter { it !== same } + merged).takeLast(MAX_CRASH_RECORDS)
}

/**
 * The files. Exits are written by the main process only, when it starts; each process writes only its own
 * crash file, from its own exception handler, so no two processes write the same file.
 */
internal class ExitStore(private val directory: File) {
    private val exits get() = File(directory, "exits.log")
    private fun crashes(role: ProcessRole) = File(directory, "crash-${role.word}.log")

    fun exits(): List<ExitRecord> = read(exits).mapNotNull(::parseExitRecord).takeLast(MAX_EXIT_RECORDS)
    fun crashes(): List<CrashRecord> = ProcessRole.entries.flatMap { read(crashes(it)).mapNotNull(::parseCrashRecord) }.sortedBy { it.hour }
    private fun crashesOf(role: ProcessRole) = read(crashes(role)).mapNotNull(::parseCrashRecord)

    fun addExits(records: List<ExitRecord>) {
        if (records.isEmpty()) return
        write(exits, (exits() + records).takeLast(MAX_EXIT_RECORDS).map { it.line })
    }

    fun addCrash(record: CrashRecord) = write(crashes(record.role), addCrash(crashesOf(record.role), record).map { it.stored })

    fun clear() { exits.delete(); ProcessRole.entries.forEach { crashes(it).delete() } }

    private fun read(file: File): List<String> = runCatching {
        if (!file.isFile || file.length() > MAX_RECORD_FILE_BYTES) emptyList() else file.readLines(Charsets.UTF_8)
    }.getOrDefault(emptyList())

    /** Written whole to a temporary file and renamed, so a death halfway leaves the old file. */
    private fun write(file: File, lines: List<String>) {
        directory.mkdirs()
        val text = lines.joinToString("\n", postfix = "\n").takeIf { it.length <= MAX_RECORD_FILE_BYTES } ?: return
        val temporary = File(directory, file.name + ".tmp")
        temporary.writeText(text, Charsets.UTF_8)
        if (!temporary.renameTo(file)) { file.delete(); temporary.renameTo(file) }
    }

    companion object {
        fun of(context: Context) = ExitStore(File(context.applicationContext.noBackupFilesDir, "diagnostics"))
    }
}

private const val EXIT_RECORD_PREFS = "exit_records"
private const val SEEN = "seen_at"
private const val LAST_CODE = "last_version_code"

/**
 * Reads the exits Android recorded since the last start and stores them reduced. Main process, off the main
 * thread, once per start. The version they ran is the one the previous start recorded: after an update, the
 * exits read at the first start belong to the version before it.
 */
internal fun recordProcessExits(context: Context, read: () -> List<PlatformExit> = { platformExits(context) }) {
    val prefs = context.getSharedPreferences(EXIT_RECORD_PREFS, Context.MODE_PRIVATE)
    val previousCode = prefs.getInt(LAST_CODE, BuildConfig.VERSION_CODE)
    prefs.edit().putInt(LAST_CODE, BuildConfig.VERSION_CODE).apply()
    val exits = runCatching(read).getOrElse { return }
    val records = reduceExits(exits, prefs.getLong(SEEN, 0L), context.packageName, previousCode)
    if (records.isEmpty()) return
    ExitStore.of(context).addExits(records)
    prefs.edit().putLong(SEEN, exits.maxOf { it.timestamp }).apply()
}

/** The platform's records for this package, main and solver both. Only these four fields are read. */
private fun platformExits(context: Context): List<PlatformExit> =
    context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 16)
        .map { PlatformExit(it.timestamp, it.reason, it.importance, it.processName) }

/**
 * Records an uncaught exception, then hands it on unchanged to the handler that was there before, which ends
 * the process as it would have. Recording is one small file write; if it fails, nothing else changes.
 */
internal class CrashRecorder(
    private val store: ExitStore,
    private val role: ProcessRole,
    private val appPackage: String,
    private val versionCode: Int,
    private val clock: () -> Long,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try { store.addCrash(reduceCrash(error, role, clock(), versionCode, appPackage)) } catch (_: Throwable) {}
        // Android always has a handler that ends the process. Without one (a plain JVM, as in unit tests) do
        // what the JVM does by default: print the error and let only the thread end.
        if (previous != null) previous.uncaughtException(thread, error)
        else error.printStackTrace()
    }

    companion object {
        /** Installs the recorder for this process once; a second call leaves the first in place. */
        fun install(context: Context, role: ProcessRole) {
            val current = Thread.getDefaultUncaughtExceptionHandler()
            if (current is CrashRecorder) return
            Thread.setDefaultUncaughtExceptionHandler(CrashRecorder(ExitStore.of(context), role,
                CODE_PACKAGE, BuildConfig.VERSION_CODE, System::currentTimeMillis, current))
        }
    }
}
