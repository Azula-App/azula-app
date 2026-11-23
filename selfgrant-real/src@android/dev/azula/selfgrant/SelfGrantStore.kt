package dev.azula.selfgrant

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * What survives process death: the flow's phase, whether our adb key is paired
 * with this device, and the adb private key (PKCS#8, encrypted under a
 * non-exportable AndroidKeyStore AES key, so the file on disk is useless
 * without this app's keystore entry).
 */
internal class SelfGrantStore(context: Context) {
    private val prefs = context.getSharedPreferences("azula_selfgrant", Context.MODE_PRIVATE)

    var phase: GrantPhase
        get() = prefs.getString(K_PHASE, null)?.let { runCatching { GrantPhase.valueOf(it) }.getOrNull() } ?: GrantPhase.Idle
        set(v) = prefs.edit().putString(K_PHASE, v.name).apply()

    var paired: Boolean
        get() = prefs.getBoolean(K_PAIRED, false)
        set(v) = prefs.edit().putBoolean(K_PAIRED, v).apply()

    /** The persisted adb key, creating and storing a fresh one on first use. */
    @Synchronized
    fun adbKey(): AdbKeyPair {
        prefs.getString(K_KEY, null)?.let { blob ->
            runCatching { AdbKeyPair.fromPrivateBytes(decrypt(Base64.decode(blob, Base64.NO_WRAP))) }
                .getOrNull()?.let { return it }
        }
        // Missing or undecryptable (e.g. keystore entry lost): a new key means a new pairing.
        val fresh = AdbKeyPair.generate()
        prefs.edit()
            .putString(K_KEY, Base64.encodeToString(encrypt(fresh.privateBytes()), Base64.NO_WRAP))
            .putBoolean(K_PAIRED, false)
            .apply()
        return fresh
    }

    private fun aesKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, aesKey()) }
        return c.iv + c.doFinal(plain)
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.DECRYPT_MODE, aesKey(), GCMParameterSpec(128, blob, 0, 12)) }
        return c.doFinal(blob, 12, blob.size - 12)
    }

    /** Drops the pairing state and key (the user removed the device in Settings, or reset). */
    fun forget() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "azula_selfgrant_adbkey"
        const val K_PHASE = "phase"
        const val K_PAIRED = "paired"
        const val K_KEY = "adbkey"
    }
}
