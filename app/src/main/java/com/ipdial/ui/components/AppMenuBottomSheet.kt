package com.ipdial.ui.components

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ipdial.NavDest
import com.ipdial.data.model.RegStatus
import com.ipdial.ui.SipViewModel
import com.ipdial.ui.theme.GlassMode
import com.ipdial.ui.theme.LocalGlassMode
import coil.compose.AsyncImage
import java.util.concurrent.TimeUnit

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
    val isPro by vm.isPro.collectAsState()
    val proExpiration by vm.proExpiration.collectAsState()
    val glassMode = LocalGlassMode.current
    val isGlass = glassMode != GlassMode.None
    val isQuartz = glassMode == GlassMode.Quartz
    val isObsidian = glassMode == GlassMode.Obsidian

    val sheetBgColor = when {
        isQuartz -> Color(0xF7FFFFFF)
        isObsidian -> Color(0xF71C1C1E)
        else -> MaterialTheme.colorScheme.surface
    }

    var showExitDialog by remember { mutableStateOf(false) }

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
        RegStatus.ERROR       -> "Error / Offline"
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
            SideEffect {
                val window = (view.parent as? DialogWindowProvider)?.window
                    ?: (view.context as? android.app.Activity)?.window
                if (window != null) {
                    @Suppress("DEPRECATION")
                    window.navigationBarColor = android.graphics.Color.TRANSPARENT
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        window.isNavigationBarContrastEnforced = false
                    }
                    val insetsController = WindowInsetsControllerCompat(window, window.decorView)
                    insetsController.isAppearanceLightNavigationBars = isLight
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Title
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Menu & Services",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

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

            Spacer(Modifier.height(8.dp))

            // 2. Pro Card
            ProBannerCard(
                isPro = isPro,
                proExpiration = proExpiration,
                onClick = {
                    onDismissRequest()
                    onNavigate(NavDest.GetPro.route)
                }
            )

            // 2.5. User Profile (if signed in)
            val isSignedIn by vm.isSignedIn.collectAsState()
            val currentUser by vm.currentUser.collectAsState()
            if (isSignedIn && currentUser != null) {
                Spacer(Modifier.height(8.dp))
                UserProfileMini(
                    name = currentUser?.displayName ?: "User",
                    email = currentUser?.email ?: "",
                    photoUrl = currentUser?.photoUrl?.toString()
                )
            }

            Spacer(Modifier.height(10.dp))

            // 3. Quick Actions Section (6 Tiles in 3-Column Grid)
            Text(
                text = "Quick Actions & VoIP",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp)
            )

            // Row 1 of 6 Tiles: Accounts | Recordings | Call Style
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MenuGridItem(
                    modifier = Modifier.weight(1f),
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
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Mic,
                    title = "Recordings",
                    subtitle = "Audio files",
                    tint = Color(0xFFE57373),
                    onClick = {
                        onDismissRequest()
                        onNavigate(NavDest.Recordings.route)
                    }
                )
                MenuGridItem(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Call,
                    title = "Call Style",
                    subtitle = "Banner/Full",
                    tint = Color(0xFF4CAF50),
                    onClick = {
                        onDismissRequest()
                        onNavigate(NavDest.IncomingCallStyle.route)
                    }
                )
            }

            Spacer(Modifier.height(8.dp))

            // Row 2 of 6 Tiles: Dialpad Style | Audio Codecs | Appearance
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MenuGridItem(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Dialpad,
                    title = "Dialpad Style",
                    subtitle = "Grid/Rounded/Ring",
                    tint = Color(0xFF64B5F6),
                    onClick = {
                        onDismissRequest()
                        onNavigate(NavDest.DialpadStyle.route)
                    }
                )
                MenuGridItem(
                    modifier = Modifier.weight(1f),
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
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Palette,
                    title = "Themes",
                    subtitle = "Appearance",
                    tint = Color(0xFFBA68C8),
                    onClick = {
                        onDismissRequest()
                        onNavigate(NavDest.ThemeSettings.route)
                    }
                )
            }

            Spacer(Modifier.height(14.dp))

            // 4. Preferences & App Info
            Text(
                text = "Preferences & App Info",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    MenuRowItem(
                        icon = Icons.Default.Settings,
                        title = "General Settings",
                        subtitle = "Ringtone, Noise Cancellation, App Icon",
                        onClick = {
                            onDismissRequest()
                            onNavigate(NavDest.Settings.route)
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f), modifier = Modifier.padding(horizontal = 16.dp))
                    MenuRowItem(
                        icon = Icons.Default.PrivacyTip,
                        title = "Privacy Policy",
                        subtitle = "Data usage & permissions",
                        onClick = {
                            onDismissRequest()
                            onNavigate(NavDest.Privacy.route)
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f), modifier = Modifier.padding(horizontal = 16.dp))
                    MenuRowItem(
                        icon = Icons.Default.Info,
                        title = "About IPDial",
                        subtitle = "Version, developer & updates",
                        onClick = {
                            onDismissRequest()
                            onNavigate(NavDest.About.route)
                        }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // 5. Exit App Button
            Surface(
                onClick = { showExitDialog = true },
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Exit & Stop Background Service",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("Exit IPDial?") },
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
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AccountBalance,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = accountName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                }
                Spacer(Modifier.height(2.dp))
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
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun ProBannerCard(
    isPro: Boolean,
    proExpiration: Long,
    onClick: () -> Unit
) {
    val proDaysLeft = remember(proExpiration) {
        val diff = proExpiration - System.currentTimeMillis()
        maxOf(0, TimeUnit.MILLISECONDS.toDays(diff) + 1)
    }

    val backgroundBrush = if (isPro) {
        Brush.horizontalGradient(
            colors = listOf(Color(0xFF8E2DE2), Color(0xFF4A00E0))
        )
    } else {
        Brush.horizontalGradient(
            colors = listOf(Color(0xFFBC4749), Color(0xFF6B1D2F))
        )
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(backgroundBrush)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPro) Icons.Default.Star else Icons.Default.CardGiftcard,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(Modifier.width(12.dp))

                    Column {
                        Text(
                            text = if (isPro) "IPDial Pro Active" else "Upgrade to IPDial Pro",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                        Text(
                            text = if (isPro) "$proDaysLeft days remaining" else "Ad-free experience & premium features",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White,
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text(
                        text = if (isPro) "Manage" else "Get Pro",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = if (isPro) Color(0xFF4A00E0) else Color(0xFFBC4749)
                        ),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
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

@Composable
private fun MenuRowItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun UserProfileMini(
    name: String,
    email: String,
    photoUrl: String?
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (photoUrl != null) {
                AsyncImage(
                    model = photoUrl,
                    contentDescription = "Profile",
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AccountCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = email,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
