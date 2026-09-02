package com.ipdial.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ipdial.data.model.*
import com.ipdial.ui.components.IPDialTopBar
import com.ipdial.ui.SipViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioCodecScreen(
    vm: SipViewModel,
    onOpenDrawer: () -> Unit,
    onBack: () -> Unit
) {
    val activeAccount by vm.activeAccount.collectAsState()
    val availableCodecs = remember { com.ipdial.service.SipEngine.getAvailableCodecs() }
    val accounts by vm.accounts.collectAsState()
    val enabledAccounts = remember(accounts) { accounts.filter { it.isEnabled } }

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

            if (enabledAccounts.isNotEmpty()) {
                item {
                    Text(
                        "Enabled Codecs",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                if (enabledAccounts.size > 1) {
                    items(enabledAccounts) { account ->
                        AccountCodecSection(
                            account = account,
                            availableCodecs = availableCodecs,
                            onToggleCodec = { codec ->
                                val set = account.enabledCodecs.toMutableSet()
                                if (set.contains(codec)) set.remove(codec) else set.add(codec)
                                if (set.isNotEmpty()) {
                                    val preferred = if (account.codec == codec && !set.contains(codec))
                                        set.first() else account.codec
                                    vm.saveAccount(account.copy(enabledCodecs = set, codec = preferred))
                                }
                            },
                            onSelectPreferred = { codec ->
                                vm.saveAccount(account.copy(codec = codec))
                            }
                        )
                    }
                } else if (activeAccount != null) {
                    val acct = activeAccount!!
                    items(PreferredCodec.entries) { codec ->
                        val isEngineAvailable = availableCodecs.any {
                            it.name.lowercase().contains(codec.name.lowercase().replace("g711", "pcm")) && it.isAvailable
                        }
                        CodecSelectionRow(
                            codec = codec,
                            isEnabled = acct.enabledCodecs.contains(codec),
                            isPreferred = acct.codec == codec,
                            isEngineAvailable = isEngineAvailable,
                            onToggleEnabled = {
                                val set = acct.enabledCodecs.toMutableSet()
                                if (set.contains(codec)) set.remove(codec) else set.add(codec)
                                if (set.isNotEmpty()) {
                                    val preferred = if (acct.codec == codec && !set.contains(codec))
                                        set.first() else acct.codec
                                    vm.saveAccount(acct.copy(enabledCodecs = set, codec = preferred))
                                }
                            },
                            onSetPreferred = {
                                if (acct.enabledCodecs.contains(codec)) vm.saveAccount(acct.copy(codec = codec))
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
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
            }

            if (availableCodecs.isEmpty()) {
                item {
                    Text("No engine info available. Register an account first.", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                items(availableCodecs) { info ->
                    CodecInfoRow(info)
                }
            }
        }
    }
}

@Composable
fun AccountCodecSection(
    account: SipAccount,
    availableCodecs: List<CodecInfo>,
    onToggleCodec: (PreferredCodec) -> Unit,
    onSelectPreferred: (PreferredCodec) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = account.label.ifBlank { account.domain },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            PreferredCodec.entries.forEach { codec ->
                val isEngineAvailable = availableCodecs.any {
                    it.name.lowercase().contains(codec.name.lowercase().replace("g711", "pcm")) && it.isAvailable
                }
                CodecSelectionRow(
                    codec = codec,
                    isEnabled = account.enabledCodecs.contains(codec),
                    isPreferred = account.codec == codec,
                    isEngineAvailable = isEngineAvailable,
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
                "Codecs determine voice quality and data usage. G.711A is the standard for most SIP providers.",
                style = MaterialTheme.typography.bodyMedium
            )
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
                        text = codec.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isEnabled) FontWeight.Bold else FontWeight.Normal,
                        color = if (isEngineAvailable) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
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
                    text = when(codec) {
                        PreferredCodec.G711A -> "Best compatibility (PCMA) · 64 kbps · MOS 4.1"
                        PreferredCodec.G711U -> "Standard (PCMU) · 64 kbps · MOS 4.1"
                        PreferredCodec.G722 -> "High Definition · 48 kbps · MOS 4.0"
                        PreferredCodec.G729 -> "Low Bandwidth · 8 kbps · MOS 3.9"
                        PreferredCodec.Opus -> "Best quality & adaptability · variable bitrate"
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
                        Icons.Default.Star,
                        null,
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
