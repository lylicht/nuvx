package app.vyral.night.data.sync

import android.content.Context
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class DiscoveredLanHost(val address: String, val tlsFingerprint: String, val version: Int, val name: String)

/** Explicit local /24 discovery only. A four-digit code locates a peer; it never authorizes pairing. */
internal object AndroidLanSyncDiscovery {
    private const val WORKERS = 48
    private const val CONNECT_TIMEOUT_MS = 200
    private const val READ_TIMEOUT_MS = 250
    private const val SCAN_TIMEOUT_MS = 30_000L
    private const val MAX_RESPONSE_BYTES = 8 * 1024
    private val json = Json { ignoreUnknownKeys = true }
    private val discoveryTls by lazy {
        val trustAnyCertificateForDiscoveryOnly = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAnyCertificateForDiscoveryOnly), SecureRandom()) }
    }

    suspend fun findByCode(context: Context, code: String): DiscoveredLanHost? {
        require(code.matches(Regex("[0-9]{4}"))) { "Enter the four-digit code shown by the host." }
        val local = AndroidLanSyncHost.lanAddress(context.applicationContext) ?:
            error("Connect to the same Wi-Fi or Ethernet network as the host first.")
        val prefix = local.address
        val addresses = (1..254).map { hostOctet ->
            Inet4Address.getByAddress(prefix.copyOf().apply { this[3] = hostOctet.toByte() }) as Inet4Address
        }
        return scanCandidates(code, addresses, AndroidLanSyncHost.PORT_RANGE.toList())
    }

    internal suspend fun scanCandidates(
        code: String,
        addresses: List<Inet4Address>,
        ports: List<Int>,
    ): DiscoveredLanHost? {
        require(code.matches(Regex("[0-9]{4}"))) { "Enter the four-digit code shown by the host." }
        require(ports.isNotEmpty() && ports.all { it in AndroidLanSyncHost.PORT_RANGE })
        if (addresses.isEmpty()) return null
        val candidateCount = addresses.size * ports.size
        return withTimeoutOrNull(SCAN_TIMEOUT_MS) {
            coroutineScope {
                val found = CompletableDeferred<DiscoveredLanHost?>()
                val next = AtomicInteger()
                val workersDone = AtomicInteger(WORKERS)
                val workers = List(WORKERS) {
                    launch(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            while (isActive && !found.isCompleted) {
                                currentCoroutineContext().ensureActive()
                                val index = next.getAndIncrement()
                                if (index >= candidateCount) break
                                val hostOctet = addresses[index / ports.size]
                                val port = ports[index % ports.size]
                                probe(hostOctet, port, code)?.let(found::complete)
                            }
                        } finally {
                            if (workersDone.decrementAndGet() == 0) found.complete(null)
                        }
                    }
                }
                try {
                    found.await()
                } finally {
                    workers.forEach { it.cancel() }
                    workers.forEach { it.cancelAndJoin() }
                }
            }
        }
    }

    private fun probe(host: Inet4Address, port: Int, expectedCode: String): DiscoveredLanHost? = try {
        val socket = discoveryTls.socketFactory.createSocket() as SSLSocket
        socket.use { tls ->
            tls.soTimeout = READ_TIMEOUT_MS
            tls.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            tls.startHandshake()
            val certificate = tls.session.peerCertificates.firstOrNull() as? X509Certificate ?: return null
            val presentedFingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                .joinToString("") { "%02x".format(it) }
            tls.getOutputStream().apply {
                write("GET /lan/hello HTTP/1.1\r\nHost: ${host.hostAddress}:$port\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                flush()
            }
            val input = tls.getInputStream()
            val status = readAsciiLine(input)?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: return null
            if (status != 200) return null
            var contentLength = -1
            var headerBytes = 0
            while (true) {
                val line = readAsciiLine(input) ?: return null
                headerBytes += line.length
                if (headerBytes > 4 * 1024) return null
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0 && line.substring(0, colon).equals("Content-Length", ignoreCase = true)) {
                    contentLength = line.substring(colon + 1).trim().toIntOrNull() ?: return null
                }
            }
            if (contentLength !in 1..MAX_RESPONSE_BYTES) return null
            val response = ByteArray(contentLength)
            var offset = 0
            while (offset < response.size) {
                val count = input.read(response, offset, response.size - offset)
                if (count < 0) return null
                offset += count
            }
            val hello = runCatching { json.parseToJsonElement(response.decodeToString()).jsonObject }.getOrNull() ?: return null
            val advertisedFingerprint = hello["tlsFingerprint"]?.jsonPrimitive?.content?.lowercase() ?: return null
            val version = hello["version"]?.jsonPrimitive?.intOrNull ?: return null
            if (hello["app"]?.jsonPrimitive?.content != "vyral" ||
                hello["api"]?.jsonPrimitive?.intOrNull != 1 ||
                version != 1 ||
                hello["code"]?.jsonPrimitive?.content != expectedCode ||
                advertisedFingerprint != presentedFingerprint ||
                hello["port"]?.jsonPrimitive?.intOrNull != port
            ) return null
            DiscoveredLanHost("https://${host.hostAddress}:$port", presentedFingerprint, version, hello["name"]?.jsonPrimitive?.content ?: "VYRAL device")
        }
    } catch (_: SocketTimeoutException) {
        null
    } catch (_: Exception) {
        null
    }

    private fun readAsciiLine(input: java.io.InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() <= 8 * 1024) {
            val next = input.read()
            if (next < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.US_ASCII.name())
            if (next == '\n'.code) break
            if (next != '\r'.code) bytes.write(next)
        }
        require(bytes.size() <= 8 * 1024) { "LAN discovery response line is too long." }
        return bytes.toString(Charsets.US_ASCII.name())
    }
}
