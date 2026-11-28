package app.vyral.night.data.sync

import android.content.ContentResolver
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.provider.OpenableColumns
import app.vyral.night.auth.EncryptedPrefs
import app.vyral.night.download.DownloadStore
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.math.BigInteger
import java.net.Inet4Address
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Date
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLServerSocket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import javax.security.auth.x500.X500Principal

/** Android/TV implementation of the existing encrypted LAN Sync v1 host. */
object AndroidLanSyncHost {
    internal val PORT_RANGE = 47731..47740
    private const val MAX_HTTP_BODY = 512 * 1024
    private const val MAX_TRANSFER = 500L * 1024 * 1024
    private const val CHUNK = 192 * 1024
    private const val TRANSFER_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    private const val PAIR_TTL_MS = 5 * 60_000L
    private const val CLOCK_SKEW_MS = 2 * 60_000L
    private const val PREFS = "lan_sync_secure"
    private const val PLAIN_PREFS = "lan_sync_fallback"
    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()
    private val lock = Any()
    private var server: RunningServer? = null
    private var startGeneration = 0L
    private var starting = false
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    data class PairRequest(val id: String, val name: String, val confirmationCode: String, val expiresAtMs: Long)
    data class Transfer(
        val id: String,
        val name: String,
        val size: Long,
        val mime: String,
        val sha256: String,
        val title: String?,
        val artist: String?,
        val status: String,
        val received: Long,
        val deviceId: String,
        val deviceName: String,
        /** `upload`: peer sends bytes here; `download`: peer fetches an accepted local file. */
        val direction: String,
    )
    data class Device(val id: String, val name: String)
    data class State(
        val running: Boolean = false,
        val address: String? = null,
        val error: String? = null,
        val pairingRequests: List<PairRequest> = emptyList(),
        val pairedDevices: List<Device> = emptyList(),
        val transfers: List<Transfer> = emptyList(),
        val remoteControlAllowed: Boolean = false,
        val allowSendFiles: Boolean = false,
        val allowReceiveFiles: Boolean = false,
        val findCode: String? = null,
        val tlsFingerprint: String? = null,
    )

    /** PlaybackService supplies callbacks on its own player thread; control is off until explicitly enabled. */
    interface PlaybackBridge {
        fun snapshot(): JsonObject
        fun command(body: JsonObject): Boolean
    }

    @Volatile private var playbackBridge: PlaybackBridge? = null

    fun setPlaybackBridge(bridge: PlaybackBridge?) { playbackBridge = bridge }

    fun start(context: Context): Boolean {
        val generation = synchronized(lock) {
            if (server != null) return true
            if (starting) return false
            starting = true
            ++startGeneration
        }
        val app = context.applicationContext
        val secrets = runCatching { AndroidLanSyncSecretStore.open(app) }.getOrElse {
            finishStart(generation, State(error = it.message ?: "Could not open secure LAN pairing storage."))
            return false
        }
        val address = lanAddress(app)?.hostAddress
        if (address == null) {
            val saved = EncryptedPrefs.open(app, PREFS, PLAIN_PREFS)
            finishStart(generation, State(
                error = "Connect this device to the same Wi-Fi or Ethernet network first.",
                remoteControlAllowed = saved.getBoolean("allowConnect", false),
                allowSendFiles = saved.getBoolean("allowSendFiles", false),
                allowReceiveFiles = saved.getBoolean("allowReceiveFiles", false),
            ))
            return false
        }
        val store = EncryptedPrefs.open(app, PREFS, PLAIN_PREFS)
        val hostId = store.getString("hostId", null) ?: uuid().also { store.edit().putString("hostId", it).apply() }
        val spaceId = store.getString("spaceId", null) ?: uuid().also { store.edit().putString("spaceId", it).apply() }
        val findCode = (1000 + random.nextInt(9000)).toString()
        store.edit().remove("findCode").apply()
        val identity = runCatching { AndroidLanTlsIdentity.loadOrCreate() }.getOrElse {
            finishStart(generation, State(
                error = it.message ?: "Could not create a secure local sync identity.",
                remoteControlAllowed = store.getBoolean("allowConnect", false),
                allowSendFiles = store.getBoolean("allowSendFiles", false),
                allowReceiveFiles = store.getBoolean("allowReceiveFiles", false),
            ))
            return false
        }
        val running = RunningServer(app, store, secrets, hostId, spaceId, findCode, address, identity)
        return runCatching {
            running.start()
            val snapshot = running.snapshot()
            val installed = synchronized(lock) {
                if (!starting || startGeneration != generation) false
                else {
                    server = running
                    starting = false
                    _state.value = snapshot
                    true
                }
            }
            if (!installed) running.stop()
            installed
        }.getOrElse {
            running.stop()
            finishStart(generation, State(
                error = it.message ?: "Could not start local sync.",
                remoteControlAllowed = store.getBoolean("allowConnect", false),
                allowSendFiles = store.getBoolean("allowSendFiles", false),
                allowReceiveFiles = store.getBoolean("allowReceiveFiles", false),
            ))
            false
        }
    }

    private fun finishStart(generation: Long, state: State) = synchronized(lock) {
        if (startGeneration == generation) {
            starting = false
            _state.value = state
        }
    }

    fun stop() {
        val previous = synchronized(lock) {
            startGeneration++
            starting = false
            val old = server
            server = null
            old
        }
        val current = previous?.snapshot() ?: _state.value
        synchronized(lock) {
            _state.value = State(
                remoteControlAllowed = current.remoteControlAllowed,
                allowSendFiles = current.allowSendFiles,
                allowReceiveFiles = current.allowReceiveFiles,
                tlsFingerprint = current.tlsFingerprint,
            )
        }
        previous?.stop()
    }

    fun refresh() { currentServer()?.let { _state.value = it.snapshot() } }

    fun loadPreferences(context: Context) {
        runCatching {
            AndroidLanSyncSecretStore.open(context.applicationContext)
            EncryptedPrefs.open(context.applicationContext, PREFS, PLAIN_PREFS)
        }.onSuccess { prefs ->
            _state.value = _state.value.copy(
                error = null,
                remoteControlAllowed = prefs.getBoolean("allowConnect", false),
                allowSendFiles = prefs.getBoolean("allowSendFiles", false),
                allowReceiveFiles = prefs.getBoolean("allowReceiveFiles", false),
            )
        }.onFailure {
            _state.value = _state.value.copy(error = it.message ?: "Could not open secure LAN pairing storage.")
        }
    }

