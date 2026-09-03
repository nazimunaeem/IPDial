package com.ipdial.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.ipdial.MainActivity
import com.ipdial.data.model.AudioDeviceMode
import com.ipdial.data.model.CallDirection
import com.ipdial.data.model.CallState
import com.ipdial.data.model.RegStatus
import com.ipdial.data.repository.AccountRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SipService : Service() {

    companion object {
        fun start(context: Context, delayStartForeground: Boolean = false) {
            val intent = Intent(context, SipService::class.java).apply {
                action = ACTION_START
                if (delayStartForeground) {
                    putExtra("delayStartForeground", true)
                }
            }
            if (delayStartForeground) {
                try {
                    context.startService(intent)
                } catch (e: Exception) {
                    Log.e("SipService", "Unable to start delayed SIP service", e)
                }
            } else {
                try {
                    context.startForegroundService(intent)
                } catch (e: Exception) {
                    Log.e("SipService", "startForegroundService failed, trying regular startService", e)
                    try {
                        context.startService(intent)
                    } catch (fallbackError: Exception) {
                        Log.e("SipService", "Unable to start SIP service", fallbackError)
                    }
                }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var deferForegroundJob: kotlinx.coroutines.Job? = null
    private lateinit var audioManager: AudioManager
    private lateinit var repo: AccountRepository
    private lateinit var contactsRepo: com.ipdial.data.repository.ContactsRepository
    private val activeConfigs = java.util.concurrent.ConcurrentHashMap<String, com.ipdial.data.model.SipAccount>()
    private var isConnected = false
    private var lastNetwork: Network? = null

    private lateinit var wakeLockManager: SipWakeLockManager
    private lateinit var ringtonePlayer: SipRingtonePlayer
    private lateinit var audioRouter: SipAudioRouter
    private lateinit var incomingCallHandler: SipIncomingCallHandler

    private var lastWasConfirmed = false
    private var callStartTime = 0L
    private var lastSession: com.ipdial.data.model.CallSession? = null
    private var autoRecordedCallId = -1
    private val authFailedAccounts = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun isPermanentAuthFailure(statusCode: Int) = statusCode == 401 || statusCode == 403

    override fun onCreate() {
        super.onCreate()
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("SipService", "Stopping SIP service because microphone permission is not granted")
            stopSelf()
            return
        }
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        repo = AccountRepository(applicationContext)
        contactsRepo = com.ipdial.data.repository.ContactsRepository(applicationContext)

        wakeLockManager = SipWakeLockManager(this)
        ringtonePlayer = SipRingtonePlayer(this, audioManager, repo)
        audioRouter = SipAudioRouter(this, audioManager)
        SipEngine.audioRouter = audioRouter
        incomingCallHandler = SipIncomingCallHandler(this, scope, repo, contactsRepo, wakeLockManager)

        // Keep CPU awake for background SIP network stack and NAT keepalives
        wakeLockManager.acquireWakeLock()

        createNotificationChannels(this)
        TelecomHelper.registerPhoneAccount(applicationContext)

        SipEngine.onIncomingCall = { session ->
            incomingCallHandler.handle(session)
        }

        SipEngine.onCallDisconnected = { callId ->
            Log.d("SipService", "onCallDisconnected: callId=$callId — stopping ringtone/vibration and dismissing incoming UI")
            SipEngine.setDndActive(false)
            stopPushingBanner()
            ringtonePlayer.stopRingtone()
            cancelIncomingNotification(this)
            
            // CRITICAL FIX: Force session cleanup when call is disconnected remotely
            // This prevents the UI from showing a stale call screen
            val session = SipEngine.callSession.value
            if (session != null && session.callId == callId) {
                Log.d("SipService", "onCallDisconnected: forcing session cleanup for callId=$callId")
                SipEngine.callMap.remove(callId)
                SipEngine._callSession.value = null
            }
        }

        scope.launch {
            withContext(Dispatchers.Main) {
                SipEngine.init(applicationContext)
            }

            try {
                contactsRepo.buildNumberIndex()
                Log.d("SipService", "Contact number index built")
            } catch (e: Exception) {
                Log.e("SipService", "Failed to build contact number index", e)
            }

            registerAccountsFromDataStore()
            registerDefaultNetworkCallback()
        }

        observeCallState()

        scope.launch {
            while (true) {
                delay(10_000)
                try {
                    SipEngine.nullSessionIfStale()
                } catch (_: Throwable) {}
            }
        }
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

                    // Reset auth-failed set on network switch so accounts get a fresh chance
                    // on the new network connection (e.g. switching off captive WiFi)
                    authFailedAccounts.clear()

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

                            // If a call is in progress, preserve its audio path during
                            // the network switch instead of tearing down all accounts
                            // (which would drop the call's media transport).
                            val hasActiveCall = SipEngine.callSession.value != null &&
                                SipEngine.callSession.value?.state != CallState.DISCONNECTED
                            if (hasActiveCall) {
                                Log.d("SipService", "Active call during network change — re-registering registration only, preserving call media")
                                SipEngine.handleIpChange()
                            } else {
                                SipEngine.reconnectOnNetworkChange(network, applicationContext)
                            }
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
            startServiceForeground()
        }

        when (intent?.action) {
            ACTION_ANSWER -> {
                val callId = intent.getIntExtra("callId", -1)
                SipEngine.answerCall(callId)
                audioRouter.routeAudioToEarpiece()
                stopPushingBanner()
                cancelIncomingNotification(this)
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
                cancelIncomingNotification(this)
            }
            "ACTION_STOP_BANNER" -> {
                cancelIncomingNotification(this)
                stopPushingBanner()
            }
            ACTION_SET_AUDIO_DEVICE -> {
                val mode = intent.getStringExtra("mode") ?: AudioDeviceMode.EARPIECE.name
                Log.d("SipService", "ACTION_SET_AUDIO_DEVICE: $mode")
                when (mode) {
                    AudioDeviceMode.EARPIECE.name -> audioRouter.routeAudioToEarpiece()
                    AudioDeviceMode.SPEAKER.name -> audioRouter.routeAudioToSpeaker(true)
                    AudioDeviceMode.BLUETOOTH.name -> audioRouter.routeAudioToBluetooth()
                }
            }
            ACTION_HANGUP -> {
                val callId = intent.getIntExtra("callId", -1)
                val session = SipEngine.callSession.value
                val id = if (callId >= 0) callId else session?.callId ?: -1
                val cause = CallHangupResolver.resolveDisconnectCause(session)
                SipEngine.hangupCall(id)
                if (id != -1) {
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
            repo.accounts.collect { accounts ->
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
                            authFailedAccounts.remove(account.id)
                            activeConfigs[account.id] = account
                            SipEngine.addAccount(account)
                        }
                    } else {
                        authFailedAccounts.remove(account.id)
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

        scope.launch {
            SipEngine.registrationEvents.collect { (accountId, status, statusCode) ->
                repo.updateRegStatus(accountId, status)
                when {
                    status == RegStatus.REGISTERED -> {
                        if (authFailedAccounts.remove(accountId)) {
                            Log.i("SipService", "Account $accountId re-registered, removed from auth-failed watchlist")
                        }
                    }
                    status == RegStatus.ERROR && isPermanentAuthFailure(statusCode) -> {
                        authFailedAccounts.add(accountId)
                        Log.w("SipService", "Account $accountId permanent auth failure (code=$statusCode). Pausing auto-retry until credentials change.")
                    }
                    status == RegStatus.UNREGISTERED -> {
                        authFailedAccounts.remove(accountId)
                        val accounts = repo.accounts.first()
                        val targetAcc = accounts.firstOrNull { it.id == accountId }
                        if (targetAcc != null && targetAcc.isEnabled) {
                            Log.w("SipService", "Account $accountId unregistered. Scheduling quick retry in 5s.")
                            scope.launch {
                                delay(5_000)
                                val freshAccounts = repo.accounts.first()
                                val freshAcc = freshAccounts.firstOrNull { it.id == accountId }
                                if (freshAcc != null && freshAcc.isEnabled &&
                                    freshAcc.regStatus != RegStatus.REGISTERED &&
                                    accountId !in authFailedAccounts
                                ) {
                                    Log.i("SipService", "Quick retry re-adding account $accountId")
                                    repo.updateRegStatus(accountId, RegStatus.REGISTERING)
                                    SipEngine.addAccount(freshAcc)
                                }
                            }
                        }
                    }
                    status == RegStatus.ERROR -> {
                        // Non-permanent errors (e.g. DNS failure, network unreachable)
                        authFailedAccounts.remove(accountId)
                        val accounts = repo.accounts.first()
                        val targetAcc = accounts.firstOrNull { it.id == accountId }
                        if (targetAcc != null && targetAcc.isEnabled) {
                            Log.w("SipService", "Account $accountId error (code=$statusCode). Scheduling retry in 10s.")
                            scope.launch {
                                delay(10_000)
                                val freshAccounts = repo.accounts.first()
                                val freshAcc = freshAccounts.firstOrNull { it.id == accountId }
                                if (freshAcc != null && freshAcc.isEnabled &&
                                    freshAcc.regStatus != RegStatus.REGISTERED &&
                                    accountId !in authFailedAccounts
                                ) {
                                    Log.i("SipService", "Retrying account $accountId")
                                    repo.updateRegStatus(accountId, RegStatus.REGISTERING)
                                    SipEngine.addAccount(freshAcc)
                                }
                            }
                        }
                    }
                }
            }
        }

        scope.launch {
            while (true) {
                delay(30_000)
                try {
                    val accounts = repo.accounts.first()
                    val enabledAccounts = accounts.filter { it.isEnabled }
                    for (account in enabledAccounts) {
                        if (account.id in authFailedAccounts) continue
                        // Skip accounts that are already in the process of registering
                        if (account.regStatus == RegStatus.REGISTERED || account.regStatus == RegStatus.REGISTERING) continue
                        
                        Log.d("SipService", "Keep-alive: Account ${account.id} not registered (status=${account.regStatus}). Attempting re-registration.")
                        // First try lightweight re-registration
                        SipEngine.reconnectAccount(account.id)
                        // Allow time for transaction to complete before checking
                        delay(15_000)
                        // If still not registered and not in progress, force full re-add
                        val freshAccounts = repo.accounts.first()
                        val freshAccount = freshAccounts.firstOrNull { it.id == account.id }
                        if (freshAccount != null && freshAccount.isEnabled &&
                            freshAccount.regStatus != RegStatus.REGISTERED &&
                            freshAccount.regStatus != RegStatus.REGISTERING
                        ) {
                            Log.w("SipService", "Keep-alive: Re-registration failed for ${account.id}. Force re-adding account.")
                            repo.updateRegStatus(account.id, RegStatus.REGISTERING)
                            SipEngine.addAccount(freshAccount)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SipService", "Keep-alive loop error", e)
                }
            }
        }
    }

    private fun observeCallState() {
        scope.launch {
            SipEngine.callSession.collect { session ->
                if (session == null) {
                    val sessionToLog = lastSession
                    if (sessionToLog != null) {
                        val duration = if (callStartTime > 0) (System.currentTimeMillis() - callStartTime) / 1000 else 0L
                        // D5: pull the final SIP disconnect code/reason from SipEngine's
                        // side-channel (StateFlow conflates the DISCONNECTED value for
                        // slow collectors, so we cannot rely on the session alone).
                        val pendingDisconnect = SipEngine.consumeDisconnectInfo()
                        val disconnectCode = sessionToLog.disconnectCode ?: pendingDisconnect?.first
                        val disconnectReason = sessionToLog.disconnectReason ?: pendingDisconnect?.second
                        Log.d("SipService", "Call ended: code=$disconnectCode reason=$disconnectReason (via session=${sessionToLog.disconnectCode}/${sessionToLog.disconnectReason}, sideChannel=${pendingDisconnect})")
                        val entry = com.ipdial.data.model.CallLogEntry(
                            accountId = sessionToLog.accountId,
                            remoteUri = sessionToLog.remoteUri,
                            remoteDisplayName = sessionToLog.remoteDisplayName,
                            direction = sessionToLog.direction,
                            timestampMs = System.currentTimeMillis(),
                            durationSeconds = duration,
                            missed = !lastWasConfirmed && sessionToLog.direction == CallDirection.INCOMING,
                            disconnectCode = disconnectCode,
                            disconnectReason = disconnectReason
                        )
                        // D5: surface busy/no-answer/rejected as a toast so the user knows why.
                        if (disconnectCode != null && disconnectCode >= 300) {
                            val reasonText = buildString {
                                when (disconnectCode) {
                                    480 -> append("No Answer")
                                    486 -> append("Line Busy")
                                    487 -> append("Request Terminated")
                                    603 -> append("Call Declined")
                                    else -> append("Call Failed ($disconnectCode)")
                                }
                                if (!disconnectReason.isNullOrBlank() && disconnectCode != 480 && disconnectCode != 487) {
                                    append(" — ").append(disconnectReason)
                                }
                            }
                            withContext(Dispatchers.Main) {
                                android.widget.Toast.makeText(applicationContext, reasonText, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                        // Use the service scope so the write is tied to the service lifecycle
                        scope.launch(Dispatchers.IO) {
                            com.ipdial.data.repository.CallLogRepository.getInstance(applicationContext).insert(entry)
                        }
                        if (entry.missed) {
                            showMissedCallNotification(this@SipService, sessionToLog.remoteDisplayName, sessionToLog.remoteUri)
                        }
                    }
                    ringtonePlayer.stopRingtone()
                    stopPushingBanner()
                    audioRouter.restoreAudio()

                    // Close the PJSIP sound device so the mic (AudioRecord) is
                    // released.  The native call.delete() is posted to the main
                    // looper in onCallState(DISCONNECTED); give it a moment to
                    // finish before tearing down the sound device.  The next call's
                    // forceAudioDevicesForCall() re-opens it automatically.
                    scope.launch {
                        try {
                            kotlinx.coroutines.delay(500)
                            SipEngine.releaseSoundDevice()
                        } catch (_: Throwable) {}
                    }

                    wakeLockManager.releaseWakeLock()
                    cancelIncomingNotification(this@SipService)
                    sessionToLog?.callId?.let { SipConnectionService.disconnectCall(it) }
                    lastWasConfirmed = false
                    callStartTime = 0
                    activeCallStartTime = 0L
                    lastSession = null
                    autoRecordedCallId = -1

                    val idleType = if (Build.VERSION.SDK_INT >= 34) {
                        1073741824 // ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    }
                    updateForegroundType(idleType)
                } else {
                    val stateChanged = session.state != lastSession?.state
                    val speakerChanged = session.isSpeaker != lastSession?.isSpeaker

                    lastSession = session

                    audioManager.isMicrophoneMute = session.isMuted

                    when (session.state) {
                        CallState.INCOMING -> {
                            if (audioManager.mode != AudioManager.MODE_NORMAL) {
                                audioManager.mode = AudioManager.MODE_NORMAL
                            }
                            ringtonePlayer.playRingtone()
                            wakeLockManager.acquireWakeLockForIncoming()
                            wakeLockManager.acquireWakeLock()
                            updateForegroundType(ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
                            if (speakerChanged) {
                                audioRouter.routeAudioToSpeaker(session.isSpeaker)
                            }
                            if (!com.ipdial.AppState.isForeground && !SipEngine.isDndActive()) {
                                showCallNotificationStatic(this@SipService, session.remoteDisplayName, session.callId)
                            }
                        }
                        CallState.CONFIRMED -> {
                            if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
                                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                            }
                            ringtonePlayer.stopRingtone()
                            stopPushingBanner()

                            if (stateChanged || speakerChanged) {
                                scope.launch {
                                    if (stateChanged) delay(300)
                                    audioRouter.routeAudioToDefault()
                                }
                            }

                            wakeLockManager.acquireWakeLock()
                            wakeLockManager.acquireProximityWakeLock()
                            lastWasConfirmed = true
                            if (callStartTime == 0L) {
                                callStartTime = System.currentTimeMillis()
                                activeCallStartTime = callStartTime
                            }
                            updateForegroundType(ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
                            autoStartRecordingIfEnabled(session)

                            if (!com.ipdial.AppState.isForeground) {
                                showCallNotificationStatic(this@SipService, session.remoteDisplayName, session.callId)
                            }
                        }
                        CallState.CALLING, CallState.EARLY, CallState.CONNECTING -> {
                            if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
                                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                            }
                            if (stateChanged || speakerChanged) {
                                audioRouter.routeAudioToDefault()
                            }
                            wakeLockManager.acquireWakeLock()
                            wakeLockManager.acquireProximityWakeLock()
                            updateForegroundType(ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)

                            if (!com.ipdial.AppState.isForeground) {
                                showCallNotificationStatic(this@SipService, session.remoteDisplayName, session.callId)
                            }
                        }
                        else -> {
                            if (speakerChanged) {
                                audioRouter.routeAudioToDefault()
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
        val notification = buildServiceNotification(this)
        val initialType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasActiveCall = SipEngine.callSession.value?.state != null && SipEngine.callSession.value?.state != CallState.DISCONNECTED
            if (hasActiveCall) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            } else if (Build.VERSION.SDK_INT >= 34) {
                1073741824 // ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
        } else 0

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    androidx.core.app.ServiceCompat.startForeground(
                        this,
                        NOTIF_ID_SERVICE,
                        notification,
                        initialType
                    )
                } else {
                    startForeground(NOTIF_ID_SERVICE, notification, initialType)
                }
                Log.d("SipService", "Started FGS with type $initialType")
            } catch (e: Exception) {
                Log.w("SipService", "Failed to start FGS with type $initialType: ${e.message}")
                try {
                    startForeground(NOTIF_ID_SERVICE, notification)
                } catch (lastEx: Exception) {
                    Log.e("SipService", "Absolute FGS failure", lastEx)
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
                        buildServiceNotification(this),
                        type
                    )
                } else {
                    startForeground(NOTIF_ID_SERVICE, buildServiceNotification(this), type)
                }
            } catch (e: Exception) {
                Log.e("SipService", "Failed to update FGS type to $type", e)
            }
        }
    }

    override fun onDestroy() {
        wakeLockManager.releaseWakeLock()
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                SipEngine.destroy()
            } catch (_: Exception) {}
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // If the user swiped the app away from recents, keep the service running
        // to continue receiving calls (this is a VoIP app).
        // But if the app was explicitly exited via the Exit button,
        // the service is stopped before this is called.
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
