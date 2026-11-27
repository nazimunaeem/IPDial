package com.ipdial.ui.screens.home

import android.content.Intent
import android.provider.ContactsContract
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ipdial.data.model.CallDirection
import com.ipdial.data.model.CallLogEntry
import com.ipdial.data.model.CallTransport
import com.ipdial.data.model.Contact
import com.ipdial.data.model.SipAccount
import com.ipdial.ui.components.ContactAvatar
import com.ipdial.ui.components.filterHistoryForTarget
import com.ipdial.ui.screens.cleanDisplayName
import com.ipdial.ui.screens.cleanUri
import com.ipdial.ui.screens.call.CompactHdBadge
import com.ipdial.ui.screens.call.formatHistoryDurationSuffix
import com.ipdial.ui.theme.GlassMode
import com.ipdial.ui.theme.LocalGlassMode
import com.ipdial.ui.theme.glass
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class LogGroup(
    val mainEntry: CallLogEntry,
    val count: Int,
    val allEntries: List<CallLogEntry>
)

@Composable
fun callDirectionColor(entry: CallLogEntry): Color {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return when {
        entry.missed -> MaterialTheme.colorScheme.error
        entry.direction == CallDirection.INCOMING -> if (isDark) Color(0xFF66BB6A) else Color(0xFF2E7D32)
        else -> if (isDark) Color(0xFF64B5F6) else Color(0xFF1565C0)
    }
}

@Composable
fun callLineAccentColor(
    transport: CallTransport,
    label: String,
    accountId: String = ""
): Color {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return when (transport) {
        CallTransport.SIM -> {
            val isSecondarySim = label.contains("2") || accountId.endsWith("2")
            if (isSecondarySim) {
                if (isDark) Color(0xFFFFB74D) else Color(0xFFB45309)
            } else {
                if (isDark) Color(0xFF4DB6AC) else Color(0xFF00796B)
            }
        }
        CallTransport.SIP -> {
            val key = accountId.ifBlank { label }
            val variant = if (key.isNotBlank()) (key.hashCode() and 1) else 0
            if (variant == 1) {
                if (isDark) Color(0xFFCE93D8) else Color(0xFF8E24AA)
            } else {
                if (isDark) Color(0xFFA5B4FC) else Color(0xFF4F46E5)
            }
        }
    }
}

