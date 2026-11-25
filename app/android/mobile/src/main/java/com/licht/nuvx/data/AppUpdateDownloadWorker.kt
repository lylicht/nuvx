package com.licht.nuvx.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.licht.nuvx.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

class AppUpdateDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val url = inputData.getString(AppUpdateChecker.KEY_URL) ?: return failure("Missing update URL")
        val version = inputData.getString(AppUpdateChecker.KEY_VERSION) ?: return failure("Missing update version")
        return try {
            setForeground(foregroundInfo(version, 0f))
            val apk = download(url, version)
            Result.success(workDataOf(AppUpdateChecker.KEY_OUTPUT_FILE to apk.absolutePath))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (runAttemptCount < MAX_RETRIES) Result.retry()
            else failure(error.message ?: "Download failed")
        }
    }

    private suspend fun download(url: String, version: String): File = withContext(Dispatchers.IO) {
        val directory = File(applicationContext.cacheDir, AppUpdateChecker.CACHE_SUBDIR).apply { mkdirs() }
        val partial = File(directory, "nuvx-$version.apk.part")
        val target = File(directory, "nuvx-$version.apk")
        directory.listFiles()?.filter { it != partial && it != target }?.forEach { it.delete() }
        val offset = partial.length()
        val request = Request.Builder().url(url).apply {
            if (offset > 0) header("Range", "bytes=$offset-")
        }.build()

        Http.client.newCall(request).execute().use { response ->
            if (offset > 0 && response.code == 416) {
                partial.delete()
                error("Update server rejected the resume range; retrying from the beginning")
            }
            check(response.isSuccessful) { "Download failed: HTTP ${response.code}" }
            val append = offset > 0 && response.code == 206
            val start = if (append) offset else 0L
            val body = response.body ?: error("Empty download body")
            val total = response.header("Content-Range")
                ?.substringAfterLast('/')
                ?.toLongOrNull()
                ?: body.contentLength().takeIf { it >= 0 }?.let { start + it }

            if (!append && partial.exists() && !partial.delete()) error("Could not restart update download")
            body.byteStream().use { input ->
                FileOutputStream(partial, append).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var current = start
                    var lastReportedPercent = -1
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        current += count
                        if (total != null && total > 0) {
                            val fraction = (current.toFloat() / total).coerceIn(0f, 1f)
                            val percent = (fraction * 100).toInt()
                            if (percent != lastReportedPercent) {
                                setProgress(workDataOf(AppUpdateChecker.KEY_PROGRESS to fraction))
                                setForeground(foregroundInfo(version, fraction))
                                lastReportedPercent = percent
                            }
                        }
                    }
                }
            }
            check(partial.isFile && partial.length() > 0L) { "The downloaded APK is empty" }
            if (total != null) check(partial.length() == total) { "The downloaded APK is incomplete" }
        }

        if (target.exists() && !target.delete()) error("Could not replace the previous update")
        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
        check(target.isFile && target.length() > 0L) { "The downloaded APK is missing" }
        target
    }

    private fun foregroundInfo(version: String, fraction: Float): ForegroundInfo {
        applicationContext.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(applicationContext.getString(R.string.software_update))
            .setContentText(applicationContext.getString(R.string.update_downloading_body, version))
            .setProgress(100, (fraction * 100).toInt(), fraction <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        return ForegroundInfo(NOTIFICATION_ID, notification, type)
    }

    private fun failure(message: String) = Result.failure(workDataOf(AppUpdateChecker.KEY_OUTPUT_ERROR to message))

    private companion object {
        const val CHANNEL_ID = "app_updates"
        const val NOTIFICATION_ID = 0x8176
        const val BUFFER_SIZE = 64 * 1024
        const val MAX_RETRIES = 3
    }
}
