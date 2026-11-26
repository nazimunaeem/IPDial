package com.ipdial.data.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.ipdial.data.model.CallLogEntry
import com.ipdial.data.model.PreferredCodec
import com.ipdial.data.model.RegStatus
import com.ipdial.data.model.SipAccount
import com.ipdial.data.model.withSanitizedCodecs
import com.ipdial.data.repository.AccountRepository
import com.ipdial.data.repository.CallLogRepository
import com.ipdial.data.repository.SystemCallLogRepository
import com.ipdial.service.RecordingManager
import com.ipdial.ui.mergeCallLogEntries
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Type
import java.security.MessageDigest

/**
 * Local folder backup & restore engine.
 * Writes settings.json, sip_accounts.json, call_history.json, manifest.json,
 * and recording audio files directly into the user-selected local folder (no ZIP).
 * Updates only new or changed items on every run.
 */
object BackupManager {

    internal const val FILE_MANIFEST = "manifest.json"
    internal const val FILE_SETTINGS = "settings.json"
    internal const val FILE_ACCOUNTS = "sip_accounts.json"
    internal const val FILE_CALL_HISTORY = "call_history.json"
    private const val BACKUP_FORMAT_VERSION = 3

    private val AUDIO_EXTENSIONS = setOf(
        "m4a", "wav", "mp3", "ogg", "aac", "3gp", "amr", "flac", "opus", "mp4"
    )

    internal val gson: Gson = GsonBuilder()
        .registerTypeAdapter(PreferredCodec::class.java, object : JsonDeserializer<PreferredCodec> {
            override fun deserialize(
                json: JsonElement,
                typeOfT: Type,
                context: JsonDeserializationContext
            ): PreferredCodec? = try {
                PreferredCodec.valueOf(json.asString)
            } catch (_: IllegalArgumentException) {
                null
            }
        })
        .setPrettyPrinting()
        .create()

