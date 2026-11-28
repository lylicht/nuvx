package app.vyral.desktop

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.nio.file.Files

/**
 * Checks GitHub Releases and downloads the matching installer inside the app.
 *
 * GitHub's "latest" skips pre-releases and drafts, so a `-beta` desktop build is only ever
 * nudged toward a published release.
 */
internal object DesktopUpdateChecker {

    data class UpdateInfo(
        val version: String,
        /** The installer for this platform, or null when the release has none. */
        val assetName: String?,
        val downloadUrl: String?,
        val notes: String?,
    )

    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/bxanedot/vyral/releases/latest"

    val currentVersion: String = System.getProperty("vyral.version") ?: "1.0.0"
    val currentVersionCode: Int = Integer.getInteger("vyral.versionCode", 1)

    private val json = Json { ignoreUnknownKeys = true }

    private val http = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
        }
    }

    suspend fun check(): UpdateInfo? = runCatching {
        val response = http.get(LATEST_RELEASE_URL) {
            header("Accept", "application/vnd.github+json")
        }
        if (!response.status.isSuccess()) return@runCatching null
        val release = json.parseToJsonElement(response.bodyAsText()) as? JsonObject
            ?: return@runCatching null
        val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
        val latest = tag.removePrefix("v")
        if (!isNewer(latest, currentVersion)) return@runCatching null
        val asset = installerAsset(
            release,
            installerSuffixes(System.getProperty("os.name").orEmpty(), System.getProperty("os.arch").orEmpty()),
        )
        UpdateInfo(
            version = latest,
            assetName = asset?.first,
            downloadUrl = asset?.second,
            notes = release["body"]?.jsonPrimitive?.contentOrNull,
        )
    }.getOrNull()

    suspend fun downloadInstaller(update: UpdateInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val name = requireNotNull(update.assetName) { "No installer is available for this device." }
        val url = URI(requireNotNull(update.downloadUrl) { "No installer is available for this device." })
        require(isVyralReleaseAsset(url)) { "Update URL is not a Vyral release asset." }
        val suffix = installerSuffixes(
            System.getProperty("os.name").orEmpty(),
            System.getProperty("os.arch").orEmpty(),
        ).firstOrNull { name.endsWith(it, ignoreCase = true) }
            ?: error("The release does not include an installer for this device.")
        val target = Files.createTempFile("vyral-update-", suffix.substringAfterLast('.').let { ".$it" }).toFile()
        try {
            val response = http.get(url.toASCIIString()) {
                header("Accept", "application/octet-stream")
                timeout { requestTimeoutMillis = 30 * 60 * 1_000L; socketTimeoutMillis = 120_000 }
            }
            check(response.status.isSuccess()) { "Download failed: HTTP ${response.status.value}" }
            val total = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.takeIf { it > 0 }
            val channel = response.bodyAsChannel()
            var downloaded = 0L
            var reportedProgress = 0f
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = channel.readAvailable(buffer, 0, buffer.size)
                    if (count < 0) break
                    if (count > 0) {
                        output.write(buffer, 0, count)
                        downloaded += count
                        total?.let {
                            val progress = (downloaded.toFloat() / it).coerceIn(0f, 1f)
                            if (progress - reportedProgress >= 0.01f) {
                                reportedProgress = progress
                                withContext(Dispatchers.Main) { onProgress(progress) }
                            }
                        }
                    }
                }
                output.fd.sync()
            }
            check(target.length() > 0 && (total == null || target.length() == total)) { "The update download was incomplete." }
            withContext(Dispatchers.Main) { onProgress(1f) }
            target
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        }
    }

    fun launchInstaller(file: File) {
        require(file.isFile) { "The downloaded installer is missing." }
        val command = when {
            System.getProperty("os.name").startsWith("Windows", ignoreCase = true) && file.extension.equals("msi", true) ->
                listOf("msiexec", "/i", file.absolutePath)
            System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> listOf(file.absolutePath)
            System.getProperty("os.name").contains("Linux", ignoreCase = true) -> listOf("xdg-open", file.absolutePath)
            else -> error("This device cannot open the installer.")
        }
        ProcessBuilder(command).start()
    }

    /**
     * These suffixes match the installers staged by .github/workflows/release.yml.
     */
    internal fun installerSuffixes(osName: String, architecture: String): List<String> = when {
        osName.startsWith("Windows", ignoreCase = true) && architecture.isX64() ->
            listOf("-windows-x64-setup.exe", "-windows-x64.msi")
        osName.contains("Linux", ignoreCase = true) && architecture.isX64() ->
            listOf("-linux-amd64.deb", "-linux-x86_64.rpm")
        else -> emptyList()
    }

    private fun String.isX64(): Boolean = lowercase() in setOf("amd64", "x86_64", "x64")

    internal fun isVyralReleaseAsset(url: URI): Boolean =
        url.scheme == "https" && url.host == "github.com" && url.userInfo == null &&
            url.path.startsWith("/bxanedot/vyral/releases/download/")

    private fun installerAsset(release: JsonObject, suffixes: List<String>): Pair<String, String>? {
        val assets = release["assets"]?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty()
        for (suffix in suffixes) {
            val hit = assets.firstOrNull { asset ->
                asset["name"]?.jsonPrimitive?.contentOrNull?.endsWith(suffix, ignoreCase = true) == true &&
                    asset["state"]?.jsonPrimitive?.contentOrNull == "uploaded"
            }
            val name = hit?.get("name")?.jsonPrimitive?.contentOrNull
            val url = hit?.get("browser_download_url")?.jsonPrimitive?.contentOrNull
            if (name != null && url != null) return name to url
        }
        return null
    }

    private class Parsed(val parts: List<Int>, val preRelease: Boolean)

    private fun parse(raw: String): Parsed {
        val dash = raw.indexOf('-')
        val base = if (dash >= 0) raw.substring(0, dash) else raw
        return Parsed(base.split('.').map { it.toIntOrNull() ?: 0 }, dash >= 0)
    }

    /** Numeric comparison, with a `-betaN` build counted as older than the plain release it leads up to. */
    internal fun isNewer(latest: String, current: String): Boolean {
        val l = parse(latest)
        val c = parse(current)
        for (i in 0 until maxOf(l.parts.size, c.parts.size)) {
            val a = l.parts.getOrElse(i) { 0 }
            val b = c.parts.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return c.preRelease && !l.preRelease
    }
}
