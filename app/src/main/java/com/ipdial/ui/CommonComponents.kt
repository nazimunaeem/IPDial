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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import android.content.Intent
import android.net.Uri
import com.ipdial.data.model.Contact
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

/**
 * Unified contact avatar composable.
 * Shows contact photo if available, otherwise a colored circle with the first letter.
 */
@Composable
fun ContactAvatar(
    name: String,
    photoUri: android.net.Uri? = null,
    size: androidx.compose.ui.unit.Dp = 44.dp,
    backgroundColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(backgroundColor)
            .then(if (onClick != null) Modifier.clickableWithRipple { onClick() } else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (photoUri != null) {
            val request = remember(photoUri) {
                coil.request.ImageRequest.Builder(context)
                    .data(photoUri)
                    .size(size.value.toInt() * 2, size.value.toInt() * 2)
                    .crossfade(true)
                    .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                    .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                    .build()
            }
            coil.compose.AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = name.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = contentColor
            )
        }
    }
}

@Composable
fun ContactItem(
    contact: Contact,
    onNumberClick: (String) -> Unit,
    onContactClick: () -> Unit,
    modifier: Modifier = Modifier,
    isGlass: Boolean = false
) {
    val isFav = contact.isFavorite
    ListItem(
        headlineContent = {
            Text(
                text = contact.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isFav) FontWeight.Bold else FontWeight.Normal
            )
        },
        supportingContent = {
            Column {
                contact.numbers.take(2).forEach { number ->
                    Text(
                        text = number,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clickable { onNumberClick(number) }
                            .padding(vertical = 1.dp)
                    )
                }
            }
        },
        leadingContent = {
            ContactAvatar(
                name = contact.name,
                photoUri = contact.photoUri,
                size = 44.dp,
                onClick = onContactClick
            )
        },
        trailingContent = if (isFav) {
            {
                Icon(
                    Icons.Default.Star,
                    contentDescription = "Favorite",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        } else null,
        colors = if (isGlass)
            ListItemDefaults.colors(containerColor = Color.Transparent)
        else ListItemDefaults.colors(),
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (isGlass) Modifier
                    .padding(horizontal = 12.dp, vertical = 2.dp)
                    .glass(RoundedCornerShape(12.dp))
                else Modifier.padding(horizontal = 4.dp)
            )
    )
}

@Composable
fun StartIoBanner(modifier: Modifier = Modifier, vm: SipViewModel? = null) {
    val adsEnabled = vm?.adsEnabled?.collectAsState()?.value ?: true
    if (!adsEnabled) return

    val isPro = vm?.isPro?.collectAsState()?.value ?: false

    // Check if custom ad is enabled via Firestore (admin-managed)
    val customEnabled by com.ipdial.util.FirestoreAdConfig.customAdEnabled.collectAsState()
    val imageUrl by com.ipdial.util.FirestoreAdConfig.customAdImageUrl.collectAsState()
    val linkUrl by com.ipdial.util.FirestoreAdConfig.customAdLink.collectAsState()
    val showTo by com.ipdial.util.FirestoreAdConfig.customAdShowTo.collectAsState()
    val bgColorHex by com.ipdial.util.FirestoreAdConfig.customAdBg.collectAsState()

    val bgColor = try {
        Color(android.graphics.Color.parseColor(bgColorHex))
    } catch (_: Exception) {
        MaterialTheme.colorScheme.surface
    }

    if (customEnabled && imageUrl.isNotBlank()) {
        val showForThisUser = when (showTo) {
            "pro" -> isPro
            "non_pro" -> !isPro
            else -> true  // "all"
        }
        if (!showForThisUser) return
        CustomAdBanner(imageUrl = imageUrl, linkUrl = linkUrl, bgColor = bgColor, modifier = modifier)
    } else {
        if (isPro) return
        AndroidView(
            modifier = modifier.fillMaxWidth(),
            factory = { ctx ->
                Banner(ctx)
        }
    )
}

@Composable
fun EmptyState(message: String, icon: ImageVector = Icons.Default.Info) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            textAlign = TextAlign.Center
        )
    }
}
}

@Composable
fun CustomAdBanner(imageUrl: String, linkUrl: String, bgColor: Color = MaterialTheme.colorScheme.surface, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var dismissed by remember { mutableStateOf(false) }
    if (dismissed) return

    Card(
        onClick = {
            if (linkUrl.isNotBlank()) {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(linkUrl)))
                } catch (_: Exception) {}
            }
        },
        modifier = modifier
            .size(width = 720.dp, height = 90.dp),
        shape = RectangleShape,
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Box(Modifier.fillMaxSize()) {
            if (imageUrl.isNotBlank()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = "Advertisement",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            IconButton(
                onClick = { dismissed = true },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(24.dp)
                    .background(Color.Black.copy(alpha = 0.5f), shape = CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close ad",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun CustomAccountPageAd(vm: SipViewModel, modifier: Modifier = Modifier) {
    val adsEnabled = vm.adsEnabled.collectAsState().value
    if (!adsEnabled) return

    val rc = com.ipdial.util.FirestoreAdConfig
    val enabled by rc.accountPageAdEnabled.collectAsState()
    if (!enabled) return

    val imageUrl by rc.accountPageAdImage.collectAsState()
    val bgColorHex by rc.accountPageAdBg.collectAsState()
    val ctaUrl by rc.accountPageAdCta.collectAsState()
    val showTo by rc.accountPageAdShowTo.collectAsState()

    val isPro by vm.isPro.collectAsState()
    val showForThisUser = when (showTo) {
        "pro" -> isPro
        "non_pro" -> !isPro
        else -> true  // "all"
    }
    if (!showForThisUser) return

    val context = LocalContext.current
    val bgColor = try {
        Color(android.graphics.Color.parseColor(bgColorHex))
    } catch (_: Exception) {
        MaterialTheme.colorScheme.primary
    }

    Card(
        onClick = {
            if (ctaUrl.isNotBlank()) {
                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ctaUrl))) }
                catch (_: Exception) {}
            }
        },
        modifier = modifier.size(width = 720.dp, height = 90.dp),
        shape = RectangleShape,
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        if (imageUrl.isNotBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = "Advertisement",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
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
    val appName = if (isPro) "IPDial Pro" else "IPDial"
    val appNameColor = MaterialTheme.colorScheme.onSurface
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
    balances: Map<String, String> = emptyMap(),
    onAccountSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = AlertDialogDefaults.shape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = AlertDialogDefaults.TonalElevation
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                Text(
                    text = "Select Account",
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(Modifier.height(16.dp))
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    enabledAccounts.forEach { account ->
                        val balance = balances[account.id]
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
                            if (!balance.isNullOrBlank()) {
                                Text(
                                    text = balance,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}