    fun approvePairing(id: String) = currentServer()?.approvePairing(id)
    fun declinePairing(id: String) = currentServer()?.declinePairing(id)
    fun acceptTransfer(id: String) = currentServer()?.acceptTransfer(id)
    fun declineTransfer(id: String) = currentServer()?.declineTransfer(id)
    fun cancelTransfer(id: String) = currentServer()?.cancelTransfer(id)
    fun setRemoteControlAllowed(context: Context, allowed: Boolean) {
        EncryptedPrefs.open(context.applicationContext, PREFS, PLAIN_PREFS).edit().putBoolean("allowConnect", allowed).apply()
        currentServer()?.setRemoteControlAllowed(allowed) ?: run { _state.value = _state.value.copy(remoteControlAllowed = allowed) }
    }

    fun setAllowSendFiles(context: Context, allowed: Boolean) {
        EncryptedPrefs.open(context.applicationContext, PREFS, PLAIN_PREFS).edit().putBoolean("allowSendFiles", allowed).apply()
        currentServer()?.setAllowSendFiles(allowed) ?: run { _state.value = _state.value.copy(allowSendFiles = allowed) }
    }

    fun setAllowReceiveFiles(context: Context, allowed: Boolean) {
        EncryptedPrefs.open(context.applicationContext, PREFS, PLAIN_PREFS).edit().putBoolean("allowReceiveFiles", allowed).apply()
        currentServer()?.setAllowReceiveFiles(allowed) ?: run { _state.value = _state.value.copy(allowReceiveFiles = allowed) }
    }

    /** Stage a SAF-selected audio file as an offer to a paired peer; the peer must accept before downloading. */
    suspend fun offerLocalFile(context: Context, deviceId: String, uri: Uri): String? =
        currentServer()?.offerLocalFile(context.applicationContext, deviceId, uri)

    private fun currentServer(): RunningServer? = synchronized(lock) { server }

    private data class Pairing(
        val id: String,
        val name: String,
        val commit: String,
        val key: LanSyncCrypto.HostKey,
        val expiresAtMs: Long,
        var secret: ByteArray? = null,
        var confirmationCode: String? = null,
        var approvedDeviceId: String? = null,
        var denied: Boolean = false,
    )

    private data class StoredDevice(val id: String, val name: String, val key: ByteArray)
    private data class TransferRecord(
        val id: String,
        val name: String,
        val size: Long,
        val mime: String,
        val sha256: String,
        val title: String?,
        val artist: String?,
        val deviceId: String,
        val deviceName: String,
        val direction: String,
        var status: String,
        var received: Long = 0,
        val file: File,
        val createdAtMs: Long = System.currentTimeMillis(),
        var lastChunkOffset: Long = -1,
        var lastChunkSize: Int = 0,
        var lastChunkSha256: String? = null,
        var served: Long = 0,
        var lastServedOffset: Long = -1,
    )

