package com.musicglass.personal

import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Automix stage 2 on the owner's test set (engine report 6.1): Riff's beat tracker on each song's first 30 s and
 * last 45 s, decoded by `scripts/automix/extract_windows.py`. Runs only when RIFF_AUTOMIX_PCM names that folder;
 * the songs stay on the owner's PC, so elsewhere it does nothing (the lane allows no skipped tests). Writes
 * `riff-beats.json` there for `scripts/automix/score_beats.py`.
 */
class AutomixSetEvaluation {
    @Test fun trackTheOwnersSet() {
        val folder = System.getenv("RIFF_AUTOMIX_PCM")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val windows = Regex("\"n\": (\\d+),[^}]*?\"tailStartMs\": (\\d+)").findAll(File(folder, "windows.json").readText())
            .map { it.groupValues[1].toInt() to it.groupValues[2].toLong() }.toList()
        val rows = windows.flatMap { (n, tailStart) ->
            listOf("head" to 0L, "tail" to tailStart).map { (part, startMs) ->
                val bytes = File(folder, "$n.$part.pcm").readBytes()
                val pcm = ShortArray(bytes.size / 2).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
                val started = System.nanoTime()
                // Fed in decoder-sized pieces, as SmartTransition feeds it.
                val grid = OnsetBuilder(48_000, 2, startMs).apply { var i = 0; while (i < pcm.size) { val end = minOf(pcm.size, i + 8_192); add(pcm.copyOfRange(i, end)); i = end } }.beats()
                val ms = (System.nanoTime() - started) / 1_000_000
                if (grid == null) "{\"n\":$n,\"part\":\"$part\",\"ms\":$ms}"
                else "{\"n\":$n,\"part\":\"$part\",\"ms\":$ms,\"bpm\":${grid.bpm},\"confidence\":${grid.confidence}," +
                    "\"downbeatConfidence\":${grid.downbeatConfidence},\"beats\":${grid.beatsMs.joinToString(",", "[", "]")}," +
                    "\"downbeats\":${grid.downbeatsMs.joinToString(",", "[", "]")}}"
            }
        }
        File(folder, "riff-beats.json").writeText(rows.joinToString(",\n", "[\n", "\n]\n"))
    }
}
