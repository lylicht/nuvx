package app.vyral.night.data.sync

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.vyral.night.download.DownloadStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Android client for the existing LAN v1 host. Android's cleartext policy is deliberately unchanged. */
object AndroidLanSyncClient {
    private const val SESSION_PREFIX = "session."
    private const val MAX_BYTES = 500L * 1024 * 1024
    private const val CHUNK_BYTES = 192 * 1024
    data class Pairing internal constructor(
        val request: LanSyncPairingRequest,
        val confirmationCode: String,
        val hostName: String,
        val tlsFingerprint: String,
        internal val client: LanSyncClient,
        internal val http: HttpClient,
    )

    suspend fun beginPairing(address: String, deviceName: String, tlsFingerprint: String): Pairing = withContext(Dispatchers.IO) {
        val pin = tlsFingerprint.trim().lowercase()
        require(pin.matches(Regex("[0-9a-f]{64}"))) { "Enter the 64-character fingerprint shown on the host or in its QR code." }
        val (http, client) = createPinnedClient(pin)
        try {
            val request = client.beginPairing(address, deviceName)
            Pairing(request, request.confirmationCode, request.host.name, pin, client, http)
        } catch (e: Exception) {
            http.close()
            throw e
        }
    }

    /** Poll after the user has compared the SAS with the host and approved it there. */
    suspend fun pollApproval(context: Context, pairing: Pairing): LanSyncSession? = withContext(Dispatchers.IO) {
        pairing.client.pollApproval(pairing.request)?.also { session ->
            AndroidLanSyncSecretStore.open(context).putString("$SESSION_PREFIX${session.hostId}", session.exportForEncryptedStorage())
            pairing.request.clearSensitiveData()
            pairing.http.close()
        }
    }

    fun sessions(context: Context): List<LanSyncSession> = AndroidLanSyncSecretStore.open(context).let { store ->
        store.keys(SESSION_PREFIX).mapNotNull { key ->
            store.getString(key)?.let(LanSyncSession::restoreFromEncryptedStorage)
                ?.takeIf { key == "$SESSION_PREFIX${it.hostId}" }
        }
    }

    fun forget(context: Context, hostId: String) {
        AndroidLanSyncSecretStore.open(context).remove("$SESSION_PREFIX$hostId")
    }

    fun closePairing(pairing: Pairing) {
        pairing.http.close()
        pairing.request.clearSensitiveData()
    }

    suspend fun nowPlaying(session: LanSyncSession): JsonObject = withApi(session) {
        it.request(session, "GET", "/v1/connect").jsonObject
    }

    suspend fun sendControl(session: LanSyncSession, action: String, positionMs: Long? = null): JsonObject {
        require(action in setOf("toggle", "play", "pause", "next", "previous", "seek"))
        val body = buildJsonObject {
            put("action", action)
            positionMs?.let { put("positionMs", it.coerceIn(0, 86_400_000L)) }
        }
        return withApi(session) { it.request(session, "POST", "/v1/connect", body).jsonObject }
    }

    suspend fun transfers(session: LanSyncSession): List<LanSyncFileTransfer> = withApi(session) { it.listTransfers(session) }
    suspend fun accept(session: LanSyncSession, id: String) = withApi(session) { it.acceptTransfer(session, id) }
    suspend fun decline(session: LanSyncSession, id: String) = withApi(session) { it.declineTransfer(session, id) }
    suspend fun cancel(session: LanSyncSession, id: String) = withApi(session) { it.cancelTransfer(session, id) }

    suspend fun cancel(context: Context, session: LanSyncSession, id: String) {
        cancel(session, id)
        File(context.filesDir, "lan-sync-received/$id.part").delete()
        File(context.cacheDir, "lan-$id.part").delete()
    }

