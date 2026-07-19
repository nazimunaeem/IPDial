package com.ipdial.ui.screens

import android.content.Intent
import android.telecom.TelecomManager
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ipdial.data.model.CallDirection
import com.ipdial.data.model.CallLogEntry
import com.ipdial.data.model.Contact
import com.ipdial.data.model.SipAccount
import com.ipdial.ui.AccountSelectionDialog
import com.ipdial.ui.IPDialTopBar
import com.ipdial.ui.NumberPickerDialog
import com.ipdial.ui.SipViewModel
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
fun HomeScreen(
    vm: SipViewModel, 
    onOpenDrawer: () -> Unit,
    onNavigateToAccounts: () -> Unit = {},
    onEditBeforeCall: (String) -> Unit = {}
) {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val accounts  by vm.accounts.collectAsState()
    val callLog   by vm.callLog.collectAsState()
    val searchQuery by vm.searchQuery.collectAsState()
    val contactsState by vm.contacts.collectAsState()
    
    var activeContactForNumberPicker by remember { mutableStateOf<Contact?>(null) }
    var activeHistoryEntryForDetail by remember { mutableStateOf<CallLogEntry?>(null) }

    val locale = LocalConfiguration.current.locales[0]
    
    // O(1) map for contact lookup by phone numbers (exact and last 10 digits for suffix matching)
    val contactLookupMap = remember(contactsState) {
        val map = mutableMapOf<String, Contact>()
        contactsState.forEach { contact ->
            contact.numbers.forEach { num ->
                val cleaned = num.filter { it.isDigit() }
                if (cleaned.isNotEmpty()) {
                    map[cleaned] = contact
                    if (cleaned.length >= 10) {
                        map[cleaned.takeLast(10)] = contact
                    }
                }
            }
        }
        map
    }

    val filteredLog = remember(callLog, searchQuery) {
        callLog.filter { entry ->
            val matchesSearch = searchQuery.isBlank() || 
                entry.remoteDisplayName.contains(searchQuery, ignoreCase = true) || 
                entry.remoteUri.contains(searchQuery)
            matchesSearch
        }
    }

    val grouped = remember(filteredLog, contactLookupMap) {
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val yesterdayStart = todayStart - 86400000L
        val dateFormat = SimpleDateFormat("MMMM d, yyyy", locale)

        filteredLog.groupBy { entry ->
            when {
                entry.timestampMs >= todayStart -> "Today"
                entry.timestampMs >= yesterdayStart -> "Yesterday"
                else -> dateFormat.format(Date(entry.timestampMs))
            }
        }.mapValues { (_, dayEntries) ->
            val groups = mutableListOf<LogGroup>()
            val contactToGroup = mutableMapOf<String, Int>()
            
            dayEntries.forEach { entry ->
                val cleanNumber = cleanUri(entry.remoteUri).filter { it.isDigit() }
                val contactId = if (cleanNumber.length >= 10) {
                    val last10 = cleanNumber.takeLast(10)
                    contactLookupMap[last10]?.id ?: last10
                } else {
                    cleanNumber
                }
                
                val groupIndex = contactToGroup[contactId]
                if (groupIndex != null) {
                    val existingGroup = groups[groupIndex]
                    groups[groupIndex] = existingGroup.copy(
                        count = existingGroup.count + 1,
                        allEntries = existingGroup.allEntries + entry
                    )
                } else {
                    contactToGroup[contactId] = groups.size
                    groups.add(LogGroup(entry, 1, listOf(entry)))
                }
            }
            groups
        }.toList().sortedByDescending { it.second.firstOrNull()?.mainEntry?.timestampMs ?: 0L }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Bottom),
        bottomBar = {
            val isPro by vm.isPro.collectAsState()
            val showAd by vm.showAd.collectAsState()
            if (!isPro && showAd) {
                com.ipdial.ui.StartIoBanner(
                    vm = vm,
                    modifier = Modifier.fillMaxWidth().padding(8.dp)
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            IPDialTopBar(
                accounts = accounts, 
                vm = vm, 
                onOpenDrawer = onOpenDrawer,
                onAddAccount = onNavigateToAccounts
            )

            SearchBarRow(
                query = searchQuery,
                onQueryChange = { vm.onSearchQueryChanged(it) }
            )

            val historyListState = rememberLazyListState()
            
            val showSearchContactsInHistory = remember(searchQuery, filteredLog) {
                searchQuery.isNotBlank() && filteredLog.isEmpty()
            }
            
            val searchContacts = remember(contactsState, searchQuery, showSearchContactsInHistory) {
                if (!showSearchContactsInHistory) emptyList()
                else contactsState.filter {
                    it.name.contains(searchQuery, ignoreCase = true) ||
                            it.numbers.any { num -> num.contains(searchQuery) }
                }.sortedBy { it.name.trim().lowercase() }
            }

            LazyColumn(
                state = historyListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                if (grouped.isEmpty() && searchQuery.isBlank()) {
                    item { EmptyLogPrompt() }
                } else if (showSearchContactsInHistory) {
                    if (searchContacts.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                                Text("No matches found", color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    } else {
                        item {
                            Text(
                                text = "Contacts",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
                            )
                        }
                        items(searchContacts, key = { "search_" + it.id }) { contact ->
                            ContactItem(
                                contact = contact,
                                onNumberClick = { num -> vm.makeCall(num) },
                                onContactClick = {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                        data = android.net.Uri.withAppendedPath(
                                            android.provider.ContactsContract.Contacts.CONTENT_URI, 
                                            contact.id
                                        )
                                    }
                                    context.startActivity(intent)
                                }
                            )
                        }
                    }
                } else {
                    grouped.forEach { (label, entries) ->
                        item {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
                            )
                        }
                        items(entries, key = { it.mainEntry.id }) { group ->
                            val entry = group.mainEntry
                            val cleanNumber = cleanUri(entry.remoteUri).filter { it.isDigit() }
                            val contact = remember(cleanNumber, contactLookupMap) {
                                if (cleanNumber.isEmpty()) null
                                else if (cleanNumber.length >= 10) {
                                    contactLookupMap[cleanNumber.takeLast(10)]
                                } else {
                                    contactLookupMap[cleanNumber]
                                }
                            }
                            val numberToCopy = cleanUri(entry.remoteUri).filter { it.isDigit() || it == '+' }
                             CallLogRow(
                                 entry   = entry,
                                 count   = group.count,
                                 account = accounts.firstOrNull { it.id == entry.accountId },
                                 contact = contact,
                                 onClick = { activeHistoryEntryForDetail = entry },
                                 onCall  = { vm.callBack(entry) },
                                 onCopy = {
                                     clipboardManager.setText(AnnotatedString(numberToCopy))
                                     Toast.makeText(context, "Number copied", Toast.LENGTH_SHORT).show()
                                 },
                                 onEdit = { onEditBeforeCall(numberToCopy) },
                                 onDelete = { vm.deleteCallLog(entry) }
                             )
                        }
                    }
                }
            }
        }
    }

    activeContactForNumberPicker?.let { contact ->
        NumberPickerDialog(
            numbers = contact.numbers,
            onPick = { number -> vm.makeCall(number) },
            onDismiss = { activeContactForNumberPicker = null }
        )
    }

    val showAccountSelection by vm.showAccountSelectionDialog.collectAsState()
    val enabledAccounts = remember(accounts) {
        accounts.filter { it.isEnabled }
    }

    if (showAccountSelection && enabledAccounts.isNotEmpty()) {
        AccountSelectionDialog(
            enabledAccounts = enabledAccounts,
            onAccountSelected = { vm.proceedWithCallAfterAccountSelection(it) },
            onDismiss = { vm.dismissAccountSelection() }
        )
    }

    activeHistoryEntryForDetail?.let { entry ->
        val cleanNumber = cleanUri(entry.remoteUri).filter { it.isDigit() }
        val contact = remember(cleanNumber, contactLookupMap) {
            if (cleanNumber.isEmpty()) null
            else if (cleanNumber.length >= 10) {
                contactLookupMap[cleanNumber.takeLast(10)]
            } else {
                contactLookupMap[cleanNumber]
            }
        }
        CallHistoryDetailDialog(
            selectedEntry = entry,
            allEntries = callLog,
            contact = contact,
            onCall = { vm.callBack(entry) },
            onDismiss = { activeHistoryEntryForDetail = null }
        )
    }
}

@Composable
fun SearchBarRow(
    query: String,
    onQueryChange: (String) -> Unit
) {
    val isGlass = com.ipdial.ui.theme.LocalGlassMode.current != com.ipdial.ui.theme.GlassMode.None
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .then(if (isGlass) Modifier.glass(RoundedCornerShape(24.dp)) else Modifier),
        shape = RoundedCornerShape(24.dp),
        color = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.foundation.text.BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    if (query.isEmpty()) {
                        Text(
                            "Search history",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    innerTextField()
                }
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CallLogRow(
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
    val isGlass = com.ipdial.ui.theme.LocalGlassMode.current != com.ipdial.ui.theme.GlassMode.None

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
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (contact?.photoUri != null) {
                    AsyncImage(
                        model = contact.photoUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = (callerName.firstOrNull() ?: '?').uppercaseCharCompat(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

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

private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
    return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
           cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
}

private fun formatTime(ms: Long): String {
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
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    if (contact?.photoUri != null) {
                        AsyncImage(
                            model = contact.photoUri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            text = (displayName.firstOrNull() ?: '?').uppercaseCharCompat(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
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
