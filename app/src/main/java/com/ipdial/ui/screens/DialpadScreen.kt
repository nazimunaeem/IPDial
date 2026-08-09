@file:Suppress("OPT_IN_USAGE", "EXPERIMENTAL_API_USAGE", "EXPERIMENTAL_IS_NOT_ENABLED")
package com.ipdial.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.InterceptPlatformTextInput
import kotlinx.coroutines.awaitCancellation
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ipdial.data.model.Contact
import com.ipdial.data.model.KeypadDesign
import com.ipdial.ui.SipViewModel
import com.ipdial.ui.IPDialTopBar
import com.ipdial.ui.AccountSelectionDialog
import com.ipdial.ui.theme.ForestGreen
import com.ipdial.ui.theme.glass

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
    val haptic = LocalHapticFeedback.current
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
                .background(MaterialTheme.colorScheme.background),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            // Suggested contacts space - fixed weight(1f) so keypad never shifts position when typing
            Box(modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 4.dp)) {
                if (dialString.isEmpty() && mostCalled.isNotEmpty()) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item {
                            Text(
                                text = "Most Called",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                            )
                        }
                        itemsIndexed(mostCalled, key = { _, it -> it.id }) { _, contact ->
                            SuggestedContactRow(contact) { num ->
                                vm.clearDial()
                                num.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }.forEach { vm.dialPad(it) }
                                vm.makeCall()
                            }
                        }
                    }
                } else if (suggestedContacts.isNotEmpty()) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(suggestedContacts, key = { it.id }) { contact ->
                            SuggestedContactRow(contact) { num ->
                                vm.clearDial()
                                num.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }.forEach { vm.dialPad(it) }
                                vm.makeCall()
                            }
                        }
                    }
                } else if (dialString.isNotEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                    com.ipdial.ui.StartIoBanner(
                        vm = vm,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                Spacer(Modifier.height(8.dp))
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
                        fontSize = 28.sp,
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

            if (keypadDesign == KeypadDesign.Rounded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 64.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    keys.chunked(3).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            row.forEach { (digit, sub, _) ->
                                DialKeyRounded(
                                    digit = digit,
                                    subLabel = sub,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        vm.dialPad(digit[0])
                                    },
                                    onLongClick = if (digit == "0") {
                                        {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            vm.dialPad('+')
                                        }
                                    } else null,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 0.dp)
                        .background(if (com.ipdial.ui.theme.LocalGlassMode.current != com.ipdial.ui.theme.GlassMode.None) Color.Transparent else MaterialTheme.colorScheme.surface)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    keys.chunked(3).forEach { row ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                        ) {
                            row.forEachIndexed { colIndex, (digit, sub, _) ->
                                DialKey(
                                    digit = digit,
                                    subLabel = sub,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        vm.dialPad(digit[0])
                                    },
                                    onLongClick = if (digit == "0") {
                                        {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            vm.dialPad('+')
                                        }
                                    } else null,
                                    modifier = Modifier.weight(1f)
                                )
                                if (colIndex < 2) {
                                    VerticalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.fillMaxHeight().width(1.dp)
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .width(150.dp)
                    .height(62.dp)
                    .clip(CircleShape)
                    .background(ForestGreen)
                    .clickableWithRipple { 
                        if (dialString.isEmpty() && !lastDialedNumber.isNullOrEmpty()) {
                            vm.setDialString(androidx.compose.ui.text.input.TextFieldValue(lastDialedNumber!!))
                        } else if (dialString.isNotEmpty()) {
                            vm.makeCall()
                        }
                    }
            ) {
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = "Call",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(Modifier.height(8.dp))
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

private val T9_MAP = mapOf(
    'a' to '2', 'b' to '2', 'c' to '2',
    'd' to '3', 'e' to '3', 'f' to '3',
    'g' to '4', 'h' to '4', 'i' to '4',
    'j' to '5', 'k' to '5', 'l' to '5',
    'm' to '6', 'n' to '6', 'o' to '6',
    'p' to '7', 'q' to '7', 'r' to '7', 's' to '7',
    't' to '8', 'u' to '8', 'v' to '8',
    'w' to '9', 'x' to '9', 'y' to '9', 'z' to '9'
)

@Composable
fun SuggestedContactRow(contact: Contact, onNumberClick: (String) -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        com.ipdial.ui.ContactAvatar(
            name = contact.name,
            photoUri = contact.photoUri,
            size = 40.dp
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = contact.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            contact.numbers.forEach { number ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickableWithRipple { onNumberClick(number) }
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        text = number,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DialKeyRounded(
    digit: String,
    subLabel: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isGlass = com.ipdial.ui.theme.LocalGlassMode.current != com.ipdial.ui.theme.GlassMode.None
    Box(
        modifier = modifier.height(64.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = CircleShape,
            color = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier
                .size(64.dp)
                .then(if (isGlass) Modifier.glass(CircleShape) else Modifier)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                )
        ) {
            Box(contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = digit,
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontWeight = FontWeight.Normal,
                            fontSize = 32.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 32.sp
                    )
                    if (subLabel.isNotBlank() && digit.any { it.isDigit() }) {
                        Text(
                            text = subLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
                            fontSize = 12.sp,
                            lineHeight = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DialKey(
    digit: String,
    subLabel: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isGlass = com.ipdial.ui.theme.LocalGlassMode.current != com.ipdial.ui.theme.GlassMode.None
    Box(
            modifier = modifier
            .height(52.dp)
            .then(if (isGlass) Modifier.glass(RoundedCornerShape(0.dp), borderWidth = 0.5.dp) else Modifier)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = digit,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Normal),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subLabel.isNotBlank()) {
                Text(
                    text = subLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontSize = 10.sp
                )
            }
        }
    }
}
