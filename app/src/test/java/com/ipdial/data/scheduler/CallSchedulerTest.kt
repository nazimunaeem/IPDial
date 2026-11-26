package com.ipdial.data.scheduler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class CallSchedulerTest {

    @Test
    fun `resolveVerticalSlideAction confirms on slide up past threshold`() {
        assertEquals(
            ScheduledCallSwipeAction.CONFIRM_UP,
            resolveVerticalSlideAction(offsetY = -120f, thresholdPx = 100f)
        )
        assertEquals(
            ScheduledCallSwipeAction.CONFIRM_UP,
            resolveVerticalSlideAction(offsetY = -100f, thresholdPx = 100f)
        )
    }

    @Test
    fun `resolveVerticalSlideAction rejects on slide down past threshold`() {
        assertEquals(
            ScheduledCallSwipeAction.REJECT_DOWN,
            resolveVerticalSlideAction(offsetY = 120f, thresholdPx = 100f)
        )
        assertEquals(
            ScheduledCallSwipeAction.REJECT_DOWN,
            resolveVerticalSlideAction(offsetY = 100f, thresholdPx = 100f)
        )
    }

    @Test
    fun `resolveVerticalSlideAction returns none inside deadzone`() {
        assertEquals(
            ScheduledCallSwipeAction.NONE,
            resolveVerticalSlideAction(offsetY = -99f, thresholdPx = 100f)
        )
        assertEquals(
            ScheduledCallSwipeAction.NONE,
            resolveVerticalSlideAction(offsetY = 0f, thresholdPx = 100f)
        )
        assertEquals(
            ScheduledCallSwipeAction.NONE,
            resolveVerticalSlideAction(offsetY = 99f, thresholdPx = 100f)
        )
    }

    @Test
    fun `combineDateAndTimeMillis preserves UTC date and applies local time`() {
        val utcDate = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2027, Calendar.MARCH, 15)
        }.timeInMillis

        val combined = combineDateAndTimeMillis(utcDate, hourOfDay = 14, minute = 35)
        val localResult = Calendar.getInstance().apply { timeInMillis = combined }

        assertEquals(2027, localResult.get(Calendar.YEAR))
        assertEquals(Calendar.MARCH, localResult.get(Calendar.MONTH))
        assertEquals(15, localResult.get(Calendar.DAY_OF_MONTH))
        assertEquals(14, localResult.get(Calendar.HOUR_OF_DAY))
        assertEquals(35, localResult.get(Calendar.MINUTE))
        assertEquals(0, localResult.get(Calendar.SECOND))
    }

    @Test
    fun `formatCountdownLabel formats minutes hours days and due now`() {
        val now = 1_700_000_000_000L
        assertEquals("Due now", formatCountdownLabel(now, now))
        assertEquals("Due now", formatCountdownLabel(now - 5_000L, now))
        assertEquals("In 5m", formatCountdownLabel(now + 5 * 60_000L, now))
        assertEquals("In 2h 15m", formatCountdownLabel(now + (2 * 60 + 15) * 60_000L, now))
        assertEquals("In 1d 3h", formatCountdownLabel(now + (27 * 60) * 60_000L, now))
    }

    @Test
    fun `repository serialize and parse round-trips scheduled calls and drops blank numbers`() {
        val valid = ScheduledCall(
            id = "sched-1",
            contactName = "Alice",
            phoneNumber = "+15551234567",
            scheduledAtMs = 1_800_000_000_000L,
            note = "Project sync",
            lineId = "sip_work",
            lineLabel = "Work SIP",
            status = ScheduledCallStatus.PENDING,
            createdAtMs = 1_700_000_000_000L
        )
        val invalid = ScheduledCall(
            id = "sched-2",
            contactName = "Empty",
            phoneNumber = "   ",
            scheduledAtMs = 1_800_000_000_000L
        )

        val json = ScheduledCallRepository.serializeList(listOf(valid, invalid))
        val parsed = ScheduledCallRepository.parseList(json)

        assertEquals(1, parsed.size)
        assertEquals(valid, parsed.first())
        assertTrue(ScheduledCallRepository.parseList("not-json").isEmpty())

        val legacyJson = """[{"id":"old-1","contactName":"Bob","phoneNumber":"100","scheduledAtMs":1800000000000,"note":"","status":"PENDING","createdAtMs":1700000000000}]"""
        val legacyParsed = ScheduledCallRepository.parseList(legacyJson)
        assertEquals(1, legacyParsed.size)
        assertEquals(null, legacyParsed.first().lineId)
        assertEquals(null, legacyParsed.first().lineLabel)
    }
}
