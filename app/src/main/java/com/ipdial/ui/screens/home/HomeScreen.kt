package com.ipdial.ui.screens.home

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.ipdial.data.model.CallLogEntry
import com.ipdial.data.model.Contact
import com.ipdial.ui.SipViewModel
import com.ipdial.ui.components.AccountSelectionDialog
import com.ipdial.ui.components.ContactItem
import com.ipdial.ui.components.NumberPickerDialog
import com.ipdial.ui.components.StartIoBanner
import com.ipdial.ui.screens.cleanUri
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

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
    val favoriteContacts by vm.favoriteContacts.collectAsState()
    
    var activeContactForNumberPicker by remember { mutableStateOf<Contact?>(null) }
    var activeHistoryEntryForDetail by remember { mutableStateOf<CallLogEntry?>(null) }

    val locale = LocalConfiguration.current.locales[0]
    
    // O(1) map for contact lookup by phone numbers (multiple suffix lengths for cross-format matching)
    val contactLookupMap = remember(contactsState) {
        val suffixLens = intArrayOf(10, 11, 12, 13)
        val map = mutableMapOf<String, Contact>()
        contactsState.forEach { contact ->
            contact.numbers.forEach { num ->
                val cleaned = num.filter { it.isDigit() }
                if (cleaned.isNotEmpty()) {
                    map[cleaned] = contact
                    for (len in suffixLens) {
                        if (len < cleaned.length) {
                            map[cleaned.takeLast(len)] = contact
                        }
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
                val contactId = if (cleanNumber.length >= 3) {
                    val match = contactLookupMap[cleanNumber]
                        ?: (10..13).mapNotNull { l -> if (l < cleanNumber.length) contactLookupMap[cleanNumber.takeLast(l)] else null }.firstOrNull()
                    match?.id ?: cleanNumber.takeLast(maxOf(3, minOf(10, cleanNumber.length)))
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

    val showAd by vm.showAd.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
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
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            if (searchQuery.isBlank() && favoriteContacts.isNotEmpty()) {
                item {
                    Text(
                        text = "Favorites",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
                    )
                }
                item {
                    FavoriteContactsRow(
                        favorites = favoriteContacts,
                        onCall = { contact ->
                            if (contact.numbers.size == 1) {
                                vm.makeCall(contact.numbers.first())
                            } else {
                                activeContactForNumberPicker = contact
                            }
                        },
                        onRemove = { vm.toggleContactFavorite(it) }
                    )
                }
            }

            if (grouped.isEmpty() && searchQuery.isBlank() && favoriteContacts.isEmpty()) {
                item { EmptyLogPrompt() }
            } else if (showSearchContactsInHistory) {
                if (searchContacts.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                            Text("No matches found", color = MaterialTheme.colorScheme.outline)                        }
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
                                if (contact.numbers.size > 1) {
                                    activeContactForNumberPicker = contact
                                } else {
                                    contact.numbers.firstOrNull()?.let { vm.makeCall(it) }
                                }
                            },
                            onToggleFavorite = { vm.toggleContactFavorite(it) }
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
                            else contactLookupMap[cleanNumber]
                                ?: (10..13).mapNotNull { l -> if (l < cleanNumber.length) contactLookupMap[cleanNumber.takeLast(l)] else null }.firstOrNull()
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

        if (showAd) {
            StartIoBanner(
                vm = vm,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            )
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
    val balances by vm.balances.collectAsState()
    val enabledAccounts = remember(accounts) {
        accounts.filter { it.isEnabled }
    }

    if (showAccountSelection && enabledAccounts.isNotEmpty()) {
        AccountSelectionDialog(
            enabledAccounts = enabledAccounts,
            balances = balances,
            onAccountSelected = { vm.proceedWithCallAfterAccountSelection(it) },
            onDismiss = { vm.dismissAccountSelection() }
        )
    }

    activeHistoryEntryForDetail?.let { entry ->
        val cleanNumber = cleanUri(entry.remoteUri).filter { it.isDigit() }
        val contact = remember(cleanNumber, contactLookupMap) {
            if (cleanNumber.isEmpty()) null
            else contactLookupMap[cleanNumber]
                ?: (10..13).mapNotNull { l -> if (l < cleanNumber.length) contactLookupMap[cleanNumber.takeLast(l)] else null }.firstOrNull()
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
