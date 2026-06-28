package app.azula

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import dev.azula.net.SecretKeyStore

/**
 * Stores the iroh secret key in EncryptedSharedPreferences so the device keeps a
 * stable node id across launches. Injected into [dev.azula.net.IrohConfig] from
 * MainActivity. Uses the security-crypto 1.0.0 API (MasterKeys + 5-arg create).
 */
class AndroidSecretKeyStore(context: Context) : SecretKeyStore {
    private val prefs by lazy {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "azula_secret",
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun load(): ByteArray? =
        prefs.getString("endpoint_key", null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    override fun save(bytes: ByteArray) {
        prefs.edit().putString("endpoint_key", Base64.encodeToString(bytes, Base64.NO_WRAP)).apply()
    }
}
