package com.ipdial.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {
    private const val GITHUB_API_URL = "https://api.github.com/repos/nazimunaeem/IPDial/releases/latest"
    private const val TAG = "UpdateChecker"
    private const val UPDATES_DIR = "updates"
    private const val MAX_REDIRECTS = 5

    data class GitHubAsset(
        @SerializedName("name") val name: String = "",
        @SerializedName("size") val size: Long = 0L,
        @SerializedName("browser_download_url") val browserDownloadUrl: String = ""
    )

    data class GitHubRelease(
        @SerializedName("tag_name") val tagName: String = "",
        @SerializedName("html_url") val htmlUrl: String = "",
        @SerializedName("body") val body: String? = null,
        @SerializedName("assets") val assets: List<GitHubAsset> = emptyList()
    ) {
        fun findApkAsset(): GitHubAsset? {
            val apkAssets = assets.filter {
                it.name.endsWith(".apk", ignoreCase = true) && it.browserDownloadUrl.isNotBlank()
            }
            return apkAssets.firstOrNull { it.name.contains("DialDuo", ignoreCase = true) }
                ?: apkAssets.firstOrNull()
        }
    }

    sealed interface DownloadState {
        data object Idle : DownloadState
        data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : DownloadState
        data class ReadyToInstall(val apkFile: File) : DownloadState
        data class Failed(val message: String) : DownloadState
    }

    suspend fun checkForUpdates(currentVersion: String): GitHubRelease? = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(GITHUB_API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "DialDuo-Android")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return@withContext null
            }
            val json = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val release = Gson().fromJson(json, GitHubRelease::class.java) ?: return@withContext null
            val latestVersion = release.tagName.trim().trimStart('v', 'V').substringBefore('-')
            val cleanCurrent = currentVersion.trim().trimStart('v', 'V').substringBefore('-')
            if (isNewerVersion(cleanCurrent, latestVersion)) {
                return@withContext release
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for updates: ${e.message}")
        }
        null
    }

    fun cleanupCachedUpdates(context: Context) {
        try {
            val dir = File(context.cacheDir, UPDATES_DIR)
            if (dir.exists()) {
                dir.listFiles()?.forEach { it.delete() }
            }
        } catch (_: Exception) {}
    }

    suspend fun downloadApk(
        context: Context,
        asset: GitHubAsset,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val urlString = asset.browserDownloadUrl
        if (urlString.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("No APK download URL found"))
        }

        val updatesDir = File(context.cacheDir, UPDATES_DIR).apply { mkdirs() }
        // Clear previous update files so stale APKs do not consume storage
        updatesDir.listFiles()?.forEach { it.delete() }

        val safeName = asset.name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "DialDuo-update.apk" }
        val partFile = File(updatesDir, "$safeName.part")
        val targetFile = File(updatesDir, safeName)

        var connection: HttpURLConnection? = null
        try {
            connection = openFollowingRedirects(urlString)
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                return@withContext Result.failure(IllegalStateException("Download failed (HTTP $responseCode)"))
            }

            val totalBytes = connection.contentLengthLong.takeIf { it > 0L } ?: asset.size
            var downloaded = 0L
            val buffer = ByteArray(16 * 1024)
            var lastReportMs = 0L

            connection.inputStream.use { input ->
                FileOutputStream(partFile).use { output ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read

                        val now = System.currentTimeMillis()
                        if (now - lastReportMs >= 100L || (totalBytes > 0 && downloaded >= totalBytes)) {
                            lastReportMs = now
                            onProgress(downloaded, totalBytes)
                        }
                    }
                    output.flush()
                }
            }

            if (partFile.length() <= 0L) {
                partFile.delete()
                return@withContext Result.failure(IllegalStateException("Downloaded APK is empty"))
            }

            if (targetFile.exists()) targetFile.delete()
            if (!partFile.renameTo(targetFile)) {
                partFile.copyTo(targetFile, overwrite = true)
                partFile.delete()
            }

            onProgress(targetFile.length(), totalBytes.coerceAtLeast(targetFile.length()))
            Result.success(targetFile)
        } catch (e: kotlinx.coroutines.CancellationException) {
            partFile.delete()
            throw e
        } catch (e: Exception) {
            partFile.delete()
            Log.e(TAG, "APK download error: ${e.message}", e)
            Result.failure(e)
        } finally {
            try { connection?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun openFollowingRedirects(initialUrl: String): HttpURLConnection {
        var currentUrl = initialUrl
        repeat(MAX_REDIRECTS) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "DialDuo-Android")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) {
                    throw IllegalStateException("Redirect missing Location header")
                }
                currentUrl = URL(URL(currentUrl), location).toString()
            } else {
                return conn
            }
        }
        throw IllegalStateException("Too many redirects while downloading update")
    }

    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openUnknownSourcesSettings(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            } catch (_: Exception) {
                false
            }
        } else {
            true
        }
    }

    fun installApk(context: Context, apkFile: File): Boolean {
        return try {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch APK installer: ${e.message}", e)
            false
        }
    }

    internal fun isNewerVersion(current: String, latest: String): Boolean {
        val currParts = current.trim().trimStart('v', 'V').substringBefore('-').split(".").mapNotNull { it.toIntOrNull() }
        val lateParts = latest.trim().trimStart('v', 'V').substringBefore('-').split(".").mapNotNull { it.toIntOrNull() }

        for (i in 0 until minOf(currParts.size, lateParts.size)) {
            if (lateParts[i] > currParts[i]) return true
            if (lateParts[i] < currParts[i]) return false
        }
        return lateParts.size > currParts.size
    }
}
