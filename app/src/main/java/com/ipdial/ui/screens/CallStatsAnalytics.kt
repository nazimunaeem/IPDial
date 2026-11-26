package com.ipdial.ui.screens

import com.ipdial.data.model.CallDirection
import com.ipdial.data.model.CallLogEntry
import com.ipdial.data.model.CallTransport
import com.ipdial.data.model.Contact
import com.ipdial.ui.components.TopContactStat
import com.ipdial.ui.components.computeCallStats
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

enum class StatsPeriodMode {
    MONTH,
    YEAR,
    ALL_TIME
}

enum class StatsTransportFilter(val label: String) {
    ALL("All"),
    SIM("SIM"),
    SIP("SIP")
}

data class ChartBucket(
    val key: Int,
    val label: String,
    val fullLabel: String,
    val incoming: Int,
    val outgoing: Int,
    val missed: Int,
    val durationSeconds: Long
) {
    val total: Int get() = incoming + outgoing + missed
}

data class PeriodCallStats(
    val totalCalls: Int,
    val incomingCalls: Int,
    val outgoingCalls: Int,
    val missedCalls: Int,
    val totalDurationSeconds: Long,
    val avgAnsweredDurationSeconds: Long,
    val longestCallSeconds: Long,
    val simCalls: Int,
    val sipCalls: Int,
    val simDurationSeconds: Long,
    val sipDurationSeconds: Long,
    val answerRatePercent: Int,
    val chartBuckets: List<ChartBucket>,
    val weekdayBuckets: List<ChartBucket>,
    val topContacts: List<TopContactStat>
)

fun filterEntriesByPeriod(
    entries: List<CallLogEntry>,
    mode: StatsPeriodMode,
    year: Int,
    monthZeroBased: Int,
    transportFilter: StatsTransportFilter = StatsTransportFilter.ALL
): List<CallLogEntry> {
    val cal = Calendar.getInstance()
    return entries.filter { entry ->
        val transportMatch = when (transportFilter) {
            StatsTransportFilter.ALL -> true
            StatsTransportFilter.SIM -> entry.transport == CallTransport.SIM
            StatsTransportFilter.SIP -> entry.transport == CallTransport.SIP
        }
        if (!transportMatch) return@filter false

        when (mode) {
            StatsPeriodMode.ALL_TIME -> true
            StatsPeriodMode.YEAR -> {
                cal.timeInMillis = entry.timestampMs
                cal.get(Calendar.YEAR) == year
            }
            StatsPeriodMode.MONTH -> {
                cal.timeInMillis = entry.timestampMs
                cal.get(Calendar.YEAR) == year && cal.get(Calendar.MONTH) == monthZeroBased
            }
        }
    }
}

fun availableYears(
    entries: List<CallLogEntry>,
    currentYear: Int = Calendar.getInstance().get(Calendar.YEAR)
): List<Int> {
    val cal = Calendar.getInstance()
    val years = entries.mapTo(mutableSetOf()) { entry ->
        cal.timeInMillis = entry.timestampMs
        cal.get(Calendar.YEAR)
    }
    years.add(currentYear)
    return years.sortedDescending()
}

fun activeMonthsInYear(entries: List<CallLogEntry>, year: Int): Set<Int> {
    val cal = Calendar.getInstance()
    val months = mutableSetOf<Int>()
    entries.forEach { entry ->
        cal.timeInMillis = entry.timestampMs
        if (cal.get(Calendar.YEAR) == year) {
            months.add(cal.get(Calendar.MONTH))
        }
    }
    return months
}

fun latestEntryYearMonth(entries: List<CallLogEntry>): Pair<Int, Int>? {
    val latest = entries.maxByOrNull { it.timestampMs } ?: return null
    val cal = Calendar.getInstance().apply { timeInMillis = latest.timestampMs }
    return cal.get(Calendar.YEAR) to cal.get(Calendar.MONTH)
}

fun stepMonthYear(year: Int, monthZeroBased: Int, deltaMonths: Int): Pair<Int, Int> {
    val cal = Calendar.getInstance().apply {
        clear()
        set(Calendar.YEAR, year)
        set(Calendar.MONTH, monthZeroBased)
        set(Calendar.DAY_OF_MONTH, 1)
        add(Calendar.MONTH, deltaMonths)
    }
    return cal.get(Calendar.YEAR) to cal.get(Calendar.MONTH)
}

