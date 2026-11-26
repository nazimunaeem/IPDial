package com.ipdial.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ipdial.ui.SipViewModel
import com.ipdial.ui.components.IPDialTopBar
import com.ipdial.ui.theme.GlassMode
import com.ipdial.ui.theme.LocalGlassMode
import com.ipdial.ui.theme.glass

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyPolicyScreen(
    vm: SipViewModel,
    onBack: () -> Unit = {},
    onOpenDrawer: (() -> Unit)? = null
) {
    val accounts by vm.accounts.collectAsState()
    val isGlass = LocalGlassMode.current != GlassMode.None
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    Scaffold(
        topBar = {
            IPDialTopBar(accounts = accounts, vm = vm, title = "Privacy Policy", onBack = onBack)
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(if (isGlass) Color.Transparent else MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 760.dp)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Hero summary card
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (isGlass) Modifier.glass(RoundedCornerShape(20.dp)) else Modifier)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            modifier = Modifier.size(46.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.VerifiedUser,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Local-First & Private",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "No accounts, no ads, no analytics, and no cloud tracking. Your calls and credentials stay on your device.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }

                PrivacyPolicyCard(
                    icon = Icons.Default.CloudOff,
                    title = "Information We Do Not Collect",
                    isGlass = isGlass,
                    bullets = listOf(
                        "DialDuo has no developer-hosted servers and requires no sign-in.",
                        "We do not collect usage analytics, advertising IDs, or call audio.",
                        "No third-party ad networks or tracking SDKs are included."
                    )
                )

                PrivacyPolicyCard(
                    icon = Icons.Default.Lock,
                    title = "On-Device Storage & Encryption",
                    isGlass = isGlass,
                    bullets = listOf(
                        "SIP and TURN passwords are encrypted at rest with AES-256-GCM keys stored in the Android Keystore.",
                        "Call logs, call statistics, recordings, and preferences remain in app-private storage on your device.",
                        "Optional call history JSON export/import happens only when you select a destination file."
                    )
                )

                PrivacyPolicyCard(
                    icon = Icons.Default.Security,
                    title = "Permissions Used",
                    isGlass = isGlass,
                    bullets = listOf(
                        "Microphone: Captures voice during VoIP calls and optional in-app call recordings.",
                        "Contacts (Read/Write): Shows your phonebook, caller names/photos, and supports in-app edits or deletions you trigger.",
                        "Phone, Call Management & Call Log: Handles SIM & SIP calling via Android Telecom and displays unified call history.",
                        "Notifications & Full-Screen Alerts: Shows incoming/ongoing call controls and background SIP status.",
                        "Boot Completed & Package Install: Restarts SIP registration on reboot and installs user-approved GitHub APK updates."
                    )
                )

                PrivacyPolicyCard(
                    icon = Icons.Default.Public,
                    title = "Network & External Connections",
                    isGlass = isGlass,
                    bullets = listOf(
                        "Your SIP, STUN & TURN Servers: Voice media, registration, and optional balance lookups go directly to the servers you configure.",
                        "GitHub Releases: Checked only to see if a newer DialDuo version is available and to download signed update APKs when you choose to update.",
                        "Other Calling Apps: Tapping WhatsApp, Telegram, Meet, or SMS in Contact Details hands off to that installed app."
                    )
                )

                PrivacyPolicyCard(
                    icon = Icons.Default.ManageAccounts,
                    title = "Your Controls",
                    isGlass = isGlass,
                    bullets = listOf(
                        "Manage or revoke permissions anytime in Android system settings.",
                        "Clear call history, delete individual recordings, or remove SIP accounts inside DialDuo.",
                        "Uninstalling the app deletes all locally stored app-private data."
                    )
                )

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (isGlass) Modifier.glass(RoundedCornerShape(16.dp)) else Modifier)
                        .clickable {
                            try {
                                uriHandler.openUri("https://github.com/nazimunaeem/IPDial/blob/main/privacy_policy.md")
                            } catch (_: Exception) {
                                Toast.makeText(context, "No browser app found", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .testTag("privacy_github_link")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "View full policy on GitHub",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Last updated November 2026",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "Open in browser",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun PrivacyPolicyCard(
    icon: ImageVector,
    title: String,
    isGlass: Boolean,
    bullets: List<String>
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isGlass) Modifier.glass(RoundedCornerShape(18.dp)) else Modifier)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                bullets.forEach { bullet ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = bullet,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 19.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}
