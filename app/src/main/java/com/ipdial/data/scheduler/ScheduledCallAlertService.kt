package com.ipdial.data.scheduler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ipdial.R
import com.ipdial.data.repository.AccountRepository
import com.ipdial.service.IncomingCallPresentationHelper
import com.ipdial.service.SipRingtonePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Rings and vibrates when a scheduled call reminder fires, and posts a high-priority
 * full-screen notification so the user can slide up to confirm calling or down to reject.
 */
class ScheduledCallAlertService : Service() {

    private var ringtonePlayer: SipRingtonePlayer? = null
    private var autoSilenceJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        ringtonePlayer = SipRingtonePlayer(this, audioManager, AccountRepository(this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALERT) {
            stopAlertAndSelf()
            return START_NOT_STICKY
        }

        val callId = intent?.getStringExtra(CallSchedulerManager.EXTRA_SCHEDULED_CALL_ID).orEmpty()
        val name = intent?.getStringExtra(CallSchedulerManager.EXTRA_SCHEDULED_CALL_NAME).orEmpty()
        val number = intent?.getStringExtra(CallSchedulerManager.EXTRA_SCHEDULED_CALL_NUMBER).orEmpty()
        val note = intent?.getStringExtra(CallSchedulerManager.EXTRA_SCHEDULED_CALL_NOTE).orEmpty()
        val lineId = intent?.getStringExtra(CallSchedulerManager.EXTRA_SCHEDULED_CALL_LINE_ID)?.takeIf { it.isNotBlank() }
        val lineLabel = intent?.getStringExtra(CallSchedulerManager.EXTRA_SCHEDULED_CALL_LINE_LABEL)?.takeIf { it.isNotBlank() }
        if (number.isBlank()) {
            stopAlertAndSelf()
            return START_NOT_STICKY
        }

        val call = ScheduledCall(
            id = callId,
            contactName = name,
            phoneNumber = number,
            scheduledAtMs = System.currentTimeMillis(),
            note = note,
            lineId = lineId,
            lineLabel = lineLabel,
            status = ScheduledCallStatus.TRIGGERED
        )

        ScheduledCallAlertState.trigger(call)

        ensureChannel()
        val notification = buildAlertNotification(this, call)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIF_ID_SCHEDULED_CALL,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIF_ID_SCHEDULED_CALL, notification)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "startForeground fallback to notify: ${e.message}")
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID_SCHEDULED_CALL, notification)
        }

        ringtonePlayer?.playRingtone()
        autoSilenceJob?.cancel()
        autoSilenceJob = serviceScope.launch {
            delay(45_000L)
            ringtonePlayer?.stopRingtone()
        }

        if (IncomingCallPresentationHelper.isDeviceLockedOrScreenOff(this)) {
            runCatching {
                startActivity(CallSchedulerManager.buildAlertActivityIntent(this, call))
            }
        }

        return START_NOT_STICKY
    }

    private fun stopAlertAndSelf() {
        autoSilenceJob?.cancel()
        autoSilenceJob = null
        ringtonePlayer?.stopRingtone()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID_SCHEDULED_CALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        autoSilenceJob?.cancel()
        ringtonePlayer?.stopRingtone()
        super.onDestroy()
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Scheduled Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts with ring & vibration when a scheduled call is due"
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "ScheduledCallService"
        const val CHANNEL_ID = "ipdial_scheduled_calls"
        const val NOTIF_ID_SCHEDULED_CALL = 1042
        private const val ACTION_STOP_ALERT = "com.ipdial.ACTION_STOP_SCHEDULED_ALERT"

        fun start(context: Context, call: ScheduledCall) {
            val intent = Intent(context, ScheduledCallAlertService::class.java).apply {
                putExtra(CallSchedulerManager.EXTRA_SCHEDULED_CALL_ID, call.id)
                putExtra(CallSchedulerManager.EXTRA_SCHEDULED_CALL_NAME, call.contactName)
                putExtra(CallSchedulerManager.EXTRA_SCHEDULED_CALL_NUMBER, call.phoneNumber)
                putExtra(CallSchedulerManager.EXTRA_SCHEDULED_CALL_NOTE, call.note)
                putExtra(CallSchedulerManager.EXTRA_SCHEDULED_CALL_LINE_ID, call.lineId.orEmpty())
                putExtra(CallSchedulerManager.EXTRA_SCHEDULED_CALL_LINE_LABEL, call.lineLabel.orEmpty())
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Throwable) {
                Log.w(TAG, "startForegroundService failed, posting direct notification: ${e.message}")
                postFallbackNotification(context, call)
            }
        }

        fun stop(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIF_ID_SCHEDULED_CALL)
            runCatching {
                context.stopService(Intent(context, ScheduledCallAlertService::class.java))
            }
        }

        private fun postFallbackNotification(context: Context, call: ScheduledCall) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Scheduled Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                        enableVibration(true)
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    }
                )
            }
            nm.notify(NOTIF_ID_SCHEDULED_CALL, buildAlertNotification(context, call))
        }

        fun buildAlertNotification(context: Context, call: ScheduledCall): Notification {
            val openIntent = CallSchedulerManager.buildAlertActivityIntent(context, call)
            val fullScreenPi = PendingIntent.getActivity(
                context,
                CallSchedulerManager.requestCodeFor(call.id),
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val dismissIntent = Intent(context, ScheduledCallReceiver::class.java).apply {
                action = CallSchedulerManager.ACTION_DISMISS_SCHEDULED_CALL
                putExtra(CallSchedulerManager.EXTRA_SCHEDULED_CALL_ID, call.id)
            }
            val dismissPi = PendingIntent.getBroadcast(
                context,
                CallSchedulerManager.requestCodeFor(call.id) + 10_000,
                dismissIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val snoozeIntent = Intent(context, ScheduledCallReceiver::class.java).apply {
                action = CallSchedulerManager.ACTION_SNOOZE_SCHEDULED_CALL
                putExtra(CallSchedulerManager.EXTRA_SCHEDULED_CALL_ID, call.id)
            }
            val snoozePi = PendingIntent.getBroadcast(
                context,
                CallSchedulerManager.requestCodeFor(call.id) + 20_000,
                snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val title = "Scheduled Call: ${call.displayTitle()}"
            val contentText = buildString {
                append(call.phoneNumber)
                if (call.note.isNotBlank()) append(" • ${call.note}")
            }

            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(contentText)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(fullScreenPi)
                .setFullScreenIntent(fullScreenPi, true)
                .addAction(0, "Dismiss", dismissPi)
                .addAction(0, "Snooze 5m", snoozePi)
                .build()
        }
    }
}
