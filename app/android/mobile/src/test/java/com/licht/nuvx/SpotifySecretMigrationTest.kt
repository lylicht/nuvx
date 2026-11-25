package com.licht.nuvx

import com.licht.nuvx.data.settings.migratedSpotifySpdcToken
import org.junit.Assert.assertEquals
import org.junit.Test

class SpotifySecretMigrationTest {
    @Test
    fun secureTokenWinsOverLegacyPlaintext() {
        assertEquals("encrypted", migratedSpotifySpdcToken("encrypted", "plaintext"))
    }

    @Test
    fun legacyTokenMigratesWhenNoSecureCopyExists() {
        assertEquals("legacy", migratedSpotifySpdcToken(null, "legacy"))
    }

    @Test
    fun blankValuesDoNotCreateAStoredToken() {
        assertEquals("", migratedSpotifySpdcToken(" ", null))
    }
}
