package com.ipdial.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.sp
import com.ipdial.ui.components.IPDialTopBar
import com.ipdial.ui.SipViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyPolicyScreen(
    vm: SipViewModel,
    onBack: () -> Unit = {},
    onOpenDrawer: (() -> Unit)? = null
) {
    val accounts by vm.accounts.collectAsState()

    Scaffold(
        topBar = {
            IPDialTopBar(accounts = accounts, vm = vm, title = "Privacy Policy", onBack = onBack)
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = "Privacy Policy",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            
            Spacer(Modifier.height(16.dp))
            
            PrivacySection(
                title = "Information Collection",
                content = "IPDial runs locally on your device without requiring an account or cloud sign-in. SIP account settings, call logs, and recordings stay on your device."
            )
            
            PrivacySection(
                title = "Permissions",
                content = "Microphone access enables VoIP calls, Contacts access displays your phonebook and caller names/photos, and Phone State / Telecom permissions manage active call routing. Notifications are used for incoming and ongoing call alerts."
            )
            
            PrivacySection(
                title = "Data Security",
                content = "SIP credentials, call logs, and call recordings are stored locally in app-private storage on your device and are only sent to your configured SIP server when registering or placing calls."
            )

            PrivacySection(
                title = "Network & Update Checks",
                content = "IPDial connects directly to your configured SIP provider (and optional STUN/TURN servers). Checking for app updates fetches public release metadata from GitHub."
            )
            
            Spacer(Modifier.height(32.dp))
            
            Text(
                text = "Last updated November, 2026",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(Modifier.height(8.dp))

            val uriHandler = LocalUriHandler.current
            val context = androidx.compose.ui.platform.LocalContext.current
            Text(
                text = "View full privacy policy on GitHub",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable {
                        try {
                            uriHandler.openUri("https://github.com/nazimunaeem/IPDial/blob/main/privacy_policy.md")
                        } catch (_: Exception) {
                            android.widget.Toast.makeText(context, "No browser app found", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
            )
            
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun PrivacySection(title: String, content: String) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.secondary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = content,
            style = MaterialTheme.typography.bodyMedium,
            lineHeight = 20.sp
        )
    }
}
