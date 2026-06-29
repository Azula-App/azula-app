package app.azula

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import dev.azula.net.SecretKeyStore

/**
 * Stores the iroh secret key in EncryptedSharedPreferences so the device keeps a
 * stable node id across launches. Injected into [dev.azula.net.IrohConfig] from
 * MainActivity. Uses the security-crypto 1.0.0 API (MasterKeys + 5-arg create).
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

    override fun load(): ByteArray? = runCatching {
        prefs?.getString("endpoint_key", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
    }.getOrNull()

    override fun save(bytes: ByteArray) {
        runCatching {
            prefs?.edit()?.putString("endpoint_key", Base64.encodeToString(bytes, Base64.NO_WRAP))?.apply()
        }
    }
}
