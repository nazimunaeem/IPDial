package com.ipdial.data.backup

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ipdial.data.repository.AccountRepository
import kotlinx.coroutines.flow.first

class BackupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repo = AccountRepository(applicationContext)
        val settings = repo.backupSettings.first()

        if (!settings.autoBackupEnabled || settings.autoBackupTreeUri.isBlank()) {
            return Result.success()
        }
        val destLabel = settings.autoBackupFolderLabel.ifBlank { "Local backup folder" }
        if (!settings.options.hasAnySelected()) {
            repo.recordBackupStatus(
                timestampMs = System.currentTimeMillis(),
                status = "Skipped — no backup items selected",
                destination = destLabel
            )
            return Result.success()
        }

        val treeUri = runCatching { Uri.parse(settings.autoBackupTreeUri) }.getOrNull()
            ?: return Result.failure()

        val result = BackupManager.backupToTreeUri(
            context = applicationContext,
            treeUri = treeUri,
            options = settings.options
        )

        val now = System.currentTimeMillis()
        return if (result.success) {
            repo.recordBackupStatus(
                timestampMs = now,
                status = "Auto: ${result.humanSummary()}",
                destination = destLabel
            )
            Result.success()
        } else {
            repo.recordBackupStatus(
                timestampMs = now,
                status = "Auto backup failed: ${result.errorMessage ?: "Unknown error"}",
                destination = destLabel
            )
            if (result.needsFolderPermission) Result.failure() else Result.retry()
        }
    }
}
