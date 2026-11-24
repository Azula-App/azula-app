package app.azula.vmhost.ftl

import java.util.concurrent.CopyOnWriteArraySet

/**
 * Takes bearer tokens out of everything this harness writes or logs. A compute token is
 * `azc1.` plus base64url, so the shape alone is enough; tokens seen during the run are also
 * removed literally, with their payload, in case something printed one without its prefix.
 */
class Scrub {
    private val secrets = CopyOnWriteArraySet<String>()

    fun add(token: String) {
        secrets += token
        token.substringAfter('.', "").takeIf { it.length >= MIN_SECRET }?.let { secrets += it }
    }

    fun clean(text: String): String {
        var s = text
        // Longest first, so a whole token goes before its payload.
        for (x in secrets.sortedByDescending { it.length }) s = s.replace(x, REDACTED)
        s = TOKEN.replace(s, REDACTED)
        return JSON_FIELD.replace(s) { "${it.groupValues[1]}\"$REDACTED\"" }
    }

    /** True if [text] still holds a token: the last check before anything is written. */
    fun leaks(text: String): Boolean = TOKEN.containsMatchIn(text) || secrets.any { it in text }

    private companion object {
        const val REDACTED = "<redacted>"
        const val MIN_SECRET = 16
        val TOKEN = Regex("""azc\d+\.[A-Za-z0-9_-]{8,}""")
        val JSON_FIELD = Regex("""("(?:token|secret)"\s*:\s*)"[^"]*"""")
    }
}
