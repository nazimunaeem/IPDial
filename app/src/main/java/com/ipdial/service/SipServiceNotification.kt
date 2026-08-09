package com.ipdial.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ipdial.MainActivity
import com.ipdial.R
import com.ipdial.data.model.CallDirection
import com.ipdial.data.model.CallState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val NOTIF_CHANNEL_SIP = "sip_service_v1"
const val NOTIF_CHANNEL_CALL = "incoming_call_v4"
const val NOTIF_CHANNEL_MISSED = "missed_calls_v1"
const val NOTIF_ID_SERVICE = 1001
const val NOTIF_ID_INCOMING = 1002
const val NOTIF_ID_MISSED = 1003

const val ACTION_ANSWER = "com.ipdial.ANSWER"
const val ACTION_DECLINE = "com.ipdial.DECLINE"
const val ACTION_HANGUP = "com.ipdial.HANGUP"
const val ACTION_START = "com.ipdial.START"
const val ACTION_STOP = "com.ipdial.STOP"
const val ACTION_TEST_CALL = "com.ipdial.TEST_CALL"
const val ACTION_SET_AUDIO_DEVICE = "com.ipdial.SET_AUDIO_DEVICE"

@Volatile var activeCallStartTime: Long = 0L

fun createNotificationChannels(context: Context) {
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    nm.createNotificationChannel(
        NotificationChannel(NOTIF_CHANNEL_SIP, "SIP Service", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Background SIP registration"
            setShowBadge(false)
        }
    )

    nm.createNotificationChannel(
        NotificationChannel(NOTIF_CHANNEL_CALL, "Incoming Calls", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Incoming VoIP call alerts"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(true)
            setSound(null, null)
            enableVibration(false)
        }
    )

    nm.createNotificationChannel(
        NotificationChannel(NOTIF_CHANNEL_MISSED, "Missed Calls", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Missed VoIP call alerts"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(true)
        }
    )
}

fun buildServiceNotification(context: Context): Notification {
    val intent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE
    )
    val notif = NotificationCompat.Builder(context, NOTIF_CHANNEL_SIP)
        .setContentTitle("IPDial")
        .setContentText("Ready to receive calls")
        .setSmallIcon(R.drawable.ic_notif_call)
        .setContentIntent(intent)
        .setSilent(true)
        .setOngoing(true)
        .build()
    notif.flags = notif.flags or Notification.FLAG_NO_CLEAR
    return notif
}

fun cancelIncomingNotification(context: Context) {
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.cancel(NOTIF_ID_INCOMING)
}

fun showMissedCallNotification(context: Context, callerName: String, remoteUri: String) {
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val displayName = callerName.ifBlank {
        remoteUri.removePrefix("sip:").substringBefore("@")
    }

    val tapIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val tapPi = PendingIntent.getActivity(
        context, 0, tapIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val notif = NotificationCompat.Builder(context, NOTIF_CHANNEL_MISSED)
        .setContentTitle("Missed call")
        .setContentText(displayName)
        .setSmallIcon(R.drawable.ic_notif_call)
        .setContentIntent(tapPi)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .build()
    nm.notify(NOTIF_ID_MISSED, notif)
}

private var bannerPushJob: Job? = null

fun startPushingBanner(scope: CoroutineScope, context: Context, callerName: String, callId: Int) {
    stopPushingBanner()
    bannerPushJob = scope.launch {
        while (true) {
            if (!com.ipdial.AppState.isForeground && !SipEngine.isDndActive()) {
                showIncomingCallNotificationStatic(context, callerName, callId)
            }
            delay(4000)
        }
    }
}

fun stopPushingBanner() {
    bannerPushJob?.cancel()
    bannerPushJob = null
}

fun showCallNotificationStatic(context: Context, callerName: String = "", callId: Int = -1) {
    Log.d("SipService", "showCallNotificationStatic: caller=$callerName, isForeground=${com.ipdial.AppState.isForeground}")

    val session = SipEngine.callSession.value
    if (session == null || session.state == CallState.DISCONNECTED || session.state == CallState.IDLE) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID_INCOMING)
        return
    }

    val targetCallId = if (callId >= 0) callId else session.callId
    val isIncomingRinging = session.direction == CallDirection.INCOMING &&
            (session.state == CallState.INCOMING || session.state == CallState.EARLY)

    val priority = if (isIncomingRinging) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH

    val displayName = callerName.ifBlank { session.remoteDisplayName }.ifBlank {
        session.remoteUri.removePrefix("sip:").substringBefore("@")
    }

    val callerPerson = androidx.core.app.Person.Builder()
        .setName(displayName)
        .setImportant(true)
        .build()

    val fullscreenIntent = Intent(context, MainActivity::class.java).apply {
        action = "com.ipdial.ACTION_SHOW_CALL"
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    val contentPi = PendingIntent.getActivity(
        context, 0, fullscreenIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val hangupPi = PendingIntent.getService(
        context, 2,
        Intent(context, SipService::class.java).apply {
            action = ACTION_HANGUP
            putExtra("callId", targetCallId)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val notifBuilder = NotificationCompat.Builder(context, NOTIF_CHANNEL_CALL)
        .setSmallIcon(R.drawable.ic_notif_call)
        .setPriority(priority)
        .setCategory(NotificationCompat.CATEGORY_CALL)
        .setContentIntent(contentPi)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setAutoCancel(false)
        .setOngoing(true)

    if (isIncomingRinging) {
        val answerPi = PendingIntent.getService(
            context, 1,
            Intent(context, SipService::class.java).apply {
                action = ACTION_ANSWER
                putExtra("callId", targetCallId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declinePi = PendingIntent.getService(
            context, 2,
            Intent(context, SipService::class.java).apply {
                action = ACTION_DECLINE
                putExtra("callId", targetCallId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        notifBuilder
            .setContentTitle("Incoming Call")
            .setContentText(displayName)
            .setFullScreenIntent(contentPi, true)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(callerPerson, declinePi, answerPi))
    } else {
        val titleText = when (session.state) {
            CallState.CONFIRMED -> "Active Call"
            CallState.CALLING -> "Calling..."
            CallState.EARLY -> "Ringing..."
            CallState.CONNECTING -> "Connecting..."
            else -> "Active Call"
        }

        notifBuilder
            .setContentTitle(titleText)
            .setContentText(displayName)
            .setFullScreenIntent(contentPi, true)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(callerPerson, hangupPi))

        if (session.state == CallState.CONFIRMED) {
            if (activeCallStartTime == 0L) {
                activeCallStartTime = System.currentTimeMillis()
            }
            notifBuilder
                .setUsesChronometer(true)
                .setWhen(activeCallStartTime)
                .setChronometerCountDown(false)
        }
    }

    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.notify(NOTIF_ID_INCOMING, notifBuilder.build())
}

fun showIncomingCallNotificationStatic(context: Context, callerName: String, callId: Int) {
    showCallNotificationStatic(context, callerName, callId)
}
