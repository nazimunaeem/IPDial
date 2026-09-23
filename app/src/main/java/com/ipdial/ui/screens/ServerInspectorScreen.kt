package com.ipdial.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ipdial.data.model.*
import com.ipdial.ui.SipViewModel
import com.ipdial.ui.theme.GlassMode
import com.ipdial.ui.theme.LocalGlassMode
import com.ipdial.ui.theme.glass

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerInspectorScreen(
    vm: SipViewModel,
    onBack: () -> Unit = {},
) {
    val inspection by vm.inspection.collectAsState()
    val isRunning by vm.inspectionRunning.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val callSession by vm.callSession.collectAsState()
    val isGlass = LocalGlassMode.current

    var hostInput by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val kbController = LocalSoftwareKeyboardController.current

    // Pre-fill with the first enabled account's domain
    LaunchedEffect(accounts) {
        if (hostInput.isBlank()) {
            hostInput = accounts.firstOrNull { it.isEnabled }?.domain ?: ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Server Inspector") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (isGlass != GlassMode.None) Color.Transparent
                    else MaterialTheme.colorScheme.surface
                ),
            )
        },
        containerColor = if (isGlass != GlassMode.None) Color.Transparent else MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Host input ──────────────────────────────────────────
            Text(
                "Enter the SIP server host or IP to inspect call quality, DTMF delivery, DNS, and registration.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = hostInput,
                onValueChange = { hostInput = it },
                label = { Text("Server / Host") },
                placeholder = { Text("e.g. sip.example.com or 192.168.1.1") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = {
                    if (hostInput.isNotBlank() && !isRunning) {
                        kbController?.hide()
                        focusManager.clearFocus()
                        vm.runServerInspection(hostInput)
                    }
                }),
                trailingIcon = {
                    if (hostInput.isNotBlank()) {
                        IconButton(onClick = { hostInput = "" }) {
                            Icon(Icons.Default.Clear, "Clear")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            // Quick-pick chips from registered accounts
            if (accounts.any { it.isEnabled }) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    accounts.filter { it.isEnabled }.distinctBy { it.domain.lowercase() }.take(3).forEach { acct ->
                        AssistChip(
                            onClick = { hostInput = acct.domain },
                            label = { Text(acct.domain, maxLines = 1) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Dns,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                }
            }

            // Active call banner
            if (callSession != null && callSession?.state == CallState.CONFIRMED) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Default.Phone, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column {
                            Text("Live call detected", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Codec: ${callSession?.negotiatedCodec ?: "—"}  •  ${callSession?.durationSeconds ?: 0}s",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            // Run button
            Button(
                onClick = {
                    kbController?.hide()
                    focusManager.clearFocus()
                    vm.runServerInspection(hostInput)
                },
                enabled = hostInput.isNotBlank() && !isRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Inspecting…")
                } else {
                    Icon(Icons.Default.NetworkCheck, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Run Inspection")
                }
            }

            Spacer(Modifier.height(4.dp))

            // ── Results ─────────────────────────────────────────────
            if (inspection.host.isNotBlank()) {
                Text(
                    "Results for ${inspection.host}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )

                // 1. DNS
                InspectionCard(
                    title = "DNS Resolution",
                    icon = Icons.Default.Dns,
                    status = inspection.dns.status,
                ) {
                    when (inspection.dns.status) {
                        InspectStatus.PASS -> {
                            ResultRow("Resolved IP", inspection.dns.resolvedIp ?: "—")
                            ResultRow("Latency", "${inspection.dns.latencyMs} ms")
                        }
                        InspectStatus.FAIL -> {
                            ResultRow("Error", inspection.dns.error ?: "Unknown DNS error", isError = true)
                        }
                        else -> {}
                    }
                }

                // 2. Registration
                InspectionCard(
                    title = "SIP Registration",
                    icon = Icons.Default.AppRegistration,
                    status = inspection.register.status,
                ) {
                    when (inspection.register.status) {
                        InspectStatus.PASS -> {
                            ResultRow("Status", inspection.register.statusText)
                            ResultRow("Transport", inspection.register.transport)
                        }
                        InspectStatus.FAIL -> {
                            ResultRow("Error", inspection.register.statusText, isError = true)
                        }
                        InspectStatus.WARN -> {
                            ResultRow("Note", inspection.register.statusText, isWarn = true)
                        }
                        else -> {}
                    }
                }

                // 3. Call Quality
                InspectionCard(
                    title = "Call Quality",
                    icon = Icons.Default.GraphicEq,
                    status = inspection.callQuality.status,
                ) {
                    when (inspection.callQuality.status) {
                        InspectStatus.PASS -> {
                            ResultRow("Codec", inspection.callQuality.negotiatedCodec)
                            if (inspection.callQuality.clockRateHz > 0) {
                                ResultRow("Sample Rate", "${inspection.callQuality.clockRateHz / 1000} kHz")
                            }
                            ResultRow("Quality", inspection.callQuality.qualitySummary)
                            if (inspection.callQuality.callDurationSec > 0) {
                                ResultRow("Duration", "${inspection.callQuality.callDurationSec}s")
                            }
                        }
                        InspectStatus.WARN -> {
                            ResultRow("Note", inspection.callQuality.qualitySummary, isWarn = true)
                        }
                        InspectStatus.FAIL -> {
                            ResultRow("Error", inspection.callQuality.error ?: "Unknown error", isError = true)
                        }
                        else -> {}
                    }
                }

                // 4. DTMF
                InspectionCard(
                    title = "DTMF Delivery",
                    icon = Icons.Default.Dialpad,
                    status = inspection.dtmf.status,
                ) {
                    when (inspection.dtmf.status) {
                        InspectStatus.PASS -> {
                            ResultRow("Method", inspection.dtmf.method)
                            ResultRow("Test Digit", "'${inspection.dtmf.digit}'")
                            ResultRow("Accepted", "✓ Delivered successfully")
                        }
                        InspectStatus.WARN -> {
                            ResultRow("Note", inspection.dtmf.error ?: "DTMF test skipped", isWarn = true)
                        }
                        InspectStatus.FAIL -> {
                            ResultRow("Method", inspection.dtmf.method)
                            ResultRow("Error", inspection.dtmf.error ?: "DTMF delivery failed", isError = true)
                            Text(
                                "Tip: Ensure telephone-event codec is enabled in Audio Codecs settings.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        else -> {}
                    }
                }

                // Elapsed time
                if (inspection.finishedAtMs > 0) {
                    val elapsed = inspection.finishedAtMs - inspection.startedAtMs
                    Text(
                        "Completed in ${elapsed}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                // Guidance
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("How to get full results", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "• DNS and Registration run against any host.\n" +
                                "• Call Quality and DTMF require an active call to the inspected host.\n" +
                                "• Place a call first (e.g. to an echo test number), then re-run the inspection.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── Sub-components ──────────────────────────────────────────────────

@Composable
private fun InspectionCard(
    title: String,
    icon: ImageVector,
    status: InspectStatus,
    content: @Composable ColumnScope.() -> Unit,
) {
    val statusColor = when (status) {
        InspectStatus.PASS -> Color(0xFF4CAF50)
        InspectStatus.WARN -> Color(0xFFFF9800)
        InspectStatus.FAIL -> Color(0xFFF44336)
        InspectStatus.RUNNING -> MaterialTheme.colorScheme.primary
        InspectStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, contentDescription = null, tint = statusColor, modifier = Modifier.size(22.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                StatusBadge(status)
            }

            AnimatedVisibility(
                visible = status != InspectStatus.IDLE && status != InspectStatus.RUNNING,
                enter = fadeIn() + expandVertically(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    content()
                }
            }

            if (status == InspectStatus.RUNNING) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(status: InspectStatus) {
    val (text, bgColor, textColor) = when (status) {
        InspectStatus.PASS -> Triple("PASS", Color(0xFF4CAF50), Color.White)
        InspectStatus.WARN -> Triple("WARN", Color(0xFFFF9800), Color.White)
        InspectStatus.FAIL -> Triple("FAIL", Color(0xFFF44336), Color.White)
        InspectStatus.RUNNING -> Triple("…", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
        InspectStatus.IDLE -> Triple("—", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = bgColor,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun ResultRow(
    label: String,
    value: String,
    isError: Boolean = false,
    isWarn: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.35f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (!isError && !isWarn) FontFamily.Monospace else FontFamily.Default,
            fontWeight = if (isError || isWarn) FontWeight.Medium else FontWeight.Normal,
            color = when {
                isError -> Color(0xFFF44336)
                isWarn -> Color(0xFFFF9800)
                else -> MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(0.65f),
        )
    }
}
