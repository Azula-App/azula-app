package app.azula

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import dev.azula.net.SecretKeyStore

/**
 * Stores azula's two independently-present secrets -- the iroh node key and the identity
 * root secret (see the `identity` capability's Per-Platform Key-at-Rest Storage
 * requirement) -- as two named entries inside one EncryptedSharedPreferences file, so the
 * device keeps a stable node id across launches. Injected into [dev.azula.net.IrohConfig]
 * from MainActivity. Uses the security-crypto 1.0.0 API (MasterKeys + 5-arg create).
 *
 * Both entries share the same underlying prefs file deliberately: self-healing a corrupt
 * keyset (below) wipes and recreates that one file, and doing so must cover both entries
 * together rather than leaving one instance's self-heal race a second, independently-owned
 * file.
 *
 * Self-healing: after an uninstall/reinstall the Keystore master key can survive
 * while the encrypted prefs file is wiped, so `EncryptedSharedPreferences.create`
 * throws `AEADBadTagException`. We catch that, delete the corrupt prefs, and
 * recreate — so a reinstall degrades to a fresh (still-persisted) identity rather
 * than throwing out of the bind and dropping the app into demo mode.
 */
class AndroidSecretKeyStore(private val context: Context) : SecretKeyStore {
    private val prefsName = "azula_secret"

    private fun open(): SharedPreferences {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            prefsName,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val prefs: SharedPreferences? by lazy {
        runCatching { open() }.getOrElse {
            // Corrupt keyset (e.g. after reinstall): wipe and retry once.
            runCatching { context.deleteSharedPreferences(prefsName) }
            runCatching { open() }.getOrNull()
        }
    }

    override fun loadNodeKey(): ByteArray? = loadEntry(KEY_NODE)
    override fun saveNodeKey(bytes: ByteArray) = saveEntry(KEY_NODE, bytes)
    override fun loadRootSecret(): ByteArray? = loadEntry(KEY_ROOT)
    override fun saveRootSecret(bytes: ByteArray) = saveEntry(KEY_ROOT, bytes)

    /** See [dev.azula.net.IrohTransport.forgetRootSecret] -- clears the root entry only. */
    override fun clearRootSecret() {
        runCatching { prefs?.edit()?.remove(KEY_ROOT)?.apply() }
    }

    private fun loadEntry(name: String): ByteArray? = runCatching {
        prefs?.getString(name, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
    }.getOrNull()

    private fun saveEntry(name: String, bytes: ByteArray) {
        runCatching {
            prefs?.edit()?.putString(name, Base64.encodeToString(bytes, Base64.NO_WRAP))?.apply()
        }
    }

    private companion object {
        // Unchanged from the pre-multi-device-identity single-key name, so an existing
        // install's node key (and therefore its node id) is read back unchanged.
        const val KEY_NODE = "endpoint_key"
        const val KEY_ROOT = "root_secret_key"
    }
}
