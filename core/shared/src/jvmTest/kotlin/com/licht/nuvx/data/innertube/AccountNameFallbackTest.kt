package com.licht.nuvx.data.innertube

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AccountNameFallbackTest {
    @Test
    fun blankAccountHeaderNameKeepsTheAccountAndUsesItsHandle() {
        val response = Json.parseToJsonElement(
            """{"header":{"activeAccountHeaderRenderer":{"accountName":{"runs":[{"text":"\u3164\u200B"}]},"channelHandle":{"runs":[{"text":"@visible"}]}}}}""",
        )

        assertEquals("@visible", InnertubeParser.parseAccount(response)?.name)
    }

    @Test
    fun accountChannelIdentityDoesNotDependOnAVisibleDisplayName() {
        val response = Json.parseToJsonElement(
            """{"items":[{"accountItem":{"accountName":{"simpleText":"\u200B"},"channelHandle":{"simpleText":"@channel"},"pageId":"UC123"}}]}""",
        )

        val channel = InnertubeParser.parseAccountChannels(response).single()
        assertEquals("@channel", channel.name)
        assertEquals("UC123", channel.pageId)
    }

    @Test
    fun invisibleNameWithoutHandleUsesGenericVisibleLabel() {
        val response = Json.parseToJsonElement(
            """{"header":{"activeAccountHeaderRenderer":{"accountName":{"runs":[{"text":"\u3164"}]}}}}""",
        )

        assertEquals("YouTube Music", assertNotNull(InnertubeParser.parseAccount(response)).name)
    }
}
