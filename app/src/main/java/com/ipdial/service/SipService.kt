package com.ipdial.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ipdial.MainActivity
import com.ipdial.R
import com.ipdial.data.model.AudioDeviceMode
import com.ipdial.data.model.CallDirection
import com.ipdial.data.model.CallState
import com.ipdial.data.model.RegStatus
import com.ipdial.data.model.PreferredCodec
import com.ipdial.data.repository.AccountRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SipService : Service() {

    companion object {
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

        fun start(context: Context, delayStartForeground: Boolean = false) {
            val intent = Intent(context, SipService::class.java).apply {
                action = ACTION_START
                if (delayStartForeground) {
                    putExtra("delayStartForeground", true)
                }
            }
            if (delayStartForeground) {
                // Starting from background (e.g. BOOT_COMPLETED) — use regular startService
                // to avoid BackgroundServiceStartNotAllowedException on Android 12+.
                // The service will promote to foreground itself after a short delay.
                context.startService(intent)
            } else {
                try {
                    context.startForegroundService(intent)
                } catch (e: Exception) {
                    Log.e("SipService", "startForegroundService failed, trying regular startService", e)
                    context.startService(intent)
                }
            }
        }

        @Volatile var activeCallStartTime: Long = 0L

        fun showCallNotificationStatic(context: Context, callerName: String = "", callId: Int = -1) {
            Log.d("SipService", "showCallNotificationStatic: caller=$callerName, isForeground=${com.ipdial.AppState.isForeground}")

            val session = SipEngine.callSession.value
            if (session == null || session.state == CallState.DISCONNECTED || session.state == CallState.IDLE) {
                val nm = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(NOTIF_ID_INCOMING)
                return
            }

            val targetCallId = if (callId >= 0) callId else session.callId
            val isIncomingRinging = session.direction == CallDirection.INCOMING &&
                    (session.state == CallState.INCOMING || session.state == CallState.EARLY)



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
                .setPriority(NotificationCompat.PRIORITY_MAX)
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

            val nm = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID_INCOMING, notifBuilder.build())
        }

        fun showIncomingCallNotificationStatic(context: Context, callerName: String, callId: Int) {
            showCallNotificationStatic(context, callerName, callId)
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var bannerPushJob: kotlinx.coroutines.Job? = null
    private var deferForegroundJob: kotlinx.coroutines.Job? = null
    private lateinit var audioManager: AudioManager
    private lateinit var repo: AccountRepository
    private lateinit var contactsRepo: com.ipdial.data.repository.ContactsRepository
    private var wakeLock: PowerManager.WakeLock? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var audioFocusRequest: android.media.AudioFocusRequest? = null
    private val activeConfigs = java.util.concurrent.ConcurrentHashMap<String, com.ipdial.data.model.SipAccount>()
    private var isConnected = false
    private var lastNetwork: Network? = null
    private var vibrator: Vibrator? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        
        repo = AccountRepository(applicationContext)
        contactsRepo = com.ipdial.data.repository.ContactsRepository(applicationContext)
        createNotificationChannels()
        TelecomHelper.registerPhoneAccount(applicationContext)
        
        // 0. Set the incoming call listener as early as possible
        SipEngine.onIncomingCall = { session -> 
            Log.d("SipService", "onIncomingCall lambda triggered for callId=${session.callId}")
            // Guard against ghost / re-delivered calls from the Telecom framework.
            // If SipEngine already cleared the session or removed this callId from its
            // callMap (call ended, remote cancelled, etc.), ignore the delivery entirely.
            val isActive = SipEngine.callSession.value != null && SipEngine.hasActiveCall(session.callId)
            if (!isActive) {
                Log.d("SipService", "onIncomingCall: ignoring ghost delivery for callId=${session.callId} (session=${SipEngine.callSession.value?.state}, hasActive=${SipEngine.hasActiveCall(session.callId)})")
            }
            if (isActive) {
                com.ipdial.util.SipLogger.log("SipService", "Incoming call received: ${session.remoteUri}")
                scope.launch {
                    val accountsNow = repo.accounts.first()
                    val callAccount = accountsNow.firstOrNull { it.id == session.accountId }
                    if (callAccount == null || !callAccount.isEnabled) {
                        Log.d("SipService", "Rejecting incoming call for disabled account ${session.accountId}")
                        SipEngine.hangupCall(session.callId)
                        return@launch
                    }

                    val isDnd = repo.dndEnabled.first()
                    Log.d("SipService", "DND=$isDnd for callId=${session.callId}")

                    // Resolve contact name or clean number
                    val displayName = session.remoteDisplayName
                    val cleanNum = session.remoteUri.replace("<", "").replace(">", "").removePrefix("sip:").substringBefore("@").substringBefore(";")
                    
                    Log.d("SipService", "Processing incoming call from $cleanNum")
                    
                    val cleanedSessionDigits = cleanNum.filter { it.isDigit() }
                    
                    var matchedContact: com.ipdial.data.model.Contact? = null
                    if (cleanedSessionDigits.length >= 10) {
                        matchedContact = contactsRepo.findContactByNumber(cleanNum)
                    }
                    
                    val finalDisplayName = matchedContact?.name ?: cleanNum.ifBlank { displayName }
                    Log.d("SipService", "Final display name: $finalDisplayName")
                    
                    // Update session display name for logging and UI consistency
                    SipEngine.updateCallSessionName(finalDisplayName)
                    
                    // Re-check: call may have ended while we were doing contact lookup.
                    // If we proceed, reportIncomingCall + startPushingBanner would
                    // resurrect the UI after onCallState already nulled the session.
                    if (SipEngine.callSession.value?.callId != session.callId || !SipEngine.hasActiveCall(session.callId)) {
                        Log.d("SipService", "onIncomingCall: call $${session.callId} ended during contact lookup, skipping Telecom/banner")
                        return@launch
                    }

                    withContext(Dispatchers.Main) {
                        // Second check after switching to Main — remote could have
                        // hung up while the dispatcher queued this block.
                        if (SipEngine.callSession.value?.callId != session.callId || !SipEngine.hasActiveCall(session.callId)) {
                            Log.d("SipService", "onIncomingCall: call $${session.callId} ended before Telecom reporting, skipping")
                            return@withContext
                        }
                        Log.d("SipService", "Reporting incoming call to Telecom and showing notification")
                        TelecomHelper.reportIncomingCall(applicationContext, session.remoteUri, finalDisplayName, session.callId)
                        startPushingBanner(finalDisplayName, session.callId)
                        // Bring activity to front so full-screen incoming call UI appears
                        // even when device is locked or app is in background
                        if (!com.ipdial.AppState.isForeground) {
                            val activityIntent = Intent(this@SipService, com.ipdial.MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            }
                            startActivity(activityIntent)
                        }
                    }
                    // Store DND state on session so playRingtone can check it
                    // Only if call is still active
                    if (isDnd && SipEngine.callSession.value?.callId == session.callId) {
                        SipEngine.setDndActive(true)
                    }
                }
            }
        }

        // 0b. Immediately tear down incoming-call UI when the remote side hangs up
        //     (or the call is rejected/cancelled) before we could answer.
        //     This fires synchronously on the PJSIP thread, so we only do fast, thread-safe
        //     work here: cancel coroutine jobs, stop audio/vibration, cancel notification.
        SipEngine.onCallDisconnected = { callId ->
            Log.d("SipService", "onCallDisconnected: callId=$callId — stopping ringtone/vibration and dismissing incoming UI")
            SipEngine.setDndActive(false)
            stopPushingBanner()
            stopRingtone()          // also cancels vibrator internally
            cancelIncomingNotification()
        }

        // FGS promotion is deferred to onStartCommand (which handles the
        // delayStartForeground boot path). onCreate should not call it
        // because we don't yet know whether this is a boot or normal start.

        scope.launch {
            // 1. Initialize PJSIP on Main thread to avoid native crash (pj_thread_this)
            withContext(Dispatchers.Main) {
                SipEngine.init(applicationContext)
            }
            
            // 2. Build contact lookup index for fast number matching
            try {
                contactsRepo.buildNumberIndex()
                Log.d("SipService", "Contact number index built")
            } catch (e: Exception) {
                Log.e("SipService", "Failed to build contact number index", e)
            }

            // 3. Register accounts flow
            registerAccountsFromDataStore()

            // 4. Register default network callback
            registerDefaultNetworkCallback()
        }

        observeCallState()

        // Background watchdog for stale call sessions (remote hangup edge cases)
        scope.launch {
            while (true) {
                delay(2000)
                try {
                    SipEngine.nullSessionIfStale()
                } catch (_: Throwable) {}
            }
        }
    }

    private fun startPushingBanner(callerName: String, callId: Int) {
        stopPushingBanner()
        bannerPushJob = scope.launch {
            while (true) {
                showIncomingCallNotificationStatic(applicationContext, callerName, callId)
                delay(4000)
            }
        }
    }

    private fun stopPushingBanner() {
        bannerPushJob?.cancel()
        bannerPushJob = null
    }

    private fun registerDefaultNetworkCallback() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            connectivityManager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d("SipService", "onAvailable default network: $network")
                    val isInitial = (lastNetwork == null)
                    val wasOffline = !isConnected
                    val networkChanged = (lastNetwork != network)
                    
                    lastNetwork = network
                    isConnected = true
                    
                    scope.launch(Dispatchers.IO) {
                        val freshAccounts = repo.accounts.first()
                        val enabledAccounts = freshAccounts.filter { it.isEnabled }
                        if (enabledAccounts.isEmpty()) return@launch
                        
                        val hasUnregistered = enabledAccounts.any { it.regStatus != RegStatus.REGISTERED }
                        val shouldReconnect = if (!isInitial) {
                            networkChanged || wasOffline
                        } else {
                            hasUnregistered
                        }
                        
                        if (shouldReconnect) {
                            Log.d("SipService", "Default network active/changed (isInitial=$isInitial, wasOffline=$wasOffline, networkChanged=$networkChanged). Reconnecting...")
                            enabledAccounts.forEach { account ->
                                repo.updateRegStatus(account.id, RegStatus.REGISTERING)
                            }
                            
                            SipEngine.reconnectOnNetworkChange(network, applicationContext)
                        }
                    }
                }

                override fun onLost(network: Network) {
                    Log.d("SipService", "onLost default network: $network")
                    if (lastNetwork == network) {
                        isConnected = false
                        scope.launch {
                            val freshAccounts = repo.accounts.first()
                            freshAccounts.forEach {
                                repo.updateRegStatus(it.id, RegStatus.ERROR)
                            }
                        }
                    }
                }
            })
        } catch (e: Exception) {
            Log.e("SipService", "Failed to register default network callback", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val delayFg = intent?.getBooleanExtra("delayStartForeground", false) ?: false

        if (delayFg) {
            // Boot path: can't start phoneCall FGS from background on Android 12+.
            // Defer promotion until the app comes to the foreground.
            Log.d("SipService", "onStartCommand with delayStartForeground — deferring FGS promotion")
            deferForegroundJob?.cancel()
            deferForegroundJob = scope.launch {
                while (true) {
                    if (com.ipdial.AppState.isForeground) {
                        Log.d("SipService", "App is now foreground, promoting to FGS")
                        startServiceForeground()
                        break
                    }
                    delay(500)
                }
            }
        } else {
            // Normal path: promote to foreground immediately
            startServiceForeground()
        }

        when (intent?.action) {
            ACTION_ANSWER -> {
                val callId = intent.getIntExtra("callId", -1)
                SipEngine.answerCall(callId)
                routeAudioToEarpiece()
                stopPushingBanner()
                cancelIncomingNotification()
                // Launch MainActivity to show active call
                val fullIntent = Intent(this, MainActivity::class.java).apply {
                    this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                try {
                    startActivity(fullIntent)
                } catch (e: Exception) {
                    Log.e("SipService", "Failed to start MainActivity on answer", e)
                }
            }
            ACTION_DECLINE -> {
                val callId = intent.getIntExtra("callId", -1)
                val session = SipEngine.callSession.value
                val cause = CallHangupResolver.resolveDisconnectCause(session)
                SipEngine.hangupCall(callId)
                SipConnectionService.disconnectCall(callId, cause)
                stopPushingBanner()
                cancelIncomingNotification()
            }
            "ACTION_STOP_BANNER" -> {
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(NOTIF_ID_INCOMING)
                stopPushingBanner()
            }
            ACTION_SET_AUDIO_DEVICE -> {
                val mode = intent.getStringExtra("mode") ?: AudioDeviceMode.EARPIECE.name
                Log.d("SipService", "ACTION_SET_AUDIO_DEVICE: $mode")
                when (mode) {
                    AudioDeviceMode.EARPIECE.name -> routeAudioToEarpiece()
                    AudioDeviceMode.SPEAKER.name -> routeAudioToSpeaker(true)
                    AudioDeviceMode.BLUETOOTH.name -> routeAudioToBluetooth()
                }
            }
            ACTION_HANGUP -> {
                val session = SipEngine.callSession.value
                val id = session?.callId ?: -1
                if (id != -1) {
                    val cause = CallHangupResolver.resolveDisconnectCause(session)
                    SipEngine.hangupCall(id)
                    SipConnectionService.disconnectCall(id, cause)
                }
            }
            ACTION_STOP -> stopSelf()
            ACTION_TEST_CALL -> {
                val number = intent.getStringExtra("number") ?: "123"
                scope.launch {
                    try {
                        val accountsList = repo.accounts.first()
                        val acc = accountsList.firstOrNull { it.isEnabled }
                        if (acc != null) {
                            Log.d("SipService", "Test calling $number with account ${acc.id}")
                            
                            val transportSuffix = when (acc.transport) {
                                com.ipdial.data.model.Transport.TCP -> ";transport=tcp"
                                com.ipdial.data.model.Transport.TLS -> ";transport=tls"
                                else -> ""
                            }

                            val finalUri = if (number.contains("@")) {
                                val base = if (number.startsWith("sip:")) number else "sip:$number"
                                if (!base.contains("transport=") && transportSuffix.isNotEmpty()) {
                                    base + transportSuffix
                                } else {
                                    base
                                }
                            } else {
                                val num = number.removePrefix("sip:")

                                val host = if (acc.port != null && acc.port > 0 && !acc.domain.contains(":")) {
                                    "${acc.domain}:${acc.port}"
                                } else {
                                    acc.domain
                                }
                                "sip:$num@$host$transportSuffix"
                            }
                            
                            Log.d("SipService", "Dialing URI: $finalUri")
                            Log.d("SipService", "Test call codec: ${acc.codec}")
                            
                            withContext(Dispatchers.Main) {
                                SipEngine.makeCall(acc.id, finalUri)
                                android.widget.Toast.makeText(
                                    applicationContext,
                                    "Test call using ${acc.codec?.name ?: "default"}",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        } else {
                            Log.e("SipService", "Test call failed: No enabled account")
                        }
                    } catch (e: Exception) {
                        Log.e("SipService", "Test call failed with exception", e)
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun registerAccountsFromDataStore() {
        scope.launch {
            repo.accounts.collectLatest { accounts ->
                accounts.forEach { account ->
                    if (account.isEnabled) {
                        val active = activeConfigs[account.id]
                        val hasChanged = active == null ||
                                active.username != account.username ||
                                active.password != account.password ||
                                active.domain != account.domain ||
                                active.proxy != account.proxy ||
                                active.port != account.port ||
                                active.transport != account.transport ||
                                active.codec != account.codec ||
                                active.ecEnabled != account.ecEnabled ||
                                active.nsEnabled != account.nsEnabled ||
                                active.agcEnabled != account.agcEnabled

                        if (hasChanged) {
                            activeConfigs[account.id] = account
                            SipEngine.addAccount(account)
                        }
                    } else {
                        if (activeConfigs.containsKey(account.id)) {
                            activeConfigs.remove(account.id)
                            SipEngine.removeAccount(account.id)
                        }
                        if (account.regStatus != RegStatus.UNREGISTERED) {
                            scope.launch {
                                repo.updateRegStatus(account.id, RegStatus.UNREGISTERED)
                            }
                        }
                    }
                }
            }
        }
        // Observe registration events to update DataStore
        scope.launch {
            SipEngine.registrationEvents.collect { (accountId, status) ->
                repo.updateRegStatus(accountId, status)
            }
        }

        // Keep-alive loop to ensure registrations stay active for incoming calls
        scope.launch {
            while (true) {
                delay(120_000) // Every 2 minutes
                try {
                    val accounts = repo.accounts.first()
                    accounts.forEach { account ->
                        if (account.isEnabled && account.regStatus != RegStatus.REGISTERED) {
                            Log.d("SipService", "Keep-alive: Triggering re-connect for ${account.id}")
                            SipEngine.reconnectAccount(account.id)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SipService", "Keep-alive loop error", e)
                }
            }
        }
    }

    private var lastWasConfirmed = false
    private var callStartTime = 0L

    private var lastSession: com.ipdial.data.model.CallSession? = null
    private var proximityWakeLock: PowerManager.WakeLock? = null
    private var autoRecordedCallId = -1
    
    private var ringtone: Ringtone? = null
    private var mediaPlayer: android.media.MediaPlayer? = null
    private var isPlayingRingtone = false
    private var ringtoneJob: kotlinx.coroutines.Job? = null

    private var ringtoneWakeLock: PowerManager.WakeLock? = null

    private fun playRingtone() {
        ringtoneJob?.cancel()
        if (isPlayingRingtone || ringtone?.isPlaying == true || mediaPlayer?.isPlaying == true) return
        
        // Skip ringtone/vibrate if DND is active
        if (SipEngine.isDndActive()) {
            Log.d("SipService", "DND active, skipping ringtone and vibration")
            return
        }

        // Match phone's ringer mode
        val ringerMode = audioManager.ringerMode
        if (ringerMode == AudioManager.RINGER_MODE_SILENT) {
            Log.d("SipService", "Silent mode, skipping ringtone")
            return
        }

        isPlayingRingtone = true
        
        // Keep CPU alive for ringtone
        if (ringtoneWakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            ringtoneWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IPDial:ringtone_wake")
        }
        ringtoneWakeLock?.acquire(30000L)

        ringtoneJob = scope.launch {
            try {
                val ringtoneUriStr = repo.globalRingtone.first()
                val vibrateEnabled = repo.globalVibrate.first()
                
                withContext(Dispatchers.Main) {
                    // Check again on main thread to avoid races
                    if (!isPlayingRingtone || ringtone?.isPlaying == true || mediaPlayer?.isPlaying == true) {
                        return@withContext
                    }

                    if (ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                        val ringtoneUri = ringtoneUriStr?.let { android.net.Uri.parse(it) }
                            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

                        var mp: android.media.MediaPlayer? = null
                        try {
                            mediaPlayer?.release()
                            mp = android.media.MediaPlayer()
                            mp.setDataSource(applicationContext, ringtoneUri)
                            mp.setAudioAttributes(
                                android.media.AudioAttributes.Builder()
                                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                    .build()
                            )
                            mp.isLooping = true
                            
                            withContext(Dispatchers.IO) {
                                mp.prepare()
                            }
                            
                            if (!isPlayingRingtone) {
                                mp.release()
                                return@withContext
                            }
                            mp.start()
                            mediaPlayer = mp
                        } catch (e: Exception) {
                            Log.e("SipService", "MediaPlayer failed for ringtone, falling back to RingtoneManager", e)
                            mp?.release()
                            ringtone = RingtoneManager.getRingtone(applicationContext, ringtoneUri)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                ringtone?.isLooping = true
                            }
                            ringtone?.play()
                        }
                    }
                    
                    if (vibrateEnabled || ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
                        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 1000, 1000), 0))
                    }
                }
            } catch (e: Exception) {
                Log.e("SipService", "Failed to play ringtone", e)
                isPlayingRingtone = false
            }
        }
    }

    private fun stopRingtone() {
        ringtoneJob?.cancel()
        ringtoneJob = null
        isPlayingRingtone = false
        try {
            ringtoneWakeLock?.let { if (it.isHeld) it.release() }
            ringtone?.stop()
            ringtone = null
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            vibrator?.cancel()
        } catch (e: Exception) {}
    }

    private fun isBluetoothConnected(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            return devices.any {
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            }
        } else {
            @Suppress("DEPRECATION")
            return audioManager.isBluetoothScoAvailableOffCall || audioManager.isBluetoothA2dpOn
        }
    }

    private fun routeAudioToDefault() {
        requestAudioFocus()
        val session = SipEngine.callSession.value ?: return
        if (session.isSpeaker) {
            routeAudioToSpeaker(true)
        } else if (isBluetoothConnected()) {
            routeAudioToBluetooth()
        } else {
            routeAudioToEarpiece()
        }
    }

    private fun observeCallState() {
        scope.launch {
            SipEngine.callSession.collect { session ->
                if (session == null) {
                    val sessionToLog = lastSession
                    if (sessionToLog != null) {
                        val duration = if (callStartTime > 0) (System.currentTimeMillis() - callStartTime) / 1000 else 0L
                        val entry = com.ipdial.data.model.CallLogEntry(
                            accountId = sessionToLog.accountId,
                            remoteUri = sessionToLog.remoteUri,
                            remoteDisplayName = sessionToLog.remoteDisplayName,
                            direction = sessionToLog.direction,
                            timestampMs = System.currentTimeMillis(),
                            durationSeconds = duration,
                            missed = !lastWasConfirmed && sessionToLog.direction == CallDirection.INCOMING
                        )
                        // Use a separate scope to ensure insertion completes
                        CoroutineScope(Dispatchers.IO).launch {
                            com.ipdial.data.repository.CallLogRepository.getInstance(applicationContext).insert(entry)
                        }
                        // Show missed call notification
                        if (entry.missed) {
                            showMissedCallNotification(sessionToLog.remoteDisplayName, sessionToLog.remoteUri)
                        }
                    }
                    stopRingtone()
                    stopPushingBanner()   // safety net — kills banner job on any call end path
                    restoreAudio()
                    releaseWakeLock()
                    cancelIncomingNotification()
                    // Safety net: tear down any lingering Telecom connection for this
                    // callId. disconnectCall() is idempotent (checks isDestroyed),
                    // so this is harmless if already cleaned up by onCallState.
                    sessionToLog?.callId?.let { SipConnectionService.disconnectCall(it) }
                    lastWasConfirmed = false
                    callStartTime = 0
                    activeCallStartTime = 0L
                    lastSession = null
                    autoRecordedCallId = -1
                } else {
                    val stateChanged = session.state != lastSession?.state
                    val speakerChanged = session.isSpeaker != lastSession?.isSpeaker
                    
                    lastSession = session
                    
                    audioManager.isMicrophoneMute = false
                    
                    when (session.state) {
                        CallState.INCOMING -> {
                            if (audioManager.mode != AudioManager.MODE_NORMAL) {
                                audioManager.mode = AudioManager.MODE_NORMAL
                            }
                            playRingtone()
                            acquireWakeLockForIncoming()
                            acquireWakeLock()
                            acquireProximityWakeLock() // Proximity sensor active for incoming ringing
                            updateForegroundType(ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
                            if (speakerChanged) {
                                routeAudioToSpeaker(session.isSpeaker)
                            }
                            if (!com.ipdial.AppState.isForeground) {
                                showCallNotificationStatic(applicationContext, session.remoteDisplayName, session.callId)
                            }
                        }
                        CallState.CONFIRMED -> {
                            if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
                                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                            }
                            stopRingtone()
                            
                            if (stateChanged || speakerChanged) {
                                if (stateChanged) delay(300) 
                                routeAudioToDefault()
                            }
                            
                            acquireWakeLock()
                            acquireProximityWakeLock() // Ensure active during confirmed call
                            lastWasConfirmed = true
                            if (callStartTime == 0L) {
                                callStartTime = System.currentTimeMillis()
                                activeCallStartTime = callStartTime
                            }
                            updateForegroundType(ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
                            autoStartRecordingIfEnabled(session)

                            if (!com.ipdial.AppState.isForeground) {
                                showCallNotificationStatic(applicationContext, session.remoteDisplayName, session.callId)
                            }
                        }
                        CallState.CALLING, CallState.EARLY, CallState.CONNECTING -> {
                            if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
                                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                            }
                            if (stateChanged || speakerChanged) {
                                routeAudioToDefault()
                            }
                            acquireWakeLock()
                            acquireProximityWakeLock() // Proximity active for dialing
                            updateForegroundType(ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)

                            if (!com.ipdial.AppState.isForeground) {
                                showCallNotificationStatic(applicationContext, session.remoteDisplayName, session.callId)
                            }
                        }
                        else -> {
                            if (speakerChanged) {
                                routeAudioToDefault()
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun autoStartRecordingIfEnabled(session: com.ipdial.data.model.CallSession) {
        try {
            if (autoRecordedCallId == session.callId) return
            if (session.isRecording) return

            val proExpiration = repo.proExpiration.first()
            if (proExpiration <= System.currentTimeMillis()) return // Not Pro

            val enabled = repo.autoRecordEnabled.first()
            if (!enabled) return

            autoRecordedCallId = session.callId
            Log.d("SipService", "Auto-recording call ${session.callId}")
            RecordingManager.startRecording(applicationContext, session)
        } catch (e: Exception) {
            Log.e("SipService", "Auto-record failed: ${e.message}", e)
        }
    }

    private fun startServiceForeground() {
        val notification = buildServiceNotification()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                // Try phoneCall type first as it's the primary purpose
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    androidx.core.app.ServiceCompat.startForeground(
                        this,
                        NOTIF_ID_SERVICE,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                    )
                } else {
                    startForeground(NOTIF_ID_SERVICE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
                }
                Log.d("SipService", "Started FGS with type phoneCall")
            } catch (e: Exception) {
                Log.w("SipService", "Failed to start FGS with type phoneCall, trying fallback: ${e.message}")
                
                // Fallback to dataSync if phoneCall is not allowed (common on some background starts)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    try {
                        androidx.core.app.ServiceCompat.startForeground(
                            this,
                            NOTIF_ID_SERVICE,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                        )
                        Log.d("SipService", "Started FGS with type dataSync fallback")
                    } catch (ex: Exception) {
                        Log.e("SipService", "Failed to start FGS with dataSync fallback", ex)
                        // Absolute fallback: no type
                        try {
                            startForeground(NOTIF_ID_SERVICE, notification)
                        } catch (lastEx: Exception) {
                            Log.e("SipService", "Final FGS attempt failed", lastEx)
                        }
                    }
                } else {
                    try {
                        startForeground(NOTIF_ID_SERVICE, notification)
                    } catch (lastEx: Exception) {
                        Log.e("SipService", "Absolute FGS failure", lastEx)
                    }
                }
            }
        } else {
            startForeground(NOTIF_ID_SERVICE, notification)
        }
    }

    private fun updateForegroundType(type: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    androidx.core.app.ServiceCompat.startForeground(
                        this,
                        NOTIF_ID_SERVICE,
                        buildServiceNotification(),
                        type
                    )
                } else {
                    startForeground(NOTIF_ID_SERVICE, buildServiceNotification(), type)
                }
            } catch (e: Exception) {
                Log.e("SipService", "Failed to update FGS type to $type", e)
            }
        }
    }

    private fun routeAudioToEarpiece() {
        val session = SipEngine.callSession.value
        if (session != null && session.callId >= 0) {
            val connection = SipConnectionService.getConnection(session.callId)
            @Suppress("DEPRECATION")
            connection?.setAudioRoute(android.telecom.CallAudioState.ROUTE_EARPIECE)
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isMicrophoneMute = false
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
    }

    fun routeAudioToSpeaker(on: Boolean) {
        val session = SipEngine.callSession.value
        if (session != null && session.callId >= 0) {
            val connection = SipConnectionService.getConnection(session.callId)
            if (connection != null) {
                val route = if (on) android.telecom.CallAudioState.ROUTE_SPEAKER else android.telecom.CallAudioState.ROUTE_EARPIECE
                @Suppress("DEPRECATION")
                connection.setAudioRoute(route)
                Log.d("SipService", "Routed audio via Telecom Connection to speaker=$on")
            }
        }

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isMicrophoneMute = false
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = on
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (on) {
                val devices = audioManager.availableCommunicationDevices
                val speakerDevice = devices.find { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speakerDevice != null) {
                    val res = audioManager.setCommunicationDevice(speakerDevice)
                    Log.d("SipService", "setCommunicationDevice speaker: $res")
                } else {
                    Log.e("SipService", "Built-in speaker device not found")
                }
            } else {
                audioManager.clearCommunicationDevice()
                Log.d("SipService", "clearCommunicationDevice")
            }
        }
    }

    private fun routeAudioToBluetooth() {
        val session = SipEngine.callSession.value
        if (session != null && session.callId >= 0) {
            val connection = SipConnectionService.getConnection(session.callId)
            @Suppress("DEPRECATION")
            connection?.setAudioRoute(android.telecom.CallAudioState.ROUTE_BLUETOOTH)
            Log.d("SipService", "Routed audio via Telecom Connection to Bluetooth")
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isMicrophoneMute = false
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            val btDevice = devices.find {
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            }
            if (btDevice != null) {
                val res = audioManager.setCommunicationDevice(btDevice)
                Log.d("SipService", "setCommunicationDevice Bluetooth: $res")
            } else {
                Log.e("SipService", "Bluetooth device not found in available devices")
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = true
        }
    }



    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest == null) {
                audioFocusRequest = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { }
                    .build()
            }
            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
    }

    private fun restoreAudio() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }

        if (audioManager.mode != AudioManager.MODE_NORMAL) {
            audioManager.mode = AudioManager.MODE_NORMAL
        }
        @Suppress("DEPRECATION")
        if (audioManager.isSpeakerphoneOn) {
            audioManager.isSpeakerphoneOn = false
        }
        @Suppress("DEPRECATION")
        if (audioManager.isBluetoothScoOn) {
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                audioManager.clearCommunicationDevice()
            } catch (e: Exception) {
                Log.e("SipService", "Failed to clear communication device", e)
            }
        }
    }

    private fun acquireWakeLock() {
        // Partial wake lock to keep CPU alive during call even if screen is off
        if (cpuWakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            cpuWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IPDial:cpu_call").apply {
                setReferenceCounted(false)
                acquire(60 * 60 * 1000L)
            }
        }
    }

    private fun acquireWakeLockForIncoming() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "IPDial:incoming_call_wake"
            )
            wl.acquire(10000L) // 10 seconds should be enough to show UI
        } catch (e: Exception) {
            Log.e("SipService", "Failed to acquire incoming wake lock", e)
        }
    }

    private fun acquireProximityWakeLock() {
        if (proximityWakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        proximityWakeLock = pm.newWakeLock(
            PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
            "IPDial:proximity_dialing"
        ).apply {
            setReferenceCounted(false)
            acquire(60 * 60 * 1000L)
        }
    }

    private fun releaseProximityWakeLock() {
        proximityWakeLock?.let { if (it.isHeld) it.release() }
        proximityWakeLock = null
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        cpuWakeLock?.let { if (it.isHeld) it.release() }
        cpuWakeLock = null
        releaseProximityWakeLock()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

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
                // We handle sound and vibration manually for better control
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

    private fun buildServiceNotification(): Notification {
        val intent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, NOTIF_CHANNEL_SIP)
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

    private fun cancelIncomingNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID_INCOMING)
    }

    private fun showMissedCallNotification(callerName: String, remoteUri: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val displayName = callerName.ifBlank {
            remoteUri.removePrefix("sip:").substringBefore("@")
        }

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPi = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, NOTIF_CHANNEL_MISSED)
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

    override fun onDestroy() {
        releaseWakeLock()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SipEngine.destroy()
            } catch (_: Exception) {}
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
