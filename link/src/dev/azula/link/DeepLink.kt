package dev.azula.link

/**
 * Parses azula deeplinks into a session token (an iroh ticket / code).
 * Accepts the universal/app links and the custom scheme:
 *   - https://azula.app/s/<token>      (and /connect/<token>)
 *   - azula://connect?code=<token>
 */
object AzulaLinks {
    const val HOST = "azula.app"
    const val SCHEME = "azula"

    /** The canonical web link for a connect [token] — what the QR encodes and share sheets present. */
    fun webLink(token: String): String = "https://$HOST/s/$token"

    fun parse(raw: String): String? {
        val s = raw.trim()

        // azula://connect?code=<token>
        val codeIdx = s.indexOf("code=")
        if (codeIdx >= 0) {
            var v = s.substring(codeIdx + "code=".length)
            v = v.substringBefore('&').substringBefore('#')
            return v.ifEmpty { null }?.let(::percentDecode)
        }

        // .../s/<token> or .../connect/<token>
        for (prefix in listOf("/s/", "/connect/")) {
            val i = s.indexOf(prefix)
            if (i >= 0) {
                var v = s.substring(i + prefix.length)
                v = v.substringBefore('?').substringBefore('#').substringBefore('/')
                return v.ifEmpty { null }?.let(::percentDecode)
            }
        }
        return null
    }

    private fun percentDecode(s: String): String {
        if (!s.contains('%')) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3)
                val code = hex.toIntOrNull(16)
                if (code != null) { out.append(code.toChar()); i += 3; continue }
            }
            out.append(c); i++
        }
        return out.toString()
    }
}

/**
 * A tiny bus that delivers an incoming deeplink token to whoever is listening.
 * Buffers one token if the handler isn't registered yet (deeplink arrives before
 * the app state is ready).
 */
object DeepLinkBus {
    private var handler: ((String) -> Unit)? = null
    private var pending: String? = null

    fun setHandler(h: (String) -> Unit) {
        handler = h
        pending?.let { p -> pending = null; h(p) }
    }

    fun deliver(token: String) {
        val h = handler
        if (h != null) h(token) else pending = token
    }
}

/** Entry point for the iOS Swift layer: pass the raw opened-URL string. */
fun handleDeepLink(url: String) {
    AzulaLinks.parse(url)?.let { DeepLinkBus.deliver(it) }
}
