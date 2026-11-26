package com.ipdial.ui.screens.home

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ipdial.data.model.CallLogEntry
import com.ipdial.data.model.Contact
import com.ipdial.data.model.SipAccount
import com.ipdial.ui.SipViewModel
import com.ipdial.ui.components.CallStatsDialog
import com.ipdial.ui.components.ContactItem
import com.ipdial.ui.components.NumberPickerDialog
import com.ipdial.ui.screens.cleanUri
import com.ipdial.ui.theme.GlassMode
import com.ipdial.ui.theme.LocalGlassMode
import com.ipdial.ui.theme.glass
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private data class StatsModalRequest(
    val title: String,
    val subtitle: String?,
    val entries: List<CallLogEntry>,
    val isUniversal: Boolean
)

@Composable
fun HomeScreen(
    vm: SipViewModel, 
    onOpenDrawer: () -> Unit,
    onNavigateToAccounts: () -> Unit = {},
    onEditBeforeCall: (String) -> Unit = {},
    onOpenContactDetails: (String) -> Unit = {},
    onOpenStats: ((contactId: String, number: String, title: String) -> Unit)? = null
) {
    val accounts  by vm.accounts.collectAsState()
    val callLog   by vm.callLog.collectAsState()
    val searchQuery by vm.searchQuery.collectAsState()
    val contactsState by vm.contacts.collectAsState()
    val favoriteContacts by vm.favoriteContacts.collectAsState()
    val groupSubsequentCalls by vm.groupSubsequentCalls.collectAsState()
    
    var activeContactForNumberPicker by remember { mutableStateOf<Contact?>(null) }
    var activeHistoryEntryForDetail by remember { mutableStateOf<CallLogEntry?>(null) }
    var activeStatsRequest by remember { mutableStateOf<StatsModalRequest?>(null) }

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

    val grouped = remember(filteredLog, contactLookupMap, groupSubsequentCalls) {
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
            if (!groupSubsequentCalls) {
                return@mapValues dayEntries.map { LogGroup(it, 1, listOf(it)) }
            }
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

    val isWide = LocalConfiguration.current.screenWidthDp >= 600

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val isGlass = LocalGlassMode.current != GlassMode.None
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SearchBarRow(
                query = searchQuery,
                onQueryChange = { vm.onSearchQueryChanged(it) },
                modifier = Modifier.weight(1f)
            )
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .size(48.dp)
                    .then(if (isGlass) Modifier.glass(RoundedCornerShape(24.dp)) else Modifier)
            ) {
                IconButton(
                    onClick = {
                        if (onOpenStats != null) {
                            onOpenStats("", "", "Call Statistics")
                        } else {
                            activeStatsRequest = StatsModalRequest(
                                title = "Call Statistics",
                                subtitle = null,
                                entries = callLog,
                                isUniversal = true
                            )
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("home_universal_stats_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.BarChart,
                        contentDescription = "Call statistics",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        val context = LocalContext.current
        var isDefaultDialer by remember {
            mutableStateOf(com.ipdial.service.sim.DefaultDialerHelper.isDefaultDialer(context))
        }
        var canUseFullScreenIntent by remember {
            mutableStateOf(com.ipdial.service.IncomingCallPresentationHelper.canUseFullScreenIntent(context))
        }
        val defaultDialerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) {
            isDefaultDialer = com.ipdial.service.sim.DefaultDialerHelper.isDefaultDialer(context)
            canUseFullScreenIntent = com.ipdial.service.IncomingCallPresentationHelper.canUseFullScreenIntent(context)
            vm.refreshSimAccounts()
            vm.refreshCallHistory()
        }
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    isDefaultDialer = com.ipdial.service.sim.DefaultDialerHelper.isDefaultDialer(context)
                    canUseFullScreenIntent = com.ipdial.service.IncomingCallPresentationHelper.canUseFullScreenIntent(context)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        val hasSipAccount = remember(accounts) { accounts.any { it.isEnabled } }
        if (!hasSipAccount) {
            SipSetupBanner(onSetup = onNavigateToAccounts)
        } else if (!canUseFullScreenIntent) {
            FullScreenIntentPermissionBanner(
                onAllow = {
                    val intent = com.ipdial.service.IncomingCallPresentationHelper.createManageFullScreenIntentSettingsIntent(context)
                    if (intent != null) {
                        try {
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            Toast.makeText(context, "Open App Info → Notifications → Full screen notifications", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            )
        } else if (!isDefaultDialer) {
            DefaultDialerBanner(
                onSetDefault = {
                    val intent = com.ipdial.service.sim.DefaultDialerHelper.createRequestDefaultDialerIntent(context)
                    if (intent != null) {
                        try {
                            defaultDialerLauncher.launch(intent)
                        } catch (_: Exception) {
                            Toast.makeText(context, "Could not open Default Phone App picker", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        }

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
            onOpenContactDetails = onOpenContactDetails,
            onOpenStats = { title, subtitle, entries ->
                if (onOpenStats != null) {
                    onOpenStats(
                        contact?.id.orEmpty(),
                        cleanUri(entry.remoteUri),
                        "$title · Stats"
                    )
                } else {
                    activeStatsRequest = StatsModalRequest(
                        title = title,
                        subtitle = subtitle,
                        entries = entries,
                        isUniversal = false
                    )
                }
            },
            onDismiss = { activeHistoryEntryForDetail = null }
        )
    }

    activeStatsRequest?.let { statsReq ->
        CallStatsDialog(
            title = statsReq.title,
            subtitle = statsReq.subtitle,
            entries = statsReq.entries,
            contacts = contactsState,
            isUniversal = statsReq.isUniversal,
            onDismiss = { activeStatsRequest = null }
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

@Composable
private fun SipSetupBanner(onSetup: () -> Unit) {
    var dismissed by remember { mutableStateOf(false) }
    if (dismissed) return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        )
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Set up an IP/SIP account",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "You can setup from sip accounts menu later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = {
                dismissed = true
                onSetup()
            }) {
                Text("Setup")
            }
            IconButton(
                onClick = { dismissed = true },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun DefaultDialerBanner(onSetDefault: () -> Unit) {
    var dismissed by remember { mutableStateOf(false) }
    if (dismissed) return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
        )
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Set DialDuo as Default Phone App",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Enables native SIM call screen and full device call history.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = {
                dismissed = true
                onSetDefault()
            }) {
                Text("Set Default")
            }
            IconButton(
                onClick = { dismissed = true },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun FullScreenIntentPermissionBanner(onAllow: () -> Unit) {
    var dismissed by remember { mutableStateOf(false) }
    if (dismissed) return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
        )
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Allow Lock-Screen Incoming Calls",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Enable full-screen notifications so calls wake your locked screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                onClick = onAllow,
                modifier = Modifier.testTag("home_allow_full_screen_button")
            ) {
                Text("Allow")
            }
            IconButton(
                onClick = { dismissed = true },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
