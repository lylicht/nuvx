package app.vyral.night.data.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidLanTlsPairingInstrumentedTest {
    @Test
    fun nearbyCodeFindsHostAndStillRequiresSasAndHostApproval() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AndroidLanSyncHost.stop()
        var pairing: AndroidLanSyncClient.Pairing? = null
        try {
            assertTrue(withContext(Dispatchers.IO) { AndroidLanSyncHost.start(context) })
            val host = AndroidLanSyncHost.state.value
            val code = requireNotNull(host.findCode)
            assertTrue("Host code is four digits", code.matches(Regex("[0-9]{4}")))

            val discovered = AndroidLanSyncDiscovery.findByCode(context, code)
            assertNotNull("Explicit subnet scan should locate the running host", discovered)
            assertEquals(host.address, discovered?.address)
            assertEquals(host.tlsFingerprint, discovered?.tlsFingerprint)
            assertEquals("Android /lan/hello is protocol version 1", 1, discovered?.version)

            val result = requireNotNull(discovered)
            pairing = AndroidLanSyncClient.beginPairing(result.address, "Nearby discovery test", result.tlsFingerprint)
            val request = AndroidLanSyncHost.state.value.pairingRequests.singleOrNull { it.confirmationCode == pairing?.confirmationCode }
            assertNotNull("Discovery must lead into the existing SAS flow", request)
            assertNull("The paired session remains unavailable until the host explicitly approves", AndroidLanSyncClient.pollApproval(context, requireNotNull(pairing)))
            assertTrue(AndroidLanSyncClient.sessions(context).none { it.hostId == pairing?.request?.host?.id })
        } finally {
            pairing?.let(AndroidLanSyncClient::closePairing)
            AndroidLanSyncHost.stop()
        }
    }

    @Test
    fun wrongNearbyCodeDoesNotReturnTheLocalHost() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AndroidLanSyncHost.stop()
        try {
            assertTrue(withContext(Dispatchers.IO) { AndroidLanSyncHost.start(context) })
            val state = AndroidLanSyncHost.state.value
            val local = requireNotNull(AndroidLanSyncHost.lanAddress(context))
            val port = URI(requireNotNull(state.address)).port
            assertNull(AndroidLanSyncDiscovery.scanCandidates("0000", listOf(local), listOf(port)))
        } finally {
            AndroidLanSyncHost.stop()
        }
    }

    @Test
    fun hostFallsForwardWithinTheSharedPortRangeWhenPrimaryPortIsBusy() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AndroidLanSyncHost.stop()
        val local = requireNotNull(AndroidLanSyncHost.lanAddress(context))
        val blocker = ServerSocket()
        try {
            blocker.reuseAddress = false
            blocker.bind(InetSocketAddress(local, AndroidLanSyncHost.PORT_RANGE.first))
            assertTrue(withContext(Dispatchers.IO) { AndroidLanSyncHost.start(context) })
            val port = URI(requireNotNull(AndroidLanSyncHost.state.value.address)).port
            assertTrue("Host should skip the occupied first port", port > AndroidLanSyncHost.PORT_RANGE.first)
            assertTrue("Host should stay inside the shared port range", port in AndroidLanSyncHost.PORT_RANGE)
            val host = AndroidLanSyncHost.state.value
            val discovered = AndroidLanSyncDiscovery.findByCode(context, requireNotNull(host.findCode))
            assertNotNull("The subnet scan should find the host on its fallback port", discovered)
            assertEquals(host.address, discovered?.address)
            assertEquals(host.tlsFingerprint, discovered?.tlsFingerprint)
            assertEquals("The fallback-port hello must use protocol version 1", 1, discovered?.version)
        } finally {
            AndroidLanSyncHost.stop()
            blocker.close()
        }
    }

    @Test
    fun pinnedHttpsClientCompletesPairingWithAndroidHost() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AndroidLanSyncHost.stop()

        try {
            assertTrue("The emulator must expose a local Wi-Fi/Ethernet address", withContext(Dispatchers.IO) {
                AndroidLanSyncHost.start(context)
            })
            val host = AndroidLanSyncHost.state.value
            val address = requireNotNull(host.address)
            val fingerprint = requireNotNull(host.tlsFingerprint)

            val pairing = AndroidLanSyncClient.beginPairing(address, "TLS loopback test", fingerprint)
            try {
                val request = AndroidLanSyncHost.state.value.pairingRequests
                    .singleOrNull { it.confirmationCode == pairing.confirmationCode }
                assertNotNull("Pinned HTTPS must reach pair/reveal on the Android host", request)
                AndroidLanSyncHost.approvePairing(requireNotNull(request).id)

                val session = AndroidLanSyncClient.pollApproval(context, pairing)
                assertNotNull("The approved device should receive a v1 session", session)
                assertEquals(host.address, session?.baseUrl)
                AndroidLanSyncClient.forget(context, requireNotNull(session).hostId)
            } finally {
                AndroidLanSyncClient.closePairing(pairing)
            }
        } finally {
            AndroidLanSyncHost.stop()
        }
    }
}
