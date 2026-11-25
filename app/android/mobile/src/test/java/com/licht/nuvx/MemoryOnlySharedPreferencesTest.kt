package com.licht.nuvx

import com.licht.nuvx.auth.MemoryOnlySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryOnlySharedPreferencesTest {
    @Test
    fun storesTypedValuesForTheCurrentProcessAndSupportsRemoval() {
        val prefs = MemoryOnlySharedPreferences()
        prefs.edit().putString("token", "secret").putBoolean("enabled", true).apply()

        assertEquals("secret", prefs.getString("token", null))
        assertTrue(prefs.getBoolean("enabled", false))
        prefs.edit().remove("token").commit()
        assertFalse(prefs.contains("token"))
    }
}
