package com.ipdial.ui.components

import com.ipdial.data.model.CallDirection
import com.ipdial.data.model.CallLogEntry
import com.ipdial.data.model.CallTransport
import com.ipdial.data.model.Contact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class CallStatsTest {

    @Test
    fun `phoneNumbersMatch matches country code variants and sip uris`() {
        assertTrue(phoneNumbersMatch("sip:+8801712345678@pbx.example.com", "01712345678"))
        assertTrue(phoneNumbersMatch("01712-345678", "+8801712345678"))
        assertTrue(phoneNumbersMatch("sip:alice@pbx.example.com", "alice"))
        assertFalse(phoneNumbersMatch("01712345678", "01812345678"))
    }

    @Test
    fun `filterHistoryForTarget includes all numbers of a contact without 7 day cutoff`() {
        val contact = Contact(
            id = "c1",
            name = "Alice",
            numbers = listOf("+8801711111111", "01822222222")
        )
        val now = 1_750_000_000_000L
        val thirtyDaysAgo = now - 30L * 24L * 60L * 60L * 1000L

        val entries = listOf(
            CallLogEntry(id = "1", remoteUri = "01711111111", direction = CallDirection.INCOMING, missed = false, timestampMs = thirtyDaysAgo, durationSeconds = 90),
            CallLogEntry(id = "2", remoteUri = "01822222222", direction = CallDirection.INCOMING, missed = true, timestampMs = now - 10_000L, durationSeconds = 0),
            CallLogEntry(id = "3", remoteUri = "01933333333", direction = CallDirection.OUTGOING, missed = false, timestampMs = now, durationSeconds = 45)
        )

        val result = filterHistoryForTarget(entries, "01711111111", contact)
        assertEquals(listOf("2", "1"), result.map { it.id })
    }

    @Test
    fun `computeCallStats calculates totals breakdown transport split and top contacts`() {
        val now = 1_750_000_000_000L
        val oneDayMs = 24L * 60L * 60L * 1000L
        val contact = Contact(id = "c1", name = "Bob", numbers = listOf("01700000001"))

        val entries = listOf(
            CallLogEntry(id = "1", remoteUri = "01700000001", direction = CallDirection.OUTGOING, missed = false, timestampMs = now - 1_000L, durationSeconds = 120, transport = CallTransport.SIP),
            CallLogEntry(id = "2", remoteUri = "01700000001", direction = CallDirection.INCOMING, missed = false, timestampMs = now - oneDayMs, durationSeconds = 60, transport = CallTransport.SIM),
            CallLogEntry(id = "3", remoteUri = "01700000001", direction = CallDirection.INCOMING, missed = true, timestampMs = now - 2 * oneDayMs, durationSeconds = 0, transport = CallTransport.SIM),
            CallLogEntry(id = "4", remoteUri = "01800000002", direction = CallDirection.OUTGOING, missed = false, timestampMs = now - 10 * oneDayMs, durationSeconds = 30, transport = CallTransport.SIP)
        )

        val stats = computeCallStats(entries, listOf(contact), nowMs = now, locale = Locale.US)

        assertEquals(4, stats.totalCalls)
        assertEquals(1, stats.incomingCalls)
        assertEquals(2, stats.outgoingCalls)
        assertEquals(1, stats.missedCalls)
        assertEquals(210L, stats.totalDurationSeconds)
        assertEquals(70L, stats.avgAnsweredDurationSeconds)
        assertEquals(120L, stats.longestCallSeconds)
        assertEquals(2, stats.simCalls)
        assertEquals(2, stats.sipCalls)
        assertEquals(75, stats.answerRatePercent)
        assertEquals(7, stats.dailyBuckets.size)
        assertEquals(3, stats.dailyBuckets.sumOf { it.totalCalls }) // 4th call was 10 days ago
        assertEquals("Bob", stats.topContacts.first().displayName)
        assertEquals(3, stats.topContacts.first().totalCalls)
    }
}
