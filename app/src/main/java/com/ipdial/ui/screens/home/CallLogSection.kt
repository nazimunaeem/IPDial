package com.ipdial.ui.screens.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ipdial.data.model.CallDirection
import com.ipdial.data.model.CallLogEntry
import com.ipdial.data.model.Contact
import com.ipdial.data.model.SipAccount
import com.ipdial.ui.components.ContactAvatar
import com.ipdial.ui.screens.cleanDisplayName
import com.ipdial.ui.screens.cleanUri
import com.ipdial.ui.screens.call.formatDuration
import com.ipdial.ui.theme.GlassMode
import com.ipdial.ui.theme.LocalGlassMode
import com.ipdial.ui.theme.glass
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogGroup(
    val mainEntry: CallLogEntry,
    val count: Int,
    val allEntries: List<CallLogEntry>
)

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
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val viaLabel  = account?.label?.ifBlank { account.domain } ?: "SIP"
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
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = when {
                            entry.missed                           -> Icons.AutoMirrored.Filled.CallMissed
                            entry.direction == CallDirection.INCOMING -> Icons.AutoMirrored.Filled.CallReceived
                            else                                   -> Icons.AutoMirrored.Filled.CallMade
                        },
                        contentDescription = null,
                        tint = when {
                            entry.missed -> MaterialTheme.colorScheme.error
                            else         -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(14.dp)
                    )
                    val durationSuffix = if (entry.missed) "" else " • ${formatDuration(entry.durationSeconds)}"
                    Text(
                        text = "$viaLabel • $timeStr$durationSuffix",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                text = { Text("Copy number") },
                onClick = { expanded = false; onCopy() }
            )
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
                "Add a SIP account in Settings to get started",
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

@Composable
fun CallHistoryDetailDialog(
    selectedEntry: CallLogEntry,
    allEntries: List<CallLogEntry>,
    contact: Contact?,
    onCall: () -> Unit,
    onDismiss: () -> Unit
) {
    val locale = LocalConfiguration.current.locales[0]
    val cleanNumber = cleanUri(selectedEntry.remoteUri)
    val displayName = contact?.name ?: selectedEntry.remoteDisplayName.ifBlank { cleanNumber }

    // Filter calls in the last 7 days for this number
    val sevenDaysAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
    val filteredHistory = remember(allEntries, selectedEntry) {
        allEntries.filter {
            cleanUri(it.remoteUri) == cleanNumber && it.timestampMs >= sevenDaysAgo
        }.sortedByDescending { it.timestampMs }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ContactAvatar(
                    name = displayName,
                    photoUri = contact?.photoUri,
                    size = 40.dp,
                    backgroundColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Column {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (displayName != cleanNumber) {
                        Text(
                            text = cleanNumber,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
            ) {
                Text(
                    text = "Calls in the last 7 days",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                if (filteredHistory.isEmpty()) {
                    Text(
                        text = "No calls found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredHistory) { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = when {
                                        entry.missed                           -> Icons.AutoMirrored.Filled.CallMissed
                                        entry.direction == CallDirection.INCOMING -> Icons.AutoMirrored.Filled.CallReceived
                                        else                                   -> Icons.AutoMirrored.Filled.CallMade
                                    },
                                    contentDescription = null,
                                    tint = when {
                                        entry.missed -> MaterialTheme.colorScheme.error
                                        else         -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    val dateStr = SimpleDateFormat("MMM d, h:mm a", locale).format(Date(entry.timestampMs))
                                    Text(
                                        text = dateStr,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    val callTypeText = when {
                                        entry.missed -> "Missed"
                                        entry.direction == CallDirection.INCOMING -> "Incoming"
                                        else -> "Outgoing"
                                    }
                                    val durationStr = if (entry.missed) "" else " (${formatDuration(entry.durationSeconds)})"
                                    Text(
                                        text = "$callTypeText$durationStr",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onCall()
                    onDismiss()
                }
            ) {
                Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Call")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