    private class RunningServer(
        val context: Context,
        private val prefs: android.content.SharedPreferences,
        private val secrets: AndroidLanSyncSecretStore.Store,
        private val hostId: String,
        private val spaceId: String,
        private val findCode: String,
        private val address: String,
        private val tlsIdentity: AndroidLanTlsIdentity.Identity,
    ) {
        private val alive = AtomicBoolean(false)
        private val requests = ThreadPoolExecutor(
            4, 4, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(12),
        )
        private val pairings = linkedMapOf<String, Pairing>()
        private val transfers = linkedMapOf<String, TransferRecord>()
        private var pendingLocalOffers = 0
        private val nonces = linkedMapOf<String, Long>()
        private var listener: ServerSocket? = null
        @Volatile private var boundPort = 0
        private var allowedConnect = prefs.getBoolean("allowConnect", false)
        private var allowedSendFiles = prefs.getBoolean("allowSendFiles", false)
        private var allowedReceiveFiles = prefs.getBoolean("allowReceiveFiles", false)
        private val incoming = File(context.filesDir, "lan-transfers").apply { mkdirs() }
        private val keyPrefix = "device."

        fun start() {
            restoreTransfers()
            var socket: SSLServerSocket? = null
            var lastBindFailure: BindException? = null
            for (port in PORT_RANGE) {
                val candidate = tlsIdentity.context.serverSocketFactory.createServerSocket() as SSLServerSocket
                candidate.reuseAddress = true
                candidate.enabledProtocols = candidate.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
                try {
                    candidate.bind(InetSocketAddress(address, port), 16)
                    socket = candidate
                    boundPort = port
                    break
                } catch (e: BindException) {
                    lastBindFailure = e
                    candidate.close()
                }
            }
            val bound = socket ?: throw BindException("VYRAL could not bind any local sync port in ${PORT_RANGE.first}–${PORT_RANGE.last}.").also {
                lastBindFailure?.let(it::initCause)
            }
            listener = bound
            alive.set(true)
            Thread({ acceptLoop(bound) }, "vyral-lan-host").apply { isDaemon = true; start() }
        }

        fun stop() {
            alive.set(false)
            runCatching { listener?.close() }
            requests.shutdownNow()
            synchronized(this) {
                transfers.clear()
                pairings.values.forEach { it.secret?.fill(0) }
                pairings.clear()
                nonces.clear()
            }
        }

        @Synchronized fun snapshot(): State = State(
            running = alive.get(),
            address = if (alive.get()) "https://$address:$boundPort" else null,
            pairingRequests = pairings.values.mapNotNull { pair ->
                pair.confirmationCode?.let { PairRequest(pair.id, pair.name, it, pair.expiresAtMs) }
            },
            pairedDevices = devices().map { Device(it.id, it.name) },
            transfers = transfers.values.map(::toPublic),
            remoteControlAllowed = allowedConnect,
            allowSendFiles = allowedSendFiles,
            allowReceiveFiles = allowedReceiveFiles,
            findCode = findCode,
            tlsFingerprint = tlsIdentity.fingerprint,
        )

        @Synchronized fun approvePairing(id: String) {
            val pair = pairings[id] ?: return
            val key = pair.secret ?: return
            val deviceId = uuid()
            try {
                secrets.putString("$keyPrefix$deviceId", LanSyncCrypto.encodeBase64Url(key))
                prefs.edit().putString("${keyPrefix}${deviceId}.name", pair.name).apply()
            } catch (e: Exception) {
                runCatching { secrets.remove("$keyPrefix$deviceId") }
                _state.value = _state.value.copy(error = e.message ?: "Could not store the secure pairing key.")
                return
            }
            pair.approvedDeviceId = deviceId
            pair.secret = null
            publish()
        }

        @Synchronized fun declinePairing(id: String) {
            pairings[id]?.let { it.denied = true; it.secret?.fill(0); it.secret = null }
            publish()
        }

        @Synchronized fun setRemoteControlAllowed(allowed: Boolean) {
            allowedConnect = allowed
            prefs.edit().putBoolean("allowConnect", allowed).apply()
            publish()
        }

        @Synchronized fun setAllowSendFiles(allowed: Boolean) {
            allowedSendFiles = allowed
            prefs.edit().putBoolean("allowSendFiles", allowed).apply()
            publish()
        }

        @Synchronized fun setAllowReceiveFiles(allowed: Boolean) {
            allowedReceiveFiles = allowed
            prefs.edit().putBoolean("allowReceiveFiles", allowed).apply()
            publish()
        }

        @Synchronized fun acceptTransfer(id: String) {
            if (!allowedReceiveFiles) return
            transfers[id]?.takeIf { it.direction == "upload" && it.status == "pending" }?.let {
                it.status = "accepted"
                it.received = 0
                it.file.parentFile?.mkdirs()
                it.file.writeBytes(ByteArray(0))
                if (!persistTransfer(it)) {
                    it.status = "pending"
                    it.file.delete()
                    _state.value = _state.value.copy(error = "Could not save the transfer state. Enable storage space and retry.")
                }
            }
            publish()
        }

        @Synchronized fun declineTransfer(id: String) {
            transfers[id]?.takeIf { it.direction == "upload" && it.status == "pending" }?.let {
                it.status = "declined"
                removeTransferStorage(it)
            }
            publish()
        }

        @Synchronized fun cancelTransfer(id: String) {
            transfers[id]?.let {
                it.status = "cancelled"
                removeTransferStorage(it)
            }
            publish()
        }

        suspend fun offerLocalFile(context: Context, deviceId: String, uri: Uri): String? = withContext(Dispatchers.IO) {
            val meta = queryFile(context.contentResolver, uri) ?: return@withContext null
            if (meta.size !in 1..MAX_TRANSFER || !supportedAudio(meta.mime)) return@withContext null
            val device = synchronized(this@RunningServer) {
                if (!allowedSendFiles || activeTransferCount() + pendingLocalOffers >= 8) null
                else devices().firstOrNull { it.id == deviceId }?.also { pendingLocalOffers++ }
            } ?: return@withContext null
            val id = uuid()
            val file = File(incoming, "$id.offer")
            val digest = MessageDigest.getInstance("SHA-256")
            var bytesCopied = 0L
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().buffered().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            bytesCopied += n
                            if (bytesCopied > MAX_TRANSFER || bytesCopied > meta.size) throw IllegalArgumentException("File exceeds the transfer limit")
                            digest.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                        }
                    }
                } ?: return@withContext null
                if (bytesCopied != meta.size) throw IllegalStateException("The selected file changed while it was being read")
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                synchronized(this@RunningServer) {
                    if (!allowedSendFiles || !alive.get()) {
                        file.delete()
                        null
                    } else {
                        val record = TransferRecord(
                            id, meta.name, bytesCopied, meta.mime, sha, meta.title, meta.artist,
                            device.id, device.name, "download", "pending", 0, file,
                        )
                        transfers[id] = record
                        if (!persistTransfer(record)) {
                            transfers.remove(id)
                            removeTransferStorage(record)
                            _state.value = _state.value.copy(error = "Could not save this transfer for retry.")
                            return@synchronized null
                        }
                        publish()
                        id
                    }
                }
            } catch (e: CancellationException) {
                file.delete()
                throw e
            } catch (_: Exception) {
                file.delete()
                null
            } finally {
                synchronized(this@RunningServer) { pendingLocalOffers-- }
            }
        }

        private data class FileMeta(val name: String, val mime: String, val size: Long, val title: String?, val artist: String?)

        private fun queryFile(resolver: ContentResolver, uri: Uri): FileMeta? = runCatching {
            var name = uri.lastPathSegment?.substringAfterLast('/') ?: "audio"
            var size = -1L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) ?: name
                    size = c.getLong(c.getColumnIndexOrThrow(OpenableColumns.SIZE))
                }
            }
            val mime = resolver.getType(uri)?.lowercase().orEmpty()
            val base = name.substringBeforeLast('.', name).take(180).ifBlank { "audio" }
            val ext = name.substringAfterLast('.', "bin").lowercase().take(12).filter(Char::isLetterOrDigit)
            val safeName = "$base.$ext".replace(Regex("[\\\\/:*?\"<>|\\p{Cc}]"), "_")
            if (size < 0 || safeName.length > 240) return@runCatching null
            FileMeta(safeName, mime, size, null, null)
        }.getOrNull()

        private fun acceptLoop(socket: ServerSocket) {
            while (alive.get()) {
                try {
                    val client = socket.accept() as SSLSocket
                    client.soTimeout = 10_000
                    client.startHandshake()
                    try {
                        requests.execute { serve(client) }
                    } catch (_: java.util.concurrent.RejectedExecutionException) {
                        client.close()
                    }
                } catch (_: Exception) {
                    if (alive.get()) continue else return
                }
            }
        }

        private data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

        private fun serve(socket: Socket) {
            socket.use { client ->
                runCatching {
                    val request = readRequest(client.getInputStream())
                    val (status, body, type) = handle(request)
                    val output = BufferedOutputStream(client.getOutputStream())
                    val reason = when (status) { 200 -> "OK"; 201 -> "Created"; 202 -> "Accepted"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 409 -> "Conflict"; 413 -> "Payload Too Large"; 422 -> "Unprocessable Entity"; 429 -> "Too Many Requests"; else -> "Internal Server Error" }
                    val headers = "HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n"
                    output.write(headers.toByteArray(Charsets.US_ASCII))
                    output.write(body)
                    output.flush()
                }
            }
        }

        private fun readRequest(input: InputStream): Request {
            val line = readLine(input) ?: throw IllegalArgumentException("Missing request line")
            val pieces = line.split(' ', limit = 3)
            require(pieces.size == 3 && pieces[2].startsWith("HTTP/1."))
            val headers = linkedMapOf<String, String>()
            var total = line.length
            while (true) {
                val header = readLine(input) ?: throw IllegalArgumentException("Incomplete headers")
                total += header.length
                require(total <= 16 * 1024) { "Headers too large" }
                if (header.isEmpty()) break
                val colon = header.indexOf(':')
                require(colon > 0)
                headers[header.substring(0, colon).trim().lowercase()] = header.substring(colon + 1).trim()
            }
            require(headers["transfer-encoding"].isNullOrEmpty()) { "Chunked requests are unsupported" }
            val size = headers["content-length"]?.toIntOrNull() ?: 0
            require(size in 0..MAX_HTTP_BODY) { "Request body too large" }
            val body = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val n = input.read(body, offset, size - offset)
                if (n < 0) throw IllegalArgumentException("Incomplete body")
                offset += n
            }
            return Request(pieces[0].uppercase(), pieces[1], headers, body)
        }

        private fun readLine(input: InputStream): String? {
            val bytes = ByteArrayOutputStream()
            while (bytes.size() <= 16 * 1024) {
                val next = input.read()
                if (next < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.US_ASCII.name())
                if (next == '\n'.code) break
                if (next != '\r'.code) bytes.write(next)
            }
            require(bytes.size() <= 16 * 1024) { "HTTP line too long" }
            return bytes.toString(Charsets.US_ASCII.name())
        }

        private fun handle(request: Request): Triple<Int, ByteArray, String> {
            val path = request.path.substringBefore('?')
            if (request.method == "GET" && path == "/lan/hello") return plain(200, buildJsonObject {
                put("app", "vyral"); put("api", 1); put("version", 1); put("name", "VYRAL Android"); put("id", hostId); put("code", findCode); put("bt", false)
                put("tlsFingerprint", tlsIdentity.fingerprint); put("port", boundPort)
            })
            if (request.method == "POST" && path == "/lan/pair/start") return pairStart(request.body)
            if (request.method == "POST" && path == "/lan/pair/reveal") return pairReveal(request.body)
            if (request.method == "POST" && path == "/lan/pair/claim") return pairClaim(request.body)
            if (request.method != "POST" || path != "/lan/v1") return plain(404, obj("message" to "Not found."))
            val deviceId = request.headers["x-vyral-device"].orEmpty()
            val key = device(deviceId)?.key ?: return plain(401, obj("message" to "This device is not paired with VYRAL."))
            val sealed = request.body.toString(Charsets.UTF_8)
            val opened = LanSyncCrypto.open(key, deviceId.encodeToByteArray(), sealed)?.decodeToString()
                ?: return plain(401, obj("message" to "This device is not paired with VYRAL."))
            val envelope = runCatching { json.parseToJsonElement(opened).jsonObject }.getOrNull()
                ?: return plain(401, obj("message" to "This device is not paired with VYRAL."))
            val nonce = envelope.str("n").orEmpty()
            val timestamp = envelope.long("t") ?: 0L
            if (nonce.length !in 16..64 || kotlin.math.abs(System.currentTimeMillis() - timestamp) > CLOCK_SKEW_MS || !rememberNonce(deviceId, nonce)) {
                return sealedResponse(key, deviceId, nonce, 401, obj("message" to "This request expired or was already used."))
            }
            val method = envelope.str("m")?.uppercase().orEmpty()
            val innerPath = envelope.str("p")?.takeIf { it.startsWith("/v1/") && !it.contains("://") && '#' !in it }
                ?: return sealedResponse(key, deviceId, nonce, 422, obj("message" to "Invalid sync path."))
            val body = envelope["b"] ?: JsonNull
            val (status, payload) = route(deviceId, method, innerPath, body)
            return sealedResponse(key, deviceId, nonce, status, payload)
        }

        @Synchronized private fun pairStart(raw: ByteArray): Triple<Int, ByteArray, String> {
            val body = parse(raw) ?: return plain(422, obj("message" to "Body must be JSON."))
            val name = body.str("deviceName")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 80 }
            val commit = body.str("commit")?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
            if (name == null || commit == null) return plain(422, obj("message" to "deviceName and commit are required."))
            expirePairings()
            if (pairings.size >= 4) return plain(429, obj("message" to "Too many pairing requests."))
            val pairId = uuid()
            val hostKey = LanSyncCrypto.newHostKey()
            pairings[pairId] = Pairing(pairId, name, commit.lowercase(), hostKey, System.currentTimeMillis() + PAIR_TTL_MS)
            publish()
            return plain(201, obj("pairingId" to pairId, "hostKey" to LanSyncCrypto.encodeBase64Url(hostKey.publicKey), "hostId" to hostId, "hostName" to "VYRAL Android"))
        }

        @Synchronized private fun pairReveal(raw: ByteArray): Triple<Int, ByteArray, String> {
            val body = parse(raw) ?: return plain(422, obj("message" to "Body must be JSON."))
            val pair = pairings[body.str("pairingId")] ?: return plain(404, obj("message" to "That pairing expired."))
            if (pair.confirmationCode != null || pair.denied) return plain(409, obj("message" to "This pairing is no longer available."))
            val public = body.str("deviceKey")?.let(LanSyncCrypto::decodeBase64Url)
                ?.takeIf { it.size == 65 && it[0] == 0x04.toByte() }
                ?: return plain(422, obj("message" to "Invalid pairing key."))
            if (LanSyncCrypto.sha256Hex(public) != pair.commit) {
                pairings.remove(pair.id)
                publish()
                return plain(401, obj("message" to "The device key did not match its commitment."))
            }
            val derived = pair.key.agree(public) ?: return plain(422, obj("message" to "Invalid pairing key."))
            pair.secret = derived.copyKeyForSecureStorage()
            pair.confirmationCode = derived.confirmationCode
            publish()
            return plain(200, obj("ok" to true))
        }

        @Synchronized private fun pairClaim(raw: ByteArray): Triple<Int, ByteArray, String> {
            val body = parse(raw) ?: return plain(422, obj("message" to "Body must be JSON."))
            val id = body.str("pairingId") ?: return plain(404, obj("message" to "That pairing expired."))
            val pair = pairings[id] ?: return plain(404, obj("message" to "That pairing expired."))
            return when {
                pair.denied -> { pairings.remove(id); publish(); plain(403, obj("message" to "The device was declined.")) }
                pair.approvedDeviceId != null -> {
                    val deviceId = pair.approvedDeviceId!!
                    pairings.remove(id)
                    publish()
                    plain(200, obj("deviceId" to deviceId, "spaceId" to spaceId, "hostId" to hostId, "hostName" to "VYRAL Android"))
                }
                else -> plain(202, obj("pending" to true))
            }
        }

        @Synchronized private fun route(deviceId: String, method: String, path: String, body: JsonElement): Pair<Int, JsonElement> {
            val split = path.split('?', limit = 2)
            val route = split[0]
            val query = split.getOrNull(1).orEmpty()
            if (route == "/v1/files") return 200 to obj("files" to JsonArray(emptyList())) // Never expose the device's full download library.
            if (route == "/v1/files/transfers/offer" && method == "POST") return offerFromPeer(deviceId, body.jsonObject)
            if (route == "/v1/files/transfers" && method == "GET") return 200 to obj("transfers" to JsonArray(
                transfers.values.filter { it.deviceId == deviceId }.map(::transferJson),
            ))
            val transferPath = route.removePrefix("/v1/files/transfers/")
            if (route.startsWith("/v1/files/transfers/") && transferPath.isNotEmpty()) {
                val segments = transferPath.split('/')
                val id = segments.firstOrNull()?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,96}")) }
                    ?: return 404 to obj("message" to "That transfer is not here.")
                val record = transfers[id]?.takeIf { it.deviceId == deviceId } ?: return 404 to obj("message" to "That transfer is not here.")
                when {
                    segments.size == 1 && method == "GET" -> {
                        if (record.direction == "upload" && record.status == "receiving" && record.received == record.size) {
                            finalizeIncomingUpload(record)
                        }
                        return 200 to transferJson(record)
                    }
                    segments.size == 2 && method == "POST" && segments[1] == "accept" && record.direction == "download" && record.status == "pending" -> {
                        if (!allowedSendFiles) return 403 to obj("message" to "File sending is turned off on this device.")
                        record.status = "accepted"
                        if (!persistTransfer(record)) { record.status = "pending"; return 500 to obj("message" to "Could not save the transfer state for retry.") }
                        publish(); return 200 to obj("id" to id, "status" to record.status)
                    }
                    segments.size == 2 && method == "POST" && segments[1] == "decline" && record.direction == "download" && record.status == "pending" -> {
                        record.status = "declined"; removeTransferStorage(record); publish(); return 200 to obj("id" to id, "status" to record.status)
                    }
                    segments.size == 2 && segments[1] == "chunk" && method == "POST" -> return receiveChunk(record, body.jsonObject)
                    segments.size == 2 && segments[1] == "chunk" && method == "GET" -> return sendChunk(record, query)
                    segments.size == 2 && segments[1] == "complete" && method == "POST" -> return completeDownload(record, body.jsonObject)
                    segments.size == 1 && method == "DELETE" -> {
                        if (record.status !in setOf("pending", "accepted", "receiving", "sending")) {
                            return 409 to obj("message" to "This transfer is already finished.")
                        }
                        record.status = "cancelled"; removeTransferStorage(record); publish(); return 200 to obj("ok" to true, "status" to record.status)
                    }
                }
            }
            if (route == "/v1/connect") {
                if (!allowedConnect) return 403 to obj("code" to "connect_off", "message" to "Remote playback control is turned off on this device.")
                return when (method) {
                    "GET" -> (playbackBridge?.snapshot() ?: obj("track" to JsonNull, "playing" to false)) .let { 200 to it }
                    "POST" -> {
                        val accepted = playbackBridge?.command(body.jsonObject) == true
                        if (accepted) 202 to obj("queued" to true) else 503 to obj("message" to "Playback is not available right now.")
                    }
                    else -> 405 to obj("message" to "Method not allowed.")
                }
            }
            return 404 to obj("message" to "Not found.")
        }

        @Synchronized private fun offerFromPeer(deviceId: String, body: JsonObject): Pair<Int, JsonElement> {
            if (!allowedReceiveFiles) return 403 to obj("message" to "File receiving is turned off on this device.")
            val id = body.str("id")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,96}")) }
                ?: return 422 to obj("message" to "Invalid transfer id.")
            val name = safeName(body.str("name")) ?: return 422 to obj("message" to "Invalid file name.")
            val size = body.long("size")?.takeIf { it in 1..MAX_TRANSFER } ?: return 413 to obj("message" to "File size is not supported.")
            val mime = body.str("mime")?.lowercase()?.takeIf(::supportedAudio) ?: return 422 to obj("message" to "Only supported audio files can be shared.")
            val sha = body.str("sha256")?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
                ?: return 422 to obj("message" to "Invalid file checksum.")
            if (transfers.containsKey(id) || activeTransferCount() >= 8) return 429 to obj("message" to "Too many transfers are pending.")
            val device = devices().firstOrNull { it.id == deviceId } ?: return 401 to obj("message" to "This device is no longer paired.")
            val title = body.str("title")?.take(300)
            val artist = body.str("artist")?.take(300)
            val record = TransferRecord(id, name, size, mime, sha, title, artist, deviceId, device.name, "upload", "pending", 0, File(incoming, "$id.part"))
            transfers[id] = record
            if (!persistTransfer(record)) {
                transfers.remove(id)
                removeTransferStorage(record)
                return 500 to obj("message" to "Could not save this transfer for retry.")
            }
            publish()
            return 201 to obj("id" to id, "status" to "pending")
        }

        @Synchronized private fun receiveChunk(record: TransferRecord, body: JsonObject): Pair<Int, JsonElement> {
            if (!allowedReceiveFiles) return 403 to obj("message" to "File receiving is turned off on this device.")
            if (record.direction != "upload") return 409 to obj("message" to "This transfer does not accept uploaded chunks.")
            val offset = body.long("offset")?.takeIf { it >= 0 } ?: return 422 to obj("message" to "Invalid chunk offset.")
            val bytes = body.str("data")?.let(LanSyncCrypto::decodeBase64Url) ?: return 422 to obj("message" to "Invalid chunk data.")
            if (bytes.isEmpty() || bytes.size > CHUNK) return 409 to obj("message" to "Chunks must be sent in order and within the offered size.")
            val chunkSha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            if (offset == record.lastChunkOffset) {
                if (bytes.size != record.lastChunkSize || chunkSha != record.lastChunkSha256) {
                    return 409 to obj("message" to "A retried chunk must match the last received bytes exactly.")
                }
                return 200 to obj("received" to record.received, "status" to record.status)
            }
            if (record.status !in setOf("accepted", "receiving")) return 409 to obj("message" to "The recipient has not accepted this transfer.")
            if (record.received + bytes.size > record.size) return 409 to obj("message" to "Chunks must be sent in order and within the offered size.")
            if (offset != record.received) return 409 to obj("message" to "Chunks must be sent in order and within the offered size.")
            val previousReceived = record.received
            val previousStatus = record.status
            val previousChunkOffset = record.lastChunkOffset
            val previousChunkSize = record.lastChunkSize
            val previousChunkSha = record.lastChunkSha256
            if (record.file.length() != previousReceived) return 500 to obj("message" to "The saved transfer offset no longer matches its partial file. Cancel it and send again.")
            runCatching {
                FileOutputStream(record.file, true).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
            }.onFailure { return 500 to obj("message" to "Could not write the received file.") }
            record.lastChunkOffset = offset
            record.lastChunkSize = bytes.size
            record.lastChunkSha256 = chunkSha
            record.received += bytes.size
            record.status = "receiving"
            if (!persistTransfer(record)) {
                runCatching { RandomAccessFile(record.file, "rw").use { it.setLength(previousReceived) } }
                record.received = previousReceived
                record.status = previousStatus
                record.lastChunkOffset = previousChunkOffset
                record.lastChunkSize = previousChunkSize
                record.lastChunkSha256 = previousChunkSha
                return 500 to obj("message" to "Could not save transfer progress. Free storage space and retry.")
            }
            if (record.received < record.size) {
                publish()
                return 200 to obj("received" to record.received, "status" to record.status)
            }
            return finalizeIncomingUpload(record)
        }

        @Synchronized private fun sendChunk(record: TransferRecord, query: String): Pair<Int, JsonElement> {
            if (!allowedSendFiles) return 403 to obj("message" to "File sending is turned off on this device.")
            if (record.direction != "download" || record.status !in setOf("accepted", "sending")) return 409 to obj("message" to "Accept the transfer before downloading.")
            val offset = query.split('&').firstNotNullOfOrNull { it.substringAfter("offset=", "").toLongOrNull() }
                ?.takeIf { it >= 0 && it < record.size } ?: return 422 to obj("message" to "Invalid chunk offset.")
            val retry = offset < record.served
            if (!retry && offset != record.served) return 409 to obj("message" to "Download chunks must be requested in order.")
            val bytes = runCatching {
                record.file.inputStream().use { input ->
                    input.skipFully(offset)
                    val buffer = ByteArray(minOf(CHUNK.toLong(), record.size - offset).toInt())
                    var at = 0
                    while (at < buffer.size) {
                        val n = input.read(buffer, at, buffer.size - at)
                        if (n < 0) break
                        at += n
                    }
                    if (at != buffer.size) throw java.io.EOFException("The offered file changed during transfer.")
                    buffer
                }
            }.getOrElse { return 500 to obj("message" to "The offered file is no longer available.") }
            if (bytes.isEmpty() || offset + bytes.size > record.size) return 500 to obj("message" to "The offered file changed during transfer.")
            val oldServed = record.served
            val oldReceived = record.received
            val oldLastOffset = record.lastServedOffset
            if (!retry) {
                record.lastServedOffset = offset
                record.served += bytes.size
                record.received = record.served
            }
            record.status = "sending"
            if (!retry && !persistTransfer(record)) {
                record.served = oldServed
                record.received = oldReceived
                record.lastServedOffset = oldLastOffset
                record.status = if (oldServed == 0L) "accepted" else "sending"
                return 500 to obj("message" to "Could not save download progress. Free storage space and retry.")
            }
            publish()
            return 200 to obj("id" to record.id, "data" to LanSyncCrypto.encodeBase64Url(bytes), "offset" to offset, "size" to record.size, "sha256" to record.sha256)
        }

        @Synchronized private fun completeDownload(record: TransferRecord, body: JsonObject): Pair<Int, JsonElement> {
            if (!allowedSendFiles) return 403 to obj("message" to "File sending is turned off on this device.")
            if (record.direction != "download" || record.status !in setOf("accepted", "sending")) return 409 to obj("message" to "This download is not accepted.")
            if (record.served != record.size) return 409 to obj("message" to "The receiver has not requested every byte of this file yet.")
            if (body.str("sha256")?.equals(record.sha256, ignoreCase = true) != true) return 422 to obj("message" to "Downloaded file checksum did not match.")
            record.status = "complete"
            removeTransferStorage(record)
            publish()
            return 200 to obj("done" to true, "status" to "complete")
        }

        private fun sealedResponse(key: ByteArray, deviceId: String, nonce: String, status: Int, payload: JsonElement) =
            Triple(200, LanSyncCrypto.seal(key, "resp:$deviceId".encodeToByteArray(), obj("s" to status, "n" to nonce, "b" to payload).toString().encodeToByteArray()).encodeToByteArray(), "text/plain; charset=utf-8")

        private fun rememberNonce(deviceId: String, nonce: String): Boolean = synchronized(nonces) {
            val now = System.currentTimeMillis()
            nonces.entries.removeAll { it.value < now }
            if (nonces.size >= 10_000) nonces.clear()
            val key = "$deviceId/$nonce"
            if (nonces.containsKey(key)) false else { nonces[key] = now + 2 * CLOCK_SKEW_MS; true }
        }

        private fun device(id: String): StoredDevice? {
            if (!id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) return null
            val secret = secrets.getString("$keyPrefix$id")?.let(LanSyncCrypto::decodeBase64Url)
                ?.takeIf { it.size == 32 } ?: return null
            return StoredDevice(id, prefs.getString("${keyPrefix}${id}.name", null) ?: "VYRAL device", secret)
        }

        private fun devices(): List<StoredDevice> = secrets.keys(keyPrefix)
            .filterNot { it.endsWith(".name") }
            .mapNotNull { key -> key.removePrefix(keyPrefix).let(::device) }

        private fun activeTransferCount(): Int = transfers.values.count {
            it.status in setOf("pending", "accepted", "receiving", "sending")
        }

        private fun manifestFile(id: String) = File(incoming, "$id.manifest")

        private fun persistTransfer(record: TransferRecord): Boolean {
            if (record.id !in transfers || record.status !in setOf("pending", "accepted", "receiving", "sending")) return false
            val bytes = obj(
                "version" to 1,
                "id" to record.id,
                "name" to record.name,
                "size" to record.size,
                "mime" to record.mime,
                "sha256" to record.sha256,
                "title" to record.title,
                "artist" to record.artist,
                "deviceId" to record.deviceId,
                "deviceName" to record.deviceName,
                "direction" to record.direction,
                "status" to record.status,
                "received" to record.received,
                "createdAtMs" to record.createdAtMs,
                "lastChunkOffset" to record.lastChunkOffset,
                "lastChunkSize" to record.lastChunkSize,
                "lastChunkSha256" to record.lastChunkSha256,
                "served" to record.served,
                "lastServedOffset" to record.lastServedOffset,
            ).toString().toByteArray(Charsets.UTF_8)
            if (bytes.size > 16 * 1024) return false
            val target = manifestFile(record.id)
            val temporary = File(incoming, "${record.id}.manifest.tmp")
            return runCatching {
                FileOutputStream(temporary).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
                try {
                    Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                true
            }.getOrElse {
                temporary.delete()
                false
            }
        }

        private fun removeTransferStorage(record: TransferRecord) {
            record.file.delete()
            manifestFile(record.id).delete()
            File(incoming, "${record.id}.manifest.tmp").delete()
        }

        /** Restores active transfers only for paired peers; byte offsets and files are checked before reuse. */
        private fun restoreTransfers() {
            incoming.listFiles().orEmpty().filter { it.name.endsWith(".manifest.tmp") }.forEach(File::delete)
            val paired = devices().associateBy { it.id }
            val now = System.currentTimeMillis()
            val manifests = incoming.listFiles().orEmpty().filter { it.name.endsWith(".manifest") }
            val restoredFiles = mutableSetOf<String>()
            manifests.forEach { manifest ->
                runCatching {
                    val value = Json.parseToJsonElement(manifest.readText(Charsets.UTF_8)).jsonObject
                    require(value.long("version") == 1L)
                    val id = value.str("id")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,96}")) } ?: error("Invalid id")
                    require(manifest.name == "$id.manifest")
                    val name = safeName(value.str("name")) ?: error("Invalid name")
                    val size = value.long("size")?.takeIf { it in 1..MAX_TRANSFER } ?: error("Invalid size")
                    val mime = value.str("mime")?.lowercase()?.takeIf(::supportedAudio) ?: error("Invalid media type")
                    val sha = value.str("sha256")?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: error("Invalid hash")
                    val deviceId = value.str("deviceId")?.takeIf { it in paired } ?: error("Peer is no longer paired")
                    val peer = paired.getValue(deviceId)
                    val direction = value.str("direction")?.takeIf { it == "upload" || it == "download" } ?: error("Invalid direction")
                    var status = value.str("status")?.takeIf { it in setOf("pending", "accepted", "receiving", "sending") } ?: error("Invalid status")
                    val received = value.long("received")?.takeIf { it in 0..size } ?: error("Invalid offset")
                    val served = value.long("served")?.takeIf { it in 0..size } ?: error("Invalid served offset")
                    val createdAt = value.long("createdAtMs")?.takeIf { it in 1..(now + 60_000) } ?: error("Invalid timestamp")
                    if (now - createdAt > TRANSFER_RETENTION_MS) error("Transfer expired")
                    val extension = if (direction == "upload") "part" else "offer"
                    val data = File(incoming, "${id}.$extension")
                    require(data.isFile && data.canonicalFile.parentFile == incoming.canonicalFile)
                    val record = TransferRecord(
                        id = id,
                        name = name,
                        size = size,
                        mime = mime,
                        sha256 = sha,
                        title = value.str("title")?.take(300),
                        artist = value.str("artist")?.take(300),
                        deviceId = deviceId,
                        deviceName = peer.name,
                        direction = direction,
                        status = status,
                        received = received,
                        file = data,
                        createdAtMs = createdAt,
                        lastChunkOffset = value.long("lastChunkOffset") ?: -1,
                        lastChunkSize = (value.long("lastChunkSize") ?: 0).toInt(),
                        lastChunkSha256 = value.str("lastChunkSha256"),
                        served = served,
                        lastServedOffset = value.long("lastServedOffset") ?: -1,
                    )
                    if (direction == "download") {
                        require(data.length() == size && sha256(data.inputStream()) == sha && audioSignatureOk(data, mime))
                        require(served == received)
                    } else {
                        if (status == "pending") require(received == 0L)
                        var offset = minOf(data.length(), received)
                        offset = if (offset == size) size else offset / CHUNK * CHUNK
                        if (offset != received || data.length() != offset) RandomAccessFile(data, "rw").use { it.setLength(offset) }
                        record.received = offset
                        if (offset == 0L) {
                            record.lastChunkOffset = -1
                            record.lastChunkSize = 0
                            record.lastChunkSha256 = null
                            if (status == "receiving") status = "accepted"
                        } else {
                            val lastSize = minOf(CHUNK.toLong(), offset).toInt()
                            val lastOffset = offset - lastSize
                            val actual = sha256Range(data, lastOffset, lastSize) ?: error("Partial chunk is unreadable")
                            if (record.lastChunkSha256 != actual) {
                                RandomAccessFile(data, "rw").use { it.setLength(0) }
                                record.received = 0
                                record.lastChunkOffset = -1
                                record.lastChunkSize = 0
                                record.lastChunkSha256 = null
                                status = "accepted"
                            } else {
                                record.lastChunkOffset = lastOffset
                                record.lastChunkSize = lastSize
                                record.lastChunkSha256 = actual
                            }
                        }
                        record.status = status
                    }
                    transfers[id] = record
                    restoredFiles += data.name
                    if (direction == "upload" && record.status == "receiving" && record.received == record.size) {
                        finalizeIncomingUpload(record)
                    } else if (!persistTransfer(record)) {
                        error("Could not retain transfer state")
                    }
                }.onFailure {
                    val id = manifest.name.removeSuffix(".manifest")
                    if (id.matches(Regex("[A-Za-z0-9_-]{1,96}"))) {
                        File(incoming, "${id}.part").delete()
                        File(incoming, "${id}.offer").delete()
                        manifest.delete()
                    }
                }
            }
            incoming.listFiles().orEmpty()
                .filter { (it.name.endsWith(".part") || it.name.endsWith(".offer")) && it.name !in restoredFiles }
                .forEach(File::delete)
        }

        private fun sha256Range(file: File, offset: Long, length: Int): String? = runCatching {
            RandomAccessFile(file, "r").use { input ->
                input.seek(offset)
                val digest = MessageDigest.getInstance("SHA-256")
                val bytes = ByteArray(length)
                var read = 0
                while (read < length) {
                    val amount = input.read(bytes, read, length - read)
                    if (amount < 0) error("Incomplete saved chunk")
                    read += amount
                }
                digest.digest(bytes).joinToString("") { "%02x".format(it) }
            }
        }.getOrNull()

        private fun finalizeIncomingUpload(record: TransferRecord): Pair<Int, JsonObject> {
            val digest = runCatching { record.file.inputStream().use(::sha256) }.getOrDefault("")
            if (digest != record.sha256 || !audioSignatureOk(record.file, record.mime)) {
                record.status = "failed"
                record.received = 0
                removeTransferStorage(record)
                publish()
                return 422 to obj("message" to "File checksum did not match.")
            }
            val existing = DownloadStore.existing(context, record.name)
            if (existing != null) {
                val existingHash = runCatching {
                    val input = if (existing.scheme == "file") existing.path?.let(::File)?.inputStream()
                    else context.contentResolver.openInputStream(existing)
                    input?.use(::sha256)
                }.getOrNull()
                if (existingHash == record.sha256) {
                    record.status = "complete"
                    removeTransferStorage(record)
                    publish()
                    return 200 to obj("done" to true, "status" to "complete", "duplicate" to true)
                }
                record.status = "failed"
                record.received = 0
                removeTransferStorage(record)
                publish()
                return 409 to obj("message" to "A different file with this name already exists on this device.")
            }
            val saved = runCatching {
                val pending = DownloadStore.begin(context, record.name, record.mime)
                try {
                    record.file.inputStream().use { input -> pending.openStream().use { input.copyTo(it) } }
                    pending.commit()
                } catch (e: Exception) {
                    pending.abort()
                    throw e
                }
            }.getOrNull()
            if (saved == null) {
                record.status = "receiving"
                record.received = record.size
                val persisted = persistTransfer(record)
                publish()
                return 500 to obj("message" to if (persisted) {
                    "The full file is retained. Check storage space, then resume to retry saving it."
                } else {
                    "The file could not be saved, and its resume state could not be retained."
                })
            }
            record.status = "complete"
            removeTransferStorage(record)
            publish()
            return 200 to obj("done" to true, "status" to "complete")
        }

        private fun expirePairings() {
            val now = System.currentTimeMillis()
            pairings.values.filter { it.expiresAtMs <= now }.forEach { it.secret?.fill(0) }
            pairings.entries.removeAll { it.value.expiresAtMs <= now }
        }

        private fun publish() { synchronized(lock) { if (server === this) _state.value = snapshot() } }

        private fun toPublic(it: TransferRecord) = Transfer(
            it.id, it.name, it.size, it.mime, it.sha256, it.title, it.artist, it.status, it.received,
            it.deviceId, it.deviceName, it.direction,
        )

        private fun transferJson(it: TransferRecord): JsonObject = obj(
            "id" to it.id, "name" to it.name, "size" to it.size, "mime" to it.mime, "sha256" to it.sha256,
            "title" to it.title, "artist" to it.artist, "status" to it.status, "received" to it.received,
            "deviceId" to it.deviceId, "deviceName" to it.deviceName, "direction" to it.direction,
            "updatedMs" to it.createdAtMs,
        )
    }

    private fun plain(status: Int, body: JsonElement): Triple<Int, ByteArray, String> =
        Triple(status, body.toString().encodeToByteArray(), "application/json; charset=utf-8")

    private fun parse(raw: ByteArray): JsonObject? = runCatching { json.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject }.getOrNull()

    private fun obj(vararg values: Pair<String, Any?>): JsonObject = buildJsonObject {
        values.forEach { (key, value) -> when (value) {
            null -> put(key, JsonNull)
            is String -> put(key, value)
            is Boolean -> put(key, value)
            is Int -> put(key, value)
            is Long -> put(key, value)
            is JsonElement -> put(key, value)
            else -> put(key, value.toString())
        } }
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull
    private fun uuid(): String = UUID.randomUUID().toString().replace("-", "")

    private fun safeName(raw: String?): String? {
        if (raw.isNullOrBlank() || raw.length > 240 || raw.any { it.code < 32 }) return null
        val name = raw.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return name.takeIf { it.isNotBlank() && it != "." && it != ".." }
    }

    private fun supportedAudio(mime: String): Boolean = mime in setOf(
        "audio/mpeg", "audio/mp4", "audio/aac", "audio/flac", "audio/x-flac", "audio/wav", "audio/x-wav",
        "audio/ogg", "audio/opus", "audio/vorbis", "audio/alac",
    )

    internal fun audioSignatureOk(file: File, mime: String): Boolean {
        val head = runCatching { file.inputStream().use { input -> ByteArray(12).also { input.read(it) } } }.getOrNull() ?: return false
        fun ascii(offset: Int, value: String): Boolean = value.indices.all { head[offset + it] == value[it].code.toByte() }
        return when (mime) {
            "audio/flac", "audio/x-flac" -> ascii(0, "fLaC")
            "audio/wav", "audio/x-wav" -> ascii(0, "RIFF") && ascii(8, "WAVE")
            "audio/ogg", "audio/opus", "audio/vorbis" -> ascii(0, "OggS")
            "audio/mp4", "audio/alac" -> ascii(4, "ftyp")
            "audio/mpeg" -> ascii(0, "ID3") || (head[0] == 0xff.toByte() && (head[1].toInt() and 0xe0) == 0xe0)
            "audio/aac" -> head[0] == 0xff.toByte() && (head[1].toInt() and 0xf6) == 0xf0
            else -> false
        }
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun InputStream.skipFully(count: Long) {
        var left = count
        while (left > 0) {
            val skipped = skip(left)
            if (skipped <= 0) { if (read() < 0) throw java.io.EOFException(); left-- } else left -= skipped
        }
    }

    internal fun lanAddress(context: Context): Inet4Address? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return manager.allNetworks.asSequence().mapNotNull { network ->
            val caps = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return@mapNotNull null
            manager.getLinkProperties(network)?.linkAddresses?.firstNotNullOfOrNull { it.address as? Inet4Address }
        }.firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
    }
}

