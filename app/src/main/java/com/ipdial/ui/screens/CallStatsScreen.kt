package com.ipdial.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ipdial.ui.SipViewModel
import com.ipdial.ui.components.IPDialTopBar
import com.ipdial.ui.components.TopContactStat
import com.ipdial.ui.components.filterHistoryForTarget
import com.ipdial.ui.components.formatStatsDuration
import com.ipdial.ui.components.matchesContactNumbers
import com.ipdial.ui.theme.GlassMode
import com.ipdial.ui.theme.LocalGlassMode
import com.ipdial.ui.theme.glass
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallStatsScreen(
    vm: SipViewModel,
    contactId: String = "",
    targetNumber: String = "",
    customTitle: String = "",
    onBack: () -> Unit,
    onOpenContactDetails: (String) -> Unit = {}
) {
    val accounts by vm.accounts.collectAsState()
    val allCallLog by vm.callLog.collectAsState()
    val contacts by vm.contacts.collectAsState()
    val isGlass = LocalGlassMode.current != GlassMode.None
    val locale = LocalConfiguration.current.locales[0]

    val targetContact = remember(contacts, contactId) {
        if (contactId.isNotBlank()) contacts.find { it.id == contactId } else null
    }
    val isUniversal = contactId.isBlank() && targetNumber.isBlank()

    val scopedEntries = remember(allCallLog, targetContact, targetNumber, isUniversal) {
        if (isUniversal) {
            allCallLog
        } else {
            filterHistoryForTarget(allCallLog, targetNumber, targetContact)
        }
    }

    val screenTitle = remember(customTitle, targetContact, targetNumber, isUniversal) {
        when {
            customTitle.isNotBlank() -> customTitle
            targetContact != null -> "${targetContact.name} · Stats"
            targetNumber.isNotBlank() -> "$targetNumber · Stats"
            else -> "Call Statistics"
        }
    }

    val nowCal = remember { Calendar.getInstance() }
    val currentYear = remember { nowCal.get(Calendar.YEAR) }
    val currentMonth = remember { nowCal.get(Calendar.MONTH) }

    var periodMode by remember { mutableStateOf(StatsPeriodMode.MONTH) }
    var selectedYear by remember { mutableIntStateOf(currentYear) }
    var selectedMonth by remember { mutableIntStateOf(currentMonth) }
    var transportFilter by remember { mutableStateOf(StatsTransportFilter.ALL) }
    var showMonthYearPicker by remember { mutableStateOf(false) }
    var selectedBucketIndex by remember(periodMode, selectedYear, selectedMonth, transportFilter) {
        mutableStateOf<Int?>(null)
    }

    val stats = remember(
        scopedEntries,
        contacts,
        periodMode,
        selectedYear,
        selectedMonth,
        transportFilter,
        locale
    ) {
        computePeriodCallStats(
            allEntries = scopedEntries,
            contacts = contacts,
            mode = periodMode,
            year = selectedYear,
            monthZeroBased = selectedMonth,
            transportFilter = transportFilter,
            locale = locale
        )
    }

    val monthYearLabel = remember(selectedYear, selectedMonth, periodMode, locale) {
        val cal = Calendar.getInstance().apply {
            clear()
            set(Calendar.YEAR, selectedYear)
            set(Calendar.MONTH, selectedMonth)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        when (periodMode) {
            StatsPeriodMode.MONTH -> SimpleDateFormat("MMMM yyyy", locale).format(cal.time)
            StatsPeriodMode.YEAR -> selectedYear.toString()
            StatsPeriodMode.ALL_TIME -> "All Recorded History"
        }
    }

    val latestActivityYm = remember(scopedEntries) { latestEntryYearMonth(scopedEntries) }
    val yearsList = remember(scopedEntries, currentYear) { availableYears(scopedEntries, currentYear) }

    Scaffold(
        topBar = {
            IPDialTopBar(accounts = accounts, vm = vm, title = screenTitle, onBack = onBack)
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(if (isGlass) Color.Transparent else MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 760.dp)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Period Mode & Transport Filter Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = periodMode == StatsPeriodMode.MONTH,
                        onClick = { periodMode = StatsPeriodMode.MONTH },
                        label = { Text("Month") },
                        modifier = Modifier.testTag("stats_mode_month")
                    )
                    FilterChip(
                        selected = periodMode == StatsPeriodMode.YEAR,
                        onClick = { periodMode = StatsPeriodMode.YEAR },
                        label = { Text("Year") },
                        modifier = Modifier.testTag("stats_mode_year")
                    )
                    FilterChip(
                        selected = periodMode == StatsPeriodMode.ALL_TIME,
                        onClick = { periodMode = StatsPeriodMode.ALL_TIME },
                        label = { Text("All Time") },
                        modifier = Modifier.testTag("stats_mode_all")
                    )

                    Spacer(Modifier.width(4.dp))

                    StatsTransportFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = transportFilter == filter,
                            onClick = { transportFilter = filter },
                            label = { Text(filter.label) },
                            modifier = Modifier.testTag("stats_filter_${filter.name.lowercase()}")
                        )
                    }
                }

                // Month & Year Picker Bar
                if (periodMode != StatsPeriodMode.ALL_TIME) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (isGlass) Modifier.glass(RoundedCornerShape(18.dp)) else Modifier)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            IconButton(
                                onClick = {
                                    if (periodMode == StatsPeriodMode.MONTH) {
                                        val (y, m) = stepMonthYear(selectedYear, selectedMonth, -1)
                                        selectedYear = y
                                        selectedMonth = m
                                    } else {
                                        selectedYear -= 1
                                    }
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("stats_prev_period_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                    contentDescription = "Previous period",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { showMonthYearPicker = true }
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CalendarMonth,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = monthYearLabel,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.testTag("stats_period_picker_button")
                                )
                            }

                            IconButton(
                                onClick = {
                                    if (periodMode == StatsPeriodMode.MONTH) {
                                        val (y, m) = stepMonthYear(selectedYear, selectedMonth, +1)
                                        selectedYear = y
                                        selectedMonth = m
                                    } else {
                                        selectedYear += 1
                                    }
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("stats_next_period_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = "Next period",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                if (stats.totalCalls == 0) {
                    EmptyStatsCard(
                        periodLabel = monthYearLabel,
                        hasAnyHistory = scopedEntries.isNotEmpty(),
                        isGlass = isGlass,
                        onJumpToLatest = {
                            latestActivityYm?.let { (y, m) ->
                                selectedYear = y
                                selectedMonth = m
                            }
                        },
                        onResetFilter = if (transportFilter != StatsTransportFilter.ALL) {
                            { transportFilter = StatsTransportFilter.ALL }
                        } else null
                    )
                } else {
                    // KPI Summary Grid
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        StatsKpiCard(
                            icon = Icons.Default.Call,
                            label = "Total Calls",
                            value = stats.totalCalls.toString(),
                            subtext = "${stats.answerRatePercent}% connected",
                            isGlass = isGlass,
                            modifier = Modifier.weight(1f)
                        )
                        StatsKpiCard(
                            icon = Icons.Default.Schedule,
                            label = "Talk Time",
                            value = formatStatsDuration(stats.totalDurationSeconds),
                            subtext = "Avg ${formatStatsDuration(stats.avgAnsweredDurationSeconds)} · Max ${formatStatsDuration(stats.longestCallSeconds)}",
                            isGlass = isGlass,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Stacked Activity Bar Chart (Daily in Month / Monthly in Year or All Time)
                    if (stats.chartBuckets.isNotEmpty()) {
                        ActivityChartCard(
                            title = when (periodMode) {
                                StatsPeriodMode.MONTH -> "Daily Call Activity"
                                StatsPeriodMode.YEAR -> "Monthly Call Activity"
                                StatsPeriodMode.ALL_TIME -> "Call Activity Trend"
                            },
                            buckets = stats.chartBuckets,
                            isMonthDailyView = periodMode == StatsPeriodMode.MONTH,
                            selectedIndex = selectedBucketIndex,
                            onSelectIndex = { idx ->
                                selectedBucketIndex = if (selectedBucketIndex == idx) null else idx
                            },
                            isGlass = isGlass
                        )
                    }

                    // Donut Breakdown + Transport Split
                    CallBreakdownDonutCard(
                        stats = stats,
                        isGlass = isGlass
                    )

                    // Day-of-Week Activity Chart
                    WeekdayActivityCard(
                        weekdayBuckets = stats.weekdayBuckets,
                        isGlass = isGlass
                    )

                    // Top Contacts Leaderboard (Universal Mode)
                    if (isUniversal && stats.topContacts.isNotEmpty()) {
                        TopContactsLeaderboardCard(
                            topContacts = stats.topContacts,
                            isGlass = isGlass,
                            onContactClick = { stat ->
                                val matched = contacts.firstOrNull { contact ->
                                    (stat.rawNumber.isNotBlank() && matchesContactNumbers(stat.rawNumber, contact.numbers)) ||
                                        contact.name.equals(stat.displayName, ignoreCase = true)
                                }
                                if (matched != null) {
                                    onOpenContactDetails(matched.id)
                                }
                            }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (showMonthYearPicker) {
        MonthYearPickerDialog(
            mode = periodMode,
            initialYear = selectedYear,
            initialMonth = selectedMonth,
            currentYear = currentYear,
            currentMonth = currentMonth,
            yearsList = yearsList,
            activeMonthsProvider = { year -> activeMonthsInYear(scopedEntries, year) },
            locale = locale,
            onConfirm = { year, month ->
                selectedYear = year
                selectedMonth = month
                showMonthYearPicker = false
            },
            onDismiss = { showMonthYearPicker = false }
        )
    }
}

@Composable
private fun MonthYearPickerDialog(
    mode: StatsPeriodMode,
    initialYear: Int,
    initialMonth: Int,
    currentYear: Int,
    currentMonth: Int,
    yearsList: List<Int>,
    activeMonthsProvider: (Int) -> Set<Int>,
    locale: Locale,
    onConfirm: (year: Int, month: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var pickerYear by remember { mutableIntStateOf(initialYear) }
    var pickerMonth by remember { mutableIntStateOf(initialMonth) }
    val activeMonths = remember(pickerYear) { activeMonthsProvider(pickerYear) }
    val shortMonths = remember(locale) { DateFormatSymbols(locale).shortMonths }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 380.dp)
                .testTag("month_year_picker_dialog")
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = if (mode == StatsPeriodMode.YEAR) "Select Year" else "Select Month & Year",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(Modifier.height(12.dp))

                // Year stepper row
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(
                            onClick = { pickerYear -= 1 },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous year")
                        }
                        Text(
                            text = pickerYear.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        IconButton(
                            onClick = { pickerYear += 1 },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next year")
                        }
                    }
                }

                // Quick year chips
                if (yearsList.size > 1) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        yearsList.forEach { y ->
                            FilterChip(
                                selected = pickerYear == y,
                                onClick = {
                                    pickerYear = y
                                    if (mode == StatsPeriodMode.YEAR) {
                                        onConfirm(y, pickerMonth)
                                    }
                                },
                                label = { Text(y.toString()) }
                            )
                        }
                    }
                }

                // 3x4 Month Grid
                if (mode == StatsPeriodMode.MONTH) {
                    Spacer(Modifier.height(12.dp))
                    for (rowIdx in 0 until 4) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (colIdx in 0 until 3) {
                                val mIdx = rowIdx * 3 + colIdx
                                val isSelected = pickerMonth == mIdx
                                val hasData = mIdx in activeMonths
                                val monthName = shortMonths.getOrElse(mIdx) { "" }.ifBlank { "M${mIdx + 1}" }

                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerLow
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            pickerMonth = mIdx
                                            onConfirm(pickerYear, mIdx)
                                        }
                                        .testTag("picker_month_$mIdx")
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = monthName,
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isSelected) {
                                                    MaterialTheme.colorScheme.onPrimary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                }
                                            )
                                            if (hasData) {
                                                Spacer(Modifier.height(2.dp))
                                                Box(
                                                    modifier = Modifier
                                                        .size(5.dp)
                                                        .clip(CircleShape)
                                                        .background(
                                                            if (isSelected) {
                                                                MaterialTheme.colorScheme.onPrimary
                                                            } else {
                                                                MaterialTheme.colorScheme.primary
                                                            }
                                                        )
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { onConfirm(currentYear, currentMonth) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(if (mode == StatsPeriodMode.YEAR) "This Year" else "This Month")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text("Cancel")
                        }
                        if (mode == StatsPeriodMode.YEAR) {
                            Button(
                                onClick = { onConfirm(pickerYear, pickerMonth) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text("Apply")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyStatsCard(
    periodLabel: String,
    hasAnyHistory: Boolean,
    isGlass: Boolean,
    onJumpToLatest: () -> Unit,
    onResetFilter: (() -> Unit)?
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isGlass) Modifier.glass(RoundedCornerShape(20.dp)) else Modifier)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.BarChart,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "No calls in $periodLabel",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (hasAnyHistory) {
                    "Try choosing another month or year, or switch line filters."
                } else {
                    "Your call analytics and charts will appear here once you make or receive calls."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (onResetFilter != null || hasAnyHistory) {
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onResetFilter != null) {
                        TextButton(onClick = onResetFilter) {
                            Text("Show All Lines")
                        }
                    }
                    if (hasAnyHistory) {
                        Button(onClick = onJumpToLatest) {
                            Text("Jump to Latest Activity")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsKpiCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    subtext: String,
    isGlass: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = modifier.then(if (isGlass) Modifier.glass(RoundedCornerShape(18.dp)) else Modifier)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtext,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ActivityChartCard(
    title: String,
    buckets: List<ChartBucket>,
    isMonthDailyView: Boolean,
    selectedIndex: Int?,
    onSelectIndex: (Int) -> Unit,
    isGlass: Boolean
) {
    val maxTotal = remember(buckets) { buckets.maxOfOrNull { it.total }?.coerceAtLeast(1) ?: 1 }
    val selectedBucket = selectedIndex?.let { buckets.getOrNull(it) }
    val incomingColor = MaterialTheme.colorScheme.primary
    val outgoingColor = MaterialTheme.colorScheme.tertiary
    val missedColor = MaterialTheme.colorScheme.error
    val emptyBarColor = MaterialTheme.colorScheme.surfaceVariant

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isGlass) Modifier.glass(RoundedCornerShape(20.dp)) else Modifier)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Peak: $maxTotal/bar",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))

            // Legend
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LegendDot("Incoming", incomingColor)
                LegendDot("Outgoing", outgoingColor)
                LegendDot("Missed", missedColor)
            }

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(148.dp),
                horizontalArrangement = Arrangement.spacedBy(if (isMonthDailyView) 2.dp else 6.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                buckets.forEachIndexed { idx, bucket ->
                    val isSelected = selectedIndex == idx
                    val total = bucket.total
                    val heightFraction = (total.toFloat() / maxTotal.toFloat())
                        .coerceIn(if (total > 0) 0.12f else 0.04f, 1f)

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onSelectIndex(idx) }
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                else Color.Transparent
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        if (!isMonthDailyView && total > 0) {
                            Text(
                                text = total.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1
                            )
                            Spacer(Modifier.height(3.dp))
                        }

                        // Stacked vertical bar
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(if (isMonthDailyView) 0.8f else 0.6f)
                                .height(104.dp * heightFraction)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                                .then(
                                    if (isSelected) {
                                        Modifier.border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                                    } else Modifier
                                )
                        ) {
                            if (total == 0) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(emptyBarColor)
                                )
                            } else {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    if (bucket.missed > 0) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .weight(bucket.missed.toFloat())
                                                .background(missedColor)
                                        )
                                    }
                                    if (bucket.outgoing > 0) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .weight(bucket.outgoing.toFloat())
                                                .background(outgoingColor)
                                        )
                                    }
                                    if (bucket.incoming > 0) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .weight(bucket.incoming.toFloat())
                                                .background(incomingColor)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(5.dp))

                        val showLabel = if (isMonthDailyView) {
                            val dayNum = bucket.key
                            dayNum == 1 || dayNum % 5 == 0 || dayNum == buckets.size
                        } else {
                            true
                        }
                        Text(
                            text = if (showLabel) bucket.label else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            if (selectedBucket != null) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (selectedBucket.total == 0) {
                            "${selectedBucket.fullLabel}: No calls"
                        } else {
                            "${selectedBucket.fullLabel}: ${selectedBucket.total} calls " +
                                "(${selectedBucket.incoming} in, ${selectedBucket.outgoing} out, ${selectedBucket.missed} missed) · " +
                                formatStatsDuration(selectedBucket.durationSeconds)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            } else {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Tap any bar to inspect details",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                )
            }
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CallBreakdownDonutCard(
    stats: PeriodCallStats,
    isGlass: Boolean
) {
    val incomingColor = MaterialTheme.colorScheme.primary
    val outgoingColor = MaterialTheme.colorScheme.tertiary
    val missedColor = MaterialTheme.colorScheme.error
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isGlass) Modifier.glass(RoundedCornerShape(20.dp)) else Modifier)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Call Distribution",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(116.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val strokePx = 18.dp.toPx()
                        val diameter = size.minDimension - strokePx
                        val topLeft = Offset(
                            (size.width - diameter) / 2f,
                            (size.height - diameter) / 2f
                        )
                        val arcSize = Size(diameter, diameter)
                        val total = stats.totalCalls.coerceAtLeast(1).toFloat()

                        drawArc(
                            color = trackColor,
                            startAngle = 0f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokePx)
                        )

                        val segments = listOf(
                            stats.incomingCalls to incomingColor,
                            stats.outgoingCalls to outgoingColor,
                            stats.missedCalls to missedColor
                        ).filter { it.first > 0 }

                        val gapDegrees = if (segments.size > 1) 4f else 0f
                        val usableDegrees = 360f - gapDegrees * segments.size
                        var startAngle = -90f
                        segments.forEach { (count, color) ->
                            val sweep = (count / total) * usableDegrees
                            if (sweep > 0.5f) {
                                drawArc(
                                    color = color,
                                    startAngle = startAngle,
                                    sweepAngle = sweep,
                                    useCenter = false,
                                    topLeft = topLeft,
                                    size = arcSize,
                                    style = Stroke(width = strokePx, cap = StrokeCap.Butt)
                                )
                            }
                            startAngle += sweep + gapDegrees
                        }
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stats.totalCalls.toString(),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "calls",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.width(18.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val totalF = stats.totalCalls.coerceAtLeast(1).toFloat()
                    BreakdownMetricRow(
                        icon = Icons.AutoMirrored.Filled.CallReceived,
                        label = "Incoming",
                        count = stats.incomingCalls,
                        percent = ((stats.incomingCalls * 100f) / totalF).roundToInt(),
                        color = incomingColor
                    )
                    BreakdownMetricRow(
                        icon = Icons.AutoMirrored.Filled.CallMade,
                        label = "Outgoing",
                        count = stats.outgoingCalls,
                        percent = ((stats.outgoingCalls * 100f) / totalF).roundToInt(),
                        color = outgoingColor
                    )
                    BreakdownMetricRow(
                        icon = Icons.AutoMirrored.Filled.CallMissed,
                        label = "Missed",
                        count = stats.missedCalls,
                        percent = ((stats.missedCalls * 100f) / totalF).roundToInt(),
                        color = missedColor
                    )
                }
            }

            if (stats.simCalls > 0 || stats.sipCalls > 0) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(Modifier.height(12.dp))

                val totalLines = (stats.simCalls + stats.sipCalls).coerceAtLeast(1).toFloat()
                val simWeight = stats.simCalls / totalLines
                val sipWeight = stats.sipCalls / totalLines
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(trackColor),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (simWeight > 0f) {
                        Box(
                            modifier = Modifier
                                .weight(simWeight)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                    if (sipWeight > 0f) {
                        Box(
                            modifier = Modifier
                                .weight(sipWeight)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.secondary)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "SIM: ${stats.simCalls} (${formatStatsDuration(stats.simDurationSeconds)})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "SIP: ${stats.sipCalls} (${formatStatsDuration(stats.sipDurationSeconds)})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun BreakdownMetricRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    count: Int,
    percent: Int,
    color: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "$count ($percent%)",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun WeekdayActivityCard(
    weekdayBuckets: List<ChartBucket>,
    isGlass: Boolean
) {
    val maxDay = remember(weekdayBuckets) { weekdayBuckets.maxOfOrNull { it.total }?.coerceAtLeast(1) ?: 1 }
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isGlass) Modifier.glass(RoundedCornerShape(20.dp)) else Modifier)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Calls by Day of Week",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(106.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                weekdayBuckets.forEach { bucket ->
                    val fraction = (bucket.total.toFloat() / maxDay.toFloat())
                        .coerceIn(if (bucket.total > 0) 0.14f else 0.05f, 1f)
                    val isPeak = bucket.total > 0 && bucket.total == maxDay
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        Text(
                            text = if (bucket.total > 0) bucket.total.toString() else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isPeak) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (isPeak) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1
                        )
                        Spacer(Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.55f)
                                .height(62.dp * fraction)
                                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                                .background(
                                    when {
                                        bucket.total == 0 -> MaterialTheme.colorScheme.surfaceVariant
                                        isPeak -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.58f)
                                    }
                                )
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = bucket.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopContactsLeaderboardCard(
    topContacts: List<TopContactStat>,
    isGlass: Boolean,
    onContactClick: (TopContactStat) -> Unit
) {
    val maxCalls = remember(topContacts) { topContacts.maxOfOrNull { it.totalCalls }?.coerceAtLeast(1) ?: 1 }
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isGlass) Modifier.glass(RoundedCornerShape(20.dp)) else Modifier)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Top Contacts",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(10.dp))
            topContacts.forEachIndexed { idx, stat ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onContactClick(stat) }
                        .padding(vertical = 7.dp, horizontal = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(26.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "${idx + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stat.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (stat.rawNumber.isNotBlank() && stat.rawNumber != stat.displayName) {
                                Text(
                                    text = stat.rawNumber,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "${stat.totalCalls} ${if (stat.totalCalls == 1) "call" else "calls"}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (stat.totalDurationSeconds > 0L) {
                                Text(
                                    text = formatStatsDuration(stat.totalDurationSeconds),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { (stat.totalCalls.toFloat() / maxCalls.toFloat()).coerceIn(0.05f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
                if (idx < topContacts.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                }
            }
        }
    }
}
