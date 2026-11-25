package com.licht.nuvx.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.licht.nuvx.BuildConfig
import com.licht.nuvx.core.compareReleaseOrder
import com.licht.nuvx.core.releaseIsNewer
import com.licht.nuvx.core.isOldBuild as coreIsOldBuild
import com.licht.nuvx.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.io.File

object AppUpdateChecker {

    data class UpdateInfo(
        val version: String,
        val releaseUrl: String,
        val apkUrl: String?,
        val notes: String?,
    )

    internal const val CACHE_SUBDIR = "updates"

    private const val RELEASES_URL =
        "https://api.github.com/repos/lylicht/nuvx/releases?per_page=100"

    private val json = Json { ignoreUnknownKeys = true }

    private val _available = MutableStateFlow<UpdateInfo?>(null)
    val available = _available.asStateFlow()

    sealed interface DownloadState {
        data object Idle : DownloadState
        data class Downloading(val fraction: Float) : DownloadState
        data class Ready(val file: File) : DownloadState
        data class Failed(val message: String) : DownloadState
    }

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download = _download.asStateFlow()

    private var workObserver: Observer<WorkInfo>? = null
    private var workLiveData: LiveData<WorkInfo>? = null
    private var observedWorkId: String? = null
    private var observedContext: Context? = null

    fun observeDownload(context: Context) {
        val app = context.applicationContext
        val workId = app.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_WORK_ID, null)
        attachToWork(app, workId)
    }

    private fun attachToWork(app: Context, workId: String?) {
        if (observedContext === app && observedWorkId == workId && (workObserver != null || workId == null)) return
        workLiveData?.let { liveData -> workObserver?.let(liveData::removeObserver) }
        observedContext = app
        observedWorkId = workId
        if (workId == null) {
            workObserver = null
            workLiveData = null
            _download.value = DownloadState.Idle
            return
        }
        val liveData = runCatching {
            WorkManager.getInstance(app).getWorkInfoByIdLiveData(UUID.fromString(workId))
        }.getOrNull() ?: run {
            workObserver = null
            workLiveData = null
            _download.value = DownloadState.Idle
            return
        }
        val observer = Observer<WorkInfo> { work ->
            if (work == null) {
                _download.value = DownloadState.Idle
                return@Observer
            }
            _download.value = when (work.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.RUNNING ->
                    DownloadState.Downloading(work.progress.getFloat(KEY_PROGRESS, 0f))
                WorkInfo.State.SUCCEEDED -> {
                    val path = work.outputData.getString(KEY_OUTPUT_FILE)
                    val file = path?.let(::File)
                    if (file?.isFile == true && file.length() > 0L) DownloadState.Ready(file)
                    else DownloadState.Failed("Downloaded APK is missing")
                }
                WorkInfo.State.FAILED -> DownloadState.Failed(
                    work.outputData.getString(KEY_OUTPUT_ERROR) ?: "Download failed",
                )
                WorkInfo.State.CANCELLED -> DownloadState.Idle
            }
        }
        workObserver = observer
        workLiveData = liveData
        liveData.observeForever(observer)
    }

    suspend fun check() = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(RELEASES_URL).build()
            val body = Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            } ?: return@runCatching
            val releases = json.parseToJsonElement(body) as? JsonArray ?: return@runCatching
            _available.value = selectUpdate(
                releases.mapNotNull { it as? JsonObject }.mapNotNull(::releaseCandidate),
                BuildConfig.VERSION_NAME,
                BuildConfig.NUVX_RELEASE_TAG,
                BuildConfig.NUVX_BUILD_TIME,
                allowNightly = AppSettings.nightlyUpdates.value,
            )?.let { UpdateInfo(it.version, it.releaseUrl, it.apkUrl, it.notes) }
        }
    }

    internal data class ReleaseCandidate(
        val version: String,
        val tag: String,
        val publishedAt: String,
        val releaseUrl: String,
        val apkUrl: String,
        val notes: String?,
    )

    private fun releaseCandidate(release: JsonObject): ReleaseCandidate? {
        if (release["draft"]?.jsonPrimitive?.booleanOrNull != false) return null
        val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
        val url = release["html_url"]?.jsonPrimitive?.contentOrNull ?: return null
        val title = release["name"]?.jsonPrimitive?.contentOrNull
        if (isOldBuild(title, tag, release["prerelease"]?.jsonPrimitive?.booleanOrNull == true)) return null
        val apk = apkAsset(release) ?: return null
        val version = if (tag.startsWith("nightly.")) {
            versionFromApkAsset(apk.first, BuildConfig.UPDATE_APK_PREFIX)
                ?: title?.removePrefix("nuvx ")?.takeIf { it != title }
        } else {
            tag.removePrefix("v")
        } ?: return null
        if (!version.matches(Regex("\\d+\\.\\d+\\.\\d+(-nightly\\.\\d+\\.\\d+)?"))) return null
        val publishedAt = release["published_at"]?.jsonPrimitive?.contentOrNull ?: return null
        return ReleaseCandidate(version, tag, publishedAt, url, apk.second, release["body"]?.jsonPrimitive?.contentOrNull)
    }

    private fun apkAsset(release: JsonObject): Pair<String, String>? = runCatching {
        val assets = release["assets"]?.jsonArray
            ?.mapNotNull { it as? JsonObject }
            ?.mapNotNull { asset ->
                val name = asset["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                if (name.startsWith(BuildConfig.UPDATE_APK_PREFIX, ignoreCase = true) &&
                    name.endsWith(".apk", ignoreCase = true) &&
                    asset["state"]?.jsonPrimitive?.contentOrNull == "uploaded"
                ) name.removeSuffix(".apk").removeSuffix(".APK") to url else null
            }
            .orEmpty()
        val selected = selectApkAssetUrl(assets, BuildConfig.NUVX_DISTRIBUTION, Build.SUPPORTED_ABIS.toList())
        assets.firstOrNull { it.second == selected }
    }.getOrNull()

    suspend fun clearCache(context: Context) = withContext(Dispatchers.IO) {
        File(context.cacheDir, CACHE_SUBDIR).listFiles()?.forEach { file ->
            if (!file.name.endsWith(".part") && !file.name.endsWith(".apk")) file.delete()
        }
    }

    suspend fun downloadApk(context: Context) {
        observeDownload(context)
        val info = _available.value ?: return
        val url = info.apkUrl ?: return
        withContext(Dispatchers.IO) {
            val request = OneTimeWorkRequestBuilder<AppUpdateDownloadWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(KEY_URL, url)
                        .putString(KEY_VERSION, info.version)
                        .build(),
                )
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UPDATE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
            context.applicationContext.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_WORK_ID, request.id.toString())
                .apply()
            attachToWork(context.applicationContext, request.id.toString())
        }
    }

    fun cancelDownload() {
        observedContext?.let { WorkManager.getInstance(it).cancelUniqueWork(UPDATE_WORK_NAME) }
    }

    fun resetDownload() {
        observedContext?.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.remove(KEY_WORK_ID)
            ?.apply()
        workLiveData?.let { liveData -> workObserver?.let(liveData::removeObserver) }
        observedWorkId = null
        workLiveData = null
        workObserver = null
        _download.value = DownloadState.Idle
    }

    internal const val UPDATE_WORK_NAME = "nuvx-app-update-download"
    internal const val KEY_URL = "url"
    internal const val KEY_VERSION = "version"
    internal const val KEY_PROGRESS = "progress"
    internal const val KEY_OUTPUT_FILE = "apk_file"
    internal const val KEY_OUTPUT_ERROR = "error"
    private const val UPDATE_PREFS = "app_update_download"
    private const val KEY_WORK_ID = "work_id"

    fun installApk(context: Context, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val opened = runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.isSuccess
            if (opened) return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                .putExtra(Intent.EXTRA_RETURN_RESULT, true)
                .addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_NEW_TASK,
                ),
        )
    }

}

