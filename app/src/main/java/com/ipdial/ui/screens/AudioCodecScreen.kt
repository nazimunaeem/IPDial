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
import android.content.Intent
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.ui.platform.LocalContext
import com.ipdial.data.model.*
import com.ipdial.service.CallAudioQualityHelper
import com.ipdial.ui.components.IPDialTopBar
import com.ipdial.ui.SipViewModel

internal fun matchCodec(codec: PreferredCodec, engineName: String): Boolean {
    val name = engineName.lowercase()
    return when (codec) {
        PreferredCodec.G711A -> name.contains("pcma") || name.contains("g711a")
        PreferredCodec.G711U -> name.contains("pcmu") || name.contains("g711u") || name.contains("g.711u")
        PreferredCodec.G722 -> name.startsWith("g722/") || name == "g722"
        PreferredCodec.G729 -> name.contains("g729")
        PreferredCodec.GSM -> name.contains("gsm")
        PreferredCodec.Opus -> name.contains("opus")
        PreferredCodec.Speex -> name.contains("speex")
        PreferredCodec.ILBC -> name.contains("ilbc")
    }
}

/**
 * Returns every codec to show in the checklist:
 * - Always includes every [PreferredCodec] that is currently "Ready" in [availableCodecs]
 * - Keeps default/already-enabled supported codecs visible so unchecking one doesn't hide it
 * - Falls back to [DEFAULT_ENABLED_CODECS] when engine codec info isn't available yet.
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
                    items(availablePreferredCodecs) { codec ->
                        CodecSelectionRow(
                            codec = codec,
                            isEnabled = acct.enabledCodecs.contains(codec),
                            isPreferred = acct.codec == codec,
                            isEngineAvailable = true,
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
    val availablePreferredCodecs = remember(availableCodecs, account.enabledCodecs) {
        checklistCodecs(availableCodecs, account.enabledCodecs)
    }
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
                "Opus and G.722 deliver HD wideband SIP voice when supported by your provider; G.711 is the standard fallback.",
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
                        text = codec.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isEnabled) FontWeight.Bold else FontWeight.Normal,
                        color = if (isEngineAvailable) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                    if (codec == PreferredCodec.Opus || codec == PreferredCodec.G722) {
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
                    text = when(codec) {
                        PreferredCodec.G711A -> "Best compatibility (PCMA) · 64 kbps · MOS 4.1"
                        PreferredCodec.G711U -> "Standard (PCMU) · 64 kbps · MOS 4.1"
                        PreferredCodec.G722 -> "High Definition · 48 kbps · MOS 4.0"
                        PreferredCodec.G729 -> "Low Bandwidth · 8 kbps · MOS 3.9"
                        PreferredCodec.GSM -> "Mobile Fallback · 13 kbps · MOS 3.5"
                        PreferredCodec.Opus -> "Best quality & adaptability · variable bitrate"
                        PreferredCodec.Speex -> "Adaptive wideband/narrowband · 15 kbps"
                        PreferredCodec.ILBC -> "Packet-loss resilient · 15 kbps"
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
