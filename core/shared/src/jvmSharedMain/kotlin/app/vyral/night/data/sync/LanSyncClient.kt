package app.vyral.night.data.sync

import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom

private const val LAN_SYNC_API_VERSION = 1

data class LanSyncHost(
    val baseUrl: String,
    val id: String,
    val name: String,
    val apiVersion: Int,
    val findCode: String,
    val bluetoothAvailable: Boolean,
    val tlsFingerprint: String,
)

/** A peer-scoped offer or transfer returned by the encrypted `/v1/files/transfers` API. */
data class LanSyncFileTransfer(
    val id: String,
    val name: String,
    val size: Long,
    val mime: String,
    val sha256: String,
    val title: String?,
    val artist: String?,
    val status: String,
    val received: Long,
    val deviceId: String?,
    val deviceName: String?,
    val direction: String,
    val updatedMs: Long?,
)

data class LanSyncTransferOffer(
    val id: String,
    val name: String,
    val size: Long,
    val mime: String,
    val sha256: String,
    val title: String? = null,
    val artist: String? = null,
)

/** Pairing is paused until the user compares this code and approves the new device on the host. */
class LanSyncPairingRequest internal constructor(
    val host: LanSyncHost,
    internal val pairingId: String,
    internal val deviceName: String,
    internal val key: ByteArray,
    val confirmationCode: String,
) {
    /** Clears the temporary pairing secret once approval is finished or cancelled. */
    fun clearSensitiveData() {
        key.fill(0)
    }

    override fun toString(): String = "LanSyncPairingRequest(host=${host.name}, confirmationCode=$confirmationCode)"
}

