package com.ipdial.data.backup

enum class BackupFrequency(
    val label: String,
    val subtitle: String,
    val intervalHours: Long
) {
    HOURLY("Hourly", "Every 1 hour", 1L),
    DAILY("Daily", "Once every 24 hours", 24L),
    WEEKLY("Weekly", "Once every 7 days", 24L * 7L),
    MONTHLY("Monthly", "Once every 30 days", 24L * 30L);

    companion object {
        fun fromNameOrDefault(raw: String?): BackupFrequency =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: DAILY
    }
}

data class BackupOptions(
    val includeSettings: Boolean = true,
    val includeSipAccounts: Boolean = true,
    val includeCallHistory: Boolean = true,
    val includeRecordings: Boolean = true
) {
    fun hasAnySelected(): Boolean =
        includeSettings || includeSipAccounts || includeCallHistory || includeRecordings
}

data class BackupRecordingMeta(
    val name: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long
)

data class BackupSettings(
    val options: BackupOptions = BackupOptions(),
    val autoBackupEnabled: Boolean = false,
    val frequency: BackupFrequency = BackupFrequency.DAILY,
    val autoBackupTreeUri: String = "",
    val autoBackupFolderLabel: String = "",
    val lastBackupAtMs: Long = 0L,
    val lastBackupStatus: String = "",
    val lastBackupDestination: String = ""
)

data class BackupArchivePreview(
    val createdAtMs: Long = 0L,
    val appVersion: String = "",
    val hasSettings: Boolean = false,
    val sipAccountsCount: Int = 0,
    val callHistoryCount: Int = 0,
    val recordingsCount: Int = 0
)

data class BackupResult(
    val success: Boolean,
    val folderName: String = "",
    val bytesWritten: Long = 0L,
    val accountsCount: Int = 0,
    val callHistoryCount: Int = 0,
    val recordingsCount: Int = 0,
    val skippedNoChanges: Boolean = false,
    val newCallsCount: Int = 0,
    val newRecordingsCount: Int = 0,
    val updatedSections: List<String> = emptyList(),
    val needsFolderPermission: Boolean = false,
    val errorMessage: String? = null
) {
    fun humanSummary(): String {
        if (!success) return errorMessage ?: "Backup failed"
        if (skippedNoChanges) return "Already up to date — no new changes"
        val details = buildList {
            if ("settings" in updatedSections) add("settings")
            if ("accounts" in updatedSections) add("$accountsCount account(s)")
            if ("history" in updatedSections) {
                add(if (newCallsCount > 0) "+$newCallsCount call(s)" else "$callHistoryCount call(s)")
            }
            if (newRecordingsCount > 0) add("+$newRecordingsCount recording(s)")
        }
        return if (details.isEmpty()) "Backup updated" else "Updated (${details.joinToString(", ")})"
    }
}

data class RestoreResult(
    val success: Boolean,
    val restoredSettings: Boolean = false,
    val restoredAccounts: Int = 0,
    val restoredCalls: Int = 0,
    val restoredRecordings: Int = 0,
    val errorMessage: String? = null
)
