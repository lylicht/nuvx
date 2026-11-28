package app.vyral.night.data.sync

import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.ECPrivateKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanSyncCryptoTest {
    @Test
    fun `phone key derivation and sealed payload match the Rust protocol vector`() {
        val phonePublic = LanSyncCrypto.decodeBase64Url(
            "BB4YUy_UdUwC8wQdnHXOszuD_9gax85P6ILMscmLxYlupGwxHE4v9A3ZajZT5uRURdMt_khuztdcepDGoYiBwKM",
        )!!
        val hostPublic = LanSyncCrypto.decodeBase64Url(
            "BHE1-k_ZOgnc6Yu_aBtL_PUOfA1jVOYq-wv_KjQpYXhl7UwfAt25Aj7lalV-UV1qncZsEfIglg3llDNN9Yh3ZyQ",
        )!!
        val phonePrivate = KeyFactory.getInstance("EC").generatePrivate(
            ECPrivateKeySpec(BigInteger(1, ByteArray(32) { 7 }), LanSyncCrypto.curveParameterSpec()),
        )

        val derived = LanSyncCrypto.derive(phonePrivate, hostPublic, phonePublic)
        assertNotNull(derived)
        assertEquals("-1eROVFwGCfadmnoAcAd3QieVNQcwN7-AlDDVFjQ27w", LanSyncCrypto.encodeBase64Url(derived.copyBytes()))
        assertEquals("064925", derived.confirmationCode)

        val sealed = "j1lcdfDcIeuA7RKcphT2e6_U9hjuLQJOaJBXGJlMVOH5egeYSQ-BUKLUcaBF"
        assertContentEquals(
            "hello from kotlin".encodeToByteArray(),
            LanSyncCrypto.open(derived.copyBytes(), "dev1".encodeToByteArray(), sealed),
        )
        assertNull(LanSyncCrypto.open(derived.copyBytes(), "dev2".encodeToByteArray(), sealed))
    }

    @Test
    fun `generated pairing key encrypts authenticated messages and rejects tampering`() {
        val phone = LanSyncCrypto.newPhoneKey()
        assertEquals(65, phone.publicKey.size)
        assertEquals(64, phone.commitment().length)

        val derived = phone.agree(
            "BHE1-k_ZOgnc6Yu_aBtL_PUOfA1jVOYq-wv_KjQpYXhl7UwfAt25Aj7lalV-UV1qncZsEfIglg3llDNN9Yh3ZyQ",
        )
        assertNotNull(derived)
        assertTrue(derived.confirmationCode.matches(Regex("[0-9]{6}")))

        val aad = "device-42".encodeToByteArray()
        val sealed = LanSyncCrypto.seal(derived.copyBytes(), aad, "hello".encodeToByteArray())
        assertContentEquals("hello".encodeToByteArray(), LanSyncCrypto.open(derived.copyBytes(), aad, sealed))
        assertNull(LanSyncCrypto.open(derived.copyBytes(), "other-device".encodeToByteArray(), sealed))
        val tampered = sealed.dropLast(1) + if (sealed.last() == 'A') 'B' else 'A'
        assertNull(LanSyncCrypto.open(derived.copyBytes(), aad, tampered))
    }

    @Test
    fun `host and phone pairing roles derive the same key and confirmation code`() {
        val host = LanSyncCrypto.newHostKey()
        val phone = LanSyncCrypto.newPhoneKey()
        val phoneDerived = phone.agree(LanSyncCrypto.encodeBase64Url(host.publicKey))
        val hostDerived = host.agree(phone.publicKey)

        assertNotNull(phoneDerived)
        assertNotNull(hostDerived)
        assertEquals(phoneDerived.confirmationCode, hostDerived.confirmationCode)
        assertContentEquals(phoneDerived.copyBytes(), hostDerived.copyBytes())
    }

    @Test
    fun `address input requires a local-sync HTTPS authority`() {
        assertEquals("https://192.168.1.24:47731", normalizeLanAddress("192.168.1.24:47731"))
        assertEquals("https://192.168.1.24:47731", normalizeLanAddress("https://192.168.1.24:47731/"))
        assertTrue(runCatching { normalizeLanAddress("http://192.168.1.24:47731") }.isFailure)
        assertTrue(runCatching { normalizeLanAddress("https://user:pass@192.168.1.2:47731") }.isFailure)
        assertTrue(runCatching { normalizeLanAddress("https://192.168.1.2:47731/other") }.isFailure)
    }
}