@Composable
fun CompactLineBadge(
    label: String,
    transport: CallTransport,
    accountId: String = "",
    modifier: Modifier = Modifier
) {
    val accent = callLineAccentColor(transport = transport, label = label, accountId = accountId)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(5.dp),
        color = accent.copy(alpha = 0.11f),
        border = BorderStroke(0.5.dp, accent.copy(alpha = 0.25f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CallLogItem(
    entry: CallLogEntry,
    count: Int = 1,
    account: SipAccount?,
    contact: Contact?,
    onClick: () -> Unit,
    onCall: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAddToContact: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val viaLabel = entry.lineLabel.ifBlank {
        account?.label?.ifBlank { account.domain }
            ?: if (entry.transport == com.ipdial.data.model.CallTransport.SIM) "SIM" else "SIP"
    }
    val callerName = contact?.name ?: cleanDisplayName(entry.remoteDisplayName, entry.remoteUri)
    val displayNameWithCount = if (count > 1) "$callerName ($count)" else callerName
    val timeStr   = formatTime(entry.timestampMs)
    val isGlass = LocalGlassMode.current != GlassMode.None

    Surface(
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .then(if (isGlass) Modifier.glass(RoundedCornerShape(12.dp)) else Modifier)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { expanded = true }
                )
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ContactAvatar(
                name = callerName,
                photoUri = contact?.photoUri,
                size = 44.dp,
                backgroundColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            )

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayNameWithCount,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (entry.missed)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = when {
                            entry.missed                           -> Icons.AutoMirrored.Filled.CallMissed
                            entry.direction == CallDirection.INCOMING -> Icons.AutoMirrored.Filled.CallReceived
                            else                                   -> Icons.AutoMirrored.Filled.CallMade
                        },
                        contentDescription = null,
                        tint = callDirectionColor(entry),
                        modifier = Modifier.size(14.dp)
                    )
                    CompactLineBadge(
                        label = viaLabel,
                        transport = entry.transport,
                        accountId = entry.accountId,
                        modifier = Modifier.widthIn(max = 120.dp)
                    )
                    val durationSuffix = formatHistoryDurationSuffix(entry.missed, entry.durationSeconds)
                    Text(
                        text = "$timeStr$durationSuffix",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (entry.isHdAudio) {
                        CompactHdBadge()
                    }
                }
            }

            IconButton(onClick = onCall) {
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = "Call back",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("Send message") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Sms,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = {
                    expanded = false
                    launchSmsIntent(context, entry.remoteUri)
                }
            )
            DropdownMenuItem(
                text = { Text("Copy number") },
                onClick = { expanded = false; onCopy() }
            )
            if (contact == null) {
                DropdownMenuItem(
                    text = { Text("Add to contact") },
                    onClick = {
                        expanded = false
                        if (onAddToContact != null) {
                            onAddToContact()
                        } else {
                            val number = cleanUri(entry.remoteUri).filter { it.isDigit() || it == '+' }
                            val intent = Intent(Intent.ACTION_INSERT).apply {
                                type = ContactsContract.Contacts.CONTENT_TYPE
                                putExtra(ContactsContract.Intents.Insert.PHONE, number)
                            }
                            try {
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(context, "Could not open contacts app", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }
            DropdownMenuItem(
                text = { Text("Edit before call") },
                onClick = { expanded = false; onEdit() }
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                onClick = { expanded = false; onDelete() }
            )
        }
    }
}

@Composable
fun EmptyLogPrompt() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 80.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Call,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "No recent calls",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Dial any number or add a SIP account in Settings",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

fun formatTime(ms: Long): String {
    val now = System.currentTimeMillis()
    val diffMin = (now - ms) / 60_000
    return when {
        diffMin < 1   -> "Just now"
        diffMin < 60  -> "$diffMin min ago"
        else          -> SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))
    }
}

private enum class HistoryDialogFilter { ALL, MISSED, INCOMING, OUTGOING }

@Composable
fun CallHistoryDetailDialog(
    selectedEntry: CallLogEntry,
    allEntries: List<CallLogEntry>,
    contact: Contact?,
    onCall: () -> Unit,
    onOpenContactDetails: (String) -> Unit = {},
    onOpenStats: (String, String?, List<CallLogEntry>) -> Unit = { _, _, _ -> },
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val isGlass = LocalGlassMode.current != GlassMode.None
    val cleanNumber = cleanUri(selectedEntry.remoteUri)
    val displayName = contact?.name?.takeIf { it.isNotBlank() }
        ?: cleanDisplayName(selectedEntry.remoteDisplayName, selectedEntry.remoteUri).ifBlank { cleanNumber }

    val matchingHistory = remember(allEntries, selectedEntry, contact) {
        filterHistoryForTarget(allEntries, selectedEntry.remoteUri, contact)
    }

    var activeFilter by remember { mutableStateOf(HistoryDialogFilter.ALL) }
    val missedCount = remember(matchingHistory) { matchingHistory.count { it.missed } }
    val incomingCount = remember(matchingHistory) {
        matchingHistory.count { !it.missed && it.direction == CallDirection.INCOMING }
    }
    val outgoingCount = remember(matchingHistory) {
        matchingHistory.count { !it.missed && it.direction == CallDirection.OUTGOING }
    }

    val visibleHistory = remember(matchingHistory, activeFilter) {
        when (activeFilter) {
            HistoryDialogFilter.ALL -> matchingHistory
            HistoryDialogFilter.MISSED -> matchingHistory.filter { it.missed }
            HistoryDialogFilter.INCOMING -> matchingHistory.filter { !it.missed && it.direction == CallDirection.INCOMING }
            HistoryDialogFilter.OUTGOING -> matchingHistory.filter { !it.missed && it.direction == CallDirection.OUTGOING }
        }
    }

    val currentYear = remember { Calendar.getInstance().get(Calendar.YEAR) }
    val sameYearFmt = remember(locale) { SimpleDateFormat("MMM d, h:mm a", locale) }
    val otherYearFmt = remember(locale) { SimpleDateFormat("MMM d, yyyy • h:mm a", locale) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            tonalElevation = if (isGlass) 0.dp else 6.dp,
            color = if (isGlass) MaterialTheme.colorScheme.surface.copy(alpha = 0.92f) else MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 24.dp)
                .widthIn(max = 420.dp)
                .then(if (isGlass) Modifier.glass(RoundedCornerShape(26.dp)) else Modifier)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp, start = 16.dp, end = 16.dp, bottom = 10.dp)
            ) {
                // Clickable Contact / Caller Name Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .then(
                            if (contact != null) {
                                Modifier.clickable {
                                    onDismiss()
                                    onOpenContactDetails(contact.id)
                                }
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = 4.dp, vertical = 6.dp)
                        .testTag("history_dialog_contact_header"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ContactAvatar(
                        name = displayName,
                        photoUri = contact?.photoUri,
                        size = 44.dp,
                        backgroundColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (displayName != cleanNumber && cleanNumber.isNotBlank()) {
                            Text(
                                text = cleanNumber,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (contact != null) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Open contact details",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Filter Chips for All / Missed / In / Out
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = activeFilter == HistoryDialogFilter.ALL,
                        onClick = { activeFilter = HistoryDialogFilter.ALL },
                        label = { Text("All (${matchingHistory.size})", maxLines = 1, softWrap = false) }
                    )
                    FilterChip(
                        selected = activeFilter == HistoryDialogFilter.MISSED,
                        onClick = { activeFilter = HistoryDialogFilter.MISSED },
                        label = { Text("Missed ($missedCount)", maxLines = 1, softWrap = false) }
                    )
                    FilterChip(
                        selected = activeFilter == HistoryDialogFilter.INCOMING,
                        onClick = { activeFilter = HistoryDialogFilter.INCOMING },
                        label = { Text("In ($incomingCount)", maxLines = 1, softWrap = false) }
                    )
                    FilterChip(
                        selected = activeFilter == HistoryDialogFilter.OUTGOING,
                        onClick = { activeFilter = HistoryDialogFilter.OUTGOING },
                        label = { Text("Out ($outgoingCount)", maxLines = 1, softWrap = false) }
                    )
                }

                Spacer(Modifier.height(6.dp))

                // All-Time Call History List
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 320.dp)
                ) {
                    if (visibleHistory.isEmpty()) {
                        Text(
                            text = "No calls found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(vertical = 24.dp)
                        )
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(visibleHistory, key = { it.id }) { entry ->
                                val entryYear = Calendar.getInstance().apply {
                                    timeInMillis = entry.timestampMs
                                }.get(Calendar.YEAR)
                                val dateStr = if (entryYear == currentYear) {
                                    sameYearFmt.format(Date(entry.timestampMs))
                                } else {
                                    otherYearFmt.format(Date(entry.timestampMs))
                                }
                                val callTypeText = when {
                                    entry.missed -> "Missed"
                                    entry.direction == CallDirection.INCOMING -> "Incoming"
                                    else -> "Outgoing"
                                }
                                val viaLabel = entry.lineLabel.ifBlank {
                                    if (entry.transport == CallTransport.SIM) "SIM" else "SIP"
                                }
                                val durationStr = formatHistoryDurationSuffix(entry.missed, entry.durationSeconds)
                                val entryNumber = cleanUri(entry.remoteUri)
                                val numberSuffix = if ((contact?.numbers?.size ?: 0) > 1 && entryNumber.isNotBlank()) {
                                    " • $entryNumber"
                                } else {
                                    ""
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = when {
                                            entry.missed -> Icons.AutoMirrored.Filled.CallMissed
                                            entry.direction == CallDirection.INCOMING -> Icons.AutoMirrored.Filled.CallReceived
                                            else -> Icons.AutoMirrored.Filled.CallMade
                                        },
                                        contentDescription = callTypeText,
                                        tint = callDirectionColor(entry),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = dateStr,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (entry.missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                                        )
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            CompactLineBadge(
                                                label = viaLabel,
                                                transport = entry.transport,
                                                accountId = entry.accountId,
                                                modifier = Modifier.widthIn(max = 110.dp)
                                            )
                                            Text(
                                                text = "$callTypeText$durationStr$numberSuffix",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            if (entry.isHdAudio) {
                                                CompactHdBadge()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(Modifier.height(4.dp))

                // Single-Line Action Bar: Close, [Add to contact], Stats icon, Message icon, Call icon
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("history_dialog_close_button")
                    ) {
                        Text(
                            text = "Close",
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (contact == null) {
                        TextButton(
                            onClick = {
                                val number = cleanNumber.filter { it.isDigit() || it == '+' }
                                val intent = Intent(Intent.ACTION_INSERT).apply {
                                    type = ContactsContract.Contacts.CONTENT_TYPE
                                    putExtra(ContactsContract.Intents.Insert.PHONE, number)
                                }
                                try {
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    android.widget.Toast.makeText(
                                        context,
                                        "Could not open contacts app",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                                onDismiss()
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .weight(1f, fill = false)
                                .testTag("history_dialog_add_contact_button")
                        ) {
                            Text(
                                text = "Add to contact",
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = {
                            onDismiss()
                            onOpenStats(
                                displayName,
                                if (displayName != cleanNumber) cleanNumber else "Call statistics",
                                matchingHistory
                            )
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("history_dialog_stats_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.BarChart,
                            contentDescription = "Call statistics",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            launchSmsIntent(context, cleanNumber)
                            onDismiss()
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("history_dialog_sms_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sms,
                            contentDescription = "Send message",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            onCall()
                            onDismiss()
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("history_dialog_call_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = "Call",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun launchSmsIntent(context: android.content.Context, rawNumber: String) {
    val target = cleanUri(rawNumber).trim()
    if (target.isBlank()) {
        android.widget.Toast.makeText(context, "No valid number for messaging", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val intent = Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:${android.net.Uri.encode(target)}"))
        context.startActivity(intent)
    } catch (_: Exception) {
        android.widget.Toast.makeText(context, "No messaging app found", android.widget.Toast.LENGTH_SHORT).show()
    }
}
