package com.ipdial.ui.components

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ipdial.ui.theme.GlassMode
import com.ipdial.ui.theme.LocalGlassMode
import com.ipdial.ui.theme.glass
import com.ipdial.util.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun AppUpdateDialog(
    release: UpdateChecker.GitHubRelease,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val isGlass = LocalGlassMode.current != GlassMode.None

    val apkAsset = remember(release) { release.findApkAsset() }
    var downloadState by remember(release) { mutableStateOf<UpdateChecker.DownloadState>(UpdateChecker.DownloadState.Idle) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var awaitingInstallPermission by remember { mutableStateOf(false) }

    val tryLaunchInstaller: (java.io.File) -> Unit = { file ->
        if (UpdateChecker.canInstallPackages(context)) {
            awaitingInstallPermission = false
            if (!UpdateChecker.installApk(context, file)) {
                Toast.makeText(context, "Could not launch APK installer", Toast.LENGTH_SHORT).show()
            }
        } else {
            awaitingInstallPermission = true
        }
    }

    DisposableEffect(lifecycleOwner, downloadState, awaitingInstallPermission) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && awaitingInstallPermission) {
                val ready = downloadState as? UpdateChecker.DownloadState.ReadyToInstall
                if (ready != null && UpdateChecker.canInstallPackages(context)) {
                    awaitingInstallPermission = false
                    UpdateChecker.installApk(context, ready.apkFile)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val startDownload: () -> Unit = {
        val asset = apkAsset
        if (asset == null) {
            openReleaseBrowser(context, release.htmlUrl)
            onDismiss()
        } else {
            downloadJob?.cancel()
            downloadState = UpdateChecker.DownloadState.Downloading(0L, asset.size)
            downloadJob = scope.launch {
                val result = UpdateChecker.downloadApk(context, asset) { downloaded, total ->
                    withContext(Dispatchers.Main) {
                        downloadState = UpdateChecker.DownloadState.Downloading(downloaded, total)
                    }
                }
                result.onSuccess { file ->
                    downloadState = UpdateChecker.DownloadState.ReadyToInstall(file)
                    tryLaunchInstaller(file)
                }.onFailure { err ->
                    downloadState = UpdateChecker.DownloadState.Failed(
                        err.message?.takeIf { it.isNotBlank() } ?: "Download failed"
                    )
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (downloadState !is UpdateChecker.DownloadState.Downloading) {
                onDismiss()
            }
        },
        containerColor = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.surface,
        modifier = if (isGlass) Modifier.glass(MaterialTheme.shapes.extraLarge, alpha = 0.95f) else Modifier,
        icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
        title = { Text("Update Available") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "DialDuo ${release.tagName} is available" +
                        (apkAsset?.takeIf { it.size > 0 }?.let { " (${formatMb(it.size)})" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )

                val notes = release.body?.takeIf { it.isNotBlank() }
                if (notes != null && downloadState is UpdateChecker.DownloadState.Idle) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                when (val state = downloadState) {
                    is UpdateChecker.DownloadState.Idle -> Unit
                    is UpdateChecker.DownloadState.Downloading -> {
                        Spacer(Modifier.height(2.dp))
                        if (state.totalBytes > 0L) {
                            val progress = (state.bytesDownloaded.toFloat() / state.totalBytes.toFloat()).coerceIn(0f, 1f)
                            val percent = (progress * 100).toInt()
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("update_download_progress")
                            )
                            Text(
                                text = "Downloading… ${formatMb(state.bytesDownloaded)} / ${formatMb(state.totalBytes)} ($percent%)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("update_download_progress")
                            )
                            Text(
                                text = "Downloading… ${formatMb(state.bytesDownloaded)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    is UpdateChecker.DownloadState.ReadyToInstall -> {
                        Text(
                            text = if (awaitingInstallPermission && !UpdateChecker.canInstallPackages(context)) {
                                "Download complete. Allow DialDuo to install apps in Settings, then return to install."
                            } else {
                                "Download complete. Ready to install DialDuo ${release.tagName}."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    is UpdateChecker.DownloadState.Failed -> {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (val state = downloadState) {
                is UpdateChecker.DownloadState.Idle -> {
                    Button(
                        onClick = startDownload,
                        modifier = Modifier.testTag("update_download_button")
                    ) {
                        Text(if (apkAsset != null) "Download & Install" else "Open Release")
                    }
                }
                is UpdateChecker.DownloadState.Downloading -> {
                    TextButton(
                        onClick = {
                            downloadJob?.cancel()
                            downloadJob = null
                            downloadState = UpdateChecker.DownloadState.Idle
                        },
                        modifier = Modifier.testTag("update_cancel_button")
                    ) {
                        Text("Cancel")
                    }
                }
                is UpdateChecker.DownloadState.ReadyToInstall -> {
                    if (!UpdateChecker.canInstallPackages(context)) {
                        Button(
                            onClick = {
                                awaitingInstallPermission = true
                                if (!UpdateChecker.openUnknownSourcesSettings(context)) {
                                    Toast.makeText(context, "Open Settings to allow app installs", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.testTag("update_allow_install_button")
                        ) {
                            Text("Allow & Install")
                        }
                    } else {
                        Button(
                            onClick = { tryLaunchInstaller(state.apkFile) },
                            modifier = Modifier.testTag("update_install_now_button")
                        ) {
                            Text("Install Now")
                        }
                    }
                }
                is UpdateChecker.DownloadState.Failed -> {
                    Button(
                        onClick = startDownload,
                        modifier = Modifier.testTag("update_retry_button")
                    ) {
                        Text("Retry")
                    }
                }
            }
        },
        dismissButton = {
            when (downloadState) {
                is UpdateChecker.DownloadState.Downloading -> Unit
                is UpdateChecker.DownloadState.Failed -> {
                    TextButton(
                        onClick = {
                            openReleaseBrowser(context, release.htmlUrl)
                            onDismiss()
                        }
                    ) {
                        Text("Browser")
                    }
                }
                else -> {
                    TextButton(onClick = onDismiss) {
                        Text("Later")
                    }
                }
            }
        }
    )
}

private fun formatMb(bytes: Long): String {
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return String.format(Locale.US, "%.1f MB", mb)
}

private fun openReleaseBrowser(context: android.content.Context, url: String) {
    if (url.isBlank()) return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (_: Exception) {
        Toast.makeText(context, "No browser app found", Toast.LENGTH_SHORT).show()
    }
}
