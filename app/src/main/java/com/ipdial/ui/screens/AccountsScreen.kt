package com.ipdial.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.ipdial.data.model.PreferredCodec
import com.ipdial.data.model.SipAccount
import com.ipdial.data.model.Transport
import com.ipdial.ui.IPDialTopBar
import com.ipdial.ui.RegStatusIndicator
import com.ipdial.ui.SipViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(vm: SipViewModel, onOpenDrawer: () -> Unit) {
    val accounts by vm.accounts.collectAsState()
    val isPro by vm.isPro.collectAsState()
    val defaultDomain by vm.defaultDomain.collectAsState()
    var editingAccount by remember { mutableStateOf<SipAccount?>(null) }
    var showEditSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            IPDialTopBar(accounts = accounts, vm = vm, onOpenDrawer = onOpenDrawer)
        },
        bottomBar = {
            if (!isPro) {
                com.ipdial.ui.StartIoBanner(
                    vm = vm,
                    modifier = Modifier.fillMaxWidth().padding(8.dp)
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                if (!isPro && accounts.size >= 1) {
                    vm.showAdGate {
                        editingAccount = null
                        showEditSheet = true
                    }
                } else {
                    editingAccount = null
                    showEditSheet = true
                }
            }) {
                Icon(Icons.Default.Add, "Add Account")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            // ── Donation ──────────────────────────────────────────────────
            item {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    TelegramSupportCard()
                }
            }

            items(accounts) { account ->
                AccountSettingsRow(
                    account = account,
                    onEdit = { editingAccount = account; showEditSheet = true },
                    onDelete = { vm.deleteAccount(account.id) },
                    onSetDefault = { vm.setDefaultAccount(account.id) },
                    onToggleEnabled = { vm.saveAccount(account.copy(isEnabled = !account.isEnabled)) }
                )
            }
        }

        if (showEditSheet) {
            AccountEditSheet(
                existing = editingAccount,
                defaultDomain = defaultDomain,
                onSave = { 
                    vm.saveAccount(it)
                    showEditSheet = false 
                },
                onDismiss = { showEditSheet = false }
            )
        }
    }
}

@Composable
fun AccountSettingsRow(
    account: SipAccount,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSetDefault: () -> Unit,
    onToggleEnabled: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickableWithRipple { onEdit() }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RegStatusIndicator(accounts = listOf(account))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        account.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = if (account.isEnabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.outline
                    )
                    if (account.isDefault) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "●",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(
                    "${account.username}@${account.domain}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Switch(
                checked = account.isEnabled,
                onCheckedChange = { onToggleEnabled() },
                modifier = Modifier.size(40.dp, 24.dp)
            )

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, null)
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = { showMenu = false; onEdit() }
                    )
                    DropdownMenuItem(
                        text = { Text("Set as default") },
                        onClick = { showMenu = false; onSetDefault() }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        onClick = { showMenu = false; onDelete() }
                    )
                }
            }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
            modifier = Modifier.padding(start = 40.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountEditSheet(
    existing: SipAccount?,
    defaultDomain: String,
    onSave: (SipAccount) -> Unit,
    onDismiss: () -> Unit
) {
    var label     by remember { mutableStateOf(existing?.label ?: "") }
    var username  by remember { mutableStateOf(existing?.username ?: "") }
    var password  by remember { mutableStateOf(existing?.password ?: "") }
    var domain    by remember { mutableStateOf(existing?.domain ?: defaultDomain) }
    var proxy     by remember { mutableStateOf(existing?.proxy ?: "") }
    var port      by remember { mutableStateOf(existing?.port?.toString() ?: "") }
    var transport by remember { mutableStateOf(existing?.transport ?: Transport.UDP) }
    var codec     by remember { mutableStateOf(existing?.codec ?: PreferredCodec.G729) }
    var ecEnabled by remember { mutableStateOf(existing?.ecEnabled ?: true) }
    var nsEnabled by remember { mutableStateOf(existing?.nsEnabled ?: true) }
    var agcEnabled by remember { mutableStateOf(existing?.agcEnabled ?: true) }
    var showPass  by remember { mutableStateOf(false) }

    // Auto-detect transport based on domain, proxy, and port
    LaunchedEffect(domain, proxy, port) {
        val isSips = domain.startsWith("sips:", ignoreCase = true) || proxy.startsWith("sips:", ignoreCase = true) || domain.contains("transport=tls", ignoreCase = true) || proxy.contains("transport=tls", ignoreCase = true)
        val isTcp = domain.contains("transport=tcp", ignoreCase = true) || proxy.contains("transport=tcp", ignoreCase = true)
        val parsedPort = port.toIntOrNull()
        
        transport = when {
            isSips || parsedPort == 5061 -> Transport.TLS
            isTcp -> Transport.TCP
            else -> Transport.UDP
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = if (existing == null) "Add SIP Account" else "Edit Account",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            OutlinedTextField(
                value = label, onValueChange = { label = it },
                label = { Text("Display Name (e.g. Work, Home)") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = username, onValueChange = { username = it },
                label = { Text("SIP Username *") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("Password *") },
                singleLine = true,
                visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPass = !showPass }) {
                        Icon(if (showPass) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = domain, onValueChange = { domain = it },
                label = { Text("SIP Domain / Server *") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = proxy, onValueChange = { proxy = it },
                label = { Text("Outbound Proxy (optional)") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = port, onValueChange = { port = it.filter(Char::isDigit) },
                label = { Text("Port (optional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        if (username.isNotBlank() && password.isNotBlank() && domain.isNotBlank()) {
                            onSave(
                                (existing ?: SipAccount()).copy(
                                    label = label,
                                    username = username,
                                    password = password,
                                    domain = domain,
                                    proxy = proxy,
                                    port = port.toIntOrNull(),
                                    transport = transport,
                                    codec = codec,
                                    ecEnabled = ecEnabled,
                                    nsEnabled = nsEnabled,
                                    agcEnabled = agcEnabled
                                )
                            )
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Register")
                }
            }
        }
    }
}
