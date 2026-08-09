package dev.azula.link

/**
 * The recognized kind of a parsed azula deeplink/pasted string (multi-device-identity task
 * 6.6). [AzulaLinks.parse] already unwraps the connect forms (invite/legacy) into a bare
 * token, but a device-link payload is a completely different flow -- never a dialable peer
 * ticket, always the input to the root-holding device's grant/dial (see
 * [dev.azula.state.ConnectService.beginGrantDial] in `shared`) -- so telling the two apart
 * needs an actual tag, not just another string. See [AzulaLinks.parseTagged].
 */
sealed class ParsedAzulaLink {
    /** An invite (`azi…`) or a bare raw ticket -- see [AzulaLinks.parse]. */
    data class Connect(val token: String) : ParsedAzulaLink()

    /** A device-link (`azl…`) payload -- see [AzulaLinks.parseDeviceLink]. */
    data class DeviceLink(val payload: String) : ParsedAzulaLink()
}

/**
 * Parses azula deeplinks into a session token (an invite `azi…` string — see
 * [dev.azula.link.InviteCodec.isInvite]). Accepts the universal/app links and
 * the custom scheme:
 *   - https://azula.app/i/<payload>    (invite; canonical share link)
 *   - azula://i?c=<payload>            (invite; custom-scheme fallback)
 *   - bare azi… string                 (invite; pasteable into the connect box)
 *
 * The pre-invite share-link forms (`/s/<token>`, `/connect/<token>`,
 * `azula://connect?code=<token>`) are **not** parsed: the transition hatch they
 * belonged to is closed, and those routes no longer exist on azula.app. They
 * return null like any other unrecognized input. A bare raw ticket pasted into
 * the connect box still works — callers fall back to the trimmed input.
 *
 * A device-link (`azl…`) form is a separate flow entirely -- see [parseDeviceLink]/[parseTagged].
 */
object AzulaLinks {
    const val HOST = "azula.app"
    const val SCHEME = "azula"

    /** The canonical web link for an encoded invite [payload] (an `azi…` string). */
    fun inviteLink(payload: String): String = "https://$HOST/i/$payload"

    /**
     * The canonical web link for a device-link [payload] (an `azl…` string, `core`'s
     * `LinkPayloadCodec`) -- multi-device-identity task 6.6: wrapping the bare payload in a
     * deeplink is what makes a phone's camera app able to act on the QR at all (a bare azl…
     * string is just inert text to a camera, since the app has no in-app scanner). This is
     * what the device-linking QR renders instead of the bare payload; the bare payload stays
     * available alongside it as the copyable fallback string.
     */
    fun deviceLinkUrl(payload: String): String = "https://$HOST/l/$payload"

    fun parse(raw: String): String? {
        val s = raw.trim()

        // Bare invite string, pasted directly.
        if (isInvite(s)) return s

        // .../i/<payload> (invite universal/app link)
        run {
            val i = s.indexOf("/i/")
            if (i >= 0) {
                var v = s.substring(i + "/i/".length)
                v = v.substringBefore('?').substringBefore('#').substringBefore('/')
                return v.ifEmpty { null }?.let(::percentDecode)
            }
        }

        // azula://i?c=<payload> (invite custom-scheme fallback)
        val cIdx = s.indexOf("c=")
        if (s.startsWith("$SCHEME://i") && cIdx >= 0) {
            var v = s.substring(cIdx + "c=".length)
            v = v.substringBefore('&').substringBefore('#')
            return v.ifEmpty { null }?.let(::percentDecode)
        }

        return null
    }

    /**
     * Device-link-only parse (multi-device-identity task 6.2's paste box / task 6.6's
     * QR-follow): extracts the bare `azl…` payload from a wrapped deeplink, or accepts it
     * bare. Deliberately separate from [parse] -- a device-link payload is never a dialable
     * connect ticket, so it must never be handed to a caller expecting one. Accepts:
     *   - https://azula.app/l/<payload>    (canonical share link / what the QR now encodes)
     *   - azula://l?c=<payload>            (custom-scheme fallback)
     *   - bare azl… string                 (pasteable into the "Link a new device" box)
     */
    fun parseDeviceLink(raw: String): String? {
        val s = raw.trim()

        // Bare device-link string, pasted directly.
        if (isDeviceLink(s)) return s

        // .../l/<payload> (device-link universal/app link)
        run {
            val i = s.indexOf("/l/")
            if (i >= 0) {
                var v = s.substring(i + "/l/".length)
                v = v.substringBefore('?').substringBefore('#').substringBefore('/')
                return v.ifEmpty { null }?.let(::percentDecode)
            }
        }

        // azula://l?c=<payload> (device-link custom-scheme fallback)
        val cIdx = s.indexOf("c=")
        if (s.startsWith("$SCHEME://l") && cIdx >= 0) {
            var v = s.substring(cIdx + "c=".length)
            v = v.substringBefore('&').substringBefore('#')
            return v.ifEmpty { null }?.let(::percentDecode)
        }

        return null
    }

    /**
     * Tag-aware parse distinguishing which flow a deeplink/pasted string targets (multi-device-
     * identity task 6.6): tries the connect forms first ([parse]: invite), then
     * device-link forms ([parseDeviceLink]). Null if [raw] matches neither.
     */
    fun parseTagged(raw: String): ParsedAzulaLink? {
        parse(raw)?.let { return ParsedAzulaLink.Connect(it) }
        parseDeviceLink(raw)?.let { return ParsedAzulaLink.DeviceLink(it) }
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

/** True if [s] looks like a bare encoded device-link payload (does not decode/validate the
 *  payload itself -- mirrors [dev.azula.link.isInvite]'s prefix-only check). */
fun isDeviceLink(s: String): Boolean = s.trim().startsWith("azl")

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
