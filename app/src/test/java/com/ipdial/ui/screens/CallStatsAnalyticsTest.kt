package com.ipdial.ui.screens

import com.ipdial.data.model.CallDirection
import com.ipdial.data.model.CallLogEntry
import com.ipdial.data.model.CallTransport
import com.ipdial.data.model.Contact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class CallStatsAnalyticsTest {

    private fun ts(year: Int, monthZeroBased: Int, day: Int, hour: Int = 12): Long {
        return Calendar.getInstance().apply {
            clear()
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, monthZeroBased)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }.timeInMillis
    }

    @Test
    fun `month mode filters entries and builds daily buckets for selected month and year`() {
        val entries = listOf(
            CallLogEntry(id = "1", remoteUri = "01711111111", direction = CallDirection.INCOMING, missed = false, timestampMs = ts(2026, Calendar.NOVEMBER, 5), durationSeconds = 90, transport = CallTransport.SIM),
            CallLogEntry(id = "2", remoteUri = "01711111111", direction = CallDirection.OUTGOING, missed = false, timestampMs = ts(2026, Calendar.NOVEMBER, 5, 16), durationSeconds = 30, transport = CallTransport.SIP),
            CallLogEntry(id = "3", remoteUri = "01822222222", direction = CallDirection.INCOMING, missed = true, timestampMs = ts(2026, Calendar.NOVEMBER, 20), durationSeconds = 0, transport = CallTransport.SIM),
            CallLogEntry(id = "4", remoteUri = "01711111111", direction = CallDirection.OUTGOING, missed = false, timestampMs = ts(2026, Calendar.OCTOBER, 15), durationSeconds = 200, transport = CallTransport.SIP),
            CallLogEntry(id = "5", remoteUri = "01711111111", direction = CallDirection.OUTGOING, missed = false, timestampMs = ts(2025, Calendar.NOVEMBER, 5), durationSeconds = 300, transport = CallTransport.SIM)
        )
        val contact = Contact(id = "c1", name = "Alice", numbers = listOf("01711111111"))

        val stats = computePeriodCallStats(
            allEntries = entries,
            contacts = listOf(contact),
            mode = StatsPeriodMode.MONTH,
            year = 2026,
            monthZeroBased = Calendar.NOVEMBER,
            transportFilter = StatsTransportFilter.ALL,
            locale = Locale.US
        )

        assertEquals(3, stats.totalCalls)
        assertEquals(1, stats.incomingCalls)
        assertEquals(1, stats.outgoingCalls)
        assertEquals(1, stats.missedCalls)
        assertEquals(120L, stats.totalDurationSeconds)
        assertEquals(60L, stats.avgAnsweredDurationSeconds)
        assertEquals(90L, stats.longestCallSeconds)
        assertEquals(2, stats.simCalls)
        assertEquals(1, stats.sipCalls)
        assertEquals(30, stats.chartBuckets.size) // November has 30 days
        assertEquals(2, stats.chartBuckets[4].total) // Day 5
        assertEquals(120L, stats.chartBuckets[4].durationSeconds)
        assertEquals(1, stats.chartBuckets[19].missed) // Day 20
        assertEquals("Alice", stats.topContacts.first().displayName)
        assertEquals(2, stats.topContacts.first().totalCalls)
    }

    @Test
    fun `year mode aggregates 12 monthly buckets and respects transport filter`() {
        val entries = listOf(
            CallLogEntry(id = "1", remoteUri = "1001", direction = CallDirection.OUTGOING, missed = false, timestampMs = ts(2026, Calendar.JANUARY, 10), durationSeconds = 40, transport = CallTransport.SIP),
            CallLogEntry(id = "2", remoteUri = "1002", direction = CallDirection.INCOMING, missed = false, timestampMs = ts(2026, Calendar.MARCH, 2), durationSeconds = 80, transport = CallTransport.SIM),
            CallLogEntry(id = "3", remoteUri = "1003", direction = CallDirection.OUTGOING, missed = false, timestampMs = ts(2026, Calendar.MARCH, 8), durationSeconds = 60, transport = CallTransport.SIP)
        )

        val sipOnly = computePeriodCallStats(
            allEntries = entries,
            mode = StatsPeriodMode.YEAR,
            year = 2026,
            monthZeroBased = Calendar.JANUARY,
            transportFilter = StatsTransportFilter.SIP,
            locale = Locale.US
        )

        assertEquals(2, sipOnly.totalCalls)
        assertEquals(100L, sipOnly.totalDurationSeconds)
        assertEquals(12, sipOnly.chartBuckets.size)
        assertEquals(1, sipOnly.chartBuckets[Calendar.JANUARY].total)
        assertEquals(1, sipOnly.chartBuckets[Calendar.MARCH].total)
        assertEquals(0, sipOnly.chartBuckets[Calendar.FEBRUARY].total)
    }

    @Test
    fun `stepMonthYear and activeMonthsInYear handle year rollovers cleanly`() {
        assertEquals(2025 to Calendar.DECEMBER, stepMonthYear(2026, Calendar.JANUARY, -1))
        assertEquals(2027 to Calendar.JANUARY, stepMonthYear(2026, Calendar.DECEMBER, +1))

        val entries = listOf(
            CallLogEntry(id = "1", remoteUri = "100", timestampMs = ts(2026, Calendar.FEBRUARY, 1)),
            CallLogEntry(id = "2", remoteUri = "100", timestampMs = ts(2026, Calendar.NOVEMBER, 12)),
            CallLogEntry(id = "3", remoteUri = "100", timestampMs = ts(2025, Calendar.MAY, 12))
        )

        val active2026 = activeMonthsInYear(entries, 2026)
        assertEquals(setOf(Calendar.FEBRUARY, Calendar.NOVEMBER), active2026)
        val years = availableYears(entries, currentYear = 2026)
        assertTrue(years.containsAll(listOf(2026, 2025)))
        assertEquals(2026 to Calendar.NOVEMBER, latestEntryYearMonth(entries))
    }
}
