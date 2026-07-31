@file:OptIn(ExperimentalMaterial3Api::class)
package com.ipdial.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ipdial.data.model.Contact
import com.ipdial.ui.AccountSelectionDialog
import com.ipdial.ui.ContactItem
import com.ipdial.ui.NumberPickerDialog
import com.ipdial.ui.SipViewModel
import com.ipdial.ui.theme.LocalGlassMode
import kotlinx.coroutines.launch

@Composable
private fun EmptyState(message: String, icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.Info) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    vm: SipViewModel,
    onOpenDrawer: () -> Unit,
    onNavigateToAccounts: () -> Unit = {}
) {
    val groupedContacts by vm.groupedContacts.collectAsState()
    val searchQuery by vm.searchQuery.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val context = LocalContext.current
    var activeContactForNumberPicker by remember { mutableStateOf<Contact?>(null) }
    var searchActive by remember { mutableStateOf(false) }
    var favoritesOnly by remember { mutableStateOf(false) }

    // Filter main's grouped (already search-filtered) contacts down to
    // favorites when the Favorites tab is selected.
    val visibleEntries: List<Pair<Char, List<Contact>>> = remember(groupedContacts, favoritesOnly) {
        if (!favoritesOnly) {
            groupedContacts.map { it.key to it.value }
        } else {
            groupedContacts.mapNotNull { (letter, contacts) ->
                val favs = contacts.filter { it.isFavorite }
                if (favs.isEmpty()) null else letter to favs
            }
        }
    }


    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val isGlass = LocalGlassMode.current != com.ipdial.ui.theme.GlassMode.None

    // Build letter → first index map for the indexer (over the visible entries
    // so the index stays in sync with the All/Favorites filter).
    val allEntries = remember(visibleEntries) { visibleEntries }
    val letterToFirstIndex = remember(visibleEntries) {
        val map = mutableMapOf<Char, Int>()
        var idx = 0
        for ((letter, contacts) in allEntries) {
            map[letter] = idx
            idx += 1 + contacts.size
        }
        map
    }
    val alphabet = remember { ('A'..'Z').toList() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        SearchBarRow(
            query = searchQuery,
            onQueryChange = { vm.onSearchQueryChanged(it) },
            placeholder = "Search contacts..."
        )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FavoriteFilterTab(
                    label = "All",
                    selected = !favoritesOnly,
                    onClick = { favoritesOnly = false }
                )
                FavoriteFilterTab(
                    label = "Favorites",
                    selected = favoritesOnly,
                    onClick = { favoritesOnly = true }
                )
            }

            if (visibleEntries.isEmpty()) {
                EmptyState(
                    message = if (searchQuery.isNotBlank()) "No contacts matching \"$searchQuery\""
                    else if (favoritesOnly) "No favorite contacts"
                    else "No contacts found",
                    icon = Icons.Default.Search
                )
            } else {
                Row(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        allEntries.forEach { (letter, contacts) ->
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (isGlass) Color.Transparent
                                            else MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                                        )
                                        .padding(horizontal = 16.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = letter.toString(),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            itemsIndexed(contacts, key = { _, c -> c.id }) { _, contact ->
                                ContactItem(
                                    contact = contact,
                                    onNumberClick = { vm.makeCall(it) },
                                    onContactClick = {
                                        if (contact.numbers.size > 1) {
                                            activeContactForNumberPicker = contact
                                        } else {
                                            contact.numbers.firstOrNull()?.let { vm.makeCall(it) }
                                        }
                                    },
                                    onToggleFavorite = { vm.toggleContactFavorite(it) },
                                    isGlass = isGlass
                                )
                            }
                        }
                    }

                    // AlphabetIndexer
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(28.dp)
                            .padding(end = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            verticalArrangement = Arrangement.SpaceEvenly,
                            modifier = Modifier.fillMaxHeight()
                        ) {
                            alphabet.forEach { letter ->
                                val hasLetter = letterToFirstIndex.containsKey(letter)
                                Text(
                                    text = letter.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (hasLetter) FontWeight.Bold else FontWeight.Light,
                                    color = if (hasLetter) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                    modifier = Modifier
                                        .semantics {
                                            role = Role.Button
                                            contentDescription = "Jump to $letter"
                                        }
                                        .clickable(enabled = hasLetter) {
                                            letterToFirstIndex[letter]?.let { index ->
                                                coroutineScope.launch {
                                                    listState.animateScrollToItem(index)
                                                }
                                            }
                                        }
                                        .padding(vertical = 1.dp)
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
}

@Composable
private fun FavoriteFilterTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickableWithRipple { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (label == "Favorites") {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.onPrimary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
