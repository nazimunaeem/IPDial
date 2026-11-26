package com.ipdial.ui.screens

import android.Manifest
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ipdial.data.model.Contact
import com.ipdial.data.scheduler.ScheduledCall
import com.ipdial.data.scheduler.ScheduledCallStatus
import com.ipdial.data.scheduler.combineDateAndTimeMillis
import com.ipdial.data.scheduler.formatCountdownLabel
import com.ipdial.ui.SipViewModel
import com.ipdial.ui.components.ContactAvatar
import com.ipdial.ui.theme.GlassMode
import com.ipdial.ui.theme.LocalGlassMode
import com.ipdial.ui.theme.glass
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private fun hasExactAlarmPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return true
    return am.canScheduleExactAlarms()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallSchedulerScreen(
    vm: SipViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scheduledCalls by vm.scheduledCalls.collectAsState()
    val contacts by vm.contacts.collectAsState()
    val isGlass = LocalGlassMode.current != GlassMode.None

    val upcomingCalls = remember(scheduledCalls) {
        scheduledCalls.filter {
            it.status == ScheduledCallStatus.PENDING || it.status == ScheduledCallStatus.TRIGGERED
        }.sortedBy { it.scheduledAtMs }
    }
    val pastCalls = remember(scheduledCalls) {
        scheduledCalls.filter {
            it.status == ScheduledCallStatus.COMPLETED || it.status == ScheduledCallStatus.DISMISSED
        }.sortedByDescending { it.scheduledAtMs }
    }

    var showEditorSheet by remember { mutableStateOf(false) }
    var editingCall by remember { mutableStateOf<ScheduledCall?>(null) }

    var canScheduleExact by remember { mutableStateOf(hasExactAlarmPermission(context)) }
    val exactAlarmLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        canScheduleExact = hasExactAlarmPermission(context)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        canScheduleExact = hasExactAlarmPermission(context)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            vm.syncContacts()
        }
    }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canScheduleExact = hasExactAlarmPermission(context)
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                    vm.syncContacts()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        val alarmStateReceiver = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    canScheduleExact = hasExactAlarmPermission(context)
                }
            }.also { receiver ->
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    IntentFilter(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
            }
        } else null

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (alarmStateReceiver != null) {
                runCatching { context.unregisterReceiver(alarmStateReceiver) }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Call Scheduler", fontWeight = FontWeight.Bold)
                        Text(
                            text = if (upcomingCalls.isEmpty()) {
                                "Ring, vibrate & slide up to call on time"
                            } else {
                                "${upcomingCalls.size} upcoming scheduled ${if (upcomingCalls.size == 1) "call" else "calls"}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("scheduler_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    editingCall = null
                    showEditorSheet = true
                },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Schedule Call") },
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 8.dp)
                    .testTag("schedule_call_fab")
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!canScheduleExact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item(key = "exact_alarm_notice") {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.NotificationsActive,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Exact Alarm Permission",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    text = "Allow exact alarms so scheduled calls ring at the exact minute.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                            TextButton(
                                onClick = {
                                    runCatching {
                                        exactAlarmLauncher.launch(
                                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                                data = Uri.fromParts("package", context.packageName, null)
                                            }
                                        )
                                    }.onFailure {
                                        runCatching {
                                            exactAlarmLauncher.launch(
                                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                    data = Uri.fromParts("package", context.packageName, null)
                                                }
                                            )
                                        }
                                    }
                                }
                            ) {
                                Text("Allow")
                            }
                        }
                    }
                }
            }

            if (upcomingCalls.isEmpty() && pastCalls.isEmpty()) {
                item(key = "empty_scheduler") {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isGlass) {
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp)
                            .let { if (isGlass) it.glass(RoundedCornerShape(24.dp)) else it }
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Schedule,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                            Text(
                                text = "No Scheduled Calls Yet",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Pick any contact or phone number and choose a date & time. DialDuo will ring, vibrate, and let you slide UP to call or DOWN to reject.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            Button(
                                onClick = {
                                    editingCall = null
                                    showEditorSheet = true
                                },
                                modifier = Modifier.testTag("empty_schedule_cta")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Schedule a Call")
                            }
                        }
                    }
                }
            }

            if (upcomingCalls.isNotEmpty()) {
                item(key = "upcoming_header") {
                    Text(
                        text = "UPCOMING (${upcomingCalls.size})",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                items(upcomingCalls, key = { it.id }) { call ->
                    val matchedContact = remember(call.phoneNumber, contacts) {
                        vm.findContactByNumber(call.phoneNumber, contacts)
                    }
                    ScheduledCallCard(
                        call = call,
                        photoUri = matchedContact?.photoUri,
                        isGlass = isGlass,
                        isUpcoming = true,
                        onCallNow = { vm.confirmScheduledCallAndDial(call) },
                        onEdit = {
                            editingCall = call
                            showEditorSheet = true
                        },
                        onDelete = { vm.deleteScheduledCall(call.id) }
                    )
                }
            }

            if (pastCalls.isNotEmpty()) {
                item(key = "history_header") {
                    Text(
                        text = "COMPLETED & DISMISSED",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                items(pastCalls, key = { it.id }) { call ->
                    val matchedContact = remember(call.phoneNumber, contacts) {
                        vm.findContactByNumber(call.phoneNumber, contacts)
                    }
                    ScheduledCallCard(
                        call = call,
                        photoUri = matchedContact?.photoUri,
                        isGlass = isGlass,
                        isUpcoming = false,
                        onCallNow = { vm.makeCall(call.phoneNumber) },
                        onEdit = {
                            editingCall = call.copy(
                                scheduledAtMs = System.currentTimeMillis() + 15 * 60_000L,
                                status = ScheduledCallStatus.PENDING
                            )
                            showEditorSheet = true
                        },
                        onDelete = { vm.deleteScheduledCall(call.id) }
                    )
                }
            }
        }
    }

    if (showEditorSheet) {
        ScheduleCallEditorSheet(
            initialCall = editingCall,
            contacts = contacts,
            onSyncContacts = { vm.syncContacts() },
            onDismiss = { showEditorSheet = false },
            onSave = { name, number, timeMs, note, existingId ->
                vm.scheduleCall(
                    contactName = name,
                    phoneNumber = number,
                    scheduledAtMs = timeMs,
                    note = note,
                    existingId = existingId
                )
                showEditorSheet = false
            }
        )
    }
}

@Composable
private fun ScheduledCallCard(
    call: ScheduledCall,
    photoUri: Uri?,
    isGlass: Boolean,
    isUpcoming: Boolean,
    onCallNow: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("EEE, MMM d • h:mm a", Locale.getDefault()) }
    val countdown = remember(call.scheduledAtMs, call.status) {
        when (call.status) {
            ScheduledCallStatus.PENDING -> formatCountdownLabel(call.scheduledAtMs)
            ScheduledCallStatus.TRIGGERED -> "Ringing / Due now"
            ScheduledCallStatus.COMPLETED -> "Called"
            ScheduledCallStatus.DISMISSED -> "Rejected"
        }
    }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isGlass) {
                MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            }
        ),
        border = if (isUpcoming) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
        } else null,
        modifier = Modifier
            .fillMaxWidth()
            .let { if (isGlass) it.glass(RoundedCornerShape(18.dp)) else it }
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ContactAvatar(
                    name = call.displayTitle(),
                    photoUri = photoUri,
                    size = 46.dp,
                    backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = call.displayTitle(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (call.contactName.isNotBlank()) {
                        Text(
                            text = call.phoneNumber,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = dateFormat.format(Date(call.scheduledAtMs)),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = when (call.status) {
                        ScheduledCallStatus.PENDING,
                        ScheduledCallStatus.TRIGGERED -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        ScheduledCallStatus.COMPLETED -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f)
                        ScheduledCallStatus.DISMISSED -> MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
                    }
                ) {
                    Text(
                        text = countdown,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = when (call.status) {
                            ScheduledCallStatus.PENDING,
                            ScheduledCallStatus.TRIGGERED -> MaterialTheme.colorScheme.primary
                            ScheduledCallStatus.COMPLETED -> MaterialTheme.colorScheme.tertiary
                            ScheduledCallStatus.DISMISSED -> MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            if (call.note.isNotBlank()) {
                Text(
                    text = call.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onCallNow,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Call Now")
                }
                TextButton(
                    onClick = onEdit,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isUpcoming) "Edit" else "Reschedule")
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete scheduled call",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ScheduleCallEditorSheet(
    initialCall: ScheduledCall?,
    contacts: List<Contact>,
    onSyncContacts: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (contactName: String, phoneNumber: String, scheduledAtMs: Long, note: String, existingId: String?) -> Unit
) {
    val defaultTime = remember(initialCall) {
        val base = initialCall?.scheduledAtMs?.takeIf { it > System.currentTimeMillis() + 60_000L }
            ?: (System.currentTimeMillis() + 15 * 60_000L)
        Calendar.getInstance().apply {
            timeInMillis = base
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    var contactName by remember(initialCall) { mutableStateOf(initialCall?.contactName ?: "") }
    var phoneNumber by remember(initialCall) { mutableStateOf(initialCall?.phoneNumber ?: "") }
    var note by remember(initialCall) { mutableStateOf(initialCall?.note ?: "") }
    var selectedHour by remember(initialCall) { mutableIntStateOf(defaultTime.get(Calendar.HOUR_OF_DAY)) }
    var selectedMinute by remember(initialCall) { mutableIntStateOf(defaultTime.get(Calendar.MINUTE)) }
    var selectedDateUtcMs by remember(initialCall) {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(defaultTime.get(Calendar.YEAR), defaultTime.get(Calendar.MONTH), defaultTime.get(Calendar.DAY_OF_MONTH))
        }
        mutableLongStateOf(utc.timeInMillis)
    }

    var showContactPicker by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }

    val combinedMillis = remember(selectedDateUtcMs, selectedHour, selectedMinute) {
        combineDateAndTimeMillis(selectedDateUtcMs, selectedHour, selectedMinute)
    }
    val dateFormatter = remember { SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault()) }
    val timeFormatter = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = if (initialCall != null && initialCall.status == ScheduledCallStatus.PENDING) {
                    "Edit Scheduled Call"
                } else {
                    "Schedule a Call"
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            OutlinedButton(
                onClick = { showContactPicker = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("pick_contact_button")
            ) {
                Icon(Icons.Default.Contacts, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Select Contact")
            }

            OutlinedTextField(
                value = phoneNumber,
                onValueChange = {
                    phoneNumber = it
                    validationError = null
                },
                label = { Text("Phone Number or SIP Extension *") },
                leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("scheduler_phone_input")
            )

            OutlinedTextField(
                value = contactName,
                onValueChange = { contactName = it },
                label = { Text("Contact Name (Optional)") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("scheduler_name_input")
            )

            Text(
                text = "Quick Presets",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                fun applyCalendar(cal: Calendar) {
                    selectedHour = cal.get(Calendar.HOUR_OF_DAY)
                    selectedMinute = cal.get(Calendar.MINUTE)
                    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                        clear()
                        set(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
                    }
                    selectedDateUtcMs = utc.timeInMillis
                    validationError = null
                }

                listOf(
                    "+5 min" to {
                        Calendar.getInstance().apply { add(Calendar.MINUTE, 5) }
                    },
                    "+15 min" to {
                        Calendar.getInstance().apply { add(Calendar.MINUTE, 15) }
                    },
                    "+1 hour" to {
                        Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 1) }
                    },
                    "Tomorrow 9 AM" to {
                        Calendar.getInstance().apply {
                            add(Calendar.DAY_OF_YEAR, 1)
                            set(Calendar.HOUR_OF_DAY, 9)
                            set(Calendar.MINUTE, 0)
                        }
                    }
                ).forEach { (label, targetCal) ->
                    FilterChip(
                        selected = false,
                        onClick = { applyCalendar(targetCal()) },
                        label = { Text(label) }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp)
                        .testTag("scheduler_date_button")
                ) {
                    Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(dateFormatter.format(Date(combinedMillis)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                OutlinedButton(
                    onClick = { showTimePicker = true },
                    modifier = Modifier
                        .weight(0.85f)
                        .heightIn(min = 52.dp)
                        .testTag("scheduler_time_button")
                ) {
                    Icon(Icons.Default.AccessTime, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(timeFormatter.format(Date(combinedMillis)), maxLines = 1)
                }
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note / Reminder (Optional)") },
                maxLines = 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("scheduler_note_input")
            )

            if (validationError != null) {
                Text(
                    text = validationError!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        val trimmedNum = phoneNumber.trim()
                        when {
                            trimmedNum.isBlank() -> {
                                validationError = "Please enter a phone number or pick a contact."
                            }
                            combinedMillis <= System.currentTimeMillis() + 5_000L -> {
                                validationError = "Please choose a future date and time."
                            }
                            else -> {
                                onSave(
                                    contactName.trim(),
                                    trimmedNum,
                                    combinedMillis,
                                    note.trim(),
                                    initialCall?.id?.takeIf { initialCall.status == ScheduledCallStatus.PENDING }
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .weight(1.4f)
                        .heightIn(min = 48.dp)
                        .testTag("scheduler_save_button")
                ) {
                    Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Save Schedule")
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = selectedDateUtcMs)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let {
                            selectedDateUtcMs = it
                            validationError = null
                        }
                        showDatePicker = false
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = selectedHour,
            initialMinute = selectedMinute,
            is24Hour = false
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Select Time") },
            text = {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    TimePicker(state = timePickerState)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        selectedHour = timePickerState.hour
                        selectedMinute = timePickerState.minute
                        validationError = null
                        showTimePicker = false
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showContactPicker) {
        ContactPickerDialog(
            contacts = contacts,
            onSyncContacts = onSyncContacts,
            onDismiss = { showContactPicker = false },
            onSelect = { name, number ->
                contactName = name
                phoneNumber = number
                validationError = null
                showContactPicker = false
            }
        )
    }
}

@Composable
private fun ContactPickerDialog(
    contacts: List<Contact>,
    onSyncContacts: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (name: String, number: String) -> Unit
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var multiNumberContact by remember { mutableStateOf<Contact?>(null) }
    var hasContactsPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasContactsPermission = granted
        if (granted) onSyncContacts()
    }

    val filtered = remember(contacts, query) {
        val q = query.trim()
        if (q.isBlank()) contacts else {
            val digits = q.filter { it.isDigit() }
            contacts.filter { c ->
                c.name.contains(q, ignoreCase = true) ||
                    (digits.isNotBlank() && c.numbers.any { it.filter { ch -> ch.isDigit() }.contains(digits) })
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = multiNumberContact?.let { "Select Number for ${it.name}" } ?: "Select Contact",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            if (multiNumberContact != null) {
                val chosen = multiNumberContact!!
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    chosen.numbers.forEach { num ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(chosen.name, num) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(Icons.Default.Phone, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text(num, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                    TextButton(onClick = { multiNumberContact = null }) {
                        Text("Back to all contacts")
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search name or number") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (!hasContactsPermission && contacts.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Allow contacts permission to select a saved contact.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = { contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS) }
                            ) {
                                Text("Allow Contacts")
                            }
                        }
                    } else if (filtered.isEmpty()) {
                        Text(
                            text = if (contacts.isEmpty()) "No contacts found on device." else "No matching contacts found.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(filtered, key = { it.id }) { contact ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            when {
                                                contact.numbers.size == 1 -> onSelect(contact.name, contact.numbers.first())
                                                contact.numbers.size > 1 -> multiNumberContact = contact
                                            }
                                        }
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    ContactAvatar(
                                        name = contact.name,
                                        photoUri = contact.photoUri,
                                        size = 40.dp,
                                        backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = contact.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = contact.numbers.firstOrNull().orEmpty(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
