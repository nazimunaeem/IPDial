package com.ipdial.ui.screens

import android.content.Intent
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ipdial.data.model.CodecInfo
import com.ipdial.data.model.CodecProfile
import com.ipdial.data.model.DEFAULT_ENABLED_CODECS
import com.ipdial.data.model.PreferredCodec
import com.ipdial.data.model.SipAccount
import com.ipdial.data.model.UNIVERSAL_FALLBACK_CODECS
import com.ipdial.service.CallAudioQualityHelper
import com.ipdial.ui.SipViewModel

internal fun matchCodec(codec: PreferredCodec, engineName: String): Boolean {
    val name = engineName.lowercase()
    return when (codec) {
        PreferredCodec.G722 -> name.startsWith("g722/") || name == "g722"
        PreferredCodec.G711A -> name.contains("pcma") || name.contains("g711a")
        PreferredCodec.G711U -> name.contains("pcmu") || name.contains("g711u") || name.contains("g.711u")
        PreferredCodec.G729 -> name.contains("g729")
        PreferredCodec.Speex -> name.contains("speex")
        PreferredCodec.GSM -> name.contains("gsm")
        PreferredCodec.ILBC -> name.contains("ilbc")
    }
}

internal fun hasUniversalFallback(enabled: Set<PreferredCodec>): Boolean =
    enabled.any { it in UNIVERSAL_FALLBACK_CODECS }

/**
 * Returns every codec to show in the checklist:
 * - Always includes every [PreferredCodec] that is currently "Ready" in [availableCodecs]
 * - Keeps default/already-enabled supported codecs visible so unchecking one doesn't hide it
 * - Falls back to [DEFAULT_ENABLED_CODECS] + Speex/iLBC when engine codec info isn't available yet.
 */
internal fun checklistCodecs(
    availableCodecs: List<CodecInfo>,
    accountEnabledCodecs: Set<PreferredCodec>
): List<PreferredCodec> {
    if (availableCodecs.isEmpty()) {
        return PreferredCodec.entries.filter {
            it in DEFAULT_ENABLED_CODECS || it in accountEnabledCodecs
        }
    }
    return PreferredCodec.entries.filter { codec ->
        val matches = availableCodecs.filter { matchCodec(codec, it.name) }
        val isReady = matches.any { it.isAvailable }
        val isSupported = matches.isNotEmpty()
        isReady || (isSupported && (codec in DEFAULT_ENABLED_CODECS || codec in accountEnabledCodecs))
    }.ifEmpty {
        PreferredCodec.entries.filter { it in DEFAULT_ENABLED_CODECS }
    }
}

private fun toggleAccountCodec(account: SipAccount, codec: PreferredCodec): SipAccount? {
    val set = account.enabledCodecs.toMutableSet()
    if (set.contains(codec)) set.remove(codec) else set.add(codec)
    if (set.isEmpty()) return null
    val preferred = if (account.codec == codec && !set.contains(codec)) null else account.codec
    return account.copy(enabledCodecs = set, codec = preferred)
}