    fun allRecordingFiles(context: Context): List<File> {
        val internalDir = File(context.filesDir, "recordings")
        val externalDir = RecordingManager.recordingsFolder(context)
        val combined = LinkedHashMap<String, File>()
        listOf(externalDir, internalDir).forEach { dir ->
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { file ->
                    if (file.isFile && file.length() > 0L) {
                        combined.putIfAbsent(file.name, file)
                    }
                }
            }
        }
        return combined.values.sortedByDescending { it.lastModified() }
    }

    fun resolveFolderLabel(context: Context, treeUri: Uri): String {
        val docName = try {
            DocumentFile.fromTreeUri(context, treeUri)?.name?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
        if (docName != null) return docName
        val docId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: Throwable) {
            treeUri.lastPathSegment.orEmpty()
        }
        return docId.substringAfterLast(':').ifBlank { docId }.ifBlank { "Selected folder" }
    }

    fun hasFolderAccess(context: Context, treeUriString: String): Boolean {
        if (treeUriString.isBlank()) return false
        val uri = runCatching { Uri.parse(treeUriString) }.getOrNull() ?: return false
        val hasPersisted = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        if (!hasPersisted) return false
        val folder = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull() ?: return false
        return folder.exists() && folder.canWrite()
    }

    suspend fun backupToTreeUri(
        context: Context,
        treeUri: Uri,
        options: BackupOptions
    ): BackupResult {
        if (!options.hasAnySelected()) {
            return BackupResult(success = false, errorMessage = "Select at least one backup category")
        }
        return try {
            val folder = DocumentFile.fromTreeUri(context, treeUri)
                ?: return BackupResult(
                    success = false,
                    needsFolderPermission = true,
                    errorMessage = "Selected backup folder is no longer accessible"
                )
            if (!folder.exists() || !folder.canWrite()) {
                return BackupResult(
                    success = false,
                    needsFolderPermission = true,
                    errorMessage = "Folder write permission is required"
                )
            }

            val payload = collectPayload(context, options)
            val target = SafFolderTarget(
                context = context,
                folder = folder,
                folderLabel = resolveFolderLabel(context, treeUri)
            )
            syncIncrementalToFolder(
                target = target,
                options = options,
                appVersion = payload.appVersion,
                settingsJson = payload.settingsJson,
                accounts = payload.accounts,
                callHistory = payload.callHistory,
                recordings = payload.recordings
            )
        } catch (e: SecurityException) {
            BackupResult(
                success = false,
                needsFolderPermission = true,
                errorMessage = e.message ?: "Storage permission required"
            )
        } catch (e: Exception) {
            BackupResult(success = false, errorMessage = e.message ?: "Backup failed")
        }
    }

    fun inspectFolderUri(context: Context, treeUri: Uri): BackupArchivePreview? {
        return try {
            val folder = DocumentFile.fromTreeUri(context, treeUri) ?: return null
            if (!folder.exists() || !folder.canRead()) return null
            inspectFolder(SafFolderTarget(context, folder, resolveFolderLabel(context, treeUri)))
        } catch (_: Exception) {
            null
        }
    }

    suspend fun restoreFromTreeUri(
        context: Context,
        treeUri: Uri,
        options: BackupOptions
    ): RestoreResult {
        if (!options.hasAnySelected()) {
            return RestoreResult(success = false, errorMessage = "Select at least one item to restore")
        }
        return try {
            val folder = DocumentFile.fromTreeUri(context, treeUri)
                ?: return RestoreResult(success = false, errorMessage = "Unable to open selected backup folder")
            if (!folder.exists() || !folder.canRead()) {
                return RestoreResult(success = false, errorMessage = "Read permission required for backup folder")
            }

            val target = SafFolderTarget(context, folder, resolveFolderLabel(context, treeUri))
            val files = target.listFiles().associateBy { it.name }

            val accountRepo = AccountRepository(context)
            val callLogRepo = CallLogRepository.getInstance(context)
            val recordingsFolder = RecordingManager.recordingsFolder(context)

            var restoredSettings = false
            var restoredAccounts = 0
            var restoredCalls = 0
            var restoredRecordings = 0
            var recognized = FILE_MANIFEST in files ||
                FILE_SETTINGS in files ||
                FILE_ACCOUNTS in files ||
                FILE_CALL_HISTORY in files

            if (options.includeSettings) {
                files[FILE_SETTINGS]?.let { entry ->
                    val text = target.readText(entry.name).orEmpty()
                    if (text.isNotBlank()) {
                        val obj = JsonParser.parseString(text).asJsonObject
                        accountRepo.restoreSettingsFromJson(obj)
                        restoredSettings = true
                    }
                }
            }

            if (options.includeSipAccounts) {
                files[FILE_ACCOUNTS]?.let { entry ->
                    val text = target.readText(entry.name).orEmpty()
                    if (text.isNotBlank()) {
                        val list = parseAccountsJson(text)
                        restoredAccounts = accountRepo.mergeRestoredAccounts(list)
                    }
                }
            }

            if (options.includeCallHistory) {
                files[FILE_CALL_HISTORY]?.let { entry ->
                    val text = target.readText(entry.name).orEmpty()
                    if (text.isNotBlank()) {
                        val list = parseCallHistoryJson(text)
                        if (list.isNotEmpty()) {
                            callLogRepo.importAll(list)
                            restoredCalls = list.size
                        }
                    }
                }
            }

            if (options.includeRecordings) {
                val audioEntries = files.values.filter { isRecordingFileName(it.name) }
                if (audioEntries.isNotEmpty()) recognized = true
                for (entry in audioEntries) {
                    val safeName = sanitizeRecordingName(entry.name) ?: continue
                    val outFile = File(recordingsFolder, safeName)
                    if (outFile.exists() && entry.sizeBytes > 0L && outFile.length() == entry.sizeBytes) {
                        continue
                    }
                    target.openInput(entry.name)?.use { input ->
                        FileOutputStream(outFile).use { fos -> input.copyTo(fos) }
                        restoredRecordings++
                    }
                }
            }

            if (!recognized) {
                return RestoreResult(
                    success = false,
                    errorMessage = "No DialDuo backup files found in selected folder"
                )
            }

            RestoreResult(
                success = true,
                restoredSettings = restoredSettings,
                restoredAccounts = restoredAccounts,
                restoredCalls = restoredCalls,
                restoredRecordings = restoredRecordings
            )
        } catch (e: Exception) {
            RestoreResult(success = false, errorMessage = e.message ?: "Restore failed")
        }
    }

    internal fun syncIncrementalToFolder(
        target: BackupFolderTarget,
        options: BackupOptions,
        appVersion: String,
        settingsJson: JsonObject?,
        accounts: List<SipAccount>,
        callHistory: List<CallLogEntry>,
        recordings: List<File>
    ): BackupResult {
        val existingEntries = target.listFiles().associateBy { it.name }

        val existingManifest = existingEntries[FILE_MANIFEST]?.let {
            runCatching { JsonParser.parseString(target.readText(FILE_MANIFEST).orEmpty()).asJsonObject }.getOrNull()
        }
        val prevSettingsHash = existingManifest?.get("settingsHash")?.asString.orEmpty()
        val prevAccountsHash = existingManifest?.get("accountsHash")?.asString.orEmpty()
        val prevHistoryHash = existingManifest?.get("historyHash")?.asString.orEmpty()
        val prevRecMetas = existingManifest?.let { parseManifestRecordingMetas(it) }.orEmpty()

        val settingsText = if (options.includeSettings && settingsJson != null) gson.toJson(settingsJson) else null
        val settingsHash = settingsText?.let { sha256(it) } ?: prevSettingsHash
        val settingsChanged = settingsText != null &&
            (FILE_SETTINGS !in existingEntries || settingsHash != prevSettingsHash)

        val sanitizedAccounts = accounts.map { it.copy(regStatus = RegStatus.UNREGISTERED, regStatusText = "") }
        val accountsText = if (options.includeSipAccounts) gson.toJson(sanitizedAccounts) else null
        val accountsHash = accountsText?.let { sha256(it) } ?: prevAccountsHash
        val accountsChanged = accountsText != null &&
            (FILE_ACCOUNTS !in existingEntries || accountsHash != prevAccountsHash)

        val prevHistoryList = if (options.includeCallHistory && FILE_CALL_HISTORY in existingEntries) {
            runCatching { parseCallHistoryJson(target.readText(FILE_CALL_HISTORY).orEmpty()) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val mergedHistory = if (options.includeCallHistory) {
            mergeCallLogEntries(callHistory, prevHistoryList)
        } else {
            null
        }
        val historyText = mergedHistory?.let { gson.toJson(it) }
        val historyHash = historyText?.let { sha256(it) } ?: prevHistoryHash
        val historyChanged = historyText != null &&
            (FILE_CALL_HISTORY !in existingEntries || historyHash != prevHistoryHash)
        val newCallsCount = if (historyChanged && mergedHistory != null) {
            (mergedHistory.size - prevHistoryList.size).coerceAtLeast(0)
        } else 0

        val validLocalRecordings = if (options.includeRecordings) {
            recordings.filter { it.isFile && it.exists() && it.length() > 0L }
                .mapNotNull { file -> sanitizeRecordingName(file.name)?.let { safe -> safe to file } }
                .toMap()
        } else {
            emptyMap()
        }

        val newOrModifiedRecordings = LinkedHashMap<String, File>()
        for ((safeName, localFile) in validLocalRecordings) {
            val remoteEntry = existingEntries[safeName]
            val prevMeta = prevRecMetas[safeName]
            val matchesRemoteFile = remoteEntry != null &&
                (remoteEntry.sizeBytes <= 0L || remoteEntry.sizeBytes == localFile.length())
            val matchesManifest = prevMeta != null &&
                prevMeta.sizeBytes == localFile.length() &&
                kotlin.math.abs(prevMeta.lastModifiedMs - localFile.lastModified()) <= 2000L
            if (!matchesRemoteFile || (!matchesManifest && prevMeta != null)) {
                newOrModifiedRecordings[safeName] = localFile
            }
        }

        val existingAudioCount = existingEntries.values.count { isRecordingFileName(it.name) }

        val updatedSections = buildList {
            if (settingsChanged) add("settings")
            if (accountsChanged) add("accounts")
            if (historyChanged) add("history")
            if (newOrModifiedRecordings.isNotEmpty()) add("recordings")
        }

        if (existingManifest != null && updatedSections.isEmpty()) {
            return BackupResult(
                success = true,
                folderName = target.folderLabel,
                accountsCount = sanitizedAccounts.size,
                callHistoryCount = mergedHistory?.size ?: 0,
                recordingsCount = existingAudioCount,
                skippedNoChanges = true
            )
        }

        var bytesWritten = 0L
        if (settingsChanged && settingsText != null) {
            val bytes = settingsText.toByteArray(Charsets.UTF_8)
            target.writeFile(FILE_SETTINGS, "application/json", bytes)
            bytesWritten += bytes.size
        }
        if (accountsChanged && accountsText != null) {
            val bytes = accountsText.toByteArray(Charsets.UTF_8)
            target.writeFile(FILE_ACCOUNTS, "application/json", bytes)
            bytesWritten += bytes.size
        }
        if (historyChanged && historyText != null) {
            val bytes = historyText.toByteArray(Charsets.UTF_8)
            target.writeFile(FILE_CALL_HISTORY, "application/json", bytes)
            bytesWritten += bytes.size
        }

        for ((safeName, recFile) in newOrModifiedRecordings) {
            target.copyFile(safeName, guessAudioMime(safeName), recFile)
            bytesWritten += recFile.length()
        }

        val mergedRecMetas = LinkedHashMap<String, BackupRecordingMeta>().apply {
            putAll(prevRecMetas)
            for ((safeName, recFile) in validLocalRecordings) {
                put(
                    safeName,
                    BackupRecordingMeta(
                        name = safeName,
                        sizeBytes = recFile.length(),
                        lastModifiedMs = recFile.lastModified()
                    )
                )
            }
        }

        val totalRecCount = maxOf(
            mergedRecMetas.size,
            (existingEntries.keys + newOrModifiedRecordings.keys).count { isRecordingFileName(it) }
        )
        val finalAccountsCount = if (options.includeSipAccounts) sanitizedAccounts.size
        else existingManifest?.get("sipAccountsCount")?.asInt ?: 0
        val finalHistoryCount = mergedHistory?.size
            ?: existingManifest?.get("callHistoryCount")?.asInt
            ?: 0

        val manifestJson = buildManifestJson(
            createdAtMs = System.currentTimeMillis(),
            appVersion = appVersion,
            hasSettings = settingsText != null || FILE_SETTINGS in existingEntries,
            accountsCount = finalAccountsCount,
            historyCount = finalHistoryCount,
            recordingsCount = totalRecCount,
            settingsHash = settingsHash,
            accountsHash = accountsHash,
            historyHash = historyHash,
            recordingMetas = mergedRecMetas.values.toList()
        )
        val manifestBytes = gson.toJson(manifestJson).toByteArray(Charsets.UTF_8)
        target.writeFile(FILE_MANIFEST, "application/json", manifestBytes)
        bytesWritten += manifestBytes.size

        return BackupResult(
            success = true,
            folderName = target.folderLabel,
            bytesWritten = bytesWritten,
            accountsCount = finalAccountsCount,
            callHistoryCount = finalHistoryCount,
            recordingsCount = totalRecCount,
            skippedNoChanges = false,
            newCallsCount = newCallsCount,
            newRecordingsCount = newOrModifiedRecordings.size,
            updatedSections = updatedSections
        )
    }

    internal fun inspectFolder(target: BackupFolderTarget): BackupArchivePreview? {
        val entries = target.listFiles().associateBy { it.name }
        val hasSettings = FILE_SETTINGS in entries
        val hasAccounts = FILE_ACCOUNTS in entries
        val hasHistory = FILE_CALL_HISTORY in entries
        val audioCount = entries.values.count { isRecordingFileName(it.name) }

        val manifest = entries[FILE_MANIFEST]?.let {
            runCatching { JsonParser.parseString(target.readText(FILE_MANIFEST).orEmpty()).asJsonObject }.getOrNull()
        }

        if (!hasSettings && !hasAccounts && !hasHistory && audioCount == 0 && manifest == null) {
            return null
        }

        val createdAtMs = manifest?.get("createdAtMs")?.let { runCatching { it.asLong }.getOrNull() } ?: 0L
        val appVersion = manifest?.get("appVersion")?.let { runCatching { it.asString }.getOrNull() }.orEmpty()
        val accountsCount = if (hasAccounts) {
            runCatching { parseAccountsJson(target.readText(FILE_ACCOUNTS).orEmpty()).size }.getOrDefault(0)
        } else 0
        val historyCount = if (hasHistory) {
            runCatching { parseCallHistoryJson(target.readText(FILE_CALL_HISTORY).orEmpty()).size }.getOrDefault(0)
        } else 0

        return BackupArchivePreview(
            createdAtMs = createdAtMs,
            appVersion = appVersion,
            hasSettings = hasSettings,
            sipAccountsCount = accountsCount,
            callHistoryCount = historyCount,
            recordingsCount = audioCount
        )
    }

    internal fun isRecordingFileName(name: String): Boolean {
        val lower = name.lowercase()
        val ext = lower.substringAfterLast('.', "")
        return ext in AUDIO_EXTENSIONS
    }

    internal fun sanitizeRecordingName(rawName: String): String? {
        val clean = File(rawName.replace('\\', '/')).name.trim()
        if (clean.isEmpty() || clean == "." || clean == ".." || clean.contains("..")) return null
        return clean
    }

    internal fun parseAccountsJson(text: String): List<SipAccount> {
        val type = object : TypeToken<List<SipAccount>>() {}.type
        return runCatching { gson.fromJson<List<SipAccount>>(text, type) }
            .getOrNull()
            ?.map { it.withSanitizedCodecs() }
            ?: emptyList()
    }

    internal fun parseCallHistoryJson(text: String): List<CallLogEntry> {
        val type = object : TypeToken<List<CallLogEntry>>() {}.type
        return runCatching { gson.fromJson<List<CallLogEntry>>(text, type) }.getOrNull() ?: emptyList()
    }

    private fun buildManifestJson(
        createdAtMs: Long,
        appVersion: String,
        hasSettings: Boolean,
        accountsCount: Int,
        historyCount: Int,
        recordingsCount: Int,
        settingsHash: String,
        accountsHash: String,
        historyHash: String,
        recordingMetas: List<BackupRecordingMeta>
    ): JsonObject = JsonObject().apply {
        addProperty("version", BACKUP_FORMAT_VERSION)
        addProperty("createdAtMs", createdAtMs)
        addProperty("appVersion", appVersion)
        addProperty("hasSettings", hasSettings)
        addProperty("sipAccountsCount", accountsCount)
        addProperty("callHistoryCount", historyCount)
        addProperty("recordingsCount", recordingsCount)
        addProperty("settingsHash", settingsHash)
        addProperty("accountsHash", accountsHash)
        addProperty("historyHash", historyHash)
        add("recordingEntries", gson.toJsonTree(recordingMetas))
    }

    private fun parseManifestRecordingMetas(manifest: JsonObject): Map<String, BackupRecordingMeta> {
        val elem = manifest.get("recordingEntries") ?: return emptyMap()
        if (!elem.isJsonArray) return emptyMap()
        val type = object : TypeToken<List<BackupRecordingMeta>>() {}.type
        val list: List<BackupRecordingMeta> = runCatching { gson.fromJson<List<BackupRecordingMeta>>(elem, type) }
            .getOrNull().orEmpty()
        return list.associateBy { it.name }
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun guessAudioMime(name: String): String = when {
        name.endsWith(".m4a", true) -> "audio/mp4"
        name.endsWith(".mp3", true) -> "audio/mpeg"
        name.endsWith(".wav", true) -> "audio/wav"
        name.endsWith(".ogg", true) -> "audio/ogg"
        name.endsWith(".aac", true) -> "audio/aac"
        else -> "application/octet-stream"
    }

    private suspend fun collectPayload(context: Context, options: BackupOptions): BackupPayload {
        val accountRepo = AccountRepository(context)
        val callLogRepo = CallLogRepository.getInstance(context)

        val settingsJson = if (options.includeSettings) accountRepo.exportAllSettingsJson() else null
        val accounts = if (options.includeSipAccounts) accountRepo.accounts.first() else emptyList()
        val callHistory = if (options.includeCallHistory) {
            val appEntries = callLogRepo.entries.first()
            val deviceEntries = SystemCallLogRepository.read(context)
            mergeCallLogEntries(appEntries, deviceEntries)
        } else {
            emptyList()
        }
        val recordings = if (options.includeRecordings) allRecordingFiles(context) else emptyList()
        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0)?.versionName ?: "2.0.0"
        } catch (_: Exception) {
            "2.0.0"
        }
        return BackupPayload(appVersion, settingsJson, accounts, callHistory, recordings)
    }

    internal data class FolderEntryMeta(
        val name: String,
        val sizeBytes: Long
    )

    internal interface BackupFolderTarget {
        val folderLabel: String
        fun listFiles(): List<FolderEntryMeta>
        fun readText(fileName: String): String?
        fun openInput(fileName: String): InputStream?
        fun writeFile(fileName: String, mimeType: String, bytes: ByteArray)
        fun copyFile(fileName: String, mimeType: String, source: File)
    }

    internal class LocalDirTarget(private val dir: File) : BackupFolderTarget {
        override val folderLabel: String get() = dir.name

        override fun listFiles(): List<FolderEntryMeta> {
            dir.mkdirs()
            return dir.listFiles()
                ?.filter { it.isFile }
                ?.map { FolderEntryMeta(it.name, it.length()) }
                ?: emptyList()
        }

        override fun readText(fileName: String): String? {
            val f = File(dir, fileName)
            return if (f.isFile) f.readText(Charsets.UTF_8) else null
        }

        override fun openInput(fileName: String): InputStream? {
            val f = File(dir, fileName)
            return if (f.isFile) FileInputStream(f) else null
        }

        override fun writeFile(fileName: String, mimeType: String, bytes: ByteArray) {
            dir.mkdirs()
            File(dir, fileName).writeBytes(bytes)
        }

        override fun copyFile(fileName: String, mimeType: String, source: File) {
            dir.mkdirs()
            source.copyTo(File(dir, fileName), overwrite = true)
        }
    }

    private class SafFolderTarget(
        private val context: Context,
        private val folder: DocumentFile,
        override val folderLabel: String
    ) : BackupFolderTarget {

        private fun findDoc(fileName: String): DocumentFile? {
            return folder.findFile(fileName)?.takeIf { it.isFile }
                ?: folder.listFiles().firstOrNull { it.isFile && it.name == fileName }
        }

        override fun listFiles(): List<FolderEntryMeta> {
            return folder.listFiles()
                .filter { it.isFile && !it.name.isNullOrBlank() }
                .map { FolderEntryMeta(it.name!!, it.length()) }
        }

        override fun readText(fileName: String): String? {
            val doc = findDoc(fileName) ?: return null
            return context.contentResolver.openInputStream(doc.uri)?.use { input ->
                input.bufferedReader(Charsets.UTF_8).readText()
            }
        }

        override fun openInput(fileName: String): InputStream? {
            val doc = findDoc(fileName) ?: return null
            return context.contentResolver.openInputStream(doc.uri)
        }

        override fun writeFile(fileName: String, mimeType: String, bytes: ByteArray) {
            val doc = findDoc(fileName)
                ?: folder.createFile(mimeType, fileName)
                ?: throw IllegalStateException("Could not create $fileName in backup folder")
            context.contentResolver.openOutputStream(doc.uri, "wt")?.use { out: OutputStream ->
                out.write(bytes)
                out.flush()
            } ?: throw IllegalStateException("Could not write $fileName in backup folder")
        }

        override fun copyFile(fileName: String, mimeType: String, source: File) {
            val doc = findDoc(fileName)
                ?: folder.createFile(mimeType, fileName)
                ?: throw IllegalStateException("Could not create $fileName in backup folder")
            context.contentResolver.openOutputStream(doc.uri, "wt")?.use { out ->
                FileInputStream(source).use { fis -> fis.copyTo(out) }
                out.flush()
            } ?: throw IllegalStateException("Could not write $fileName in backup folder")
        }
    }

    private data class BackupPayload(
        val appVersion: String,
        val settingsJson: JsonObject?,
        val accounts: List<SipAccount>,
        val callHistory: List<CallLogEntry>,
        val recordings: List<File>
    )
}
