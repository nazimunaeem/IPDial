package com.ipdial.data.scheduler

import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

enum class ScheduledCallStatus {
    PENDING,
    TRIGGERED,
    COMPLETED,
    DISMISSED
}

enum class ScheduledCallSwipeAction {
    NONE,
    CONFIRM_UP,
    REJECT_DOWN
}

data class ScheduledCall(
    val id: String = UUID.randomUUID().toString(),
    val contactName: String = "",
    val phoneNumber: String = "",
    val scheduledAtMs: Long = 0L,
    val note: String = "",
    val status: ScheduledCallStatus = ScheduledCallStatus.PENDING,
    val createdAtMs: Long = System.currentTimeMillis()
) {
    fun displayTitle(): String = contactName.ifBlank { phoneNumber }
}

/**
 * Pure helper for vertical slider confirmation:
 * - Dragging UP (negative Y <= -thresholdPx) confirms & places the call.
 * - Dragging DOWN (positive Y >= thresholdPx) rejects/dismisses the scheduled call.
 */
fun resolveVerticalSlideAction(offsetY: Float, thresholdPx: Float): ScheduledCallSwipeAction = when {
    offsetY <= -thresholdPx -> ScheduledCallSwipeAction.CONFIRM_UP
    offsetY >= thresholdPx -> ScheduledCallSwipeAction.REJECT_DOWN
    else -> ScheduledCallSwipeAction.NONE
}

fun combineDateAndTimeMillis(
    selectedDateUtcMillis: Long,
    hourOfDay: Int,
    minute: Int
): Long {
    val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = selectedDateUtcMillis
    }
    val localCal = Calendar.getInstance().apply {
        set(Calendar.YEAR, utcCal.get(Calendar.YEAR))
        set(Calendar.MONTH, utcCal.get(Calendar.MONTH))
        set(Calendar.DAY_OF_MONTH, utcCal.get(Calendar.DAY_OF_MONTH))
        set(Calendar.HOUR_OF_DAY, hourOfDay)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return localCal.timeInMillis
}

fun formatCountdownLabel(targetMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val diffMs = targetMs - nowMs
    if (diffMs <= 0L) return "Due now"
    val totalMinutes = (diffMs + 59_999L) / 60_000L
    val days = totalMinutes / (24L * 60L)
    val hours = (totalMinutes % (24L * 60L)) / 60L
    val mins = totalMinutes % 60L
    return when {
        days > 0L -> "In ${days}d ${hours}h"
        hours > 0L -> "In ${hours}h ${mins}m"
        else -> "In ${mins}m"
    }
}
