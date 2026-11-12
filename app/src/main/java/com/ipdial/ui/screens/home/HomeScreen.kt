package com.ipdial.ui.screens.home

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import com.ipdial.data.model.SipAccount
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
        val digitsQuery = searchQuery.filter { it.isDigit() }
        callLog.filter { entry ->
            val matchesSearch = searchQuery.isBlank() || 
                entry.remoteDisplayName.contains(searchQuery, ignoreCase = true) || 
                entry.remoteUri.contains(searchQuery, ignoreCase = true) ||
                (digitsQuery.isNotEmpty() && cleanUri(entry.remoteUri).filter { it.isDigit() }.contains(digitsQuery))
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

    val isWide = LocalConfiguration.current.screenWidthDp >= 600

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
            else {
                val digitsQuery = searchQuery.filter { it.isDigit() }
                contactsState.filter {
                    it.name.contains(searchQuery, ignoreCase = true) ||
                    it.numbers.any { num -> 
                        num.contains(searchQuery, ignoreCase = true) ||
                        (digitsQuery.isNotEmpty() && num.filter { d -> d.isDigit() }.contains(digitsQuery))
                    }
                }.sortedBy { it.name.trim().lowercase() }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val historyListState = rememberLazyListState()

            val showSearchContactsInHistory = remember(searchQuery, filteredLog) {
                searchQuery.isNotBlank() && filteredLog.isEmpty()
            }

            val searchContacts = remember(contactsState, searchQuery, showSearchContactsInHistory) {
                if (!showSearchContactsInHistory) emptyList()
                else {
                    val digitsQuery = searchQuery.filter { it.isDigit() }
                    contactsState.filter {
                        it.name.contains(searchQuery, ignoreCase = true) ||
                        it.numbers.any { num -> 
                            num.contains(searchQuery, ignoreCase = true) ||
                            (digitsQuery.isNotEmpty() && num.filter { d -> d.isDigit() }.contains(digitsQuery))
                        }
                    }.sortedBy { it.name.trim().lowercase() }
                }
            }

            if (isWide && searchQuery.isBlank()) {
                // Landscape two-pane: favorites on the left, call log on the right
                Row(Modifier.fillMaxSize()) {
                    if (favoriteContacts.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = "Favorites",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
                            )
                            Column(
                                modifier = Modifier
                                    .widthIn(max = 360.dp)
                                    .align(Alignment.CenterHorizontally)
                            ) {
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
                            favoriteContacts.forEach { contact ->
                                key(contact.id) {
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
                                        onAvatarClick = { /* Handle avatar click if needed */ },
                                        onToggleFavorite = { vm.toggleContactFavorite(it) }
                                    )
                                }
                            }
                        }

                        VerticalDivider(
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1.5f)
                            .fillMaxHeight()
                            .fillMaxWidth()
                    ) {
                        HomeLogList(
                            vm = vm,
                            state = historyListState,
                            searchQuery = searchQuery,
                            favoriteContacts = favoriteContacts,
                            grouped = grouped,
                            searchContacts = searchContacts,
                            showSearchContactsInHistory = showSearchContactsInHistory,
                            contactLookupMap = contactLookupMap,
                            accounts = accounts,
                            showFavoritesInline = false,
                            onShowNumberPicker = { activeContactForNumberPicker = it },
                            onEntryClick = { activeHistoryEntryForDetail = it },
                            onEditBeforeCall = onEditBeforeCall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 640.dp)
                                .align(Alignment.TopCenter)
                        )
                    }
                }
            } else {
                HomeLogList(
                    vm = vm,
                    state = historyListState,
                    searchQuery = searchQuery,
                    favoriteContacts = favoriteContacts,
                    grouped = grouped,
                    searchContacts = searchContacts,
                    showSearchContactsInHistory = showSearchContactsInHistory,
                    contactLookupMap = contactLookupMap,
                    accounts = accounts,
                    showFavoritesInline = true,
                    onShowNumberPicker = { activeContactForNumberPicker = it },
                    onEntryClick = { activeHistoryEntryForDetail = it },
                    onEditBeforeCall = onEditBeforeCall,
                    modifier = if (isWide) {
                        Modifier
                            .align(Alignment.TopCenter)
                            .widthIn(max = 640.dp)
                    } else {
                        Modifier.fillMaxWidth()
                    }
                )
            }
        }

        if (showAd) {
            StartIoBanner(
                vm = vm,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 76.dp)
                    .then(
                        if (isWide) {
                            Modifier
                                .align(Alignment.CenterHorizontally)
                                .widthIn(max = 640.dp)
                        } else {
                            Modifier
                        }
                    )
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

@Composable
private fun HomeLogList(
    vm: SipViewModel,
    state: androidx.compose.foundation.lazy.LazyListState,
    searchQuery: String,
    favoriteContacts: List<Contact>,
    grouped: List<Pair<String, List<LogGroup>>>,
    searchContacts: List<Contact>,
    showSearchContactsInHistory: Boolean,
    contactLookupMap: Map<String, Contact>,
    accounts: List<SipAccount>,
    showFavoritesInline: Boolean,
    onShowNumberPicker: (Contact) -> Unit,
    onEntryClick: (CallLogEntry) -> Unit,
    onEditBeforeCall: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current

    LazyColumn(
        state = state,
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 12.dp)
    ) {
        if (showFavoritesInline && searchQuery.isBlank() && favoriteContacts.isNotEmpty()) {
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
                            onShowNumberPicker(contact)
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
                            if (contact.numbers.size > 1) {
                                onShowNumberPicker(contact)
                            } else {
                                contact.numbers.firstOrNull()?.let { vm.makeCall(it) }
                            }
                        },
                        onAvatarClick = { /* Handle avatar click if needed */ },
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
                    CallLogItem(
                        entry   = entry,
                        count   = group.count,
                        account = accounts.firstOrNull { it.id == entry.accountId },
                        contact = contact,
                        onClick = { onEntryClick(entry) },
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