fun computePeriodCallStats(
    allEntries: List<CallLogEntry>,
    contacts: List<Contact> = emptyList(),
    mode: StatsPeriodMode,
    year: Int,
    monthZeroBased: Int,
    transportFilter: StatsTransportFilter = StatsTransportFilter.ALL,
    locale: Locale = Locale.getDefault()
): PeriodCallStats {
    val filtered = filterEntriesByPeriod(
        entries = allEntries,
        mode = mode,
        year = year,
        monthZeroBased = monthZeroBased,
        transportFilter = transportFilter
    )

    val totalCalls = filtered.size
    val missedCalls = filtered.count { it.missed }
    val incomingCalls = filtered.count { !it.missed && it.direction == CallDirection.INCOMING }
    val outgoingCalls = filtered.count { !it.missed && it.direction == CallDirection.OUTGOING }

    val answered = filtered.filter { !it.missed }
    val totalDurationSeconds = answered.sumOf { it.durationSeconds.coerceAtLeast(0L) }
    val connected = answered.filter { it.durationSeconds > 0L }
    val avgAnsweredDurationSeconds = if (connected.isEmpty()) 0L else totalDurationSeconds / connected.size
    val longestCallSeconds = answered.maxOfOrNull { it.durationSeconds.coerceAtLeast(0L) } ?: 0L

    val simEntries = filtered.filter { it.transport == CallTransport.SIM }
    val sipEntries = filtered.filter { it.transport == CallTransport.SIP }
    val simCalls = simEntries.size
    val sipCalls = sipEntries.size
    val simDurationSeconds = simEntries.filter { !it.missed }.sumOf { it.durationSeconds.coerceAtLeast(0L) }
    val sipDurationSeconds = sipEntries.filter { !it.missed }.sumOf { it.durationSeconds.coerceAtLeast(0L) }

    val answerRatePercent = if (totalCalls == 0) 0 else ((answered.size * 100f) / totalCalls).roundToInt()

    val chartBuckets = when (mode) {
        StatsPeriodMode.MONTH -> buildDailyMonthBuckets(filtered, year, monthZeroBased, locale)
        StatsPeriodMode.YEAR -> buildMonthlyYearBuckets(filtered, year, locale)
        StatsPeriodMode.ALL_TIME -> buildAllTimeBuckets(filtered, locale)
    }
    val weekdayBuckets = buildWeekdayBuckets(filtered, locale)
    val topContacts = computeCallStats(entries = filtered, contacts = contacts, locale = locale).topContacts

    return PeriodCallStats(
        totalCalls = totalCalls,
        incomingCalls = incomingCalls,
        outgoingCalls = outgoingCalls,
        missedCalls = missedCalls,
        totalDurationSeconds = totalDurationSeconds,
        avgAnsweredDurationSeconds = avgAnsweredDurationSeconds,
        longestCallSeconds = longestCallSeconds,
        simCalls = simCalls,
        sipCalls = sipCalls,
        simDurationSeconds = simDurationSeconds,
        sipDurationSeconds = sipDurationSeconds,
        answerRatePercent = answerRatePercent,
        chartBuckets = chartBuckets,
        weekdayBuckets = weekdayBuckets,
        topContacts = topContacts
    )
}

private fun buildDailyMonthBuckets(
    entries: List<CallLogEntry>,
    year: Int,
    monthZeroBased: Int,
    locale: Locale
): List<ChartBucket> {
    val cal = Calendar.getInstance().apply {
        clear()
        set(Calendar.YEAR, year)
        set(Calendar.MONTH, monthZeroBased)
        set(Calendar.DAY_OF_MONTH, 1)
    }
    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val fullFmt = SimpleDateFormat("MMM d, yyyy", locale)
    val grouped = entries.groupBy { entry ->
        Calendar.getInstance().apply { timeInMillis = entry.timestampMs }.get(Calendar.DAY_OF_MONTH)
    }

    return (1..daysInMonth).map { day ->
        val dayCal = (cal.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, day) }
        val list = grouped[day].orEmpty()
        ChartBucket(
            key = day,
            label = day.toString(),
            fullLabel = fullFmt.format(dayCal.time),
            incoming = list.count { !it.missed && it.direction == CallDirection.INCOMING },
            outgoing = list.count { !it.missed && it.direction == CallDirection.OUTGOING },
            missed = list.count { it.missed },
            durationSeconds = list.filter { !it.missed }.sumOf { it.durationSeconds.coerceAtLeast(0L) }
        )
    }
}

