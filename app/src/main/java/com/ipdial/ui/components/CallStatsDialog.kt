package com.ipdial.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ipdial.data.model.CallDirection
import com.ipdial.data.model.CallLogEntry
import com.ipdial.data.model.CallTransport
import com.ipdial.data.model.Contact
import com.ipdial.ui.screens.cleanDisplayName
import com.ipdial.ui.screens.cleanUri
import com.ipdial.ui.theme.GlassMode
import com.ipdial.ui.theme.LocalGlassMode
import com.ipdial.ui.theme.glass
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Seaborn "deep" chart palette adapted cleanly for light and dark surfaces.
 */
object SeabornChartPalette {
    fun incoming(isDark: Boolean): Color = if (isDark) Color(0xFF6BC080) else Color(0xFF55A868)
    fun outgoing(isDark: Boolean): Color = if (isDark) Color(0xFF6C8EC9) else Color(0xFF4C72B0)
    fun missed(isDark: Boolean): Color = if (isDark) Color(0xFFDC6B6F) else Color(0xFFC44E52)
    fun sim(isDark: Boolean): Color = if (isDark) Color(0xFF7EC8DE) else Color(0xFF64B5CD)
    fun sip(isDark: Boolean): Color = if (isDark) Color(0xFF9B8EC8) else Color(0xFF8172B3)
    fun peak(isDark: Boolean): Color = if (isDark) Color(0xFFE89C72) else Color(0xFFDD8452)

    fun categorical(index: Int, isDark: Boolean): Color {
        val lightColors = listOf(
            Color(0xFF4C72B0), // Blue
            Color(0xFFDD8452), // Orange
            Color(0xFF55A868), // Green
            Color(0xFFC44E52), // Red
            Color(0xFF8172B3), // Purple
            Color(0xFF64B5CD), // Cyan
            Color(0xFFCCB974), // Gold
            Color(0xFFDA8BC3), // Pink
        )
        val darkColors = listOf(
            Color(0xFF6C8EC9),
            Color(0xFFE89C72),
            Color(0xFF6BC080),
            Color(0xFFDC6B6F),
            Color(0xFF9B8EC8),
            Color(0xFF7EC8DE),
            Color(0xFFD9C98A),
            Color(0xFFE3A1D0),
        )
        val palette = if (isDark) darkColors else lightColors
        return palette[index.mod(palette.size)]
    }
}

data class DailyCallBucket(
    val dayLabel: String,
    val totalCalls: Int,
    val missedCalls: Int
)

data class TopContactStat(
    val displayName: String,
    val rawNumber: String,
    val totalCalls: Int,
    val totalDurationSeconds: Long
)

data class CallStatsSummary(
    val totalCalls: Int,
    val incomingCalls: Int,
    val outgoingCalls: Int,
    val missedCalls: Int,
    val totalDurationSeconds: Long,
    val avgAnsweredDurationSeconds: Long,
    val longestCallSeconds: Long,
    val simCalls: Int,
    val sipCalls: Int,
    val answerRatePercent: Int,
    val dailyBuckets: List<DailyCallBucket>,
    val topContacts: List<TopContactStat>,
    val firstCallMs: Long?,
    val lastCallMs: Long?
)

fun phoneNumbersMatch(a: String, b: String): Boolean {
    val cleanA = cleanUri(a).trim()
    val cleanB = cleanUri(b).trim()
    if (cleanA.isEmpty() || cleanB.isEmpty()) return false
    if (cleanA.equals(cleanB, ignoreCase = true)) return true

    val digitsA = cleanA.filter { it.isDigit() }
    val digitsB = cleanB.filter { it.isDigit() }
    if (digitsA.isEmpty() || digitsB.isEmpty()) return false
    if (digitsA == digitsB) return true

    val minLen = minOf(digitsA.length, digitsB.length)
    if (minLen >= 7) {
        if (digitsA.endsWith(digitsB) || digitsB.endsWith(digitsA)) return true
        val compareLen = minOf(10, minLen)
        if (compareLen >= 7 && digitsA.takeLast(compareLen) == digitsB.takeLast(compareLen)) {
            return true
        }
    }
    return false
}

fun matchesContactNumbers(remoteUri: String, numbers: List<String>): Boolean =
    numbers.any { phoneNumbersMatch(remoteUri, it) }