/** The shared key is deliberately excluded from properties and string formatting. Persist it only in platform secure storage. */
class LanSyncSession internal constructor(
    baseUrl: String,
    val deviceId: String,
    key: ByteArray,
    val spaceId: String,
    val hostId: String,
    val hostName: String,
    val tlsFingerprint: String,
) {
    val baseUrl: String = baseUrl.trimEnd('/')
    private val secret = key.copyOf()

    init {
        require(secret.size == 32)
        require(deviceId.isNotBlank() && deviceId.length <= 128)
    }

    internal fun keyCopy() = secret.copyOf()

    /** Persist only inside the platform's encrypted credential store. */
    fun exportForEncryptedStorage(): String = buildJsonObject {
        put("baseUrl", baseUrl)
        put("deviceId", deviceId)
        put("key", LanSyncCrypto.encodeBase64Url(secret))
        put("spaceId", spaceId)
        put("hostId", hostId)
        put("hostName", hostName)
        put("tlsFingerprint", tlsFingerprint)
    }.toString()

    override fun toString(): String = "LanSyncSession(host=$hostName, deviceId=$deviceId)"

    companion object {
        fun restoreFromEncryptedStorage(value: String): LanSyncSession? = runCatching {
            val json = Json.parseToJsonElement(value).jsonObject
            val baseUrl = normalizeLanAddress(json["baseUrl"]?.jsonPrimitive?.contentOrNull.orEmpty())
            val deviceId = json["deviceId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
            val key = json["key"]?.jsonPrimitive?.contentOrNull?.let(LanSyncCrypto::decodeBase64Url)
                ?.takeIf { it.size == 32 } ?: return null
            val spaceId = json["spaceId"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
            val hostId = json["hostId"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
            val hostName = json["hostName"]?.jsonPrimitive?.contentOrNull?.take(80).orEmpty()
            val fingerprint = json["tlsFingerprint"]?.jsonPrimitive?.contentOrNull?.lowercase()
                ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return null
            LanSyncSession(baseUrl, deviceId, key, spaceId, hostId, hostName, fingerprint)
        }.getOrNull()
    }
}

class LanSyncException(
    val statusCode: Int?,
    override val message: String,
) : IllegalStateException(message)

/** Client for the existing Rust desktop LAN-sync host; it creates no local service or database. */
class LanSyncClient(
    private val http: HttpClient,
    private val expectedTlsFingerprint: String,
    private val json: Json = Json,
) {
    suspend fun hello(address: String): LanSyncHost {
        val baseUrl = normalizeLanAddress(address)
        val (status, body) = requestJson("GET", "$baseUrl/lan/hello")
        if (status != HttpStatusCode.OK.value) throw failure(status, body)
        val root = parseObject(body)
        val api = root.int("api") ?: throw LanSyncException(status, "The sync host did not report an API version.")
        if (root.string("app") != "vyral" || api != LAN_SYNC_API_VERSION) {
            throw LanSyncException(status, "This VYRAL sync host uses an unsupported protocol version.")
        }
        val id = root.string("id") ?: throw LanSyncException(status, "The sync host has no device identity.")
        val tlsFingerprint = root.string("tlsFingerprint")?.lowercase()
            ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            ?: throw LanSyncException(status, "The sync host did not report a valid TLS identity.")
        if (!expectedTlsFingerprint.matches(Regex("[0-9a-fA-F]{64}")) ||
            !MessageDigest.isEqual(
                expectedTlsFingerprint.lowercase().encodeToByteArray(),
                tlsFingerprint.encodeToByteArray(),
            )
        ) throw LanSyncException(status, "The sync host certificate does not match the QR or manual fingerprint.")
        val name = root.string("name") ?: "VYRAL desktop"
        val code = root.string("code") ?: ""
        if (code.isNotEmpty() && (code.length != 4 || code.any { !it.isDigit() })) {
            throw LanSyncException(status, "The sync host sent an invalid discovery code.")
        }
        return LanSyncHost(baseUrl, id, name, api, code, root.boolean("bt") ?: false, tlsFingerprint)
    }

    /** Starts the authenticated pairing handshake and reveals the committed one-time key to the host. */
    suspend fun beginPairing(address: String, deviceName: String): LanSyncPairingRequest {
        val host = hello(address)
        val name = deviceName.trim().take(80)
        if (name.isEmpty()) throw LanSyncException(null, "Choose a name for this device.")
        val phoneKey = LanSyncCrypto.newPhoneKey()
        val (startStatus, startBody) = requestJson(
            "POST",
            "${host.baseUrl}/lan/pair/start",
            buildJsonObject {
                put("deviceName", name)
                put("commit", phoneKey.commitment())
            },
        )
        if (startStatus != HttpStatusCode.Created.value) throw failure(startStatus, startBody)
        val start = parseObject(startBody)
        val pairingId = start.string("pairingId") ?: throw LanSyncException(startStatus, "The host did not create a pairing request.")
        if (start.string("hostId") != host.id) throw LanSyncException(startStatus, "The host identity changed during pairing.")
        val hostPublicKey = start.string("hostKey") ?: throw LanSyncException(startStatus, "The host did not provide its pairing key.")
        val derived = phoneKey.agree(hostPublicKey) ?: throw LanSyncException(startStatus, "The host sent an invalid pairing key.")
        val (revealStatus, revealBody) = requestJson(
            "POST",
            "${host.baseUrl}/lan/pair/reveal",
            buildJsonObject {
                put("pairingId", pairingId)
                put("deviceKey", LanSyncCrypto.encodeBase64Url(phoneKey.publicKey))
            },
        )
        if (revealStatus != HttpStatusCode.OK.value) throw failure(revealStatus, revealBody)
        return LanSyncPairingRequest(host, pairingId, name, derived.copyBytes(), derived.confirmationCode)
    }

    /** Returns null while the host is waiting for the user to approve this device. */
    suspend fun pollApproval(request: LanSyncPairingRequest): LanSyncSession? {
        val (status, body) = requestJson(
            "POST",
            "${request.host.baseUrl}/lan/pair/claim",
            buildJsonObject { put("pairingId", request.pairingId) },
        )
        if (status == HttpStatusCode.Accepted.value) return null
        if (status != HttpStatusCode.OK.value) throw failure(status, body)
        val claim = parseObject(body)
        val deviceId = claim.string("deviceId") ?: throw LanSyncException(status, "The host approval did not include a device identity.")
        val hostId = claim.string("hostId") ?: request.host.id
        return LanSyncSession(
            request.host.baseUrl,
            deviceId,
            request.key,
            claim.string("spaceId") ?: throw LanSyncException(status, "The host approval did not include a sync-space identity."),
            hostId,
            claim.string("hostName") ?: request.host.name,
            request.host.tlsFingerprint,
        )
    }

    /** Sends a method/path/body through the host's encrypted, replay-protected `/lan/v1` envelope. */
    suspend fun request(session: LanSyncSession, method: String, path: String, body: JsonElement = JsonNull): JsonElement {
        val verb = method.uppercase()
        require(verb in setOf("GET", "POST", "PUT", "DELETE")) { "Unsupported sync method" }
        require(path.startsWith("/v1/") && !path.contains("://") && !path.contains('#')) { "Sync paths must stay under /v1/" }
        val nonce = ByteArray(18).also(SecureRandom()::nextBytes).let(LanSyncCrypto::encodeBase64Url)
        val envelope = buildJsonObject {
            put("m", verb)
            put("p", path)
            put("b", body)
            put("n", nonce)
            put("t", System.currentTimeMillis())
        }
        val sealed = LanSyncCrypto.seal(session.keyCopy(), session.deviceId.encodeToByteArray(), envelope.toString().encodeToByteArray())
        val response = http.post("${session.baseUrl}/lan/v1") {
            accept(ContentType.Text.Plain)
            contentType(ContentType.Text.Plain)
            headers.append("x-vyral-device", session.deviceId)
            setBody(sealed)
        }
        val sealedResponse = response.bodyAsText()
        if (response.status.value != HttpStatusCode.OK.value) throw failure(response.status.value, sealedResponse)
        val clear = LanSyncCrypto.open(
            session.keyCopy(),
            "resp:${session.deviceId}".encodeToByteArray(),
            sealedResponse,
        )?.decodeToString() ?: throw LanSyncException(response.status.value, "The sync host returned an invalid encrypted response.")
        val result = parseObject(clear)
        if (result.string("n") != nonce) throw LanSyncException(response.status.value, "The sync response did not match this request.")
        val status = result.int("s") ?: throw LanSyncException(response.status.value, "The sync host returned an invalid status.")
        val payload = result["b"] ?: JsonNull
        if (status !in 200..299) throw failure(status, payload.toString())
        return payload
    }

    suspend fun syncPlaylists(session: LanSyncSession, method: String, body: JsonElement = JsonNull): JsonElement =
        request(session, method, "/v1/ops", body)

    /** Offer one user-selected local file. The receiving device must accept before chunks are sent. */
    suspend fun offerFile(session: LanSyncSession, offer: LanSyncTransferOffer): JsonObject {
        requireTransferId(offer.id)
        require(offer.name.isNotBlank() && offer.name.length <= 240 && '/' !in offer.name && '\\' !in offer.name)
        require(offer.size in 1..MAX_TRANSFER_BYTES)
        require(offer.mime.length <= 120)
        require(offer.sha256.matches(Regex("[0-9a-fA-F]{64}")))
        val body = buildJsonObject {
            put("id", offer.id)
            put("name", offer.name)
            put("size", offer.size)
            put("mime", offer.mime)
            put("sha256", offer.sha256.lowercase())
            offer.title?.takeIf(String::isNotBlank)?.take(300)?.let { put("title", it) }
            offer.artist?.takeIf(String::isNotBlank)?.take(300)?.let { put("artist", it) }
        }
        return request(session, "POST", "$TRANSFER_PATH/offer", body).jsonObject
    }

    suspend fun listTransfers(session: LanSyncSession): List<LanSyncFileTransfer> {
        val result = request(session, "GET", TRANSFER_PATH).jsonObject
        return (result["transfers"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map(::parseTransfer)
    }

    suspend fun transferStatus(session: LanSyncSession, id: String): LanSyncFileTransfer =
        parseTransfer(request(session, "GET", "$TRANSFER_PATH/${requireTransferId(id)}").jsonObject)

    suspend fun acceptTransfer(session: LanSyncSession, id: String): JsonObject =
        request(session, "POST", "$TRANSFER_PATH/${requireTransferId(id)}/accept").jsonObject

    suspend fun declineTransfer(session: LanSyncSession, id: String): JsonObject =
        request(session, "POST", "$TRANSFER_PATH/${requireTransferId(id)}/decline").jsonObject

    /** One sequential, bounded chunk. Call only after the recipient accepts the offer. */
    suspend fun sendTransferChunk(session: LanSyncSession, id: String, offset: Long, bytes: ByteArray): JsonObject {
        requireTransferId(id)
        require(offset >= 0)
        require(bytes.isNotEmpty() && bytes.size <= TRANSFER_CHUNK_BYTES)
        val body = buildJsonObject {
            put("offset", offset)
            put("data", LanSyncCrypto.encodeBase64Url(bytes))
        }
        return request(session, "POST", "$TRANSFER_PATH/$id/chunk", body).jsonObject
    }

    suspend fun cancelTransfer(session: LanSyncSession, id: String): JsonObject =
        request(session, "DELETE", "$TRANSFER_PATH/${requireTransferId(id)}").jsonObject

    /** Reads one accepted host-originated offer chunk; validate the wire bounds before exposing bytes. */
    suspend fun downloadTransferChunk(session: LanSyncSession, id: String, offset: Long): LanSyncDownloadedChunk {
        requireTransferId(id)
        require(offset >= 0)
        val result = request(session, "GET", "$TRANSFER_PATH/$id/chunk?offset=$offset").jsonObject
        val responseOffset = result.long("offset")
            ?: throw LanSyncException(null, "The sync host returned an invalid file chunk.")
        val size = result.long("size")?.takeIf { it in 1..MAX_TRANSFER_BYTES }
            ?: throw LanSyncException(null, "The sync host returned an invalid file size.")
        val data = result.string("data")?.let(LanSyncCrypto::decodeBase64Url)
            ?: throw LanSyncException(null, "The sync host returned invalid file data.")
        if (responseOffset != offset || data.isEmpty() || data.size > TRANSFER_CHUNK_BYTES || offset + data.size > size) {
            throw LanSyncException(null, "The sync host returned an out-of-range file chunk.")
        }
        val sha256 = result.string("sha256")?.lowercase()
            ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            ?: throw LanSyncException(null, "The sync host returned an invalid file checksum.")
        return LanSyncDownloadedChunk(responseOffset, size, data, sha256)
    }

    suspend fun completeTransferDownload(session: LanSyncSession, id: String, sha256: String): JsonObject {
        requireTransferId(id)
        require(sha256.matches(Regex("[0-9a-fA-F]{64}")))
        return request(
            session,
            "POST",
            "$TRANSFER_PATH/$id/complete",
            buildJsonObject { put("sha256", sha256.lowercase()) },
        ).jsonObject
    }

    private suspend fun requestJson(method: String, url: String, body: JsonElement? = null): Pair<Int, String> {
        val response = when (method) {
            "GET" -> http.get(url) { accept(ContentType.Application.Json) }
            else -> http.post(url) {
                accept(ContentType.Application.Json)
                contentType(ContentType.Application.Json)
                setBody(body?.toString() ?: "{}")
            }
        }
        return response.status.value to response.bodyAsText()
    }

    private fun parseObject(body: String): JsonObject = runCatching { json.parseToJsonElement(body).jsonObject }
        .getOrElse { throw LanSyncException(null, "The sync host returned invalid JSON.") }

    private fun failure(status: Int, body: String): LanSyncException {
        val message = runCatching { parseObject(body).string("message") }.getOrNull()
            ?: "Sync host request failed (HTTP $status)."
        return LanSyncException(status, message)
    }

    private fun parseTransfer(value: JsonObject): LanSyncFileTransfer {
        fun requiredString(key: String): String = value.string(key)
            ?: throw LanSyncException(null, "The sync host returned an incomplete file transfer.")
        val id = requireTransferId(requiredString("id"))
        val size = value.long("size")?.takeIf { it in 1..MAX_TRANSFER_BYTES }
            ?: throw LanSyncException(null, "The sync host returned an invalid file size.")
        val received = value.long("received")?.takeIf { it in 0..size } ?: 0L
        val sha256 = requiredString("sha256").lowercase()
        if (!sha256.matches(Regex("[0-9a-f]{64}"))) throw LanSyncException(null, "The sync host returned an invalid file checksum.")
        return LanSyncFileTransfer(
            id = id,
            name = requiredString("name").take(240),
            size = size,
            mime = requiredString("mime").take(120),
            sha256 = sha256,
            title = value.string("title")?.take(300),
            artist = value.string("artist")?.take(300),
            status = requiredString("status").take(24),
            received = received,
            deviceId = value.string("deviceId")?.take(128),
            deviceName = value.string("deviceName")?.take(80),
            direction = requiredString("direction").take(16),
            updatedMs = value.long("updatedMs"),
        )
    }

    private fun requireTransferId(id: String): String {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,96}"))) { "Invalid transfer id" }
        return id
    }
}

fun normalizeLanAddress(address: String): String {
    val raw = address.trim().let { if (it.contains("://")) it else "https://$it" }
    val uri = runCatching { URI(raw) }.getOrElse { throw LanSyncException(null, "Enter a local VYRAL sync address.") }
    if (!uri.scheme.equals("https", ignoreCase = true)
        || uri.host.isNullOrBlank()
        || uri.userInfo != null
        || uri.query != null
        || uri.fragment != null
        || uri.path !in listOf("", "/")
    ) {
        throw LanSyncException(null, "Use the HTTPS address and matching certificate fingerprint shown by the VYRAL sync host.")
    }
    return "https://${uri.rawAuthority}"
}

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull
private fun JsonObject.boolean(key: String): Boolean? = this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

private const val TRANSFER_PATH = "/v1/files/transfers"
private const val TRANSFER_CHUNK_BYTES = 192 * 1024
private const val MAX_TRANSFER_BYTES = 500L * 1024 * 1024

data class LanSyncDownloadedChunk(val offset: Long, val size: Long, val bytes: ByteArray, val sha256: String)
