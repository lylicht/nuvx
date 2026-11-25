package com.licht.nuvx.data.listentogether

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts the party membership bearer token with a non-exportable Android Keystore key. */
internal object PartyTokenStore {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "nuvx_party_membership_v1"
    private const val PREFIX = "v1:"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun encryptOrNull(token: String): String? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        PREFIX + Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
    }.getOrNull()

    fun decryptOrNull(stored: String?): String? = runCatching {
        if (stored == null || !stored.startsWith(PREFIX)) return@runCatching null
        val payload = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
        if (payload.size <= IV_BYTES + TAG_BITS / 8) return@runCatching null
        val iv = payload.copyOfRange(0, IV_BYTES)
        val ciphertext = payload.copyOfRange(IV_BYTES, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8).takeIf { it.isNotBlank() }
    }.getOrNull()

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
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
    }
}
