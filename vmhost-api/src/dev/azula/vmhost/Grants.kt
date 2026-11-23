package dev.azula.vmhost

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** One compute grant as `azula compute grant --json` / `listGrants` report it (never carries the secret). Times are epoch seconds. */
@Serializable
data class GrantSummary(
    val id: String,
    val label: String = "",
    val created: Long = 0,
    val expires: Long = 0,
    val ops: List<String> = emptyList(),
    val vms: List<String> = emptyList(),
    val revoked: Long? = null,
    @SerialName("last_used") val lastUsed: Long? = null,
    @SerialName("last_peer") val lastPeer: String? = null,
) {
    fun isLive(nowSeconds: Long): Boolean = revoked == null && nowSeconds < expires
}

/** A just-minted grant: the summary plus the bearer [token] (`azc1.…`). Shown once; never persisted by the host. */
@Serializable
data class MintedGrant(val grant: GrantSummary, val token: String)

/** Parsers for the JNI broker's JSON, which reports failures as `{"error": "…"}`. */
object GrantJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseMint(text: String): Result<MintedGrant> = parse(text) { json.decodeFromString(MintedGrant.serializer(), text) }

    fun parseList(text: String): Result<List<GrantSummary>> =
        parse(text) { json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(GrantSummary.serializer()), text) }

    private fun <T> parse(text: String, block: () -> T): Result<T> = runCatching {
        val el = json.parseToJsonElement(text)
        if (el is JsonObject) {
            val err = el["error"]?.jsonPrimitive?.contentOrNull
            if (err != null) throw HostException(err)
        }
        block()
    }
}

class HostException(message: String) : Exception(message)