private fun togglePreferredCodec(account: SipAccount, codec: PreferredCodec): SipAccount {
    if (!account.enabledCodecs.contains(codec)) return account
    val next = if (account.codec == codec) null else codec
    return account.copy(codec = next)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioCodecScreen(
    vm: SipViewModel,
    onBack: () -> Unit,
    onOpenDrawer: (() -> Unit)? = null
) {
    val activeAccount by vm.activeAccount.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val enabledAccounts = remember(accounts) { accounts.filter { it.isEnabled } }
    var availableCodecs by remember { mutableStateOf(com.ipdial.service.SipEngine.getAvailableCodecs()) }

    LaunchedEffect(accounts) {
        availableCodecs = com.ipdial.service.SipEngine.getAvailableCodecs()
        kotlinx.coroutines.delay(300)
        availableCodecs = com.ipdial.service.SipEngine.getAvailableCodecs()
    }

    val availablePreferredCodecs = remember(availableCodecs, activeAccount?.enabledCodecs) {
        checklistCodecs(availableCodecs, activeAccount?.enabledCodecs ?: DEFAULT_ENABLED_CODECS)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Audio Codecs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                InfoCard()
            }

            item {
                SimHdStatusCard()
            }

            if (enabledAccounts.isNotEmpty()) {
                if (enabledAccounts.size > 1) {
                    item {
                        Text(
                            "SIP Codec Profiles & Codecs",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    items(enabledAccounts) { account ->
                        AccountCodecSection(
                            account = account,
                            availableCodecs = availableCodecs,
                            onApplyProfile = { profile ->
                                vm.saveAccount(
                                    account.copy(
                                        enabledCodecs = profile.enabled,
                                        codec = profile.preferred
                                    )
                                )
                            },
                            onRestoreFallbacks = {
                                vm.saveAccount(
                                    account.copy(
                                        enabledCodecs = account.enabledCodecs + UNIVERSAL_FALLBACK_CODECS
                                    )
                                )
                            },
                            onClearPreferred = {
                                vm.saveAccount(account.copy(codec = null))
                            },
                            onToggleCodec = { codec ->
                                toggleAccountCodec(account, codec)?.let { vm.saveAccount(it) }
                            },
                            onSelectPreferred = { codec ->
                                vm.saveAccount(togglePreferredCodec(account, codec))
                            }
                        )
                    }
                } else if (activeAccount != null) {
                    val acct = activeAccount!!
                    item {
                        CodecProfileSelector(
                            account = acct,
                            onSelectProfile = { profile ->
                                vm.saveAccount(
                                    acct.copy(
                                        enabledCodecs = profile.enabled,
                                        codec = profile.preferred
                                    )
                                )
                            }
                        )
                    }
                    if (!hasUniversalFallback(acct.enabledCodecs)) {
                        item {
                            CompatibilityWarningCard(
                                onRestoreFallbacks = {
                                    vm.saveAccount(
                                        acct.copy(
                                            enabledCodecs = acct.enabledCodecs + UNIVERSAL_FALLBACK_CODECS
                                        )
                                    )
                                }
                            )
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Enabled Codecs",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (acct.codec != null) {
                                TextButton(
                                    onClick = { vm.saveAccount(acct.copy(codec = null)) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("Use Auto Priority")
                                }
                            } else {
                                Text(
                                    text = "Auto Priority",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                    items(availablePreferredCodecs) { codec ->
                        CodecSelectionRow(
                            codec = codec,
                            isEnabled = acct.enabledCodecs.contains(codec),
                            isPreferred = acct.codec == codec,
                            isEngineAvailable = true,
                            onToggleEnabled = {
                                toggleAccountCodec(acct, codec)?.let { vm.saveAccount(it) }
                            },
                            onSetPreferred = {
                                vm.saveAccount(togglePreferredCodec(acct, codec))
                            }
                        )
                    }
                }
            }

            item {
                Text(
                    "System Availability & Quality",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                )
            }

            if (availableCodecs.isEmpty()) {
                item {
                    Text(
                        "No engine info available. Register an account first.",
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            } else {
                items(availableCodecs) { info ->
                    CodecInfoRow(info)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CodecProfileSelector(
    account: SipAccount,
    onSelectProfile: (CodecProfile) -> Unit
) {
    val activeProfile = remember(account.codec, account.enabledCodecs) {
        CodecProfile.matching(account)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Quick Preset",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CodecProfile.entries.forEach { profile ->
                    val selected = activeProfile == profile
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectProfile(profile) },
                        label = { Text(profile.title) },
                        leadingIcon = if (selected) {
                            {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(FilterChipDefaults.IconSize)
                                )
                            }
                        } else null
                    )
                }
            }
            Text(
                text = activeProfile?.subtitle
                    ?: "Custom selection (${account.enabledCodecs.size} enabled${account.codec?.let { ", ${it.name} preferred" } ?: ", Auto priority"})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CompatibilityWarningCard(
    onRestoreFallbacks: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Compatibility fallback missing",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = "Keep at least one standard codec (G.711A, G.711U, or G.729) enabled so providers don't reject calls.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            TextButton(onClick = onRestoreFallbacks) {
                Text(
                    "Fix",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun AccountCodecSection(
    account: SipAccount,
    availableCodecs: List<CodecInfo>,
    onApplyProfile: (CodecProfile) -> Unit,
    onRestoreFallbacks: () -> Unit,
    onClearPreferred: () -> Unit,
    onToggleCodec: (PreferredCodec) -> Unit,
    onSelectPreferred: (PreferredCodec) -> Unit
) {
    val availablePreferredCodecs = remember(availableCodecs, account.enabledCodecs) {
        checklistCodecs(availableCodecs, account.enabledCodecs)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = account.label.ifBlank { account.domain },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                if (account.codec != null) {
                    TextButton(
                        onClick = onClearPreferred,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Auto Priority")
                    }
                }
            }
            CodecProfileSelector(
                account = account,
                onSelectProfile = onApplyProfile
            )
            if (!hasUniversalFallback(account.enabledCodecs)) {
                CompatibilityWarningCard(onRestoreFallbacks = onRestoreFallbacks)
            }
            availablePreferredCodecs.forEach { codec ->
                CodecSelectionRow(
                    codec = codec,
                    isEnabled = account.enabledCodecs.contains(codec),
                    isPreferred = account.codec == codec,
                    isEngineAvailable = true,
                    onToggleEnabled = { onToggleCodec(codec) },
                    onSetPreferred = { onSelectPreferred(codec) }
                )
            }
        }
    }
}

@Composable
fun InfoCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Text(
                "G.722 delivers HD wideband SIP voice when supported by your provider; G.711 (PCMA/PCMU) and G.729 ensure reliable calls across BD & global networks.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun SimHdStatusCard() {
    val context = LocalContext.current
    val readiness = remember { CallAudioQualityHelper.inspectSimHdReadiness(context) }
    val isReady = readiness.hdCapableNetworkCount > 0

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SimCard,
                    contentDescription = null,
                    tint = if (isReady) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "SIM HD Voice (VoLTE / 5G VoNR)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = readiness.networkSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = "SIM HD calls depend on VoLTE / 5G Calling or Wi-Fi Calling being enabled for your SIM and supported by both carriers. When active, an HD badge appears on incoming and active call screens.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(
                onClick = {
                    val intents = listOf(
                        Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS),
                        Intent(Settings.ACTION_WIRELESS_SETTINGS),
                        Intent(Settings.ACTION_SETTINGS)
                    )
                    val launched = intents.any { intent ->
                        try {
                            context.startActivity(intent)
                            true
                        } catch (_: Exception) {
                            false
                        }
                    }
                    if (!launched) {
                        android.widget.Toast.makeText(
                            context,
                            "Mobile network settings unavailable",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text("Open Mobile Network / SIM Settings")
            }
        }
    }
}

@Composable
fun CodecSelectionRow(
    codec: PreferredCodec,
    isEnabled: Boolean,
    isPreferred: Boolean,
    isEngineAvailable: Boolean = true,
    onToggleEnabled: () -> Unit,
    onSetPreferred: () -> Unit
) {
    Surface(
        onClick = onToggleEnabled,
        shape = RoundedCornerShape(12.dp),
        color = if (isEnabled) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        border = if (isEnabled) null
        else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        enabled = isEngineAvailable
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isEnabled,
                onCheckedChange = { onToggleEnabled() },
                enabled = isEngineAvailable,
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary
                )
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = when (codec) {
                            PreferredCodec.G722 -> "G.722"
                            PreferredCodec.G711A -> "G.711A (PCMA)"
                            PreferredCodec.G711U -> "G.711U (PCMU)"
                            PreferredCodec.G729 -> "G.729"
                            PreferredCodec.Speex -> "Speex"
                            PreferredCodec.GSM -> "GSM"
                            PreferredCodec.ILBC -> "iLBC"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isEnabled) FontWeight.Bold else FontWeight.Normal,
                        color = if (isEngineAvailable) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                    if (codec == PreferredCodec.G722 || codec == PreferredCodec.Speex) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.Hd,
                            contentDescription = "HD Audio",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    if (isPreferred) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "PREFERRED",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text(
                    text = when (codec) {
                        PreferredCodec.G722 -> "HD Wideband (16 kHz) · 48 kbps · Best clarity"
                        PreferredCodec.G711A -> "Standard A-Law · 64 kbps · BD & Asia default"
                        PreferredCodec.G711U -> "Standard u-Law · 64 kbps · Global fallback"
                        PreferredCodec.G729 -> "Low Bandwidth · 8 kbps · BD VoIP & mobile data"
                        PreferredCodec.Speex -> "Adaptive Wideband (16 kHz) · 15–27 kbps"
                        PreferredCodec.GSM -> "Lightweight fallback · 13 kbps"
                        PreferredCodec.ILBC -> "Packet-loss resilient · 15 kbps · Weak networks"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isEnabled) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (isEngineAvailable) 1f else 0.38f
                    )
                )
                if (!isEngineAvailable) {
                    Text(
                        text = "Not available on this device",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            if (isEnabled) {
                IconButton(
                    onClick = onSetPreferred,
                    enabled = isEngineAvailable
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = if (isPreferred) "Clear preferred codec" else "Set as preferred codec",
                        tint = if (isPreferred) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

@Composable
fun CodecInfoRow(info: CodecInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(info.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                repeat(info.quality.level) {
                    Icon(Icons.Default.Star, null, tint = Color(0xFFFFB300), modifier = Modifier.size(14.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            Row {
                Text("${info.clockRate / 1000}kHz", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(12.dp))
                Text(if (info.channelCount == 2) "Stereo" else "Mono", style = MaterialTheme.typography.labelSmall)
                if (info.bandwidthKbps > 0) {
                    Spacer(Modifier.width(12.dp))
                    Text("${info.bandwidthKbps} kbps", style = MaterialTheme.typography.labelSmall)
                }
                if (info.estimatedMOS > 0f) {
                    Spacer(Modifier.width(12.dp))
                    Text("MOS %.1f".format(info.estimatedMOS), style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (info.isAvailable) "Ready" else "Disabled",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (info.isAvailable) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
