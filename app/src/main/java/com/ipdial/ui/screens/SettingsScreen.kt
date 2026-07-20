package com.ipdial.ui.screens

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ipdial.R
import com.ipdial.data.model.*
import com.ipdial.ui.IPDialTopBar
import com.ipdial.ui.SipViewModel
import com.ipdial.ui.theme.LocalGlassMode
import com.ipdial.ui.theme.glass
import com.ipdial.util.UpdateChecker
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val fontSizeOptions = listOf(
    "Small" to 0.85f,
    "Normal" to 1.0f,
    "Large" to 1.15f,
    "Extra Large" to 1.3f
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SipViewModel,
    onOpenDrawer: () -> Unit,
    onNavigateToLogs: () -> Unit,
    onNavigateToCodecs: () -> Unit,
    onNavigateToTheme: () -> Unit = {},
    onNavigateToIncomingCallStyle: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accounts by vm.accounts.collectAsState()
    val globalRingtone by vm.globalRingtone.collectAsState()
    val activeAccount by vm.activeAccount.collectAsState()
    val fontSizeMultiplier by vm.fontSizeMultiplier.collectAsState()
    val appIconAlias by vm.appIconAlias.collectAsState()
    val keypadDesign by vm.keypadDesign.collectAsState()

    var showRestartDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

    if (showRestartDialog) {
        AlertDialog(
            onDismissRequest = { showRestartDialog = false },
            title = { Text("Restart Required") },
            text = { Text("The app icon has been updated. Please restart the app or check your home screen after a few seconds to see the change.") },
            confirmButton = {
                TextButton(onClick = {
                    showRestartDialog = false
                    val pm = context.packageManager
                    val intent = pm.getLaunchIntentForPackage(context.packageName)
                    if (intent != null) {
                        val pendingIntent = PendingIntent.getActivity(
                            context, 0, intent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        val am = context.getSystemService(Activity.ALARM_SERVICE) as AlarmManager
                        am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 500, pendingIntent)
                    }
                    (context as? Activity)?.finishAffinity()
                }) { Text("OK") }
            }
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset Settings") },
            text = { Text("Reset theme, font size, ringtone, and display preferences to defaults? Accounts and call history are preserved.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        vm.repo.resetSettings()
                        android.widget.Toast.makeText(context, "Settings reset to defaults", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    showResetDialog = false
                }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("Cancel") }
            }
        )
    }

    val ringtonePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            scope.launch {
                vm.repo.setGlobalRingtone(uri?.toString())
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    val label = if (uri == null) "Silent" else "Ringtone updated"
                    android.widget.Toast.makeText(context, label, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val currentVersion = remember {
        try {
            val pm = context.packageManager
            val pkgName = context.packageName
            pm.getPackageInfo(pkgName, 0)?.versionName ?: "1.0"
        }
        catch (e: Exception) {
            "1.0"
        }
    }

    var checkingUpdate by remember { mutableStateOf(false) }
    var updateRelease by remember { mutableStateOf<UpdateChecker.GitHubRelease?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var showFontSizeDialog by remember { mutableStateOf(false) }
    var showAppIconDialog by remember { mutableStateOf(false) }

    if (showFontSizeDialog) {
        AlertDialog(
            onDismissRequest = { showFontSizeDialog = false },
            title = { Text("Select Font Size") },
            text = {
                Column {
                    fontSizeOptions.forEach { (label, multiplier) ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                vm.setFontSize(context, multiplier)
                                showFontSizeDialog = false
                            }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = fontSizeMultiplier == multiplier, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    if (showAppIconDialog) {
        AlertDialog(
            onDismissRequest = { showAppIconDialog = false },
            title = { Text("Choose App Icon") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    val icons = listOf(
                        "Default" to R.drawable.ic_launcher_foreground,
                        "Green" to R.drawable.ic_phone_green,
                        "Blue" to R.drawable.ic_phone_blue,
                        "Red" to R.drawable.ic_phone_red
                    )

                    icons.chunked(2).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            row.forEach { (alias, resId) ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (appIconAlias == alias) MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                        )
                                        .clickable {
                                            vm.setAppIcon(context, alias)
                                            com.ipdial.util.AppIconHelper.setAppIcon(context, alias)
                                            showAppIconDialog = false
                                            showRestartDialog = true
                                        }
                                        .padding(12.dp)
                                ) {
                                    androidx.compose.foundation.Image(
                                        painter = androidx.compose.ui.res.painterResource(resId),
                                        contentDescription = alias,
                                        modifier = Modifier.size(64.dp)
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = alias,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (appIconAlias == alias) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                            if (row.size == 1) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    if (showUpdateDialog) {
        val release = updateRelease ?: return
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            icon = { Icon(Icons.Default.SystemUpdate, null) },
            title = { Text("Update Available") },
            text = {
                Column {
                    Text("Version ${release.tagName.trimStart('v')} is available.")
                    if (!release.body.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(release.body.take(300), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showUpdateDialog = false
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl))
                    context.startActivity(intent)
                }) { Text("Download") }
            },
            dismissButton = {
                TextButton(onClick = { showUpdateDialog = false }) { Text("Later") }
            }
        )
    }

    Scaffold(
        topBar = {
            IPDialTopBar(accounts = accounts, vm = vm, onOpenDrawer = onOpenDrawer)
        },
        bottomBar = {
            com.ipdial.ui.StartIoBanner(
                vm = vm,
                modifier = Modifier.fillMaxWidth().padding(8.dp)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            item {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    TelegramSupportCard()
                }
            }

            stickyHeader { SettingsSection("Updates") }
            item {
                SettingsRow(
                    icon = Icons.Default.SystemUpdate,
                    title = "Check for Updates",
                    subtitle = if (checkingUpdate) "Checking…" else "Current version: $currentVersion",
                    trailing = { if (checkingUpdate) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) },
                    onClick = {
                        if (!checkingUpdate) {
                            checkingUpdate = true
                            scope.launch {
                                val release = UpdateChecker.checkForUpdates(currentVersion)
                                checkingUpdate = false
                                if (release != null) {
                                    updateRelease = release
                                    showUpdateDialog = true
                                } else {
                                    android.widget.Toast.makeText(context, "You're on the latest version!", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                )
            }

            stickyHeader { SettingsSection("Audio") }
            item {
                val ringtoneLabel = when {
                    globalRingtone == null -> "Default"
                    globalRingtone == "silent" -> "Silent"
                    else -> {
                        try {
                            globalRingtone?.let { uri ->
                                RingtoneManager.getRingtone(context, Uri.parse(uri))?.getTitle(context)
                            } ?: "Default"
                        } catch (_: Exception) { "Default" }
                    }
                }

                SettingsRow(
                    icon = Icons.Default.NotificationsActive,
                    title = "Ringtone",
                    subtitle = ringtoneLabel,
                    onClick = {
                        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select Ringtone")
                            globalRingtone?.let { putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(it)) }
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                        }
                        ringtonePickerLauncher.launch(intent)
                    }
                )
            }

            item {
                val globalVibrate by vm.globalVibrate.collectAsState()
                SettingsRow(
                    icon = Icons.Default.Vibration,
                    title = "Vibrate on Ring",
                    subtitle = "Vibrate when receiving incoming calls",
                    trailing = { Switch(checked = globalVibrate, onCheckedChange = { vm.setGlobalVibrate(it) }) },
                    onClick = { vm.setGlobalVibrate(!globalVibrate) }
                )
            }

            stickyHeader { SettingsSection("Audio Codecs") }
            item {
                SettingsRow(
                    icon = Icons.Default.Audiotrack,
                    title = "Audio Codecs",
                    subtitle = activeAccount?.codec?.name ?: "Configure Codecs",
                    onClick = {
                        onNavigateToCodecs()
                    }
                )
            }

            stickyHeader { SettingsSection("General") }
            item {
                val fontSizeLabel = fontSizeOptions.find { it.second == fontSizeMultiplier }?.first ?: "Normal"
                SettingsRow(
                    icon = Icons.Default.TextFields,
                    title = "Font Size",
                    subtitle = fontSizeLabel,
                    onClick = { showFontSizeDialog = true }
                )
            }

            item {
                val isPro by vm.isPro.collectAsState()
                SettingsRow(
                    icon = Icons.Default.GridOn,
                    title = "Keypad Design",
                    subtitle = if (keypadDesign == KeypadDesign.Rounded) "Fully Rounded" else "Grid",
                    trailing = {
                        Switch(
                            checked = keypadDesign == KeypadDesign.Rounded,
                            onCheckedChange = {
                                if (!isPro) {
                                    vm.showAdGate {
                                        vm.setKeypadDesign(context, if (it) KeypadDesign.Rounded else KeypadDesign.Grid)
                                    }
                                } else {
                                    vm.setKeypadDesign(context, if (it) KeypadDesign.Rounded else KeypadDesign.Grid)
                                }
                            }
                        )
                    },
                    onClick = {
                        if (!isPro) {
                            vm.showAdGate {
                                vm.setKeypadDesign(context, if (keypadDesign == KeypadDesign.Rounded) KeypadDesign.Grid else KeypadDesign.Rounded)
                            }
                        } else {
                            vm.setKeypadDesign(context, if (keypadDesign == KeypadDesign.Rounded) KeypadDesign.Grid else KeypadDesign.Rounded)
                        }
                    }
                )
            }

            item {
                val isPro by vm.isPro.collectAsState()
                SettingsRow(
                    icon = Icons.Default.Brush,
                    title = "Choose App Icon",
                    subtitle = appIconAlias,
                    onClick = {
                        if (!isPro) {
                            vm.showAdGate {
                                showAppIconDialog = true
                            }
                        } else {
                            showAppIconDialog = true
                        }
                    }
                )
            }

            item {
                val callsCardsEnabled by vm.callingCardsEnabled.collectAsState()
                val isPro by vm.isPro.collectAsState()
                SettingsRow(
                    icon = Icons.Default.ContactPage,
                    title = "Full-Screen Photo",
                    subtitle = "Show contact photo on calls",
                    trailing = { Switch(checked = callsCardsEnabled, onCheckedChange = {
                        if (!isPro) vm.showAdGate { vm.setCallingCards(it) }
                        else vm.setCallingCards(it)
                    }) },
                    onClick = {
                        if (!isPro) vm.showAdGate { vm.setCallingCards(!callsCardsEnabled) }
                        else vm.setCallingCards(!callsCardsEnabled)
                    }
                )
            }

            item {
                val mode by vm.incomingCallMode.collectAsState()
                val isPro by vm.isPro.collectAsState()
                SettingsRow(
                    icon = Icons.Default.Call,
                    title = "Incoming Call Style",
                    subtitle = if (mode == IncomingCallMode.Slider) "Slider" else "Buttons",
                    onClick = {
                        if (!isPro) {
                            vm.showAdGate { onNavigateToIncomingCallStyle() }
                        } else {
                            onNavigateToIncomingCallStyle()
                        }
                    }
                )
            }

            item {
                val themeMode by vm.themeMode.collectAsState()
                val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
                SettingsRow(
                    icon = Icons.Default.DisplaySettings,
                    title = "App Theme",
                    subtitle = when(themeMode) {
                        ThemeMode.Dark -> "Dark"
                        ThemeMode.Light -> "Light"
                        ThemeMode.Obsidian -> "Obsidian"
                        ThemeMode.Quartz -> "Quartz"
                        ThemeMode.Dynamic -> "Dynamic (Wallpaper)"
                        ThemeMode.System -> "System (${if(systemDark) "Dark" else "Light"})"
                    },
                    onClick = { onNavigateToTheme() }
                )
            }

            item {
                val dndEnabled by vm.dndEnabled.collectAsState()
                SettingsRow(
                    icon = Icons.Default.DoNotDisturbOn,
                    title = "Do Not Disturb",
                    subtitle = "Silence ringtone & vibration (calls still received)",
                    trailing = { Switch(checked = dndEnabled, onCheckedChange = { vm.setDnd(it) }) },
                    onClick = { vm.setDnd(!dndEnabled) }
                )
            }

            stickyHeader { SettingsSection("System") }
            item {
                SettingsRow(
                    icon = Icons.AutoMirrored.Filled.List,
                    title = "Activity Log",
                    subtitle = "View full system activity logs",
                    onClick = { onNavigateToLogs() }
                )
            }

            item {
                SettingsRow(
                    icon = Icons.Default.DeleteSweep,
                    title = "Clear Call History",
                    subtitle = "Remove all call log entries",
                    onClick = {
                        scope.launch {
                            vm.clearCallHistory()
                            android.widget.Toast.makeText(context, "Call history cleared", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }

            stickyHeader { SettingsSection("Advanced") }
            item {
                SettingsRow(
                    icon = Icons.Default.Restore,
                    title = "Reset Settings",
                    subtitle = "Restore all preferences to defaults",
                    onClick = { showResetDialog = true }
                )
            }
        }
    }
}

@Composable
fun SettingsSection(title: String) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp, end = 16.dp)
        )
    }
}

@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val isGlass = LocalGlassMode.current != com.ipdial.ui.theme.GlassMode.None

    if (isGlass) {
        Card(
            onClick = onClick,
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .then(if (isGlass) Modifier.glass(RoundedCornerShape(12.dp)) else Modifier)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                trailing?.invoke()
            }
        }
    } else {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .clickableWithRipple { onClick() }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                trailing?.invoke()
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                modifier = Modifier.padding(start = 56.dp)
            )
        }
    }
}
