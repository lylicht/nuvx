package app.vyral.night.data.sync

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.vyral.night.download.DownloadStore
import app.vyral.night.playback.PlaybackService
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidLanSyncTransportInstrumentedTest {
    @Test
    fun securePairingUsesKeystoreAndRemoteControlGatesTheRealPlaybackService() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AndroidLanSyncHost.stop()
        val controller = connectPlaybackController(context)
        val originalItems = withContext(Dispatchers.Main.immediate) {
            List(controller.mediaItemCount) { controller.getMediaItemAt(it) }
        }
        val originalIndex = withContext(Dispatchers.Main.immediate) { controller.currentMediaItemIndex }
        val originalPosition = withContext(Dispatchers.Main.immediate) { controller.currentPosition }
        val originalPlayWhenReady = withContext(Dispatchers.Main.immediate) { controller.playWhenReady }
        val tone = FileAudio(context, "vyral-lan-playback-${System.nanoTime()}.wav", 30L * 8_000 * 2)
        var session: LanSyncSession? = null

        try {
            withContext(Dispatchers.Main.immediate) {
                controller.setMediaItem(MediaItem.fromUri(tone.uri))
                controller.prepare()
                controller.pause()
            }
            awaitCondition { withContext(Dispatchers.Main.immediate) { controller.playbackState == Player.STATE_READY } }

            session = startAndPair(context)
            val stored = context.getSharedPreferences("lan_sync_secrets_v1", Context.MODE_PRIVATE)
                .getString("session.${session.hostId}", null)
            assertNotNull("The session must be present in the dedicated encrypted store", stored)
            assertFalse("The stored value must not expose its JSON or base64 session key", stored!!.contains(session.exportForEncryptedStorage()))
            assertTrue("The legacy plain fallback must not retain session keys", context
                .getSharedPreferences("lan_sync_fallback", Context.MODE_PRIVATE).all.keys.none { it.startsWith("session.") || it.startsWith("device.") && !it.endsWith(".name") })

            AndroidLanSyncHost.setRemoteControlAllowed(context, false)
            withContext(Dispatchers.Main.immediate) { controller.pause() }
            val denied = runCatching { AndroidLanSyncClient.sendControl(session, "play") }.exceptionOrNull()
            assertTrue("Control-off must reject a joined peer", denied is LanSyncException)
            assertEquals(403, (denied as LanSyncException).statusCode)
            assertFalse(withContext(Dispatchers.Main.immediate) { controller.playWhenReady })

            AndroidLanSyncHost.setRemoteControlAllowed(context, true)
            AndroidLanSyncClient.sendControl(session, "play")
            awaitCondition { withContext(Dispatchers.Main.immediate) { controller.isPlaying } }

            AndroidLanSyncHost.setRemoteControlAllowed(context, false)
            val deniedPause = runCatching { AndroidLanSyncClient.sendControl(session, "pause") }.exceptionOrNull()
            assertTrue(deniedPause is LanSyncException)
            assertEquals(403, (deniedPause as LanSyncException).statusCode)
            assertTrue("Denied control must leave the real service player untouched", withContext(Dispatchers.Main.immediate) { controller.isPlaying })

            AndroidLanSyncHost.setRemoteControlAllowed(context, true)
            AndroidLanSyncClient.sendControl(session, "pause")
            awaitCondition { withContext(Dispatchers.Main.immediate) { !controller.isPlaying } }
        } finally {
            AndroidLanSyncHost.setRemoteControlAllowed(context, false)
            AndroidLanSyncHost.stop()
            session?.let { AndroidLanSyncClient.forget(context, it.hostId) }
            withContext(Dispatchers.Main.immediate) {
                if (originalItems.isEmpty()) controller.clearMediaItems()
                else controller.setMediaItems(originalItems, originalIndex.coerceIn(0, originalItems.lastIndex), originalPosition)
                if (originalPlayWhenReady) controller.play() else controller.pause()
                controller.release()
            }
            tone.delete()
        }
    }

    @Test
    fun selectedAudioTransfersResumeAcrossHostRestartAndExplicitCancellationIsTerminal() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AndroidLanSyncHost.stop()
        val guestFile = MediaAudio(context, "vyral-lan-guest-${System.nanoTime()}.wav", 2L * 1024 * 1024)
        val hostFile = MediaAudio(context, "vyral-lan-host-${System.nanoTime()}.wav", 2L * 1024 * 1024)
        val resumeFile = MediaAudio(context, "vyral-lan-resume-${System.nanoTime()}.wav", 2L * 1024 * 1024)
        val cancelFile = MediaAudio(context, "vyral-lan-cancel-${System.nanoTime()}.wav", 2L * 1024 * 1024)
        val cleanup = mutableListOf<Uri>()
        var session: LanSyncSession? = null
        try {
            session = startAndPair(context)
            AndroidLanSyncHost.setAllowSendFiles(context, true)
            AndroidLanSyncHost.setAllowReceiveFiles(context, true)

            val hostOfferId = requireNotNull(AndroidLanSyncHost.offerLocalFile(context, session.deviceId, hostFile.uri))
            val hostOffer = withTimeout(10_000) {
                while (true) {
                    AndroidLanSyncClient.transfers(session).firstOrNull { it.id == hostOfferId }?.let { return@withTimeout it }
                    delay(100)
                }
                error("unreachable")
            }
            val receiveFirstChunk = CompletableDeferred<Long>()
            val receiveFromHost = async(Dispatchers.IO) {
                AndroidLanSyncClient.receiveSelectedOffer(
                    context,
                    session,
                    hostOffer,
                    onProgress = { bytes -> if (bytes > 0) receiveFirstChunk.complete(bytes) },
                )
            }
            val sendToHostId = CompletableDeferred<String>()
            val sendToHost = async(Dispatchers.IO) {
                AndroidLanSyncClient.sendSelectedFile(context, session, guestFile.uri, onTransferCreated = { sendToHostId.complete(it) })
            }
            val incomingHostTransfer = awaitHostTransfer { it.direction == "upload" && it.status == "pending" }
            AndroidLanSyncHost.acceptTransfer(incomingHostTransfer.id)

            assertTrue(withTimeout(15_000) { receiveFirstChunk.await() } > 0)
            receiveFromHost.cancelAndJoin()
            assertEquals("complete", withTimeout(30_000) { sendToHost.await() })
            val guestTransferId = withTimeout(10_000) { sendToHostId.await() }
            assertEquals("complete", AndroidLanSyncHost.state.value.transfers.first { it.id == guestTransferId }.status)
            val importedOnHost = DownloadStore.existing(context, guestFile.name)
            assertNotNull("The explicitly selected guest file should be imported on the host", importedOnHost)
            importedOnHost?.let(cleanup::add)

            val downloadPart = context.filesDir.resolve("lan-sync-received/" + hostOfferId + ".part")
            assertTrue("The client retains its durable received prefix", downloadPart.isFile && downloadPart.length() > 0)
            val pausedOffer = AndroidLanSyncClient.transfers(session).first { it.id == hostOfferId }
            assertTrue("The host records its served offset for resume", pausedOffer.received > 0)
            AndroidLanSyncHost.stop()
            assertTrue("The host restores its outgoing offer after restart", AndroidLanSyncHost.start(context))
            val restoredOffer = AndroidLanSyncClient.transfers(session).first { it.id == hostOfferId }
            assertEquals("sending", restoredOffer.status)
            val receivedUri = AndroidLanSyncClient.receiveSelectedOffer(context, session, restoredOffer)
            cleanup += receivedUri
            assertEquals("complete", AndroidLanSyncClient.transfers(session).first { it.id == hostOfferId }.status)

            // Coroutine cancellation pauses; explicit DELETE is terminal.
            val transferId = CompletableDeferred<String>()
            val firstChunk = CompletableDeferred<Long>()
            val cancellation = async(Dispatchers.IO) {
                AndroidLanSyncClient.sendSelectedFile(
                    context,
                    session,
                    resumeFile.uri,
                    onTransferCreated = { transferId.complete(it) },
                    onProgress = { bytes -> if (bytes > 0) firstChunk.complete(bytes) },
                )
            }
            val cancelId = withTimeout(10_000) { transferId.await() }
            val cancelPending = awaitHostTransfer { it.id == cancelId && it.direction == "upload" && it.status == "pending" }
            AndroidLanSyncHost.acceptTransfer(cancelPending.id)
            assertTrue(withTimeout(15_000) { firstChunk.await() } > 0)
            cancellation.cancelAndJoin()
            val paused = awaitHostTransfer { it.id == cancelId && it.status == "receiving" }
            val partial = context.filesDir.resolve("lan-transfers/$cancelId.part")
            assertTrue("The receiver retains the durably written prefix while paused", partial.isFile && partial.length() == paused.received)
            AndroidLanSyncHost.stop()
            assertTrue("The host must recover its paired transfer after a restart", AndroidLanSyncHost.start(context))
            val restored = awaitHostTransfer { it.id == cancelId && it.status == "receiving" }
            assertEquals(paused.received, restored.received)
            assertEquals("complete", AndroidLanSyncClient.sendSelectedFile(context, session, resumeFile.uri, resumeTransferId = cancelId))
            val resumedImport = DownloadStore.existing(context, resumeFile.name)
            assertNotNull("The resumed transfer is imported exactly once", resumedImport)
            resumedImport?.let(cleanup::add)

            val terminalId = CompletableDeferred<String>()
            val terminalFirstChunk = CompletableDeferred<Long>()
            val terminalSend = async(Dispatchers.IO) {
                AndroidLanSyncClient.sendSelectedFile(
                    context,
                    session,
                    cancelFile.uri,
                    onTransferCreated = { terminalId.complete(it) },
                    onProgress = { bytes -> if (bytes > 0) terminalFirstChunk.complete(bytes) },
                )
            }
            val terminalTransferId = withTimeout(10_000) { terminalId.await() }
            AndroidLanSyncHost.acceptTransfer(
                awaitHostTransfer { it.id == terminalTransferId && it.status == "pending" }.id,
            )
            assertTrue(withTimeout(15_000) { terminalFirstChunk.await() } > 0)
            terminalSend.cancelAndJoin()
            assertEquals("receiving", awaitHostTransfer { it.id == terminalTransferId }.status)
            AndroidLanSyncClient.cancel(context, session, terminalTransferId)
            awaitCondition {
                AndroidLanSyncHost.state.value.transfers.firstOrNull { it.id == terminalTransferId }?.status == "cancelled"
            }
        } finally {
            session?.let { AndroidLanSyncClient.forget(context, it.hostId) }
            AndroidLanSyncHost.stop()
            cleanup.forEach { DownloadStore.delete(context, it) }
            listOf(guestFile, hostFile, resumeFile, cancelFile).forEach { it.delete() }
        }
    }

    @Test
    fun corruptedSelectedFileIsRejectedByTheHostChecksum() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AndroidLanSyncHost.stop()
        val selected = MediaAudio(context, "vyral-lan-hash-${System.nanoTime()}.wav", 256 * 1024L)
        var session: LanSyncSession? = null
        try {
            session = startAndPair(context)
            AndroidLanSyncHost.setAllowSendFiles(context, true)
            AndroidLanSyncHost.setAllowReceiveFiles(context, true)
            val transferId = CompletableDeferred<String>()
            val result = async(Dispatchers.IO) {
                runCatching {
                    AndroidLanSyncClient.sendSelectedFile(context, session, selected.uri, onTransferCreated = { id ->
                        val staged = context.cacheDir.resolve("lan-$id.part")
                        java.io.RandomAccessFile(staged, "rw").use { file ->
                            file.seek(file.length() - 1)
                            file.write(0x7f)
                        }
                        transferId.complete(id)
                    })
                }
            }
            val id = withTimeout(10_000) { transferId.await() }
            awaitHostTransfer { it.id == id && it.direction == "upload" && it.status == "pending" }
            AndroidLanSyncHost.acceptTransfer(id)
            val failure = withTimeout(20_000) { result.await().exceptionOrNull() }
            assertTrue("A payload changed after the offer must be rejected by SHA-256", failure is LanSyncException)
            assertTrue(failure?.message.orEmpty().contains("checksum", ignoreCase = true))
        } finally {
            session?.let { AndroidLanSyncClient.forget(context, it.hostId) }
            AndroidLanSyncHost.stop()
            DownloadStore.existing(context, selected.name)?.let { DownloadStore.delete(context, it) }
            selected.delete()
        }
    }

    private suspend fun startAndPair(context: Context): LanSyncSession {
        assertTrue("The emulator must expose a local Wi-Fi/Ethernet address", withContext(Dispatchers.IO) {
            AndroidLanSyncHost.start(context)
        })
        val host = AndroidLanSyncHost.state.value
        val pairing = AndroidLanSyncClient.beginPairing(
            requireNotNull(host.address),
            "Android loopback test",
            requireNotNull(host.tlsFingerprint),
        )
        try {
            val request = withTimeout(10_000) {
                while (true) {
                    AndroidLanSyncHost.state.value.pairingRequests
                        .singleOrNull { it.confirmationCode == pairing.confirmationCode }
                        ?.let { return@withTimeout it }
                    delay(50)
                }
                error("unreachable")
            }
            AndroidLanSyncHost.approvePairing(request.id)
            return withTimeout(10_000) {
                while (true) {
                    AndroidLanSyncClient.pollApproval(context, pairing)?.let { return@withTimeout it }
                    delay(50)
                }
                error("unreachable")
            }
        } finally {
            AndroidLanSyncClient.closePairing(pairing)
        }
    }

    private suspend fun awaitHostTransfer(predicate: (AndroidLanSyncHost.Transfer) -> Boolean): AndroidLanSyncHost.Transfer =
        withTimeout(15_000) {
            while (true) {
                AndroidLanSyncHost.refresh()
                AndroidLanSyncHost.state.value.transfers.firstOrNull(predicate)?.let { return@withTimeout it }
                delay(50)
            }
            error("unreachable")
        }

    private suspend fun awaitCondition(condition: suspend () -> Boolean) {
        withTimeout(15_000) {
            while (!condition()) delay(50)
        }
    }

    private suspend fun connectPlaybackController(context: Context): MediaController {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        return withContext(Dispatchers.IO) { future.get(20, TimeUnit.SECONDS) }
    }

    private inner class FileAudio(context: Context, val name: String, dataBytes: Long) {
        val uri: Uri = Uri.fromFile(context.cacheDir.resolve(name).apply { writeWav(this, dataBytes) })
        fun delete() { uri.path?.let { java.io.File(it).delete() } }
    }

    private inner class MediaAudio(context: Context, val name: String, dataBytes: Long) {
        private val resolver = context.contentResolver
        val uri: Uri = requireNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/x-wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/VYRAL/.sync-test")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        })).also { row ->
            resolver.openOutputStream(row, "w")!!.use { output -> writeWav(output, dataBytes) }
            resolver.update(row, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }

        fun delete() { resolver.delete(uri, null, null) }
    }

    private fun writeWav(file: java.io.File, dataBytes: Long) = file.outputStream().buffered().use { writeWav(it, dataBytes) }

    private fun writeWav(output: java.io.OutputStream, dataBytes: Long) {
        require(dataBytes in 2..(Int.MAX_VALUE - 44).toLong() && dataBytes % 2L == 0L)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt((36 + dataBytes).toInt()); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(8_000); putInt(16_000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataBytes.toInt())
        }.array()
        output.write(header)
        val zeroes = ByteArray(64 * 1024)
        var remaining = dataBytes
        while (remaining > 0) {
            val write = minOf(remaining, zeroes.size.toLong()).toInt()
            output.write(zeroes, 0, write)
            remaining -= write
        }
    }
}
