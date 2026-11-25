package com.licht.nuvx

import com.licht.nuvx.data.scrobbling.LastFM
import org.junit.Assert.assertEquals
import org.junit.Test

class LastFmOAuthUrlTest {
    @Test
    fun `authorization url uses configured Last fm api key and token`() {
        val previous = LastFM.currentConfig()
        try {
            LastFM.configure(
                endpoint = LastFM.DEFAULT_API_ENDPOINT,
                apiKey = "test_api_key",
                secret = "test_secret",
            )

            assertEquals(
                "https://www.last.fm/api/auth/?api_key=test_api_key&token=test_token",
                LastFM.getAuthUrl("test_token"),
            )
        } finally {
            LastFM.configure(previous.endpoint, previous.apiKey, previous.secret, previous.sessionKey)
        }
    }

    @Test
    fun `Libre fm authorization uses its auth endpoint`() {
        val previous = LastFM.currentConfig()
        try {
            LastFM.configure(
                endpoint = LastFM.LIBREFM_API_ENDPOINT,
                apiKey = "test_libre_key",
                secret = "test_secret",
            )

            assertEquals(
                "https://libre.fm/api/auth?api_key=test_libre_key&token=test_token",
                LastFM.getAuthUrl("test_token"),
            )
        } finally {
            LastFM.configure(previous.endpoint, previous.apiKey, previous.secret, previous.sessionKey)
        }
    }
}
