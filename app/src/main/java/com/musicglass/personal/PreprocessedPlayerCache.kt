package com.musicglass.personal

import java.io.File

/**
 * The solver's copy on disk of YouTube's player script as the pinned library prepares it for QuickJS. Without it a
 * solver started again (after ten idle minutes, or after Android stopped the app) downloaded and prepared the
 * script once more before the first song could play, which made that song slow to start.
 *
 * Only the `:solver` process uses it ([SolverService]). It holds YouTube's public player code and nothing about the
 * user: no address, account or song. Keys are the library's own (a SHA-1 of the player address and its solver
 * bundle), so a new player or a new library version gets a new file; the oldest files beyond [maxEntries] are
 * removed. It lives in the app's cache folder, which Android may clear at any time; the library then prepares
 * the script again.
 */
internal class PreprocessedPlayerCache(private val dir: File, private val maxEntries: Int = 3,
    private val maxChars: Int = 8 * 1024 * 1024, private val clock: () -> Long = System::currentTimeMillis) {
    private val keyShape = Regex("[0-9a-f]{40}")

    @Synchronized fun read(key: String): String? {
        val file = fileFor(key)?.takeIf { it.isFile } ?: return null
        // A UTF-8 character is at most four bytes: a larger file cannot be a script the library would keep.
        if (file.length() > maxChars * 4L) { file.delete(); return null }
        val text = runCatching { file.readText() }.getOrNull()
        if (text.isNullOrEmpty() || text.length > maxChars) { file.delete(); return null }
        file.setLastModified(clock())
        return text
    }

    /** No [value] (null or empty) is the library removing a copy it found unusable. */
    @Synchronized fun write(key: String, value: String?) {
        val file = fileFor(key) ?: return
        if (value.isNullOrEmpty() || value.length > maxChars) { file.delete(); return }
        runCatching {
            dir.mkdirs()
            // Written beside and then renamed, so a solver stopped half-way never leaves half a script to be read.
            val partial = File(dir, "${file.name}.partial")
            partial.writeText(value)
            try {
                java.nio.file.Files.move(partial.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.io.IOException) { partial.delete(); return }
            file.setLastModified(clock())
            trim()
        }
    }

    private fun fileFor(key: String) = if (keyShape.matches(key)) File(dir, "$key.js") else null

    private fun trim() {
        val files = dir.listFiles().orEmpty()
        files.filter { it.name.endsWith(".partial") }.forEach { it.delete() }
        files.filter { it.name.endsWith(".js") }.sortedByDescending { it.lastModified() }.drop(maxEntries).forEach { it.delete() }
    }
}
