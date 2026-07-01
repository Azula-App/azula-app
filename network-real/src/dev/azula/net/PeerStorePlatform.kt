package dev.azula.net

/**
 * The platform default peer store (a file on JVM, NSUserDefaults on iOS). Android
 * returns null here; its Context-backed store is bound into the Metro graph by the
 * app module instead.
 */
expect fun platformPeerStore(): PeerStore?