    /** Re-select the same source when resuming; the host validates its identity and resumes at its durable offset. */
    suspend fun sendSelectedFile(
        context: Context,
        session: LanSyncSession,
        uri: Uri,
        onTransferCreated: suspend (String) -> Unit = {},
        onProgress: suspend (Long) -> Unit = {},
        resumeTransferId: String? = null,
    ): String = withContext(Dispatchers.IO) {
        check(AndroidLanSyncHost.state.value.allowSendFiles) { "Enable file sending in Device Sync before sharing a file." }
        val resolver = context.contentResolver
        val metadata = selectedFile(resolver, uri) ?: error("Choose a supported audio file with a known size.")
        withApi(session) { api ->
        val id = resumeTransferId ?: UUID.randomUUID().toString().replace("-", "")
        require(id.matches(Regex("[A-Za-z0-9_-]{1,96}"))) { "Invalid transfer id." }
        val staged = File(context.cacheDir, "lan-$id.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            resolver.openInputStream(uri)?.use { input -> staged.outputStream().buffered().use { output ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    require(total <= MAX_BYTES && total <= metadata.size) { "File exceeds the 500 MiB transfer limit." }
                    digest.update(buffer, 0, n)
                    output.write(buffer, 0, n)
                }
            } } ?: error("Could not read the selected file.")
            require(total == metadata.size) { "The selected file changed while it was being read." }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            var transfer = if (resumeTransferId == null) {
                api.offerFile(session, LanSyncTransferOffer(id, metadata.name, total, metadata.mime, hash))
                api.transferStatus(session, id)
            } else {
                api.transferStatus(session, id).also {
                    require(it.direction == "upload" && it.name == metadata.name && it.size == total &&
                        it.mime.equals(metadata.mime, ignoreCase = true) && it.sha256 == hash) {
                        "The selected file does not match the interrupted transfer."
                    }
                    require(it.status in setOf("pending", "accepted", "receiving")) {
                        "This transfer can no longer be resumed. Cancel it and send the file again."
                    }
                }
            }
            onTransferCreated(id)
            val deadline = System.currentTimeMillis() + 5 * 60_000L
            while (transfer.status == "pending" && System.currentTimeMillis() < deadline) {
                currentCoroutineContext().ensureActive()
                check(AndroidLanSyncHost.state.value.allowSendFiles) { "File sending was turned off while waiting for approval." }
                delay(750)
                transfer = api.transferStatus(session, id)
            }
            if (transfer.status !in setOf("accepted", "receiving")) error(
                when (transfer.status) {
                    "declined" -> "The receiving device declined the file."
                    else -> "The receiving device did not accept the file in time."
                },
            )
            var offset = transfer.received
            require(offset in 0..total && (offset == total || offset % CHUNK_BYTES == 0L)) {
                "The sync host returned an invalid resume offset."
            }
            onProgress(offset)
            FileInputStream(staged).buffered().use { input ->
                input.skipFully(offset)
                val chunk = ByteArray(CHUNK_BYTES)
                while (offset < total) {
                    currentCoroutineContext().ensureActive()
                    check(AndroidLanSyncHost.state.value.allowSendFiles) { "File sending was turned off during transfer." }
                    val expected = minOf(chunk.size.toLong(), total - offset).toInt()
                    var count = 0
                    while (count < expected) {
                        val n = input.read(chunk, count, expected - count)
                        if (n < 0) error("The staged file ended early.")
                        count += n
                    }
                    api.sendTransferChunk(session, id, offset, chunk.copyOf(count))
                    offset += count
                    onProgress(offset)
                }
            }
            val final = api.transferStatus(session, id)
            if (final.status != "complete") error(
                if (final.status in setOf("accepted", "receiving") && final.received == total) {
                    "The host retained the full file but could not save it yet. Check its storage, then resume."
                } else {
                    "The transfer stopped in state " + final.status + ". Resume it or cancel it explicitly."
                },
            )
            final.status
        } finally {
            staged.delete()
        }
        }
    }

    /** Resumes an accepted host-originated offer from the last durable local chunk. */
    suspend fun receiveSelectedOffer(
        context: Context,
        session: LanSyncSession,
        transfer: LanSyncFileTransfer,
        onProgress: suspend (Long) -> Unit = {},
    ): Uri = withContext(Dispatchers.IO) {
        require(transfer.direction == "download" && transfer.size in 1..MAX_BYTES)
        require(transfer.id.matches(Regex("[A-Za-z0-9_-]{1,96}")))
        check(AndroidLanSyncHost.state.value.allowReceiveFiles) { "Enable file receiving in Device Sync before accepting a file." }
        withApi(session) { api ->
            var remote = api.transferStatus(session, transfer.id)
            require(remote.direction == "download" && remote.size == transfer.size &&
                remote.name == transfer.name && remote.mime == transfer.mime &&
                remote.sha256 == transfer.sha256) { "The host's file offer changed. Cancel it and request a fresh offer." }
            if (remote.status == "pending") {
                api.acceptTransfer(session, transfer.id)
                remote = api.transferStatus(session, transfer.id)
            }
            require(remote.status in setOf("accepted", "sending")) {
                "This transfer cannot be resumed in state " + remote.status + "."
            }

            val staged = File(File(context.filesDir, "lan-sync-received").apply { mkdirs() }, transfer.id + ".part")
            require(remote.received in 0..transfer.size && (remote.received == transfer.size || remote.received % CHUNK_BYTES == 0L)) {
                "The host returned an invalid resume offset."
            }
            val existingLength = staged.takeIf { it.isFile }?.length() ?: 0L
            var localOffset = minOf(existingLength, remote.received)
            localOffset = if (localOffset == transfer.size) localOffset else localOffset / CHUNK_BYTES * CHUNK_BYTES
            var offset = localOffset
            RandomAccessFile(staged, "rw").use { output ->
                output.setLength(offset)
                output.seek(offset)
                onProgress(offset)
                while (offset < transfer.size) {
                    currentCoroutineContext().ensureActive()
                    check(AndroidLanSyncHost.state.value.allowReceiveFiles) { "File receiving was turned off during transfer. Resume after enabling it again." }
                    val piece = api.downloadTransferChunk(session, transfer.id, offset)
                    require(piece.size == transfer.size && piece.sha256 == transfer.sha256)
                    require(piece.bytes.isNotEmpty() && piece.bytes.size <= CHUNK_BYTES && piece.offset == offset)
                    output.write(piece.bytes)
                    output.fd.sync()
                    offset += piece.bytes.size
                    onProgress(offset)
                }
            }
            val hash = FileInputStream(staged).use(::sha256)
            if (hash != transfer.sha256 || !AndroidLanSyncHost.audioSignatureOk(staged, transfer.mime)) {
                staged.delete()
                error("The received file failed its checksum or media signature check. Resume to fetch it again.")
            }

            val existing = DownloadStore.existing(context, transfer.name)
            if (existing != null) {
                val existingHash = hashUri(context, existing)
                require(existingHash == hash) { "A different file with this name already exists on this device." }
                api.completeTransferDownload(session, transfer.id, hash)
                staged.delete()
                return@withApi existing
            }
            val pending = DownloadStore.begin(context, transfer.name, transfer.mime)
            val saved = try {
                FileInputStream(staged).use { input -> pending.openStream().use { input.copyTo(it) } }
                pending.commit()
            } catch (e: Exception) {
                pending.abort()
                throw e
            }
            api.completeTransferDownload(session, transfer.id, hash)
            staged.delete()
            saved
        }
    }

    private suspend fun <T> withApi(session: LanSyncSession, block: suspend (LanSyncClient) -> T): T {
        val (http, api) = createPinnedClient(session.tlsFingerprint)
        return try { block(api) } finally { http.close() }
    }

    private fun createPinnedClient(fingerprint: String): Pair<HttpClient, LanSyncClient> {
        require(fingerprint.matches(Regex("[0-9a-fA-F]{64}")))
        val pin = fingerprint.lowercase()
        fun matches(cert: X509Certificate): Boolean = MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded).joinToString("") { "%02x".format(it) } == pin
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
                throw CertificateException("Client certificates are not used by VYRAL LAN Sync.")
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                if (chain.isEmpty() || !matches(chain.first())) throw CertificateException("VYRAL LAN certificate fingerprint mismatch.")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
        val verifier = HostnameVerifier { _, session ->
            runCatching { matches(session.peerCertificates.first() as X509Certificate) }.getOrDefault(false)
        }
        val http = HttpClient(OkHttp) {
            engine {
                config {
                    sslSocketFactory(tls.socketFactory, trustManager)
                    hostnameVerifier(verifier)
                }
            }
        }
        return http to LanSyncClient(http, pin)
    }

    private data class SelectedFile(val name: String, val mime: String, val size: Long)

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hashUri(context: Context, uri: Uri): String {
        val input = if (uri.scheme == "file") uri.path?.let { FileInputStream(it) }
        else context.contentResolver.openInputStream(uri)
        return input?.use(::sha256) ?: error("Could not verify the existing audio file.")
    }

    private fun InputStream.skipFully(count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped > 0) remaining -= skipped
            else if (read() < 0) error("The staged file ended before its resume offset.")
            else remaining--
        }
    }

    private fun selectedFile(resolver: ContentResolver, uri: Uri): SelectedFile? = runCatching {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: return null
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) ?: name
                size = c.getLong(c.getColumnIndexOrThrow(OpenableColumns.SIZE))
            }
        }
        val mime = resolver.getType(uri)?.lowercase().orEmpty()
        val ext = name.substringAfterLast('.', "audio").lowercase().take(12).filter(Char::isLetterOrDigit)
        val stem = name.substringBeforeLast('.', name).take(180).ifBlank { "audio" }
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cc}]"), "_")
        val safeName = "$stem.$ext"
        if (mime !in AUDIO_MIME || size !in 1..MAX_BYTES) return null
        SelectedFile(safeName, mime, size)
    }.getOrNull()

    private val AUDIO_MIME = setOf(
        "audio/mpeg", "audio/mp4", "audio/aac", "audio/flac", "audio/x-flac", "audio/wav", "audio/x-wav",
        "audio/ogg", "audio/opus", "audio/vorbis", "audio/alac",
    )
}
