package app.vyral.night.data.sync

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The phone side of the Rust LAN-sync pairing and sealed-request protocol. */
object LanSyncCrypto {
    private const val PUBLIC_KEY_SIZE = 65
    private const val AES_KEY_SIZE = 32
    private const val GCM_NONCE_SIZE = 12
    private const val GCM_TAG_BITS = 128
    private val random = SecureRandom()
    private val curveParameters by lazy {
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), random)
        }.generateKeyPair().public.let { (it as java.security.interfaces.ECPublicKey).params }
    }

    private const val X509_P256_PREFIX = "3059301306072A8648CE3D020106082A8648CE3D030107034200"
    private val x509PrefixBytes = X509_P256_PREFIX.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    fun newPhoneKey(): PhoneKey = newKeyPair().let { (privateKey, publicKey) -> PhoneKey(privateKey, publicKey) }

    /** The host side of the existing LAN pairing handshake; same P-256/HKDF contract as Rust. */
    fun newHostKey(): HostKey = newKeyPair().let { (privateKey, publicKey) -> HostKey(privateKey, publicKey) }

    private fun newKeyPair(): Pair<PrivateKey, ByteArray> {
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), random)
        }.generateKeyPair()
        val public = pair.public as java.security.interfaces.ECPublicKey
        val sec1 = byteArrayOf(0x04) + public.w.affineX.toFixedWidth() + public.w.affineY.toFixedWidth()
        return pair.private to sec1
    }

    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    fun encodeBase64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun decodeBase64Url(value: String): ByteArray? = runCatching {
        Base64.getUrlDecoder().decode(value.trim())
    }.getOrNull()

    fun seal(key: ByteArray, aad: ByteArray, plain: ByteArray): String {
        require(key.size == AES_KEY_SIZE) { "LAN sync keys must be 256 bits" }
        val nonce = ByteArray(GCM_NONCE_SIZE).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return encodeBase64Url(nonce + cipher.doFinal(plain))
    }

    fun open(key: ByteArray, aad: ByteArray, sealed: String): ByteArray? = runCatching {
        require(key.size == AES_KEY_SIZE)
        val raw = decodeBase64Url(sealed) ?: return null
        require(raw.size >= GCM_NONCE_SIZE + GCM_TAG_BITS / 8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, raw.copyOfRange(0, GCM_NONCE_SIZE)),
        )
        cipher.updateAAD(aad)
        cipher.doFinal(raw.copyOfRange(GCM_NONCE_SIZE, raw.size))
    }.getOrNull()

    internal fun curveParameterSpec() = curveParameters

    internal fun derive(
        privateKey: PrivateKey,
        hostPublicKey: ByteArray,
        devicePublicKey: ByteArray,
    ): DerivedKey? = deriveRole(privateKey, hostPublicKey, devicePublicKey, hostPublicKey)

    private fun deriveRole(
        privateKey: PrivateKey,
        peerPublicKey: ByteArray,
        devicePublicKey: ByteArray,
        hostPublicKey: ByteArray,
    ): DerivedKey? = runCatching {
        require(devicePublicKey.size == PUBLIC_KEY_SIZE && devicePublicKey[0] == 0x04.toByte())
        val peerPublic = decodeP256PublicKey(peerPublicKey)
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(peerPublic, true)
        val shared = agreement.generateSecret()
        val salt = devicePublicKey + hostPublicKey
        val pseudorandomKey = hmacSha256(salt, shared)
        val key = hkdfExpand(pseudorandomKey, "vyral-lan-v1 key".encodeToByteArray(), AES_KEY_SIZE)
        val sasBytes = hkdfExpand(pseudorandomKey, "vyral-lan-v1 sas".encodeToByteArray(), 4)
        val sas = "%06d".format((ByteBuffer.wrap(sasBytes).int.toLong() and 0xffff_ffffL) % 1_000_000L)
        DerivedKey(key, sas)
    }.getOrNull()

    private fun decodeP256PublicKey(raw: ByteArray): java.security.PublicKey {
        require(raw.size == PUBLIC_KEY_SIZE && raw[0] == 0x04.toByte())
        val encoded = x509PrefixBytes + raw
        return KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(encoded))
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, size: Int): ByteArray {
        require(size in 1..(255 * 32))
        val output = ByteArray(size)
        var previous = ByteArray(0)
        var written = 0
        var block = 1
        while (written < size) {
            previous = hmacSha256(prk, previous + info + byteArrayOf(block.toByte()))
            val amount = minOf(previous.size, size - written)
            previous.copyInto(output, written, 0, amount)
            written += amount
            block++
        }
        return output
    }

    private fun BigInteger.toFixedWidth(): ByteArray {
        val bytes = toByteArray().let { if (it.size > 32 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        require(bytes.size <= 32)
        return ByteArray(32 - bytes.size) + bytes
    }

    class PhoneKey internal constructor(
        private val privateKey: PrivateKey,
        publicKey: ByteArray,
    ) {
        val publicKey: ByteArray = publicKey.copyOf()

        fun commitment(): String = sha256Hex(publicKey)

        fun agree(hostPublicKeyBase64Url: String): DerivedKey? {
            val hostPublicKey = decodeBase64Url(hostPublicKeyBase64Url) ?: return null
            return derive(privateKey, hostPublicKey, publicKey)
        }
    }

    class HostKey internal constructor(
        private val privateKey: PrivateKey,
        publicKey: ByteArray,
    ) {
        val publicKey: ByteArray = publicKey.copyOf()

        fun agree(devicePublicKey: ByteArray): DerivedKey? =
            deriveRole(privateKey, devicePublicKey, devicePublicKey, this.publicKey)
    }

    class DerivedKey internal constructor(key: ByteArray, val confirmationCode: String) {
        private val bytes = key.copyOf()
        internal fun copyBytes(): ByteArray = bytes.copyOf()

        /** Returns the key only for immediate storage in the platform's encrypted credential store. */
        fun copyKeyForSecureStorage(): ByteArray = bytes.copyOf()
    }
}
