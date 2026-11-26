package com.ipdial.ui.screens

import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ipdial.data.backup.BackupArchivePreview
import com.ipdial.data.backup.BackupFrequency
import com.ipdial.data.backup.BackupManager
import com.ipdial.data.backup.BackupOptions
import com.ipdial.ui.SipViewModel
import com.ipdial.ui.theme.GlassMode
import com.ipdial.ui.theme.LocalGlassMode
import com.ipdial.ui.theme.glass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class PendingTreeAction {
    BACKUP_NOW,
    RESTORE_FOLDER,
    ENABLE_AUTO_BACKUP,
    CHANGE_FOLDER
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BackupScreen(
    vm: SipViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backupSettings by vm.backupSettings.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val callLog by vm.callLog.collectAsState()
    val isGlass = LocalGlassMode.current != GlassMode.None

    var recordingsInfo by remember { mutableStateOf(0 to 0L) }
    LaunchedEffect(Unit) {
        recordingsInfo = withContext(Dispatchers.IO) {
            val files = BackupManager.allRecordingFiles(context)
            files.size to files.sumOf { it.length() }
        }
    }

    var isBusy by remember { mutableStateOf(false) }
    var busyMessage by remember { mutableStateOf("") }

    var pendingRestoreTreeUri by remember { mutableStateOf<Uri?>(null) }
    var restorePreview by remember { mutableStateOf<BackupArchivePreview?>(null) }
    var restoreOptions by remember { mutableStateOf(BackupOptions()) }
    var pendingAction by remember { mutableStateOf(PendingTreeAction.CHANGE_FOLDER) }

    val openTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        when (pendingAction) {
            PendingTreeAction.BACKUP_NOW -> {
                scope.launch {
                    isBusy = true
                    busyMessage = "Updating backup folder (new items only)…"
                    val res = vm.grantBackupFolderAndRunBackup(
                        treeUri = treeUri,
                        options = backupSettings.options
                    )
                    isBusy = false
                    busyMessage = ""
                    Toast.makeText(context, res.humanSummary(), Toast.LENGTH_LONG).show()
                }
            }
            PendingTreeAction.RESTORE_FOLDER -> {
                scope.launch {
                    isBusy = true
                    busyMessage = "Scanning backup folder…"
                    val preview = vm.inspectBackupFolder(treeUri)
                    isBusy = false
                    busyMessage = ""
                    if (preview == null) {
                        Toast.makeText(
                            context,
                            "No DialDuo backup files found in selected folder",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        pendingRestoreTreeUri = treeUri
                        restorePreview = preview
                        restoreOptions = BackupOptions(
                            includeSettings = preview.hasSettings,
                            includeSipAccounts = preview.sipAccountsCount > 0,
                            includeCallHistory = preview.callHistoryCount > 0,
                            includeRecordings = preview.recordingsCount > 0
                        )
                    }
                }
            }
            PendingTreeAction.ENABLE_AUTO_BACKUP -> {
                vm.setAutoBackupFolder(treeUri, enableAutoBackupIfUnset = true)
                Toast.makeText(context, "Auto backup enabled", Toast.LENGTH_SHORT).show()
            }
            PendingTreeAction.CHANGE_FOLDER -> {
                vm.setAutoBackupFolder(treeUri, enableAutoBackupIfUnset = false)
                Toast.makeText(context, "Backup folder updated", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val options = backupSettings.options
    val hasAnyChecked = options.hasAnySelected()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup & Restore") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("backup_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (isBusy) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = busyMessage.ifBlank { "Working…" },
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            // ── Status Hero Card ──
            item {
                val heroGradient = Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f)
                    )
                )
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (isGlass) Modifier.glass(RoundedCornerShape(22.dp)) else Modifier)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(heroGradient)
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Local Folder Backup",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = "Saves separate JSON files and recordings directly into your chosen folder — new changes only.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                                )
                            }
                        }

                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.14f)
                        )

                        val lastTimeText = if (backupSettings.lastBackupAtMs > 0L) {
                            SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault())
                                .format(Date(backupSettings.lastBackupAtMs))
                        } else {
                            "Never"
                        }
                        Text(
                            text = "Last backup: $lastTimeText",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        if (backupSettings.lastBackupStatus.isNotBlank()) {
                            Text(
                                text = "${backupSettings.lastBackupStatus}" +
                                    if (backupSettings.lastBackupDestination.isNotBlank()) {
                                        " • ${backupSettings.lastBackupDestination}"
                                    } else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
            }

            // ── Backup Content Checkboxes ──
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isGlass) MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                        else MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                    ) {
                        Text(
                            text = "Items to Include",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )

                        BackupItemCheckboxRow(
                            icon = Icons.Default.Settings,
                            title = "Settings",
                            subtitle = "Saved as settings.json (theme, dialpad, audio & TURN)",
                            checked = options.includeSettings,
                            testTag = "backup_checkbox_settings",
                            onToggle = {
                                vm.updateBackupOptions(options.copy(includeSettings = !options.includeSettings))
                            }
                        )
                        BackupItemCheckboxRow(
                            icon = Icons.Default.AccountCircle,
                            title = "SIP Accounts",
                            subtitle = "${accounts.size} account(s) — saved as sip_accounts.json",
                            checked = options.includeSipAccounts,
                            testTag = "backup_checkbox_sip_accounts",
                            onToggle = {
                                vm.updateBackupOptions(options.copy(includeSipAccounts = !options.includeSipAccounts))
                            }
                        )
                        BackupItemCheckboxRow(
                            icon = Icons.Default.History,
                            title = "Call History",
                            subtitle = "${callLog.size} call log entries — saved as call_history.json",
                            checked = options.includeCallHistory,
                            testTag = "backup_checkbox_call_history",
                            onToggle = {
                                vm.updateBackupOptions(options.copy(includeCallHistory = !options.includeCallHistory))
                            }
                        )
                        val recSizeStr = Formatter.formatShortFileSize(context, recordingsInfo.second)
                        BackupItemCheckboxRow(
                            icon = Icons.Default.Mic,
                            title = "Recordings",
                            subtitle = "${recordingsInfo.first} file(s) • $recSizeStr — copied directly to folder",
                            checked = options.includeRecordings,
                            testTag = "backup_checkbox_recordings",
                            onToggle = {
                                vm.updateBackupOptions(options.copy(includeRecordings = !options.includeRecordings))
                            }
                        )

                        if (!hasAnyChecked) {
                            Text(
                                text = "Select at least one category to enable backup.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }

            // ── Manual Local Folder Backup & Restore ──
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isGlass) MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                        else MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Manual Backup & Restore",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Text(
                            text = if (backupSettings.autoBackupFolderLabel.isNotBlank()) {
                                "Folder: ${backupSettings.autoBackupFolderLabel} — only new or updated files will be written."
                            } else {
                                "Folder access permission will only be asked when you tap Back Up Now or Restore."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Button(
                            onClick = {
                                scope.launch {
                                    isBusy = true
                                    busyMessage = "Checking for new changes…"
                                    val res = vm.runBackupNow(backupSettings.options)
                                    isBusy = false
                                    busyMessage = ""
                                    if (res.needsFolderPermission) {
                                        pendingAction = PendingTreeAction.BACKUP_NOW
                                        openTreeLauncher.launch(null)
                                    } else {
                                        Toast.makeText(context, res.humanSummary(), Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            enabled = hasAnyChecked && !isBusy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("backup_manual_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Backup,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Back Up Now (New Items Only)")
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    pendingAction = PendingTreeAction.RESTORE_FOLDER
                                    openTreeLauncher.launch(null)
                                },
                                enabled = !isBusy,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("backup_restore_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Restore,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Restore Folder", maxLines = 1)
                            }
                            if (backupSettings.autoBackupTreeUri.isNotBlank()) {
                                OutlinedButton(
                                    onClick = {
                                        pendingAction = PendingTreeAction.CHANGE_FOLDER
                                        openTreeLauncher.launch(null)
                                    },
                                    enabled = !isBusy,
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("backup_change_folder_button")
                                ) {
                                    Text("Change Folder", maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }

            // ── Automatic Scheduled Backup ──
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isGlass) MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                        else MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Auto Backup",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (backupSettings.autoBackupEnabled) {
                                        "Scheduled ${backupSettings.frequency.label.lowercase()} (${backupSettings.frequency.subtitle})"
                                    } else {
                                        "Off — enable to back up automatically on schedule"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = backupSettings.autoBackupEnabled,
                                onCheckedChange = { checked ->
                                    if (checked && !BackupManager.hasFolderAccess(context, backupSettings.autoBackupTreeUri)) {
                                        pendingAction = PendingTreeAction.ENABLE_AUTO_BACKUP
                                        openTreeLauncher.launch(null)
                                    } else {
                                        vm.setAutoBackupEnabled(checked)
                                    }
                                },
                                modifier = Modifier.testTag("backup_auto_switch")
                            )
                        }

                        if (backupSettings.autoBackupFolderLabel.isNotBlank()) {
                            Surface(
                                onClick = {
                                    pendingAction = PendingTreeAction.CHANGE_FOLDER
                                    openTreeLauncher.launch(null)
                                },
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("backup_folder_button")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 56.dp)
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FolderOpen,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Backup Folder",
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                        Text(
                                            text = backupSettings.autoBackupFolderLabel,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        text = "Change",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }

                        Text(
                            text = "Backup Frequency",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            BackupFrequency.entries.forEach { freq ->
                                FilterChip(
                                    selected = backupSettings.frequency == freq,
                                    onClick = { vm.setBackupFrequency(freq) },
                                    label = { Text(freq.label) },
                                    modifier = Modifier.testTag("backup_freq_${freq.name.lowercase()}")
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val preview = restorePreview
    val restoreTree = pendingRestoreTreeUri
    if (preview != null && restoreTree != null) {
        AlertDialog(
            onDismissRequest = {
                restorePreview = null
                pendingRestoreTreeUri = null
            },
            title = { Text("Restore Backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val createdStr = if (preview.createdAtMs > 0L) {
                        SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault())
                            .format(Date(preview.createdAtMs))
                    } else {
                        "Local backup files found"
                    }
                    Text(
                        text = "Last updated: $createdStr",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))

                    if (preview.hasSettings) {
                        RestoreOptionCheckbox(
                            label = "Settings (settings.json)",
                            checked = restoreOptions.includeSettings,
                            onToggle = {
                                restoreOptions = restoreOptions.copy(includeSettings = !restoreOptions.includeSettings)
                            }
                        )
                    }
                    if (preview.sipAccountsCount > 0) {
                        RestoreOptionCheckbox(
                            label = "SIP Accounts (${preview.sipAccountsCount})",
                            checked = restoreOptions.includeSipAccounts,
                            onToggle = {
                                restoreOptions = restoreOptions.copy(includeSipAccounts = !restoreOptions.includeSipAccounts)
                            }
                        )
                    }
                    if (preview.callHistoryCount > 0) {
                        RestoreOptionCheckbox(
                            label = "Call History (${preview.callHistoryCount})",
                            checked = restoreOptions.includeCallHistory,
                            onToggle = {
                                restoreOptions = restoreOptions.copy(includeCallHistory = !restoreOptions.includeCallHistory)
                            }
                        )
                    }
                    if (preview.recordingsCount > 0) {
                        RestoreOptionCheckbox(
                            label = "Recordings (${preview.recordingsCount})",
                            checked = restoreOptions.includeRecordings,
                            onToggle = {
                                restoreOptions = restoreOptions.copy(includeRecordings = !restoreOptions.includeRecordings)
                            }
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = restoreOptions.hasAnySelected(),
                    onClick = {
                        val chosenTree = restoreTree
                        val chosenOptions = restoreOptions
                        restorePreview = null
                        pendingRestoreTreeUri = null
                        scope.launch {
                            isBusy = true
                            busyMessage = "Restoring files from backup folder…"
                            val res = vm.restoreFromBackupFolder(chosenTree, chosenOptions)
                            if (res.success && chosenOptions.includeRecordings) {
                                recordingsInfo = withContext(Dispatchers.IO) {
                                    val files = BackupManager.allRecordingFiles(context)
                                    files.size to files.sumOf { it.length() }
                                }
                            }
                            isBusy = false
                            busyMessage = ""
                            if (res.success) {
                                val summary = buildList {
                                    if (res.restoredSettings) add("settings")
                                    if (res.restoredAccounts > 0) add("${res.restoredAccounts} account(s)")
                                    if (res.restoredCalls > 0) add("${res.restoredCalls} call(s)")
                                    if (res.restoredRecordings > 0) add("${res.restoredRecordings} recording(s)")
                                }.joinToString(", ").ifBlank { "nothing new to restore" }
                                Toast.makeText(context, "Restored $summary", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, res.errorMessage ?: "Restore failed", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    modifier = Modifier.testTag("backup_restore_confirm_button")
                ) {
                    Text("Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    restorePreview = null
                    pendingRestoreTreeUri = null
                }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun BackupItemCheckboxRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    testTag: String,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() }
        )
    }
}

@Composable
private fun RestoreOptionCheckbox(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Spacer(Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}
