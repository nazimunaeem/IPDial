package com.ipdial.data.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.ipdial.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ScheduledCallAlertState {
    private val _activeAlert = MutableStateFlow<ScheduledCall?>(null)
    val activeAlert: StateFlow<ScheduledCall?> = _activeAlert.asStateFlow()

    fun trigger(call: ScheduledCall) {
        _activeAlert.value = call
    }

    fun clear(callId: String? = null) {
        val current = _activeAlert.value ?: return
        if (callId == null || current.id == callId) {
            _activeAlert.value = null
        }
    }
}

object CallSchedulerManager {

    private const val TAG = "CallSchedulerManager"

    const val ACTION_TRIGGER_SCHEDULED_CALL = "com.ipdial.ACTION_TRIGGER_SCHEDULED_CALL"
    const val ACTION_DISMISS_SCHEDULED_CALL = "com.ipdial.ACTION_DISMISS_SCHEDULED_CALL"
    const val ACTION_SNOOZE_SCHEDULED_CALL = "com.ipdial.ACTION_SNOOZE_SCHEDULED_CALL"
    const val ACTION_SCHEDULED_CALL_ALERT = "com.ipdial.ACTION_SCHEDULED_CALL_ALERT"
    const val EXTRA_SCHEDULED_CALL_ID = "scheduled_call_id"
    const val EXTRA_SCHEDULED_CALL_NAME = "scheduled_call_name"
    const val EXTRA_SCHEDULED_CALL_NUMBER = "scheduled_call_number"
    const val EXTRA_SCHEDULED_CALL_NOTE = "scheduled_call_note"
    const val EXTRA_SCHEDULED_CALL_LINE_ID = "scheduled_call_line_id"
    const val EXTRA_SCHEDULED_CALL_LINE_LABEL = "scheduled_call_line_label"

    fun requestCodeFor(callId: String): Int = 30_000 + (callId.hashCode() and 0x7FFF)

    fun scheduleAlarm(context: Context, call: ScheduledCall) {
        if (call.status != ScheduledCallStatus.PENDING) {
            cancelAlarm(context, call.id)
            return
        }
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAt = call.scheduledAtMs.coerceAtLeast(System.currentTimeMillis() + 1_000L)
        val pendingIntent = buildTriggerPendingIntent(context, call)

        try {
            val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alarmManager.canScheduleExactAlarms()
            } else {
                true
            }
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            }
            Log.d(TAG, "Scheduled call ${call.id} (${call.phoneNumber}) at $triggerAt exact=$canExact")
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm denied, using inexact allow-while-idle: ${e.message}")
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to schedule alarm: ${e.message}", e)
        }
    }

    fun cancelAlarm(context: Context, callId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, ScheduledCallReceiver::class.java).apply {
            action = ACTION_TRIGGER_SCHEDULED_CALL
        }
        val pending = PendingIntent.getBroadcast(
            context,
            requestCodeFor(callId),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pending != null) {
            alarmManager.cancel(pending)
            pending.cancel()
        }
    }

    suspend fun rescheduleAllPending(context: Context) {
        val repo = ScheduledCallRepository(context.applicationContext)
        val now = System.currentTimeMillis()
        repo.pendingFutureCalls(now).forEach { call ->
            scheduleAlarm(context.applicationContext, call)
        }
    }

    fun buildAlertActivityIntent(context: Context, call: ScheduledCall): Intent {
        return Intent(context, MainActivity::class.java).apply {
            action = ACTION_SCHEDULED_CALL_ALERT
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_NO_USER_ACTION
            putExtra(EXTRA_SCHEDULED_CALL_ID, call.id)
            putExtra(EXTRA_SCHEDULED_CALL_NAME, call.contactName)
            putExtra(EXTRA_SCHEDULED_CALL_NUMBER, call.phoneNumber)
            putExtra(EXTRA_SCHEDULED_CALL_NOTE, call.note)
            putExtra(EXTRA_SCHEDULED_CALL_LINE_ID, call.lineId.orEmpty())
            putExtra(EXTRA_SCHEDULED_CALL_LINE_LABEL, call.lineLabel.orEmpty())
        }
    }

    private fun buildTriggerPendingIntent(context: Context, call: ScheduledCall): PendingIntent {
        val intent = Intent(context, ScheduledCallReceiver::class.java).apply {
            action = ACTION_TRIGGER_SCHEDULED_CALL
            putExtra(EXTRA_SCHEDULED_CALL_ID, call.id)
            putExtra(EXTRA_SCHEDULED_CALL_NAME, call.contactName)
            putExtra(EXTRA_SCHEDULED_CALL_NUMBER, call.phoneNumber)
            putExtra(EXTRA_SCHEDULED_CALL_NOTE, call.note)
            putExtra(EXTRA_SCHEDULED_CALL_LINE_ID, call.lineId.orEmpty())
            putExtra(EXTRA_SCHEDULED_CALL_LINE_LABEL, call.lineLabel.orEmpty())
        }
        return PendingIntent.getBroadcast(
            context,
            requestCodeFor(call.id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
