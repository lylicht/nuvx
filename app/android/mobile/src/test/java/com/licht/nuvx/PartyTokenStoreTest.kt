package com.licht.nuvx

import com.licht.nuvx.data.listentogether.PartyTokenStore
import org.junit.Assert.assertNull
import org.junit.Test

class PartyTokenStoreTest {
    @Test
    fun legacyPlaintextAndMalformedCiphertextAreNeverAcceptedAsTokens() {
        assertNull(PartyTokenStore.decryptOrNull("legacy-plaintext-token"))
        assertNull(PartyTokenStore.decryptOrNull("v1:not-valid-base64!"))
    }
}
