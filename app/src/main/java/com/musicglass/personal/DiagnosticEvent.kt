package com.musicglass.personal

/**
 * One line of the on-device diagnostic ring, reduced to what may be kept (Q15).
 *
 * An event is a name and a few `key=value` fields. A field is kept only if its key is listed here and its
 * value has the shape that key allows: a number, true or false, or one short word such as a phase, a reason
 * or a class name. Everything else is replaced by `<redacted>` before the line is stored, written to logcat
 * or put in a report: a URL, a header, a cookie, a token, a title, an identifier, an exception message, a
 * line break. The check is on shape, so it holds for text nobody foresaw; it is not a search for known
 * secrets.
 *
 * What the shapes cannot tell apart: a short all-lowercase identifier looks like a word. The list of keys is
 * the control for that, and DiagnosticEventSourceTest fails when the code writes a key that is not listed.
 */
internal class DiagnosticEvent private constructor(val text: String) {
    override fun toString() = text
    override fun equals(other: Any?) = other is DiagnosticEvent && other.text == text
    override fun hashCode() = text.hashCode()

    companion object {
        /** Longest input looked at, longest line kept, most fields kept, longest value kept. */
        const val MAX_INPUT = 1_000
        const val MAX_TEXT = 400
        const val MAX_FIELDS = 24
        const val MAX_VALUE = 48 // each of up to five class names in an error chain; every other value is shorter by its shape
        const val REDACTED = "<redacted>"
        const val REJECTED = "rejected_event"

        private val name = Regex("[a-z][a-z0-9_]{0,47}")
        private val key = Regex("[A-Za-z_][A-Za-z0-9_-]{0,31}")
        private val number = Regex("-?[0-9]{1,15}(\\.[0-9]{1,6})?")
        private val word = Regex("[a-z][a-z0-9_+]{0,31}")
        private val code = Regex("[A-Z][A-Z0-9_]{0,47}")
        private val className = Regex("[A-Z][A-Za-z0-9]{1,47}")
        private val failureClass = Regex("[A-Z][A-Za-z0-9]{0,40}(Exception|Error)")

        private val numbers = setOf(
            "active_ms", "added", "ahead", "ahead_ms", "aliases", "asked", "at", "attempt", "attempts", "code", "conflicts",
            "count", "damaged", "delay_ms", "delta", "elapsed", "elapsedMs", "expired", "failed", "files", "filtered_shelves", "found", "gain_db",
            "hold_ms", "http", "importance", "imported", "index", "itag", "kbps", "left", "lookup", "loudness_db", "mainState", "malformed", "media",
            "mixed_rows", "mixed_shelves", "mixed_song_rows", "ms", "not_stored", "over_limit", "page", "parsed", "quiet_ms", "playback_state",
            "present", "pss_kb", "records", "rejected", "remaining", "removals", "removed", "requeued", "restored", "rss_kb",
            "rows", "sections", "speed", "standby_bucket", "state", "status", "suppression", "swept", "tailState",
            "unmerged_rows", "unrecognised", "waiting")
        private val booleans = setOf(
            "applied", "bg_restricted", "cached", "complete", "cookie", "dynamic_retry", "enabled", "hls", "idle", "init", "known", "loudness", "main", "mainPlaying",
            "name", "owner_changed", "photo", "play_when_ready", "playing", "po_token", "power_save", "premium_hint", "preprocessed", "smart", "songs_chip",
            "task_removed", "wants")
        private val words = setOf(
            "action", "cache", "causes", "class", "failed_stage", "failure", "from", "host", "kind", "owner", "part", "path", "phase",
            "player_error", "process", "profile", "quality", "reason", "result", "scheme", "session_state", "source", "stage", "to",
            "transport", "type")

        /** Every key a field may have. A new one is added here, with its shape, or the field is not kept. */
        val keys: Set<String> = numbers + booleans + words

        /** Up to five class names joined by `>`, as the player's error chain is written. */
        private fun wordValue(value: String) = value.split('>').let { parts ->
            parts.size <= 5 && parts.all { it.length <= MAX_VALUE && (number.matches(it) || isWord(it) || code.matches(it) || isClassName(it)) }
        }

        /** A class name has few digits in it; an identifier that happens to start with a capital has more. */
        private fun isClassName(value: String) = className.matches(value) && value.count { it in '0'..'9' } <= 2

        /** A lowercase word with few digits in it: a hexadecimal identifier is mostly digits. */
        private fun isWord(value: String) = word.matches(value) && value.count { it in '0'..'9' } <= 3

        private fun allowed(key: String, value: String): Boolean = when (key) {
            in numbers -> number.matches(value)
            in booleans -> value == "true" || value == "false"
            in words -> wordValue(value)
            else -> false
        }

        /** The one form of a bare word that is kept: what failed, as a class name. */
        private fun bare(token: String) = failureClass.matches(token) && token.count { it in '0'..'9' } <= 2

        /**
         * [raw] with everything that may not be kept taken out. Never throws and never returns a line break,
         * a character outside printable ASCII, or more than [MAX_TEXT] characters.
         */
        fun parse(raw: String): DiagnosticEvent {
            val flat = buildString(minOf(raw.length, MAX_INPUT)) {
                for (i in 0 until minOf(raw.length, MAX_INPUT)) { val c = raw[i]; append(if (c in '!'..'~') c else ' ') }
            }
            val tokens = flat.split(' ').filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return DiagnosticEvent(REJECTED)
            val out = ArrayList<String>(minOf(tokens.size, MAX_FIELDS + 2))
            val head = tokens[0]
            val headKey = head.substringBefore('=')
            when {
                name.matches(head) -> out += head
                '=' in head && name.matches(headKey) && headKey in keys ->
                    out += if (allowed(headKey, head.substringAfter('='))) head else "$headKey=$REDACTED"
                else -> return DiagnosticEvent(REJECTED)
            }
            for (token in tokens.drop(1).take(MAX_FIELDS)) {
                val k = token.substringBefore('=', "")
                out += when {
                    k.isNotEmpty() && k in keys -> if (allowed(k, token.substringAfter('='))) token else "$k=$REDACTED"
                    k.isNotEmpty() && key.matches(k) -> "$k=$REDACTED"
                    k.isEmpty() && bare(token) -> token
                    else -> REDACTED
                }
            }
            if (tokens.size - 1 > MAX_FIELDS) out += REDACTED
            // One mark for a run of things taken out: how many words a title had is not kept either.
            val joined = out.filterIndexed { i, t -> t != REDACTED || out[i - 1] != REDACTED }.joinToString(" ")
            return DiagnosticEvent(if (joined.length <= MAX_TEXT) joined else joined.take(MAX_TEXT - REDACTED.length - 1).substringBeforeLast(' ') + " " + REDACTED)
        }
    }
}