// Release rules live in nuvx-core (core/rust/nuvx-core/src/release.rs), shared with the desktop updater and pinned
// by core/contracts/update-order.json. Pre-licht releases ("old" pre-releases) are never offered.
internal fun isOldBuild(name: String?, tag: String, prerelease: Boolean): Boolean = coreIsOldBuild(name, tag, prerelease)

// The best release to offer: a stable release beats any nightly, then the higher version, then the later nightly build.
internal fun selectUpdate(
    candidates: List<AppUpdateChecker.ReleaseCandidate>,
    currentVersion: String,
    currentTag: String,
    currentBuildTime: String,
    allowNightly: Boolean,
): AppUpdateChecker.ReleaseCandidate? = candidates
    .filter { isNewerRelease(it.version, it.tag, it.publishedAt, currentVersion, currentTag, currentBuildTime, allowNightly) }
    .maxWithOrNull { a, b -> compareReleaseOrder(a.version, a.tag, a.publishedAt, b.version, b.tag, b.publishedAt) }

internal fun isNewerRelease(
    candidateVersion: String,
    candidateTag: String,
    candidatePublishedAt: String,
    currentVersion: String,
    currentTag: String,
    currentBuildTime: String,
    allowNightly: Boolean = currentTag.startsWith("nightly."),
): Boolean = releaseIsNewer(
    candidateVersion, candidateTag, candidatePublishedAt, currentVersion, currentTag, currentBuildTime, allowNightly,
)

internal fun versionFromApkAsset(name: String, prefix: String): String? {
    if (!name.startsWith(prefix, ignoreCase = true)) return null
    val suffix = name.drop(prefix.length).removeSuffix(".apk").removeSuffix(".APK")
    return Regex("^\\d+\\.\\d+\\.\\d+(?:-nightly\\.\\d+\\.\\d+)?(?=-|$)")
        .find(suffix)?.value
}

internal fun selectApkAssetUrl(
    assets: List<Pair<String, String>>,
    distribution: String,
    supportedAbis: List<String>,
): String? {
    if (distribution == "single") {
        return assets.firstOrNull { (name, _) ->
            !name.endsWith("-gms") && !name.endsWith("-foss") &&
                !Regex("_(armeabi-v7a|arm64-v8a|x86_64)$").containsMatchIn(name)
        }?.second
    }
    return assets.firstOrNull { (name, _) -> name.endsWith("-$distribution") }?.second
        ?: if (distribution == "gms") {
        supportedAbis.firstNotNullOfOrNull { abi ->
            assets.firstOrNull { (name, _) -> name.endsWith("_$abi") }?.second
        } ?: assets.firstOrNull { (name, _) -> '_' !in name }?.second
    } else {
        null
    }
}
