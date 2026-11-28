package app.vyral.night.data.sync

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.vyral.night.auth.EncryptedPrefs
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** LAN pairing secrets are encrypted directly with an Android Keystore key; this store never falls back to plaintext. */
internal object AndroidLanSyncSecretStore {
    private const val STORE_NAME = "lan_sync_secrets_v1"
    private const val KEY_ALIAS = "app.vyral.night.lan-sync-secrets-v1"
    private const val MIGRATED = "__legacy_migrated"
    private const val OLD_ENCRYPTED_PREFS = "lan_sync_secure"
    private const val OLD_PLAIN_PREFS = "lan_sync_fallback"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val VERSION: Byte = 1
    private const val GCM_TAG_BITS = 128

    private val migrationLock = Any()
    @Volatile private var cachedKey: SecretKey? = null

    class Store internal constructor(
        private val prefs: SharedPreferences,
        private val secretKey: SecretKey,
    ) {
        fun getString(name: String): String? = prefs.getString(name, null)?.let { decrypt(name, it, secretKey) }

        fun putString(name: String, value: String) {
            require(name.startsWith("device.") || name.startsWith("session."))
            check(prefs.edit().putString(name, encrypt(name, value, secretKey)).commit()) {
                "Could not save the encrypted LAN pairing key."
            }
        }

        fun remove(name: String) {
            check(prefs.edit().remove(name).commit()) { "Could not remove the encrypted LAN pairing key." }
        }

        fun keys(prefix: String): List<String> = prefs.all.keys.filter { it.startsWith(prefix) && it != MIGRATED }
    }

    fun open(context: Context): Store {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)
        synchronized(migrationLock) {
            ensureMigrated(app, prefs)
            return Store(prefs, keystoreKey())
        }
    }

    private fun ensureMigrated(context: Context, securePrefs: SharedPreferences) {
        if (securePrefs.getBoolean(MIGRATED, false)) return

        // Old versions used EncryptedPrefs, which can return the plain fallback when Keystore access fails.
        // Copy secret entries into this Keystore-only store and erase the old copies before enabling LAN sync.
        val legacy = EncryptedPrefs.open(context, OLD_ENCRYPTED_PREFS, OLD_PLAIN_PREFS)
        val key = keystoreKey()
        val store = Store(securePrefs, key)
        val secrets = legacy.all.filter { (name, value) -> isLegacySecret(name) && value is String }
        secrets.forEach { (name, value) ->
            store.putString(name, value as String)
            check(store.getString(name) == value) { "Could not verify a migrated LAN pairing key." }
        }
        if (secrets.isNotEmpty()) {
            val editor = legacy.edit()
            secrets.keys.forEach(editor::remove)
            check(editor.commit()) { "Could not erase old LAN pairing keys after encryption." }
        }
        check(securePrefs.edit().putBoolean(MIGRATED, true).commit()) {
            "Could not finalize secure LAN pairing storage."
        }
    }

    private fun isLegacySecret(name: String): Boolean =
        name.startsWith("session.") || (name.startsWith("device.") && !name.endsWith(".name"))

    private fun keystoreKey(): SecretKey = cachedKey ?: synchronized(migrationLock) {
        cachedKey ?: run {
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
                generateKey()
            }
        }.also { cachedKey = it }
    }

    private fun encrypt(name: String, value: String, key: SecretKey): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(name.toByteArray(StandardCharsets.UTF_8))
        val iv = cipher.iv
        require(iv.size in 12..16)
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val blob = ByteBuffer.allocate(2 + iv.size + encrypted.size)
            .put(VERSION)
            .put(iv.size.toByte())
            .put(iv)
            .put(encrypted)
            .array()
        return Base64.encodeToString(blob, Base64.NO_WRAP)
    }

    private fun decrypt(name: String, encoded: String, key: SecretKey): String = try {
        val blob = Base64.decode(encoded, Base64.NO_WRAP)
        require(blob.size >= 2 && blob[0] == VERSION)
        val ivSize = blob[1].toInt() and 0xff
        require(ivSize in 12..16 && blob.size >= 2 + ivSize + GCM_TAG_BITS / 8)
        val iv = blob.copyOfRange(2, 2 + ivSize)
        val ciphertext = blob.copyOfRange(2 + ivSize, blob.size)
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            updateAAD(name.toByteArray(StandardCharsets.UTF_8))
            doFinal(ciphertext).toString(StandardCharsets.UTF_8)
        }
    } catch (e: Exception) {
        throw IllegalStateException("VYRAL could not unlock its saved LAN pairing keys. Pair this device again after secure storage is repaired.", e)
    }
}
