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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

import com.ipdial.data.model.RegStatus
import com.ipdial.data.model.SipAccount
import com.ipdial.data.model.Transport
import com.ipdial.ui.components.IPDialTopBar
import com.ipdial.ui.components.RegStatusIndicator
import com.ipdial.ui.SipViewModel
import androidx.compose.ui.platform.LocalUriHandler

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    vm: SipViewModel,
    onBack: () -> Unit = {},
    onOpenDrawer: (() -> Unit)? = null
) {
    val accounts by vm.accounts.collectAsState()
    val isPro by vm.isPro.collectAsState()
    val defaultDomain by vm.defaultDomain.collectAsState()
    var editingAccount by remember { mutableStateOf<SipAccount?>(null) }
    var showEditSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            IPDialTopBar(accounts = accounts, vm = vm, title = "SIP Accounts", onBack = onBack)
        },
        bottomBar = {
            if (!isPro) {
                com.ipdial.ui.components.StartIoBanner(
                    vm = vm,
                    modifier = Modifier.fillMaxWidth().padding(8.dp)
                )
            }
        },
        floatingActionButton = {}
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            item {
                Button(
                    onClick = {
                        if (!isPro && accounts.size >= 1) {
                            vm.showAdGate {
                                editingAccount = null
                                showEditSheet = true
                            }
                        } else {
                            editingAccount = null
                            showEditSheet = true
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Add Account")
                }
            }

            // ── Donation ──────────────────────────────────────────────────
            item {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    TelegramSupportCard()
                }
            }

            items(accounts) { account ->
                AccountSettingsRow(
                    account = account,
                    vm = vm,
                    onEdit = { editingAccount = account; showEditSheet = true },
                    onDelete = { vm.deleteAccount(account.id) },
                    onSetDefault = { vm.setDefaultAccount(account.id) },
                    onToggleEnabled = { vm.saveAccount(account.copy(isEnabled = !account.isEnabled)) }
                )
            }

            // ── Custom Firebase ad banner ─────────────────────────────────
            item {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    com.ipdial.ui.components.CustomAccountPageAd(vm = vm)
                }
            }
        }

        if (showEditSheet) {
            AccountEditSheet(
                vm = vm,
                existing = editingAccount,
                defaultDomain = defaultDomain,
                onSave = { account ->
                    vm.saveAccount(account)
                    // Sheet stays open: it observes live registration status and
                    // dismisses itself once the account registers successfully.
                },
                onDismiss = { showEditSheet = false }
            )
        }
    }
}

