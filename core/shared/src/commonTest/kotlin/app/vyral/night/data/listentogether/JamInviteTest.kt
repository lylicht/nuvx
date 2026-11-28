package app.vyral.night.data.listentogether

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JamInviteTest {
    @Test
    fun parsesCodesAndCurrentInviteShapes() {
        assertEquals("ABC123", JamInvite.parse("abc123"))
        assertEquals("ABC123", JamInvite.parse("vyral://party/abc123"))
        assertEquals("ABC123", JamInvite.parse("https://party.example/invite/abc123?from=share"))
        assertEquals("ABC123", JamInvite.parse("bitchord://party/abc123"))
    }

    @Test
    fun rejectsMalformedInvites() {
        assertNull(JamInvite.parse("ABC12"))
        assertNull(JamInvite.parse("https://example.com/download/ABC123"))
        assertNull(JamInvite.parse("javascript:alert(1)"))
    }

    @Test
    fun noServerUsesAppSchemeAndConfiguredServerUsesInvitePath() {
        assertEquals("vyral://party/ABC123", JamInvite.url("abc123"))
        assertEquals("https://music.example/invite/ABC123", JamInvite.url("abc123", "https://music.example/"))
    }
}
