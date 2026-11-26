package com.ipdial.data.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ScheduledCallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val callId = intent.getStringExtra(CallSchedulerManager.EXTRA_SCHEDULED_CALL_ID).orEmpty()
        if (callId.isBlank()) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = ScheduledCallRepository(appContext)
                when (action) {
                    CallSchedulerManager.ACTION_TRIGGER_SCHEDULED_CALL -> {
                        val stored = repo.getById(callId)
                        val call = stored ?: ScheduledCall(
                            id = callId,
                            contactName = intent.getStringExtra(CallSchedulerManager.EXTRA_SCHEDULED_CALL_NAME).orEmpty(),
                            phoneNumber = intent.getStringExtra(CallSchedulerManager.EXTRA_SCHEDULED_CALL_NUMBER).orEmpty(),
                            scheduledAtMs = System.currentTimeMillis(),
                            note = intent.getStringExtra(CallSchedulerManager.EXTRA_SCHEDULED_CALL_NOTE).orEmpty(),
                            lineId = intent.getStringExtra(CallSchedulerManager.EXTRA_SCHEDULED_CALL_LINE_ID)?.takeIf { it.isNotBlank() },
                            lineLabel = intent.getStringExtra(CallSchedulerManager.EXTRA_SCHEDULED_CALL_LINE_LABEL)?.takeIf { it.isNotBlank() },
                            status = ScheduledCallStatus.TRIGGERED
                        )
                        if (call.phoneNumber.isNotBlank()) {
                            val triggered = call.copy(status = ScheduledCallStatus.TRIGGERED)
                            repo.upsert(triggered)
                            ScheduledCallAlertState.trigger(triggered)
                            ScheduledCallAlertService.start(appContext, triggered)
                        }
                    }
                    CallSchedulerManager.ACTION_DISMISS_SCHEDULED_CALL -> {
                        ScheduledCallAlertService.stop(appContext)
                        ScheduledCallAlertState.clear(callId)
                        repo.updateStatus(callId, ScheduledCallStatus.DISMISSED)
                    }
                    CallSchedulerManager.ACTION_SNOOZE_SCHEDULED_CALL -> {
                        ScheduledCallAlertService.stop(appContext)
                        ScheduledCallAlertState.clear(callId)
                        val existing = repo.getById(callId)
                        if (existing != null) {
                            val snoozed = existing.copy(
                                scheduledAtMs = System.currentTimeMillis() + 5 * 60_000L,
                                status = ScheduledCallStatus.PENDING
                            )
                            repo.upsert(snoozed)
                            CallSchedulerManager.scheduleAlarm(appContext, snoozed)
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
