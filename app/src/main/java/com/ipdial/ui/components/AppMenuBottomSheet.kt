package com.ipdial.ui.components

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ipdial.NavDest
import com.ipdial.data.model.RegStatus
import com.ipdial.data.scheduler.ScheduledCallStatus
import com.ipdial.ui.SipViewModel
import com.ipdial.ui.theme.GlassMode
import com.ipdial.ui.theme.LocalGlassMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppMenuBottomSheet(
    onDismissRequest: () -> Unit,
    onNavigate: (String) -> Unit,
    vm: SipViewModel = viewModel(),
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
) {
    val context = LocalContext.current
    val accounts by vm.accounts.collectAsState()
    val activeAccount by vm.activeAccount.collectAsState()
    val scheduledCalls by vm.scheduledCalls.collectAsState()
    val glassMode = LocalGlassMode.current
    val isQuartz = glassMode == GlassMode.Quartz
    val isObsidian = glassMode == GlassMode.Obsidian

    val sheetBgColor = when {
        isQuartz -> Color(0xF7FFFFFF)
        isObsidian -> Color(0xF71C1C1E)
        else -> MaterialTheme.colorScheme.surface
    }

    var showExitDialog by remember { mutableStateOf(false) }

    val upcomingCount = remember(scheduledCalls) {
        scheduledCalls.count {
            it.status == ScheduledCallStatus.PENDING || it.status == ScheduledCallStatus.TRIGGERED
        }
    }

    val currentAccount = activeAccount ?: accounts.firstOrNull { it.isEnabled } ?: accounts.firstOrNull()
    val regDotColor = when (currentAccount?.regStatus) {
        RegStatus.REGISTERED  -> DotGreen
        RegStatus.REGISTERING -> DotAmber
        RegStatus.ERROR       -> DotRed
        else                  -> DotGrey
    }
    val regStatusText = when (currentAccount?.regStatus) {
        RegStatus.REGISTERED  -> "Online"
        RegStatus.REGISTERING -> "Registering..."
        RegStatus.ERROR       -> currentAccount.regStatusText.ifBlank { "Error / Offline" }
        else                  -> if (currentAccount != null) "Unregistered" else "No Account"
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = sheetBgColor,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        val view = LocalView.current
        if (!view.isInEditMode) {
            val isLight = sheetBgColor.luminance() > 0.5f
            val sheetWindow = remember {
                (view.parent as? DialogWindowProvider)?.window
                    ?: (view.context as? android.app.Activity)?.window
            }
            LaunchedEffect(sheetWindow, isLight) {
                val window = sheetWindow ?: return@LaunchedEffect
                @Suppress("DEPRECATION")
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                }
                val insetsController = WindowInsetsControllerCompat(window, window.decorView)
                insetsController.isAppearanceLightNavigationBars = isLight
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Menu",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 10.dp)
            )

            // 1. Active Account Banner Card
            AccountStatusCard(
                accountName = currentAccount?.displayName ?: "No Account",
                sipUri = if (currentAccount != null) "${currentAccount.username}@${currentAccount.domain}" else "Tap to set up SIP account",
                statusColor = regDotColor,
                statusText = regStatusText,
                onClick = {
                    onDismissRequest()
                    onNavigate(NavDest.Accounts.route)
                }
            )

            Spacer(Modifier.height(14.dp))

            // 2. Core Actions (6-Item Grid: 2 rows x 3 columns)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MenuGridItem(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("menu_call_scheduler"),
                    icon = Icons.Default.Schedule,
                    title = "Scheduler",
                    subtitle = if (upcomingCount > 0) "$upcomingCount upcoming" else "Plan calls",
                    tint = Color(0xFF26A69A),
                    onClick = {
                        onDismissRequest()
                        onNavigate(NavDest.CallScheduler.route)
                    }
                )
                MenuGridItem(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("menu_accounts"),
                    icon = Icons.Default.AccountBalance,
                    title = "Accounts",
                    subtitle = "${accounts.size} configured",
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = {
                        onDismissRequest()
                        onNavigate(NavDest.Accounts.route)
                    }
                )
                MenuGridItem(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("menu_recordings"),
                    icon = Icons.Default.Mic,
                    title = "Recordings",
                    subtitle = "Audio files",
                    tint = Color(0xFFE57373),
                    onClick = {
                        onDismissRequest()
                        onNavigate(NavDest.Recordings.route)
                    }
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MenuGridItem(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("menu_codecs"),
                    icon = Icons.Default.Audiotrack,
                    title = "Codecs",
                    subtitle = "Audio setup",
                    tint = Color(0xFFFFB74D),
                    onClick = {
                        onDismissRequest()
                        onNavigate(NavDest.AudioCodecs.route)
                    }
                )
                MenuGridItem(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("menu_backup_restore"),
                    icon = Icons.Default.Folder,
                    title = "Backup",
                    subtitle = "Local folder",
                    tint = Color(0xFF64B5F6),
                    onClick = {
                        onDismissRequest()
                        onNavigate(NavDest.Backup.route)
                    }
                )
                MenuGridItem(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("menu_settings"),
                    icon = Icons.Default.Settings,
                    title = "Settings",
                    subtitle = "Preferences",
                    tint = Color(0xFFBA68C8),
                    onClick = {
                        onDismissRequest()
                        onNavigate(NavDest.Settings.route)
                    }
                )
            }

            Spacer(Modifier.height(14.dp))

            // 3. Exit App Button
            Surface(
                onClick = { showExitDialog = true },
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("menu_exit_app")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = "Exit",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(
                        text = "Exit & Stop Background Service",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("Exit DialDuo?") },
            text = { Text("This will close the app and stop all background processes. You will not receive incoming calls.") },
            confirmButton = {
                Button(
                    onClick = {
                        showExitDialog = false
                        onDismissRequest()
                        context.stopService(Intent(context, com.ipdial.service.SipService::class.java))
                        com.ipdial.service.SipConnectionService.destroyAll()
                        com.ipdial.service.SipEngine._callSession.value = null
                        (context as? android.app.Activity)?.finishAffinity()
                        android.os.Process.killProcess(android.os.Process.myPid())
                        System.exit(0)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Exit")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun AccountStatusCard(
    accountName: String,
    sipUri: String,
    statusColor: Color,
    statusText: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AccountBalance,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = accountName,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                }
                Text(
                    text = "$sipUri • $statusText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = "Manage",
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun MenuGridItem(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(tint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
