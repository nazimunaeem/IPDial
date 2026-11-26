package com.ipdial.data.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object BackupScheduler {

    private const val UNIQUE_PERIODIC_WORK = "ipdial_auto_backup_periodic"

    fun syncSchedule(context: Context, settings: BackupSettings) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        if (!settings.autoBackupEnabled || settings.autoBackupTreeUri.isBlank()) {
            workManager.cancelUniqueWork(UNIQUE_PERIODIC_WORK)
            return
        }

        val intervalHours = settings.frequency.intervalHours.coerceAtLeast(1L)
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<BackupWorker>(
            repeatInterval = intervalHours,
            repeatIntervalTimeUnit = TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun runImmediateAutoBackup(context: Context) {
        val request = OneTimeWorkRequestBuilder<BackupWorker>().build()
        WorkManager.getInstance(context.applicationContext).enqueue(request)
    }
}