fun filterHistoryForTarget(
    allEntries: List<CallLogEntry>,
    targetUri: String,
    contact: Contact?
): List<CallLogEntry> {
    val numbers = contact?.numbers.orEmpty().filter { it.isNotBlank() }
    return allEntries.filter { entry ->
        if (numbers.isNotEmpty()) {
            matchesContactNumbers(entry.remoteUri, numbers) ||
                (targetUri.isNotBlank() && phoneNumbersMatch(entry.remoteUri, targetUri))
        } else {
            targetUri.isNotBlank() && phoneNumbersMatch(entry.remoteUri, targetUri)
        }
    }.sortedByDescending { it.timestampMs }
}

fun computeCallStats(
    entries: List<CallLogEntry>,
    contacts: List<Contact> = emptyList(),
    nowMs: Long = System.currentTimeMillis(),
    locale: Locale = Locale.getDefault()
): CallStatsSummary {
    val totalCalls = entries.size
    val missedCalls = entries.count { it.missed }
    val incomingCalls = entries.count { !it.missed && it.direction == CallDirection.INCOMING }
    val outgoingCalls = entries.count { !it.missed && it.direction == CallDirection.OUTGOING }

    val answeredEntries = entries.filter { !it.missed }
    val totalDurationSeconds = answeredEntries.sumOf { it.durationSeconds.coerceAtLeast(0L) }
    val connectedEntries = answeredEntries.filter { it.durationSeconds > 0L }
    val avgAnsweredDurationSeconds = if (connectedEntries.isEmpty()) {
        0L
    } else {
        totalDurationSeconds / connectedEntries.size
    }
    val longestCallSeconds = answeredEntries.maxOfOrNull { it.durationSeconds.coerceAtLeast(0L) } ?: 0L

    val simCalls = entries.count { it.transport == CallTransport.SIM }
    val sipCalls = entries.count { it.transport == CallTransport.SIP }
    val answerRatePercent = if (totalCalls == 0) 0 else ((answeredEntries.size * 100f) / totalCalls).roundToInt()

    // Build 7-day calendar buckets ending on nowMs
    val dayFmt = SimpleDateFormat("EEE", locale)
    val startOfTodayCal = Calendar.getInstance().apply {
        timeInMillis = nowMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val dayStarts = (6 downTo 0).map { daysAgo ->
        (startOfTodayCal.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, -daysAgo)
        }.timeInMillis
    }
    val dayMs = 24L * 60L * 60L * 1000L
    val dailyBuckets = dayStarts.map { dayStart ->
        val dayEnd = dayStart + dayMs
        val dayEntries = entries.filter { it.timestampMs in dayStart until dayEnd }
        DailyCallBucket(
            dayLabel = dayFmt.format(Date(dayStart)).take(3),
            totalCalls = dayEntries.size,
            missedCalls = dayEntries.count { it.missed }
        )
    }

    // Contact lookup for grouping top contacts
    val topContacts = buildTopContacts(entries, contacts)

    return CallStatsSummary(
        totalCalls = totalCalls,
        incomingCalls = incomingCalls,
        outgoingCalls = outgoingCalls,
        missedCalls = missedCalls,
        totalDurationSeconds = totalDurationSeconds,
        avgAnsweredDurationSeconds = avgAnsweredDurationSeconds,
        longestCallSeconds = longestCallSeconds,
        simCalls = simCalls,
        sipCalls = sipCalls,
        answerRatePercent = answerRatePercent,
        dailyBuckets = dailyBuckets,
        topContacts = topContacts,
        firstCallMs = entries.minOfOrNull { it.timestampMs },
        lastCallMs = entries.maxOfOrNull { it.timestampMs }
    )
}

private fun buildTopContacts(
    entries: List<CallLogEntry>,
    contacts: List<Contact>
): List<TopContactStat> {
    if (entries.isEmpty()) return emptyList()

    data class Accumulator(
        val displayName: String,
        val rawNumber: String,
        var count: Int = 0,
        var durationSeconds: Long = 0L
    )

    val grouped = linkedMapOf<String, Accumulator>()
    entries.forEach { entry ->
        val cleanNum = cleanUri(entry.remoteUri)
        val matchedContact = contacts.firstOrNull { matchesContactNumbers(entry.remoteUri, it.numbers) }
        val key = matchedContact?.id?.let { "contact:$it" } ?: run {
            val digits = cleanNum.filter { it.isDigit() }
            if (digits.length >= 7) "num:${digits.takeLast(10)}" else "raw:${cleanNum.lowercase()}"
        }
        val label = matchedContact?.name?.takeIf { it.isNotBlank() }
            ?: cleanDisplayName(entry.remoteDisplayName, entry.remoteUri).ifBlank { cleanNum.ifBlank { "Unknown" } }
        val existing = grouped[key]
        val addedDuration = if (entry.missed) 0L else entry.durationSeconds.coerceAtLeast(0L)
        if (existing == null) {
            grouped[key] = Accumulator(
                displayName = label,
                rawNumber = cleanNum,
                count = 1,
                durationSeconds = addedDuration
            )
        } else {
            existing.count += 1
            existing.durationSeconds += addedDuration
        }
    }

    return grouped.values
        .sortedWith(compareByDescending<Accumulator> { it.count }.thenByDescending { it.durationSeconds })
        .take(5)
        .map {
            TopContactStat(
                displayName = it.displayName,
                rawNumber = it.rawNumber,
                totalCalls = it.count,
                totalDurationSeconds = it.durationSeconds
            )
        }
}

