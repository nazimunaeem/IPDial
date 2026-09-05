@file:Suppress("OPT_IN_USAGE", "EXPERIMENTAL_API_USAGE", "EXPERIMENTAL_IS_NOT_ENABLED")
package com.ipdial.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ipdial.ui.components.IPDialTopBar
import com.ipdial.ui.SipViewModel
import com.ipdial.ui.theme.glass
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GetProScreen(
    vm: SipViewModel,
    onBack: () -> Unit = {},
    onOpenDrawer: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val accounts by vm.accounts.collectAsState()
    val proPoints by vm.proPoints.collectAsState()
    val proExpiration by vm.proExpiration.collectAsState()
    val isPro by vm.isPro.collectAsState()
    val isLoadingAd by vm.isLoadingAd.collectAsState()
    val isSignedIn by vm.isSignedIn.collectAsState()
    val currentUser by vm.currentUser.collectAsState()
    var isSigningIn by remember { mutableStateOf(false) }
    var showProfileMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            IPDialTopBar(accounts = accounts, vm = vm, title = "IPDial Pro", onBack = onBack)
        },
        bottomBar = {
            com.ipdial.ui.components.StartIoBanner(
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
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            item {
                ProStatusCard(
                    isPro = isPro,
                    expiration = proExpiration,
                    isSignedIn = isSignedIn,
                    profilePhotoUrl = currentUser?.photoUrl?.toString(),
                    isSigningIn = isSigningIn,
                    onSignIn = {
                        isSigningIn = true
                        vm.signIn(context) { success, _ ->
                            isSigningIn = false
                        }
                    },
                    onProfileClick = { showProfileMenu = true }
                )
            }

            item {
                val cooldown by vm.adCooldownSeconds.collectAsState()
                PointsBalanceCard(proPoints, isLoadingAd, cooldown) {
                    if (!isSignedIn) {
                        android.widget.Toast.makeText(context, "Please sign in to earn points", android.widget.Toast.LENGTH_SHORT).show()
                        isSigningIn = true
                        vm.signIn(context) { _, _ -> isSigningIn = false }
                        return@PointsBalanceCard
                    }
                    vm.watchRewardedAd(context) {
                        // Reward handled in VM
                    }
                }
            }

            item {
                Text(
                    "Redeem Points for Pro",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            item {
                RedemptionOptions(proPoints) { days ->
                    if (!isSignedIn) {
                        android.widget.Toast.makeText(context, "Please sign in to buy Pro", android.widget.Toast.LENGTH_SHORT).show()
                        isSigningIn = true
                        vm.signIn(context) { _, _ -> isSigningIn = false }
                        return@RedemptionOptions
                    }
                    vm.redeemPoints(days)
                }
            }
            item {
                ReferralCard(vm = vm)
            }

            item {
                ProFeaturesList()
            }
        }
    }

    // Profile bottom sheet - shown when tapping the profile picture
    if (showProfileMenu && currentUser != null) {
        ProfileBottomSheet(
            name = currentUser?.displayName ?: "User",
            email = currentUser?.email ?: "",
            photoUrl = currentUser?.photoUrl?.toString(),
            onDismiss = { showProfileMenu = false },
            onSignOut = {
                showProfileMenu = false
                vm.signOut()
            },
            onDeleteAccount = {
                showProfileMenu = false
                vm.deleteAccount { success, msg ->
                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileBottomSheet(
    name: String,
    email: String,
    photoUrl: String?,
    onDismiss: () -> Unit,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    val glassMode = com.ipdial.ui.theme.LocalGlassMode.current
    val isGlass = glassMode != com.ipdial.ui.theme.GlassMode.None
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(12.dp))

            // Profile photo
            if (photoUrl != null) {
                AsyncImage(
                    model = photoUrl,
                    contentDescription = "Profile",
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                )
            } else {
                Icon(
                    Icons.Default.AccountCircle,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Text(
                text = email,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = onSignOut,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Text("Sign Out", fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { showDeleteDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Text("Delete Account", fontWeight = FontWeight.SemiBold)
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Account?") },
            text = {
                Text("This will permanently delete your account and all associated data including your Pro status and points. This action cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        onDeleteAccount()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ReferralCard(vm: com.ipdial.ui.SipViewModel) {
    val context = LocalContext.current
    var code by remember { mutableStateOf("") }
    var isSigningIn by remember { mutableStateOf(false) }
    val isSignedIn by vm.isSignedIn.collectAsState()
    val referralCode = remember(isSignedIn) { vm.getReferralCode() }
    val glassMode = com.ipdial.ui.theme.LocalGlassMode.current
    val isGlass = glassMode != com.ipdial.ui.theme.GlassMode.None
    val isQuartz = glassMode == com.ipdial.ui.theme.GlassMode.Quartz
    val buttonContentColor = if (isQuartz) MaterialTheme.colorScheme.primary else Color.White

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isGlass) Modifier.glass() else Modifier),
        colors = CardDefaults.cardColors(
            containerColor = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Referral", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Get 50 points per install. Share your code or enter one to claim.")

            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("Enter Referral Code") },
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        if (code.isNotBlank()) {
                            if (!isSignedIn) {
                                android.widget.Toast.makeText(context, "Please sign in to claim a referral", android.widget.Toast.LENGTH_SHORT).show()
                                isSigningIn = true
                                vm.signIn(context) { _, _ -> isSigningIn = false }
                                return@Button
                            }
                            vm.claimReferral(code) { success, msg ->
                                try {
                                    android.widget.Toast.makeText(context.applicationContext, msg, android.widget.Toast.LENGTH_SHORT).show()
                                } catch (_: Exception) {}
                            }
                        }
                    }, 
                    modifier = Modifier.weight(1f).then(if (isGlass) Modifier.glass(ButtonDefaults.shape) else Modifier),
                    colors = if (isGlass) ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = buttonContentColor) else ButtonDefaults.buttonColors()
                ) {
                    Text(
                        if (isSigningIn) "Signing in..." else "Apply Code",
                        color = if (isGlass) buttonContentColor else Color.Unspecified,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Button(
                    onClick = {
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_SUBJECT, "Join IPDial & get free points")
                            putExtra(android.content.Intent.EXTRA_TEXT, "Use my referral code: $referralCode to get 50 points in IPDial. Download here: https://github.com/nazimunaeem/IPDial/releases")
                        }
                        context.startActivity(android.content.Intent.createChooser(intent, "Share referral code"))
                    },
                    modifier = Modifier.then(if (isGlass) Modifier.glass(ButtonDefaults.shape) else Modifier),
                    colors = if (isGlass) ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = buttonContentColor) else ButtonDefaults.buttonColors()
                ) {
                    Text("Share Code", color = if (isGlass) buttonContentColor else Color.Unspecified, fontWeight = FontWeight.SemiBold)
                }
            }
            
            if (referralCode.isNotEmpty()) {
                Surface(
                    onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Referral ID", referralCode))
                        try {
                            android.widget.Toast.makeText(context.applicationContext, "ID copied", android.widget.Toast.LENGTH_SHORT).show()
                        } catch (_: Exception) {}
                    },
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "ID: $referralCode",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ProStatusCard(
    isPro: Boolean,
    expiration: Long,
    isSignedIn: Boolean = false,
    profilePhotoUrl: String? = null,
    isSigningIn: Boolean = false,
    onSignIn: () -> Unit = {},
    onProfileClick: () -> Unit = {}
) {
    val remainingDays = if (isPro) {
        val diff = expiration - System.currentTimeMillis()
        maxOf(0, TimeUnit.MILLISECONDS.toDays(diff) + 1)
    } else 0

    val proAccent = Color(0xFFBC4749)
    val glassMode = com.ipdial.ui.theme.LocalGlassMode.current
    val isGlass = glassMode != com.ipdial.ui.theme.GlassMode.None
    val isQuartz = glassMode == com.ipdial.ui.theme.GlassMode.Quartz
    val buttonContentColor = if (isQuartz) MaterialTheme.colorScheme.primary else Color.White

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isGlass) Modifier.glass() else Modifier),
        colors = CardDefaults.cardColors(
            containerColor = if (isPro) proAccent.copy(alpha = 0.1f) else (if (isGlass) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant)
        ),
        border = if (isPro) androidx.compose.foundation.BorderStroke(1.dp, proAccent.copy(alpha = 0.5f)) else null
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isPro) Icons.Default.CheckCircle else Icons.Default.CardGiftcard,
                    contentDescription = null,
                    tint = if (isPro) proAccent else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isPro) "IPDial Pro Active" else "Free Version",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (isPro) proAccent else MaterialTheme.colorScheme.onSurface
                    )
                    if (isPro) {
                        Text(
                            text = "$remainingDays Days Remaining",
                            style = MaterialTheme.typography.bodyMedium,
                            color = proAccent.copy(alpha = 0.8f)
                        )
                    }
                }

                // Auth tile - sign-in tile or profile picture (shown when not signed in / signed in)
                if (!isSignedIn) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        onClick = onSignIn,
                        enabled = !isSigningIn,
                        shape = RoundedCornerShape(8.dp),
                        color = if (isGlass) Color.Transparent else Color.White,
                        modifier = Modifier
                            .size(64.dp)
                            .then(if (isGlass) Modifier.glass(RoundedCornerShape(8.dp)) else Modifier)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            if (isSigningIn) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = "...",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Icon(
                                    painter = androidx.compose.ui.res.painterResource(com.ipdial.R.drawable.ic_google_g),
                                    contentDescription = "Sign in with Google",
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = "Sign\nIn",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        onClick = onProfileClick,
                        shape = CircleShape,
                        color = Color.Transparent,
                        border = androidx.compose.foundation.BorderStroke(1.dp, proAccent.copy(alpha = 0.4f)),
                        modifier = Modifier.size(64.dp)
                    ) {
                        if (profilePhotoUrl != null) {
                            AsyncImage(
                                model = profilePhotoUrl,
                                contentDescription = "Profile",
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                Icons.Default.AccountCircle,
                                contentDescription = "Profile",
                                modifier = Modifier.fillMaxSize().padding(10.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PointsBalanceCard(points: Int, isLoading: Boolean, cooldown: Int, onWatchAd: () -> Unit) {
    val glassMode = com.ipdial.ui.theme.LocalGlassMode.current
    val isGlass = glassMode != com.ipdial.ui.theme.GlassMode.None
    val isQuartz = glassMode == com.ipdial.ui.theme.GlassMode.Quartz
    val buttonContentColor = if (isQuartz) MaterialTheme.colorScheme.primary else Color.White

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isGlass) Modifier.glass() else Modifier),
        colors = CardDefaults.cardColors(
            containerColor = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Available Points", 
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = points.toString(),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isGlass) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                )
            }
            Button(
                onClick = onWatchAd,
                enabled = !isLoading && cooldown == 0,
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.then(if (isGlass) Modifier.glass(RoundedCornerShape(8.dp)) else Modifier),
                colors = if (isGlass) ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = buttonContentColor) else ButtonDefaults.buttonColors()
            ) {
                if (isLoading || cooldown > 0) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = buttonContentColor
                    )
                } else {
                    Icon(
                        Icons.Default.VideoLibrary,
                        null,
                        modifier = Modifier.size(16.dp),
                        tint = buttonContentColor
                    )
                }
                Spacer(Modifier.width(6.dp))
                val buttonText = when {
                    isLoading || cooldown > 0 -> "AD Loading..."
                    else -> "Watch Ad +1 Point"
                }
                Text(
                    text = buttonText,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isGlass) buttonContentColor else Color.Unspecified,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun RedemptionOptions(currentPoints: Int, onRedeem: (Int) -> Unit) {
    val glassMode = com.ipdial.ui.theme.LocalGlassMode.current
    val isGlass = glassMode != com.ipdial.ui.theme.GlassMode.None
    val isQuartz = glassMode == com.ipdial.ui.theme.GlassMode.Quartz

    val tiers = listOf(
        Triple(1, 1, "1 Day"),
        Triple(7, 5, "7 Days"),
        Triple(30, 20, "1 Month"),
        Triple(90, 50, "3 Months")
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiers.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEach { (days, cost, label) ->
                    val canAfford = currentPoints >= cost
                    val cardBgColor = when {
                        isGlass && isQuartz -> if (canAfford) Color.White.copy(alpha = 0.90f) else Color.White.copy(alpha = 0.40f)
                        isGlass -> if (canAfford) Color(0xFF2C2C2E).copy(alpha = 0.85f) else Color(0xFF1C1C1E).copy(alpha = 0.40f)
                        canAfford -> MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
                        else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                    }
                    val labelColor = when {
                        canAfford -> MaterialTheme.colorScheme.onSurface
                        isGlass && isQuartz -> Color.Black.copy(alpha = 0.35f)
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    }
                    val pointsColor = when {
                        canAfford -> MaterialTheme.colorScheme.primary
                        isGlass && isQuartz -> Color.Black.copy(alpha = 0.35f)
                        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    }

                    Surface(
                        onClick = { if (canAfford) onRedeem(days) },
                        enabled = canAfford,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(80.dp),
                        color = cardBgColor,
                        border = androidx.compose.foundation.BorderStroke(
                            width = 1.dp, 
                            color = if (canAfford) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) 
                                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = label, 
                                style = MaterialTheme.typography.labelLarge, 
                                fontWeight = FontWeight.Bold,
                                color = labelColor
                            )
                            Text(
                                text = "$cost Points", 
                                style = MaterialTheme.typography.labelSmall, 
                                color = pointsColor,
                                fontWeight = FontWeight.Medium
                            )
                            
                            if (canAfford) {
                                Spacer(Modifier.height(4.dp))
                                Icon(
                                    Icons.Default.CardGiftcard, 
                                    null, 
                                    tint = MaterialTheme.colorScheme.primary, 
                                    modifier = Modifier.size(16.dp)
                                )
                            } else {
                                Spacer(Modifier.height(20.dp))
                            }
                        }
                    }
                }
                if (rowItems.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun ProFeaturesList() {
    val features = listOf(
        "No Ads" to "Clean, ad-free calling.",
        "Multiple Accounts" to "Add unlimited SIP accounts.",
        "Unlimited Recordings" to "Record and share freely.",
        "Full Customization" to "Custom icons and keypad."
    )
    
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            text = "Pro Benefits", 
            style = MaterialTheme.typography.titleMedium, 
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))
        features.forEach { (title, desc) ->
            Row(
                verticalAlignment = Alignment.Top, 
                modifier = Modifier.padding(vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle, 
                    contentDescription = null, 
                    tint = Color(0xFF4CAF50), 
                    modifier = Modifier.size(20.dp).padding(top = 2.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
