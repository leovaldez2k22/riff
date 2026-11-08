package com.musicglass.personal

import java.io.InputStream
import java.util.Locale

/*
 * Feature 11 (Riff+, owner 8 November): the headphone profiles AutoEq publishes (MIT, Copyright (c) 2018-2022 Jaakko
 * Pasanen; each measured by the source named on its row), bundled so a listener can search for their headphones with
 * no file and no network. The list was fitted to Riff's seven bands off the phone by the same method as [fitAutoEq]
 * (scripts/autoeq), so each row is only its seven gains and its fit error: about 146 KB compressed in Riff+.
 * See NOTICE.md for the licence and the snapshot.
 */

/** One bundled profile: [gains] in tenths of a dB for the seven bands, [errorTenths] the fit error in tenths. */
internal class AutoEqEntry(val name: String, val source: String, val rig: String, private val gains: ShortArray, private val errorTenths: Short) {
    /** [name] lower-cased with punctuation as spaces, for searching. */
    internal val key = foldName(name)
    val errorDb get() = errorTenths / 10f
    /** The name Riff keeps for the applied profile: the headphones and who measured them. */
    val label get() = "$name · $source"
    fun fit() = AutoEqFit(label, gains.map { it / 10f }, errorDb)
}

private val nonWord = Regex("[^\\p{L}\\p{N}]+")
internal fun foldName(text: String) = text.lowercase(Locale.ROOT).replace(nonWord, " ").trim()

internal object AutoEqLibrary {
    const val ASSET = "autoeq/profiles.tsv"
    /** At most this many rows are read from the asset, and this many matches shown. */
    private const val MAX_ROWS = 20_000
    const val SHOWN = 30
    /** Sources AutoEq itself ranks first, in its order; the rest follow. */
    private val preferred = listOf("oratory1990", "crinacle", "Innerfidelity", "Rtings", "Headphone.com Legacy")

    @Volatile private var cached: List<AutoEqEntry>? = null

    /** The bundled list, read once; call off the main thread. Empty when the asset is missing or unreadable. */
    fun load(context: android.content.Context): List<AutoEqEntry> = cached ?: synchronized(this) {
        cached ?: runCatching { context.assets.open(ASSET).use(::read) }.getOrDefault(emptyList()).also { cached = it }
    }

    /** Reads the bundle: `#s` lines name each source and rig, every other non-comment line is one headphone. */
    internal fun read(input: InputStream): List<AutoEqEntry> {
        val sources = HashMap<Int, Pair<String, String>>()
        val out = ArrayList<AutoEqEntry>(9_000)
        input.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (out.size >= MAX_ROWS) break
                if (line.startsWith("#s\t")) {
                    val p = line.split('\t'); val id = p.getOrNull(1)?.toIntOrNull() ?: continue
                    sources[id] = (p.getOrNull(2).orEmpty()) to (p.getOrNull(3).orEmpty()); continue
                }
                if (line.isBlank() || line.startsWith("#")) continue
                val p = line.split('\t'); if (p.size != 4) continue
                val (source, rig) = sources[p[1].toIntOrNull() ?: continue] ?: continue
                val gains = p[2].split(' ').mapNotNull { it.toIntOrNull()?.takeIf { g -> g in -120..120 }?.toShort() }
                val error = p[3].toIntOrNull()?.takeIf { it in 0..400 } ?: continue
                if (gains.size != EqLayout.manualFrequencies.size || p[0].isBlank()) continue
                out += AutoEqEntry(p[0].take(120), source, rig, gains.toShortArray(), error.toShort())
            }
        }
        return out
    }

    /**
     * The profiles whose name holds every word of [query], best first: the name itself, a name that starts with it, then the
     * better-known measurers, then the closer fit. Nothing for a blank query.
     */
    fun search(entries: List<AutoEqEntry>, query: String, limit: Int = SHOWN): List<AutoEqEntry> {
        val words = foldName(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val wanted = words.joinToString(" ")
        return entries.asSequence().map { it to it.key }.filter { (_, name) -> words.all { it in name } }
            .sortedWith(compareBy<Pair<AutoEqEntry, String>>({ (_, name) -> when { name == wanted -> 0; name.startsWith(wanted) -> 1; else -> 2 } },
                { (e, _) -> preferred.indexOf(e.source).let { if (it < 0) preferred.size else it } },
                { (e, _) -> e.errorDb }, { (e, _) -> e.name }))
            .take(limit).map { it.first }.toList()
    }
}