@Composable
fun AccountSettingsRow(
    account: SipAccount,
    vm: SipViewModel,
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
            RegStatusIndicator(accounts = listOf(account), vm = vm, showAccountInfo = account)
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
    vm: SipViewModel,
    existing: SipAccount?,
    defaultDomain: String,
    onSave: (SipAccount) -> Unit,
    onDismiss: () -> Unit
) {
    var label     by remember { mutableStateOf(existing?.label ?: "") }
    var username  by remember { mutableStateOf(existing?.username ?: "") }
    var password  by remember { mutableStateOf(existing?.password ?: "") }
    var domain    by remember { mutableStateOf(existing?.domain ?: "") }
    var proxy     by remember { mutableStateOf(existing?.proxy ?: "") }
    var port      by remember { mutableStateOf(existing?.port?.toString() ?: "") }
    var transport by remember { mutableStateOf(existing?.transport ?: Transport.UDP) }
    var userManuallySelectedTransport by remember { mutableStateOf(existing != null) }
    var showPass  by remember { mutableStateOf(false) }

    // Inline validation error states
    var usernameError by remember { mutableStateOf(false) }
    var passwordError by remember { mutableStateOf(false) }
    var domainError  by remember { mutableStateOf(false) }

    // Save/registration feedback
    var isSaving by remember { mutableStateOf(false) }
    var regStatusText by remember { mutableStateOf<String?>(null) }
    var regStatusError by remember { mutableStateOf(false) }
    var savedAccountId by remember { mutableStateOf<String?>(null) }
    var lastObservedStatus by remember { mutableStateOf<RegStatus?>(null) }

    val keyboard = LocalSoftwareKeyboardController.current

    // Observe live registration status so the sheet can show progress and auto-close
    // once the account actually registers (or show the error and let the user fix it).
    val allAccounts by vm.accounts.collectAsState()
    LaunchedEffect(savedAccountId, allAccounts) {
        val id = savedAccountId ?: return@LaunchedEffect
        val live = allAccounts.firstOrNull { it.id == id } ?: return@LaunchedEffect
        when (live.regStatus) {
            RegStatus.REGISTERED -> {
                // Auto-close only on a transition INTO REGISTERED.
                // If the account was already registered before this save and nothing
                // meaningful changed, keep the sheet open so the edit UI stays usable.
                if (lastObservedStatus != RegStatus.REGISTERED) {
                    isSaving = false
                    regStatusText = "Registered"
                    regStatusError = false
                    savedAccountId = null
                    onDismiss()
                }
            }
            RegStatus.ERROR -> {
                isSaving = false
                regStatusText = live.regStatusText.ifBlank {
                    "Registration failed — check username/password"
                }
                regStatusError = true
                savedAccountId = null
            }
            else -> {} // REGISTERING / UNREGISTERED: keep waiting
        }
        lastObservedStatus = live.regStatus
    }

    val savedLabels by vm.repo.savedLabels.collectAsState(initial = emptyList<String>())
    val savedHosts by vm.repo.savedHosts.collectAsState(initial = emptyList<String>())
    val suggestedLabels = listOf("iCallBD", "BanglaCall").plus(savedLabels).distinct()
    val suggestedHosts = listOf("103.129.202.202", "103.170.231.10", "sip.amarip.net").plus(savedHosts).distinct()

    // Auto-detect transport based on domain, proxy, and port unless user manually changed it
    // Note: on edit (existing != null) we leave the account's stored transport untouched —
    // auto-detect only kicks in for newly typed values after the user edits them.
    LaunchedEffect(domain, proxy, port) {
        if (!userManuallySelectedTransport && existing == null) {
            val isSips = domain.startsWith("sips:", ignoreCase = true) || proxy.startsWith("sips:", ignoreCase = true) || domain.contains("transport=tls", ignoreCase = true) || proxy.contains("transport=tls", ignoreCase = true)
            val isTcp = domain.contains("transport=tcp", ignoreCase = true) || proxy.contains("transport=tcp", ignoreCase = true)
            val parsedPort = port.toIntOrNull()
            
            transport = when {
                isSips || parsedPort == 5061 -> Transport.TLS
                isTcp -> Transport.TCP
                else -> Transport.UDP
            }
        }
    }

    val glassMode = com.ipdial.ui.theme.LocalGlassMode.current
    val isQuartz = glassMode == com.ipdial.ui.theme.GlassMode.Quartz
    val isObsidian = glassMode == com.ipdial.ui.theme.GlassMode.Obsidian

    val sheetBgColor = when {
        isQuartz -> Color(0xF7FFFFFF)
        isObsidian -> Color(0xF71C1C1E)
        else -> MaterialTheme.colorScheme.surface
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
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
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = if (existing == null) "Add SIP Account" else "Edit Account",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Display Name (e.g. Work, Home)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            SuggestionChips(suggestions = suggestedLabels, current = label) { label = it }
            OutlinedTextField(
                value = username, onValueChange = { username = it; if (it.isNotBlank()) usernameError = false },
                label = { Text("SIP Username *") },
                singleLine = true,
                isError = usernameError,
                supportingText = if (usernameError) {
                    { Text("Username is required") }
                } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it; if (it.isNotBlank()) passwordError = false },
                label = { Text("Password *") },
                singleLine = true,
                isError = passwordError,
                supportingText = if (passwordError) {
                    { Text("Password is required") }
                } else null,
                visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPass = !showPass }) {
                        Icon(if (showPass) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = domain,
                onValueChange = { domain = it; if (it.isNotBlank()) domainError = false },
                label = { Text("SIP Domain / Server *") },
                singleLine = true,
                isError = domainError,
                supportingText = if (domainError) {
                    { Text("Domain is required") }
                } else null,
                modifier = Modifier.fillMaxWidth()
            )
            SuggestionChips(suggestions = suggestedHosts, current = domain) { domain = it }

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

            Column {
                Text(
                    text = "Transport",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Transport.entries.forEach { tp ->
                        FilterChip(
                            selected = transport == tp,
                            onClick = {
                                userManuallySelectedTransport = true
                                transport = tp
                            },
                            label = { Text(tp.name) }
                        )
                    }
                }
            }

            // Audio quality is configured globally in Settings (Call Audio Quality).
            // NAT traversal / TURN is ALSO configured globally (Settings → Network),
            // so there is nothing per-account here beyond the SIP transport above.

            Spacer(Modifier.height(8.dp))

            if (regStatusText != null) {
                Text(
                    text = regStatusText ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (regStatusError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { keyboard?.hide(); onDismiss() }, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
                Button(
                    enabled = !isSaving,
                    onClick = {
                        keyboard?.hide()
                        val trimmedUser = username.trim()
                        val trimmedPass = password.trim()
                        val trimmedDomain = domain.trim()
                        usernameError = trimmedUser.isBlank()
                        passwordError = trimmedPass.isBlank()
                        domainError = trimmedDomain.isBlank()
                        if (usernameError || passwordError || domainError) return@Button

                        val regAccount = (existing ?: SipAccount()).copy(
                            label = label.trim(),
                            username = trimmedUser,
                            password = trimmedPass,
                            domain = trimmedDomain,
                            proxy = proxy.trim(),
                            port = port.trim().toIntOrNull(),
                            transport = transport,
                            codec = existing?.codec,
                            enabledCodecs = existing?.enabledCodecs ?: com.ipdial.data.model.DEFAULT_ENABLED_CODECS
                        )
                        if (existing == null || regAccount != existing) {
                            isSaving = true
                            regStatusText = "Registering…"
                            regStatusError = false
                            savedAccountId = regAccount.id
                            lastObservedStatus = null
                            onSave(regAccount)
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isSaving) "Saving…" else "Register")
                }
            }
        }
    }
}

@Composable
private fun SuggestionChips(
    suggestions: List<String>,
    current: String,
    onPick: (String) -> Unit
) {
    val filtered = suggestions.filter {
        it.equals(current, ignoreCase = true).not() &&
        (current.isEmpty() || it.contains(current, ignoreCase = true))
    }
    if (filtered.isEmpty()) return
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        filtered.forEach { suggestion ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.clickableWithRipple { onPick(suggestion) }
            ) {
                Text(
                    text = suggestion,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}
