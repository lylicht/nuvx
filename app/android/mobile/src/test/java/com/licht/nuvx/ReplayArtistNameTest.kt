package com.licht.nuvx

import com.licht.nuvx.data.stats.NameEntry
import com.licht.nuvx.data.stats.leadName
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplayArtistNameTest {
    @Test
    fun `keeps linked artist names containing collaboration separators intact`() {
        assertEquals("Tyler, The Creator", NameEntry("Tyler, The Creator", leadNameIsVerified = true).leadName())
        assertEquals("Earth, Wind & Fire", NameEntry("Earth, Wind & Fire", leadNameIsVerified = true).leadName())
    }

    @Test
    fun `continues normalizing legacy unlinked collaboration credits`() {
        assertEquals("Cheema Y", NameEntry("Cheema Y & Gur Sidhu").leadName())
    }
}
