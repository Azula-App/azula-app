package dev.azula.persist

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile

/**
 * A user "persona": the identity you present to a peer when connecting. The app
 * keeps several and lets you choose which one (and which fields) to share per
 * connection. [avatarB64] is base64-encoded, downscaled image bytes (a String,
 * not a ByteArray, so the type stays Kotlin/Native-`@Serializable`-friendly —
 * matching [AttachmentDto.bytesB64]).
 */
@Serializable
data class Persona(
    val id: String,
    val name: String,
    val description: String? = null,
    val avatarB64: String? = null,
    val mime: String? = null,
)

/** All saved personas plus which one is the default (pre-selected when sharing). */
@Serializable
data class ProfileBook(
    val personas: List<Persona> = emptyList(),
    val defaultId: String? = null,
)

/**
 * Persists the user's personas. Mirrors the [dev.azula.net.PeerStore] /
 * [MessageStore] expect/actual + injectable-config pattern: a single small blob
 * (the whole [ProfileBook]).
 */
interface ProfileStore {
    fun load(): ProfileBook
    fun save(book: ProfileBook)
}

object ProfileStoreConfig {
    /** Platforms that need a Context (Android) inject their store here. */
    @Volatile
    var store: ProfileStore? = null
}

/** The platform default store (file on JVM, NSUserDefaults on iOS). Android injects one. */
expect fun platformProfileStore(): ProfileStore?

/** The active store: an injected one (Android) or the platform default. */
fun activeProfileStore(): ProfileStore? = ProfileStoreConfig.store ?: platformProfileStore()

val ProfileJson: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private val bookSerializer = ProfileBook.serializer()

fun encodeProfileBook(book: ProfileBook): String = ProfileJson.encodeToString(bookSerializer, book)

fun decodeProfileBook(text: String): ProfileBook? =
    runCatching { ProfileJson.decodeFromString(bookSerializer, text) }.getOrNull()
