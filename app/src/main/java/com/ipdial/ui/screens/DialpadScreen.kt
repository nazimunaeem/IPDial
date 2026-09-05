@file:Suppress("OPT_IN_USAGE", "EXPERIMENTAL_API_USAGE", "EXPERIMENTAL_IS_NOT_ENABLED")
package com.ipdial.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ipdial.data.model.KeypadDesign
import com.ipdial.ui.SipViewModel
import com.ipdial.ui.components.AccountSelectionDialog
import com.ipdial.ui.components.StartIoBanner
import com.ipdial.ui.screens.dialpad.DialpadKeypad
import com.ipdial.ui.screens.dialpad.SuggestedContactRow
import com.ipdial.ui.screens.dialpad.T9_MAP
import com.ipdial.ui.theme.ForestGreen
import kotlinx.coroutines.awaitCancellation

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun DialpadScreen(
    vm: SipViewModel,
    onOpenDrawer: () -> Unit,
    onNavigateToAccounts: () -> Unit = {}
) {
    val dialTextFieldValue by vm.dialString.collectAsState()
    val dialString = dialTextFieldValue.text
    val lastDialedNumber by vm.lastDialedNumber.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val contacts by vm.contacts.collectAsState()
    val mostCalled by vm.mostCalledContacts.collectAsState()
    val keypadDesign by vm.keypadDesign.collectAsState()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val haptic = LocalHapticFeedback.current
    val isWide = configuration.screenWidthDp > 600
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    var showMenu by remember { mutableStateOf(false) }
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current

    val suggestedContacts = remember(dialString, contacts) {
        if (dialString.isBlank()) emptyList()
        else {
            val digitsOnly = dialString.filter { it.isDigit() }
            contacts.asSequence().filter { contact ->
                contact.name.contains(dialString, ignoreCase = true) ||
                contact.numbers.any { it.filter { it.isDigit() }.contains(dialString) } ||
                (digitsOnly.isNotBlank() && contact.name.lowercase().mapNotNull { T9_MAP[it] }.joinToString("").contains(digitsOnly))
            }.take(5).toList()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            // In landscape the screen is short; allow the whole dialpad column to
            // scroll so the keypad and call button are always reachable.
            .then(if (isLandscape) Modifier.verticalScroll(rememberScrollState()) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        // Suggested contacts space stays at least one stable viewport row high so
        // the keypad does not jump upward while contacts are loading or unmatched.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (isLandscape) Modifier.height(96.dp)
                    else Modifier.weight(1f, fill = false)
                )
                .heightIn(min = if (isLandscape) 96.dp else 120.dp)
                .padding(top = 4.dp)
                .clipToBounds()
        ) {
            // In landscape the parent Column uses verticalScroll, so we must NOT use
            // LazyColumn here (nested scrollables cause infinite-height-constraint crash).
            // Use a plain Column — the list is capped at 5 items so lazy rendering is unnecessary.
            // The list scrolls within this box so items never spill onto (or hide under)
            // the keypad/digit row.
            if (dialString.isEmpty() && mostCalled.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "Most Called",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                    )
                    mostCalled.forEach { contact ->
                        key(contact.id) {
                            SuggestedContactRow(contact) { num ->
                                vm.clearDial()
                                num.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }.forEach { vm.dialPad(it) }
                                vm.makeCall()
                            }
                        }
                    }
                }
            } else if (suggestedContacts.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                ) {
                    suggestedContacts.forEach { contact ->
                        key(contact.id) {
                            SuggestedContactRow(contact) { num ->
                                vm.clearDial()
                                num.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }.forEach { vm.dialPad(it) }
                                vm.makeCall()
                            }
                        }
                    }
                }
            } else if (dialString.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "No matching contacts",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }

        // Ad above digit box
        val showAd by vm.showAd.collectAsState()
        if (showAd) {
            Box(Modifier.height(90.dp).fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                StartIoBanner(
                    vm = vm,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Dial display row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    if (clipboardManager.hasText()) {
                        DropdownMenuItem(
                            text = { Text("Paste") },
                            onClick = {
                                showMenu = false
                                clipboardManager.getText()?.text?.let { text ->
                                    text.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }.forEach { vm.dialPad(it) }
                                }
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Add to contact") },
                        onClick = {
                            showMenu = false
                            val intent = android.content.Intent(android.content.Intent.ACTION_INSERT).apply {
                                type = android.provider.ContactsContract.Contacts.CONTENT_TYPE
                                putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, dialString)
                            }
                            context.startActivity(intent)
                        }
                    )
                }
            }

            InterceptPlatformTextInput(
                interceptor = { _, _ -> awaitCancellation() }
            ) {
                BasicTextField(
                    value = dialTextFieldValue,
                    onValueChange = {
                        vm.setDialString(it.copy(text = it.text.filter { c -> c.isDigit() || c == '+' || c == '*' || c == '#' }))
                    },
                    visualTransformation = PhoneNumberTransformation(),
                    textStyle = MaterialTheme.typography.headlineMedium.copy(
                        fontSize = if (isWide) 36.sp else 28.sp,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    maxLines = 1,
                    singleLine = true,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                )
            }

            AnimatedVisibility(visible = dialString.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .combinedClickable(
                            onClick = { vm.backspace() },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                vm.clearDial()
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Backspace,
                        contentDescription = "Backspace",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (dialString.isEmpty()) Spacer(Modifier.size(48.dp))
        }

        Spacer(Modifier.height(8.dp))

        // Keypad grid
        val keys = listOf(
            Triple("1", "⠀", null),
            Triple("2", "ABC", null),
            Triple("3", "DEF", null),
            Triple("4", "GHI", null),
            Triple("5", "JKL", null),
            Triple("6", "MNO", null),
            Triple("7", "PQRS", null),
            Triple("8", "TUV", null),
            Triple("9", "WXYZ", null),
            Triple("*", "", null),
            Triple("0", "+", null),
            Triple("#", "", null),
        )

        val callAction = {
            if (dialString.isEmpty() && !lastDialedNumber.isNullOrEmpty()) {
                vm.setDialString(androidx.compose.ui.text.input.TextFieldValue(lastDialedNumber!!))
            } else if (dialString.isNotEmpty()) {
                vm.makeCall()
            }
        }

        DialpadKeypad(
            keys = keys,
            design = keypadDesign,
            onKeyPress = { vm.dialPad(it) },
            onZeroLongPress = { vm.dialPad('+') },
            onCallClick = if (keypadDesign == KeypadDesign.Ring) callAction else null
        )

        if (keypadDesign != KeypadDesign.Ring) {
            Spacer(Modifier.height(if (isLandscape) 9.dp else 13.dp))

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .width(if (isWide) 220.dp else 150.dp)
                    .height(if (isWide) 72.dp else 62.dp)
                    .clip(CircleShape)
                    .background(ForestGreen)
                    .clickableWithRipple(onClick = callAction)
            ) {
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = "Call",
                    tint = Color.White,
                    modifier = Modifier.size(if (isWide) 34.dp else 28.dp)
                )
            }
        }

        // Reserve the pill height, its bottom margin, and an 8dp gap above it.
        // navigationBarsPadding() moves this whole layout with system navigation.
        Spacer(Modifier.height(66.dp))
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
