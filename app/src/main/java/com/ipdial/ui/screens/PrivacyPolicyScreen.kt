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
        },
        bottomBar = {
            com.ipdial.ui.components.StartIoBanner(
                vm = vm,
                modifier = Modifier.fillMaxWidth().padding(8.dp)
            )
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
                content = "IPDial collects limited information needed for app features and Pro synchronization, including a device identifier and, when used, your Google account identifier."
            )
            
            PrivacySection(
                title = "Permissions",
                content = "Microphone access enables calls, Contacts access displays your phonebook, and Phone state access helps manage call status. Notifications may be used for call and service alerts."
            )
            
            PrivacySection(
                title = "Data Security",
                content = "SIP credentials and call logs remain on your device. Pro points and expiration data may be synchronized through Firebase. IPDial does not upload or store SIP passwords in Firestore."
            )

            PrivacySection(
                title = "Google Sign-In and Pro Management",
                content = "Google Sign-In is optional and associates your Pro points and expiration date with your account. Firebase handles authentication; IPDial never receives or stores your Google password."
            )

            PrivacySection(
                title = "Third-Party Services",
                content = "The free version uses Start.io for advertisements. Start.io may process device or usage information for advertising and measurement under its own privacy policy."
            )
            
            Spacer(Modifier.height(32.dp))
            
            Text(
                text = "Last updated September, 2026",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth()
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