fun formatStatsDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    val h = safe / 3600L
    val m = (safe % 3600L) / 60L
    val s = safe % 60L
    return when {
        h > 0 -> if (m > 0) "${h}h ${m}m" else "${h}h"
        m > 0 -> if (s > 0) "${m}m ${s}s" else "${m}m"
        else -> "${s}s"
    }
}

@Composable
fun CallStatsDialog(
    title: String,
    subtitle: String? = null,
    entries: List<CallLogEntry>,
    contacts: List<Contact> = emptyList(),
    isUniversal: Boolean = true,
    onDismiss: () -> Unit
) {
    val stats = remember(entries, contacts) {
        computeCallStats(entries = entries, contacts = contacts)
    }
    val isGlass = LocalGlassMode.current != GlassMode.None
    val dateFmt = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            tonalElevation = if (isGlass) 0.dp else 6.dp,
            color = if (isGlass) MaterialTheme.colorScheme.surface.copy(alpha = 0.92f) else MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .widthIn(max = 440.dp)
                .then(if (isGlass) Modifier.glass(RoundedCornerShape(24.dp)) else Modifier)
                .testTag("call_stats_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, start = 18.dp, end = 18.dp, bottom = 12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.BarChart,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val resolvedSubtitle = subtitle ?: if (stats.firstCallMs != null && stats.lastCallMs != null) {
                            "${dateFmt.format(Date(stats.firstCallMs))} – ${dateFmt.format(Date(stats.lastCallMs))}"
                        } else {
                            "All-time activity"
                        }
                        Text(
                            text = resolvedSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("call_stats_header_close")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close statistics",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                if (stats.totalCalls == 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No calls recorded yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 460.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Primary KPI Cards
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            StatMetricCard(
                                label = "Total Calls",
                                value = stats.totalCalls.toString(),
                                subtext = "${stats.answerRatePercent}% connected",
                                modifier = Modifier.weight(1f)
                            )
                            StatMetricCard(
                                label = "Talk Time",
                                value = formatStatsDuration(stats.totalDurationSeconds),
                                subtext = "Avg ${formatStatsDuration(stats.avgAnsweredDurationSeconds)}",
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // Direction Breakdown
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = "Call Breakdown",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(10.dp))

                                // Segmented distribution bar (Seaborn Deep)
                                val isDarkSurface = MaterialTheme.colorScheme.surface.luminance() < 0.5f
                                val incomingColor = SeabornChartPalette.incoming(isDarkSurface)
                                val outgoingColor = SeabornChartPalette.outgoing(isDarkSurface)
                                val missedColor = SeabornChartPalette.missed(isDarkSurface)

                                val totalF = stats.totalCalls.coerceAtLeast(1).toFloat()
                                val inWeight = stats.incomingCalls / totalF
                                val outWeight = stats.outgoingCalls / totalF
                                val missedWeight = stats.missedCalls / totalF
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(10.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    if (inWeight > 0f) {
                                        Box(
                                            modifier = Modifier
                                                .weight(inWeight)
                                                .fillMaxHeight()
                                                .background(incomingColor)
                                        )
                                    }
                                    if (outWeight > 0f) {
                                        Box(
                                            modifier = Modifier
                                                .weight(outWeight)
                                                .fillMaxHeight()
                                                .background(outgoingColor)
                                        )
                                    }
                                    if (missedWeight > 0f) {
                                        Box(
                                            modifier = Modifier
                                                .weight(missedWeight)
                                                .fillMaxHeight()
                                                .background(missedColor)
                                        )
                                    }
                                }

                                Spacer(Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    BreakdownItem(
                                        icon = Icons.AutoMirrored.Filled.CallReceived,
                                        label = "Incoming",
                                        count = stats.incomingCalls,
                                        color = incomingColor
                                    )
                                    BreakdownItem(
                                        icon = Icons.AutoMirrored.Filled.CallMade,
                                        label = "Outgoing",
                                        count = stats.outgoingCalls,
                                        color = outgoingColor
                                    )
                                    BreakdownItem(
                                        icon = Icons.AutoMirrored.Filled.CallMissed,
                                        label = "Missed",
                                        count = stats.missedCalls,
                                        color = missedColor
                                    )
                                }

                                if (stats.simCalls > 0 || stats.sipCalls > 0) {
                                    Spacer(Modifier.height(10.dp))
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                    Spacer(Modifier.height(8.dp))
                                    val totalLineCount = (stats.simCalls + stats.sipCalls).coerceAtLeast(1).toFloat()
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        if (stats.simCalls > 0) {
                                            Box(
                                                modifier = Modifier
                                                    .weight(stats.simCalls / totalLineCount)
                                                    .fillMaxHeight()
                                                    .background(SeabornChartPalette.sim(isDarkSurface))
                                            )
                                        }
                                        if (stats.sipCalls > 0) {
                                            Box(
                                                modifier = Modifier
                                                    .weight(stats.sipCalls / totalLineCount)
                                                    .fillMaxHeight()
                                                    .background(SeabornChartPalette.sip(isDarkSurface))
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "SIM: ${stats.simCalls}   •   SIP: ${stats.sipCalls}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "Longest: ${formatStatsDuration(stats.longestCallSeconds)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        // Last 7 Days Chart (Seaborn Deep)
                        val isDarkDialogSurface = MaterialTheme.colorScheme.surface.luminance() < 0.5f
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = "Last 7 Days",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(12.dp))

                                val maxDaily = stats.dailyBuckets.maxOfOrNull { it.totalCalls }?.coerceAtLeast(1) ?: 1
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(104.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.Bottom
                                ) {
                                    stats.dailyBuckets.forEach { bucket ->
                                        val isPeakDay = bucket.totalCalls > 0 && bucket.totalCalls == maxDaily
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Bottom
                                        ) {
                                            Text(
                                                text = if (bucket.totalCalls > 0) bucket.totalCalls.toString() else "",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isPeakDay) {
                                                    SeabornChartPalette.peak(isDarkDialogSurface)
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                fontWeight = if (isPeakDay) FontWeight.Bold else FontWeight.Normal,
                                                maxLines = 1
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            val barFraction = (bucket.totalCalls.toFloat() / maxDaily.toFloat())
                                                .coerceIn(if (bucket.totalCalls > 0) 0.14f else 0.05f, 1f)
                                            val barColor = when {
                                                bucket.totalCalls == 0 -> MaterialTheme.colorScheme.surfaceVariant
                                                bucket.missedCalls == bucket.totalCalls -> SeabornChartPalette.missed(isDarkDialogSurface)
                                                isPeakDay -> SeabornChartPalette.peak(isDarkDialogSurface)
                                                else -> SeabornChartPalette.outgoing(isDarkDialogSurface)
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth(0.55f)
                                                    .height((60.dp * barFraction))
                                                    .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                                                    .background(barColor)
                                            )
                                            Spacer(Modifier.height(6.dp))
                                            Text(
                                                text = bucket.dayLabel,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                textAlign = TextAlign.Center,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Top Contacts (Universal Stats only)
                        if (isUniversal && stats.topContacts.isNotEmpty()) {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(
                                        text = "Top Contacts",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    stats.topContacts.forEachIndexed { idx, top ->
                                        val rowAccent = SeabornChartPalette.categorical(idx, isDarkDialogSurface)
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Surface(
                                                shape = CircleShape,
                                                color = rowAccent.copy(alpha = 0.18f),
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Text(
                                                        text = "${idx + 1}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = rowAccent
                                                    )
                                                }
                                            }
                                            Spacer(Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = top.displayName,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Medium,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                if (top.rawNumber.isNotBlank() && top.rawNumber != top.displayName) {
                                                    Text(
                                                        text = top.rawNumber,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                            Column(horizontalAlignment = Alignment.End) {
                                                Text(
                                                    text = "${top.totalCalls} ${if (top.totalCalls == 1) "call" else "calls"}",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = rowAccent
                                                )
                                                if (top.totalDurationSeconds > 0L) {
                                                    Text(
                                                        text = formatStatsDuration(top.totalDurationSeconds),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                        if (idx < stats.topContacts.lastIndex) {
                                            HorizontalDivider(
                                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("call_stats_close_button")
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatMetricCard(
    label: String,
    value: String,
    subtext: String,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun BreakdownItem(
    icon: ImageVector,
    label: String,
    count: Int,
    color: Color
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(5.dp))
        Column {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
