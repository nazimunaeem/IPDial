package com.ipdial.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ipdial.data.model.RegStatus
import com.ipdial.data.model.SipAccount
import com.ipdial.ui.screens.clickableWithRipple
import com.ipdial.ui.theme.glass
import com.startapp.sdk.ads.banner.Banner
import kotlinx.coroutines.delay

val DotGreen  = Color(0xFF4CAF50)
val DotRed    = Color(0xFFF44336)
val DotAmber  = Color(0xFFFF9800)
val DotGrey   = Color(0xFF9E9E9E)

val ColorPro = Color(0xFFBC4749) // User requested deep red for Pro accent

@Composable
fun StartIoBanner(modifier: Modifier = Modifier, vm: SipViewModel? = null) {
    val isPro = vm?.isPro?.collectAsState()?.value ?: false
    if (isPro) return

    val adsEnabled = vm?.adsEnabled?.collectAsState()?.value ?: true
    
    if (adsEnabled) {
        AndroidView(
            modifier = modifier.fillMaxWidth(),
            factory = { ctx ->
                Banner(ctx)
            }
        )
    }
}

@Composable
fun RegStatusIndicator(
    accounts: List<SipAccount>, 
    vm: SipViewModel? = null,
    showAccountInfo: SipAccount? = null
) {
    val vmActiveAccount by (vm?.activeAccount?.collectAsState() ?: remember { mutableStateOf(null) })
    val activeAccount = showAccountInfo ?: vmActiveAccount ?: accounts.firstOrNull { it.isEnabled } ?: accounts.firstOrNull()

    val regDotColor = when {
        activeAccount != null -> when (activeAccount.regStatus) {
            RegStatus.REGISTERED  -> DotGreen
            RegStatus.REGISTERING -> DotAmber
            RegStatus.ERROR       -> DotRed
            else                  -> DotGrey
        }
        accounts.any { it.regStatus == RegStatus.REGISTERED }    -> DotGreen
        accounts.any { it.regStatus == RegStatus.REGISTERING }   -> DotAmber
        accounts.any { it.regStatus == RegStatus.ERROR }         -> DotRed
        else                                                      -> DotGrey
    }
    val context = LocalContext.current

    Column(
        modifier = Modifier.padding(start = 12.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(regDotColor.copy(alpha = 0.2f))
                )
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(regDotColor)
                )
            }
            if (activeAccount != null) {
                Spacer(Modifier.width(6.dp)) // Increased from 4
                Text(
                    text = activeAccount.displayName,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp), // Increased from 9.sp
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 140.dp) // Increased from 120
                )
            }
        }

        if (activeAccount != null && (activeAccount.domain == "sip.amarip.net" || activeAccount.domain == "103.170.231.10" || activeAccount.domain == "103.129.202.202") && vm != null) {
            val balanceMap by vm.balances.collectAsState()
            val balance = balanceMap[activeAccount.id]
            val isPro by vm.isPro.collectAsState()
            
            var isRevealing by remember { mutableStateOf(false) }
            val offsetX = remember { Animatable(-10f) }

            // Always show balance for Pro users or if specifically revealing
            val showBalance = isPro || isRevealing

            LaunchedEffect(isRevealing, isPro) {
                if (isPro) {
                    vm?.fetchBalance(activeAccount, context)
                    if (!isRevealing) offsetX.snapTo(20f)
                }
                if (isRevealing) {
                    offsetX.snapTo(0f)
                    offsetX.animateTo(20f, animationSpec = tween(600))
                    delay(10000)
                    isRevealing = false
                }
            }

            Row(
                modifier = Modifier
                    .padding(top = 1.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                    .clickable {
                        if (isPro) {
                            // Pro users just refresh on click
                            vm?.fetchBalance(activeAccount, context)
                        } else {
                            if (isRevealing) {
                                isRevealing = false
                                vm?.dismissAd()
                            } else {
                                vm?.fetchBalance(activeAccount, context)
                                isRevealing = true
                            }
                        }
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .widthIn(min = 60.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (showBalance) {
                    val cleanBalance = (balance ?: "...").replace("BDT", "").trim()
                    Text(
                        text = cleanBalance,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = "৳",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.offset(x = (offsetX.value - 20).dp)
                    )
                } else {
                    Text(
                        text = "Balance",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IPDialTopBar(
    accounts: List<SipAccount>,
    vm: SipViewModel? = null,
    onOpenDrawer: () -> Unit,
    onAddAccount: (() -> Unit)? = null
) {
    val isGlass = com.ipdial.ui.theme.LocalGlassMode.current != com.ipdial.ui.theme.GlassMode.None
    val containerColor = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.surface
    val isPro = vm?.isPro?.collectAsState()?.value ?: false
    val appName = if (isPro) "IPDialPro" else "IPDial"
    val appNameColor = if (isPro) ColorPro else MaterialTheme.colorScheme.onSurface
    val themeColor = MaterialTheme.colorScheme.primary

    Surface(
        color = containerColor,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isGlass) Modifier.glass(RoundedCornerShape(0.dp)) else Modifier),
        shadowElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp)
        ) {
            // Left: Status Dot & Name
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight(),
                contentAlignment = Alignment.CenterStart
            ) {
                RegStatusIndicator(accounts = accounts, vm = vm)
            }

            // Center: App Name with soft background
            Surface(
                color = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .then(if (isGlass) Modifier.glass(RoundedCornerShape(12.dp)) else Modifier)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = appName,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = appNameColor
                        ),
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Right side items: Add Account (if no accounts) + Hamburger
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp)
                    .fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (accounts.isEmpty() && onAddAccount != null) {
                    Surface(
                        onClick = onAddAccount,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add, 
                                contentDescription = null, 
                                modifier = Modifier.size(14.dp), 
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Setup Account",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }

                IconButton(
                    onClick = onOpenDrawer,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "Menu",
                        tint = themeColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun NumberPickerDialog(
    numbers: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Select Number") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                numbers.forEach { number ->
                    TextButton(
                        onClick = {
                            onPick(number)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = number,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun AccountSelectionDialog(
    enabledAccounts: List<SipAccount>,
    onAccountSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Account") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                enabledAccounts.forEach { account ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickableWithRipple {
                                onAccountSelected(account.id)
                            }
                            .padding(vertical = 12.dp, horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = account.label.ifBlank { account.domain },
                                style = MaterialTheme.typography.bodyLarge
                            )
                            if (account.username.isNotBlank()) {
                                Text(
                                    text = account.username,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