/** Stable per-install TLS identity for local sync; the private key never leaves Android Keystore. */
private object AndroidLanTlsIdentity {
    private const val PROVIDER = "AndroidKeyStore"
    // v2 enables the raw ECDSA operation Conscrypt requests for TLS signatures.
    // Keep the prior alias intact for rollback/migration. Serving v2 rotates
    // the TLS fingerprint, so peers pinned to v1 must pair again explicitly.
    private const val ALIAS = "app.vyral.night.lan-sync-tls-v2"

    data class Identity(val context: SSLContext, val fingerprint: String)

    fun loadOrCreate(): Identity {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val now = System.currentTimeMillis()
        val existing = store.getCertificate(ALIAS) as? X509Certificate
        if (existing == null || existing.notAfter.time <= now + 60_000L) {
            if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS)
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
            generator.initialize(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                )
                    .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
                    .setCertificateSubject(X500Principal("CN=VYRAL LAN Sync"))
                    .setCertificateSerialNumber(BigInteger.valueOf(now.coerceAtLeast(1L)))
                    .setCertificateNotBefore(Date(now - 60_000L))
                    .setCertificateNotAfter(Date(now + 10L * 365 * 24 * 60 * 60 * 1000))
                    .build(),
            )
            generator.generateKeyPair()
        }
        val ready = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val certificate = ready.getCertificate(ALIAS) as? X509Certificate
            ?: error("Android Keystore did not create the LAN sync certificate.")
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(ready, null)
        }.keyManagers
        val tls = SSLContext.getInstance("TLS").apply { init(keyManagers, null, SecureRandom()) }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }
        return Identity(tls, fingerprint)
    }
}