/**
 * A host reduced to the service it belongs to. Which server answered is not kept: a media host name carries
 * the server and the network it was reached through.
 */
internal fun hostClass(host: String?): String {
    val h = host?.lowercase()?.trimEnd('.') ?: return "none"
    fun under(domain: String) = h == domain || h.endsWith(".$domain")
    return when {
        h.isEmpty() -> "none"
        under("googlevideo.com") -> "googlevideo"
        under("youtube.com") || under("youtu.be") || under("youtube-nocookie.com") -> "youtube"
        under("ytimg.com") || under("ggpht.com") || under("googleusercontent.com") -> "google_images"
        under("google.com") || under("gstatic.com") || under("googleapis.com") || googleCountryDomains.any(::under) -> "google"
        else -> "other"
    }
}

/** A URL scheme as a diagnostic word: the few that matter by name, anything else as "other". */
internal fun schemeClass(scheme: String?): String = when (val s = scheme?.lowercase()) {
    null, "" -> "none"
    "https", "http", "intent", "file", "content", "data", "javascript", "blob", "about", "market" -> s
    else -> "other"
}

/** Logcat lines pass the same reduction as stored ones. */
internal fun diagnosticLogcat(tag: String, text: String, warning: Boolean = false) {
    val line = DiagnosticEvent.parse(text).text
    if (warning) android.util.Log.w(tag, line) else android.util.Log.i(tag, line)
}