private fun buildMonthlyYearBuckets(
    entries: List<CallLogEntry>,
    year: Int,
    locale: Locale
): List<ChartBucket> {
    val shortFmt = SimpleDateFormat("MMM", locale)
    val fullFmt = SimpleDateFormat("MMMM yyyy", locale)
    val cal = Calendar.getInstance()
    val grouped = entries.groupBy { entry ->
        cal.timeInMillis = entry.timestampMs
        cal.get(Calendar.MONTH)
    }

    return (0..11).map { month ->
        val monthCal = Calendar.getInstance().apply {
            clear()
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        val list = grouped[month].orEmpty()
        ChartBucket(
            key = month,
            label = shortFmt.format(monthCal.time).take(3),
            fullLabel = fullFmt.format(monthCal.time),
            incoming = list.count { !it.missed && it.direction == CallDirection.INCOMING },
            outgoing = list.count { !it.missed && it.direction == CallDirection.OUTGOING },
            missed = list.count { it.missed },
            durationSeconds = list.filter { !it.missed }.sumOf { it.durationSeconds.coerceAtLeast(0L) }
        )
    }
}

private fun buildAllTimeBuckets(
    entries: List<CallLogEntry>,
    locale: Locale
): List<ChartBucket> {
    if (entries.isEmpty()) return emptyList()
    val shortFmt = SimpleDateFormat("MMM ''yy", locale)
    val fullFmt = SimpleDateFormat("MMMM yyyy", locale)
    val cal = Calendar.getInstance()

    val minTs = entries.minOf { it.timestampMs }
    val maxTs = entries.maxOf { it.timestampMs }
    val startCal = Calendar.getInstance().apply {
        timeInMillis = minTs
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val endCal = Calendar.getInstance().apply {
        timeInMillis = maxTs
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    val months = mutableListOf<Pair<Int, Int>>()
    val cursor = startCal.clone() as Calendar
    while (!cursor.after(endCal)) {
        months.add(cursor.get(Calendar.YEAR) to cursor.get(Calendar.MONTH))
        cursor.add(Calendar.MONTH, 1)
    }

    // If spanning more than 12 months, show the most recent 12 months in the monthly bar chart
    val displayMonths = if (months.size > 12) months.takeLast(12) else months
    val grouped = entries.groupBy { entry ->
        cal.timeInMillis = entry.timestampMs
        cal.get(Calendar.YEAR) * 12 + cal.get(Calendar.MONTH)
    }

    return displayMonths.mapIndexed { idx, (y, m) ->
        val mCal = Calendar.getInstance().apply {
            clear()
            set(Calendar.YEAR, y)
            set(Calendar.MONTH, m)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        val list = grouped[y * 12 + m].orEmpty()
        ChartBucket(
            key = idx,
            label = shortFmt.format(mCal.time),
            fullLabel = fullFmt.format(mCal.time),
            incoming = list.count { !it.missed && it.direction == CallDirection.INCOMING },
            outgoing = list.count { !it.missed && it.direction == CallDirection.OUTGOING },
            missed = list.count { it.missed },
            durationSeconds = list.filter { !it.missed }.sumOf { it.durationSeconds.coerceAtLeast(0L) }
        )
    }
}

private fun buildWeekdayBuckets(
    entries: List<CallLogEntry>,
    locale: Locale
): List<ChartBucket> {
    val orderedWeekdays = listOf(
        Calendar.MONDAY,
        Calendar.TUESDAY,
        Calendar.WEDNESDAY,
        Calendar.THURSDAY,
        Calendar.FRIDAY,
        Calendar.SATURDAY,
        Calendar.SUNDAY
    )
    val shortFmt = SimpleDateFormat("EEE", locale)
    val fullFmt = SimpleDateFormat("EEEE", locale)
    val cal = Calendar.getInstance()
    val grouped = entries.groupBy { entry ->
        cal.timeInMillis = entry.timestampMs
        cal.get(Calendar.DAY_OF_WEEK)
    }

    return orderedWeekdays.map { dayOfWeek ->
        val dayCal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, dayOfWeek)
        }
        val list = grouped[dayOfWeek].orEmpty()
        ChartBucket(
            key = dayOfWeek,
            label = shortFmt.format(dayCal.time).take(3),
            fullLabel = fullFmt.format(dayCal.time),
            incoming = list.count { !it.missed && it.direction == CallDirection.INCOMING },
            outgoing = list.count { !it.missed && it.direction == CallDirection.OUTGOING },
            missed = list.count { it.missed },
            durationSeconds = list.filter { !it.missed }.sumOf { it.durationSeconds.coerceAtLeast(0L) }
        )
    }
}
