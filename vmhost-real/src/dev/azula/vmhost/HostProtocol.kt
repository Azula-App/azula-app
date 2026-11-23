package dev.azula.vmhost

/** Intent actions and Messenger codes between the UI process and `:vmhost`. */
internal object HostProtocol {
    // Intent actions (startForegroundService): the service must call startForeground for each.
    const val ACTION_START = "dev.azula.vmhost.START"
    const val ACTION_STOP = "dev.azula.vmhost.STOP"

    // Messenger requests (bound service): the ones that return data.
    const val MSG_MINT = 1
    const val MSG_LIST = 2
    const val MSG_REVOKE = 3
    const val MSG_REPLY = 100

    const val KEY_TTL = "ttl"
    const val KEY_LABEL = "label"
    const val KEY_ID = "id"
    const val KEY_JSON = "json"
}
