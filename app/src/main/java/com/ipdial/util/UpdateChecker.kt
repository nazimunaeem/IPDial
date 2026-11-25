package com.ipdial.util

import android.content.Intent
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

object UpdateChecker {
    private const val GITHUB_API_URL = "https://api.github.com/repos/nazimunaeem/IPDial/releases/latest"
    private const val TAG = "UpdateChecker"

    data class GitHubRelease(
        @SerializedName("tag_name") val tagName: String,
        @SerializedName("html_url") val htmlUrl: String,
        @SerializedName("body") val body: String?
    )

    suspend fun checkForUpdates(currentVersion: String): GitHubRelease? = withContext(Dispatchers.IO) {
        try {
            val json = URL(GITHUB_API_URL).readText()
            val release = Gson().fromJson(json, GitHubRelease::class.java)
            
            // Assuming version tags are like "v1.1" or "1.1"
            val latestVersion = release.tagName.replace("v", "")
            if (isNewerVersion(currentVersion, latestVersion)) {
                return@withContext release
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for updates: ${e.message}")
        }
        null
    }

    internal fun isNewerVersion(current: String, latest: String): Boolean {
        val currParts = current.split(".").mapNotNull { it.toIntOrNull() }
        val lateParts = latest.split(".").mapNotNull { it.toIntOrNull() }
        
        for (i in 0 until minOf(currParts.size, lateParts.size)) {
            if (lateParts[i] > currParts[i]) return true
            if (lateParts[i] < currParts[i]) return false
        }
        return lateParts.size > currParts.size
    }
}
