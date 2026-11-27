package com.ipdial.ui.screens

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ipdial.data.model.CallDirection
import com.ipdial.data.model.CallTransport
import com.ipdial.data.model.Contact
import com.ipdial.data.repository.ContactDetailInfo
import com.ipdial.data.repository.ContactPhoneNumber
import com.ipdial.data.repository.ExternalAppAction
import com.ipdial.ui.SipViewModel
import com.ipdial.ui.components.CallStatsDialog
import com.ipdial.ui.components.ContactAvatar
import com.ipdial.ui.components.NumberPickerDialog
import com.ipdial.ui.components.filterHistoryForTarget
import com.ipdial.ui.screens.call.formatHistoryDurationSuffix
import com.ipdial.ui.screens.home.CompactLineBadge
import com.ipdial.ui.screens.home.callDirectionColor
import com.ipdial.ui.theme.GlassMode
import com.ipdial.ui.theme.LocalGlassMode
import com.ipdial.ui.theme.glass
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

private enum class HeroActionType {
    CALL,
    SMS,
    DIALPAD
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailsScreen(
    contactId: String,
    vm: SipViewModel,
    onBack: () -> Unit,
    onOpenInDialpad: (String) -> Unit,
    onOpenStats: ((Contact) -> Unit)? = null
) {
    val context = LocalContext.current
    val contacts by vm.contacts.collectAsState()
    val contact = remember(contacts, contactId) { contacts.find { it.id == contactId } }
    var requestDeleteTrigger by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Contact Details",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("contact_details_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (contact != null) {
                        IconButton(
                            onClick = { vm.toggleContactFavorite(contact) },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("contact_details_favorite_button")
                        ) {
                            Icon(
                                imageVector = if (contact.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = if (contact.isFavorite) "Remove favorite" else "Add favorite",
                                tint = if (contact.isFavorite) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = { launchSystemEditContact(context, contact.id) },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("contact_details_edit_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit contact"
                            )
                        }
                        IconButton(
                            onClick = { requestDeleteTrigger++ },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("contact_details_delete_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Delete contact",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (contact == null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Contact unavailable",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onBack) {
                        Text("Go Back")
                    }
                }
            } else {
                ContactDetailsContent(
                    contact = contact,
                    vm = vm,
                    onOpenInDialpad = onOpenInDialpad,
                    onContactDeleted = onBack,
                    onOpenStats = onOpenStats,
                    showHeaderActions = false,
                    externalDeleteTrigger = requestDeleteTrigger,
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding()
                )
            }
        }
    }
}

@Composable
fun ContactDetailsContent(
    contact: Contact,
    vm: SipViewModel,
    onOpenInDialpad: (String) -> Unit,
    onContactDeleted: () -> Unit,
    onOpenStats: ((Contact) -> Unit)? = null,
    showHeaderActions: Boolean = false,
    externalDeleteTrigger: Int = 0,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isGlass = LocalGlassMode.current != GlassMode.None

    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current

    var refreshTrigger by remember { mutableIntStateOf(0) }
    var detailInfo by remember(contact.id) { mutableStateOf<ContactDetailInfo?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var pendingHeroAction by remember { mutableStateOf<HeroActionType?>(null) }
    var showAllHistory by remember(contact.id) { mutableStateOf(false) }
    var showContactStats by remember { mutableStateOf(false) }
    val callLog by vm.callLog.collectAsState()
    val locale = LocalConfiguration.current.locales[0]

    LaunchedEffect(externalDeleteTrigger) {
        if (externalDeleteTrigger > 0) {
            showDeleteDialog = true
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.syncContacts()
                refreshTrigger++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(contact.id, contact.name, contact.numbers, contact.photoUri, refreshTrigger) {
        detailInfo = vm.loadContactDetails(contact)
    }

    val phones: List<ContactPhoneNumber> = detailInfo?.phones
        ?: contact.numbers.map { ContactPhoneNumber(number = it, label = "Phone") }
    val externalActions: List<ExternalAppAction> = detailInfo?.externalActions.orEmpty()
    val contactCallHistory = remember(callLog, contact.id, phones) {
        val mergedNumbers = (contact.numbers + phones.map { it.number }).distinct()
        val matchingContact = contact.copy(numbers = mergedNumbers)
        filterHistoryForTarget(callLog, mergedNumbers.firstOrNull().orEmpty(), matchingContact)
    }

    val runHeroAction: (HeroActionType, String) -> Unit = { actionType, number ->
        when (actionType) {
            HeroActionType.CALL -> vm.makeCall(number)
            HeroActionType.SMS -> launchSmsMessage(context, number)
            HeroActionType.DIALPAD -> onOpenInDialpad(number)
        }
    }

    val triggerHeroAction: (HeroActionType) -> Unit = { actionType ->
        val distinctNumbers = phones.map { it.number }.distinct()
        when {
            distinctNumbers.isEmpty() -> {
                Toast.makeText(context, "No phone number saved", Toast.LENGTH_SHORT).show()
            }
            distinctNumbers.size == 1 -> runHeroAction(actionType, distinctNumbers.first())
            else -> pendingHeroAction = actionType
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (showHeaderActions) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { vm.toggleContactFavorite(contact) },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("pane_contact_favorite_button")
                    ) {
                        Icon(
                            imageVector = if (contact.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = if (contact.isFavorite) "Remove favorite" else "Add favorite",
                            tint = if (contact.isFavorite) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { launchSystemEditContact(context, contact.id) },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("pane_contact_edit_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit contact"
                        )
                    }
                    IconButton(
                        onClick = { showDeleteDialog = true },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("pane_contact_delete_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete contact",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // Profile Hero Card
            Surface(
                shape = RoundedCornerShape(24.dp),
                tonalElevation = if (isGlass) 0.dp else 2.dp,
                color = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (isGlass) Modifier.glass(RoundedCornerShape(24.dp)) else Modifier)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f),
                                    Color.Transparent
                                )
                            )
                        )
                        .padding(horizontal = 20.dp, vertical = 22.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        ContactAvatar(
                            name = contact.name,
                            photoUri = contact.photoUri,
                            size = 96.dp
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = contact.name,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                        if (contact.isFavorite) {
                            Spacer(Modifier.height(8.dp))
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Color(0xFFFFB300).copy(alpha = 0.16f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = null,
                                        tint = Color(0xFFFFB300),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = "Favorite",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        if (phones.isNotEmpty()) {
                            Spacer(Modifier.height(18.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                QuickHeroAction(
                                    icon = Icons.Default.Call,
                                    label = "Call",
                                    onClick = { triggerHeroAction(HeroActionType.CALL) },
                                    modifier = Modifier.testTag("contact_hero_call_button")
                                )
                                QuickHeroAction(
                                    icon = Icons.Default.Sms,
                                    label = "Message",
                                    onClick = { triggerHeroAction(HeroActionType.SMS) },
                                    modifier = Modifier.testTag("contact_hero_sms_button")
                                )
                                QuickHeroAction(
                                    icon = Icons.Default.Dialpad,
                                    label = "Dialpad",
                                    onClick = { triggerHeroAction(HeroActionType.DIALPAD) },
                                    modifier = Modifier.testTag("contact_hero_dialpad_button")
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Phone Numbers Section
            SectionHeader(title = "Phone Numbers")
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (isGlass) Modifier.glass(RoundedCornerShape(20.dp)) else Modifier)
            ) {
                if (phones.isEmpty()) {
                    Text(
                        text = "No phone numbers saved",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp)
                    )
                } else {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        phones.forEachIndexed { idx, phone ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 60.dp)
                                    .clickableWithRipple { vm.makeCall(phone.number) }
                                    .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp)
                                    .testTag("contact_phone_row_$idx"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Call,
                                            contentDescription = "Call ${phone.number}",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = phone.number,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = phone.label,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = { launchSmsMessage(context, phone.number) },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("contact_sms_button_$idx")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Sms,
                                        contentDescription = "Send SMS to ${phone.number}",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(phone.number))
                                        Toast.makeText(context, "Number copied", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("contact_copy_button_$idx")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy ${phone.number}",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            if (idx < phones.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                )
                            }
                        }
                    }
                }
            }

            // Other Calling & Messaging Apps Section (shown ONLY when third-party apps are linked)
            AnimatedVisibility(visible = externalActions.isNotEmpty()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(20.dp))
                    SectionHeader(title = "Other Apps")
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (isGlass) Modifier.glass(RoundedCornerShape(20.dp)) else Modifier)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            externalActions.forEachIndexed { idx, action ->
                                val iconBitmap = remember(action.packageName, action.mimeType) {
                                    runCatching {
                                        action.icon?.toBitmap(width = 96, height = 96)?.asImageBitmap()
                                    }.getOrNull()
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 60.dp)
                                        .clickableWithRipple { launchExternalAppAction(context, action) }
                                        .padding(horizontal = 16.dp, vertical = 10.dp)
                                        .testTag("contact_external_app_${idx}"),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (iconBitmap != null) {
                                        Image(
                                            bitmap = iconBitmap,
                                            contentDescription = action.appLabel,
                                            modifier = Modifier
                                                .size(38.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                        )
                                    } else {
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                            modifier = Modifier.size(38.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                                    contentDescription = action.appLabel,
                                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                    }
                                    Spacer(Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${action.actionLabel} via ${action.appLabel}",
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (action.summary.isNotBlank()) {
                                            Text(
                                                text = action.summary,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (idx < externalActions.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Call History Section
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Call History",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        if (onOpenStats != null) {
                            onOpenStats(contact)
                        } else {
                            showContactStats = true
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("contact_details_stats_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.BarChart,
                        contentDescription = "Call statistics for ${contact.name}",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (isGlass) Modifier.glass(RoundedCornerShape(20.dp)) else Modifier)
            ) {
                if (contactCallHistory.isEmpty()) {
                    Text(
                        text = "No call history for this contact",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp)
                    )
                } else {
                    val currentYear = remember { Calendar.getInstance().get(Calendar.YEAR) }
                    val sameYearFmt = remember(locale) { SimpleDateFormat("MMM d, h:mm a", locale) }
                    val otherYearFmt = remember(locale) { SimpleDateFormat("MMM d, yyyy • h:mm a", locale) }
                    val visibleHistory = if (showAllHistory) contactCallHistory else contactCallHistory.take(5)

                    Column(modifier = Modifier.fillMaxWidth()) {
                        visibleHistory.forEachIndexed { idx, entry ->
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
                            val numberSuffix = if (phones.size > 1 && entryNumber.isNotBlank()) " • $entryNumber" else ""

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 56.dp)
                                    .clickableWithRipple { vm.callBack(entry) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                                    .testTag("contact_history_row_$idx"),
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
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = dateStr,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
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
                                            com.ipdial.ui.screens.call.CompactHdBadge()
                                        }
                                    }
                                }
                                Icon(
                                    imageVector = Icons.Default.Call,
                                    contentDescription = "Call back",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            if (idx < visibleHistory.lastIndex || contactCallHistory.size > 5) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                )
                            }
                        }

                        if (contactCallHistory.size > 5) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickableWithRipple { showAllHistory = !showAllHistory }
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                                    .testTag("contact_history_toggle_button"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = if (showAllHistory) "Show less" else "Show all (${contactCallHistory.size})",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    imageVector = if (showAllHistory) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Contact Settings Section
            SectionHeader(title = "Contact Settings")
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (isGlass) Modifier.glass(RoundedCornerShape(20.dp)) else Modifier)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 58.dp)
                            .clickableWithRipple { launchSystemEditContact(context, contact.id) }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .testTag("contact_settings_edit_row"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Edit Contact",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Open in system Contacts editor",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 58.dp)
                            .clickableWithRipple { showDeleteDialog = true }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .testTag("contact_settings_delete_row"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Delete Contact",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = "Remove permanently from device contacts",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    pendingHeroAction?.let { actionType ->
        NumberPickerDialog(
            numbers = phones.map { it.number }.distinct(),
            onPick = { pickedNumber ->
                runHeroAction(actionType, pickedNumber)
                pendingHeroAction = null
            },
            onDismiss = { pendingHeroAction = null }
        )
    }

    if (showContactStats) {
        CallStatsDialog(
            title = contact.name,
            subtitle = phones.firstOrNull()?.number ?: "Contact statistics",
            entries = contactCallHistory,
            contacts = listOf(contact),
            isUniversal = false,
            onDismiss = { showContactStats = false }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Contact") },
            text = {
                Text("Open ${contact.name} in your device Contacts app to delete or manage it.")
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    onClick = {
                        showDeleteDialog = false
                        launchSystemViewContact(context, contact.id)
                    },
                    modifier = Modifier.testTag("confirm_delete_contact_button")
                ) {
                    Text("Open Contacts")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun QuickHeroAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickableWithRipple { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(48.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, bottom = 8.dp)
    )
}

private fun launchSmsMessage(context: Context, rawNumber: String) {
    val target = cleanUri(rawNumber).trim()
    if (target.isBlank()) {
        Toast.makeText(context, "No valid number for messaging", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(target)}"))
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(context, "No messaging app found", Toast.LENGTH_SHORT).show()
    }
}

private fun launchSystemViewContact(context: Context, contactId: String) {
    val idLong = contactId.toLongOrNull()
    if (idLong == null) {
        Toast.makeText(context, "Cannot open this contact", Toast.LENGTH_SHORT).show()
        return
    }
    val contactUri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, idLong)
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, contactUri))
    } catch (_: Exception) {
        Toast.makeText(context, "No contacts app found", Toast.LENGTH_SHORT).show()
    }
}

private fun launchSystemEditContact(context: Context, contactId: String) {
    val idLong = contactId.toLongOrNull()
    if (idLong == null) {
        Toast.makeText(context, "Cannot edit this contact", Toast.LENGTH_SHORT).show()
        return
    }
    val contactUri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, idLong)
    try {
        val intent = Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(contactUri, ContactsContract.Contacts.CONTENT_ITEM_TYPE)
            putExtra("finishActivityOnSaveCompleted", true)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        try {
            val fallbackIntent = Intent(Intent.ACTION_EDIT, contactUri).apply {
                putExtra("finishActivityOnSaveCompleted", true)
            }
            context.startActivity(fallbackIntent)
        } catch (_: Exception) {
            Toast.makeText(context, "No contacts editor found", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun launchExternalAppAction(context: Context, action: ExternalAppAction) {
    try {
        val dataUri = ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, action.dataId)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(dataUri, action.mimeType)
            setPackage(action.packageName)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(context, "Unable to open ${action.appLabel}", Toast.LENGTH_SHORT).show()
    }
}
