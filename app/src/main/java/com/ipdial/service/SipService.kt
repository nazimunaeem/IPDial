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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SipService : Service() {

    companion object {
        fun start(context: Context, delayStartForeground: Boolean = false) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                Log.w("SipService", "Cannot start SIP service: RECORD_AUDIO permission not granted")
                return
            }
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

        fun silenceRinger(context: Context) {
            val intent = Intent(context, SipService::class.java).apply {
                action = ACTION_SILENCE_RINGER
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e("SipService", "Unable to silence ringer", e)
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
    @Volatile private var lastSession: com.ipdial.data.model.CallSession? = null
    private var autoRecordedCallId = -1
    private var vibratedForCallId = -1
    private val authFailedAccounts = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun isPermanentAuthFailure(statusCode: Int) = statusCode == 401 || statusCode == 403

    private fun isNetworkAvailable(cm: ConnectivityManager): Boolean {
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun triggerCallConnectVibration() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            }
            if (!vibrator.hasVibrator()) return
            val effect = android.os.VibrationEffect.createOneShot(150L, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(
                    effect,
                    android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_COMMUNICATION_REQUEST)
                )
            } else {
                @Suppress("DEPRECATION")
                val attrs = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect, attrs)
            }
        } catch (e: Throwable) {
            Log.w("SipService", "Call connect vibration failed: ${e.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Must call startForeground() before anything else. The service is started
        // via startForegroundService(), and if we stopSelf() (e.g. missing mic
        // permission) or do any slow work before calling startForeground(), Android
        // kills the process with ForegroundServiceDidNotStartInTimeException once the
        // ~5s timeout elapses (onStartCommand may never even be dispatched).
        try {
            createNotificationChannels(this)
        } catch (e: Throwable) {
            Log.e("SipService", "Failed to create notification channels", e)
        }
        try {
            startServiceForeground()
        } catch (e: Throwable) {
            Log.e("SipService", "startServiceForeground threw", e)
        }

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

        TelecomHelper.registerPhoneAccount(applicationContext)

        SipEngine.onIncomingCall = { session ->
            incomingCallHandler.handle(session)
        }

        SipEngine.onOutgoingCallStarted = { session ->
            // Keep the final-state observer's fallback populated even if a very
            // fast failure conflates CALLING before its collector runs.
            lastSession = session
            val entry = com.ipdial.data.model.CallLogEntry(
                id = session.callLogId,
                accountId = session.accountId,
                remoteUri = session.remoteUri,
                remoteDisplayName = session.remoteDisplayName,
                direction = CallDirection.OUTGOING,
                timestampMs = session.startedAtMs
            )
            scope.launch(Dispatchers.IO) {
                com.ipdial.data.repository.CallLogRepository.getInstance(applicationContext).insert(entry)
            }
        }

        SipEngine.onCallDisconnected = { callId ->
            Log.d("SipService", "onCallDisconnected: callId=$callId — stopping ringtone/vibration and dismissing incoming UI")
            SipEngine.setDndActive(false)
            ringtonePlayer.stopRingtone()
            cancelIncomingNotification(this)

            // Removed the force-null of SipEngine._callSession here: SipCallDelegate
            // owns session teardown via scheduleSessionNull(), which keeps the session
            // alive briefly (2s for 486/487 busy) so the busy tone plays before the
            // screen closes. Force-nulling here raced that delay and cut it to zero.
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

            registerDefaultNetworkCallback()
            registerAccountsFromDataStore()
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
        val initialNet = connectivityManager.activeNetwork
        val initiallyConnected = isNetworkAvailable(connectivityManager)
        isConnected = initiallyConnected
        lastNetwork = if (initiallyConnected) initialNet else null
        if (!initiallyConnected) {
            scope.launch(Dispatchers.IO) {
                repo.accounts.first().filter { it.isEnabled }.forEach {
                    repo.updateRegStatus(it.id, RegStatus.ERROR, "Offline (no internet)")
                }
            }
        }

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
                        val hasMissingInEngine = enabledAccounts.any { !SipEngine.accountConfigs.containsKey(it.id) }
                        val shouldReconnect = if (!isInitial) {
                            networkChanged || wasOffline || hasUnregistered || hasMissingInEngine
                        } else {
                            hasUnregistered || hasMissingInEngine
                        }

                        if (shouldReconnect) {
                            Log.d("SipService", "Default network active/changed (isInitial=$isInitial, wasOffline=$wasOffline, networkChanged=$networkChanged). Reconnecting...")
                            enabledAccounts.forEach { account ->
                                repo.updateRegStatus(account.id, RegStatus.REGISTERING, "Registering...")
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
                                if (SipEngine.accountConfigs.isNotEmpty()) {
                                    SipEngine.reconnectOnNetworkChange(network, applicationContext)
                                }
                                enabledAccounts.forEach { account ->
                                    if (!SipEngine.accountConfigs.containsKey(account.id)) {
                                        activeConfigs[account.id] = account
                                        SipEngine.addAccount(account)
                                    }
                                }
                            }
                        }
                    }
                }

                override fun onLost(network: Network) {
                    Log.d("SipService", "onLost default network: $network")
                    val stillConnected = isNetworkAvailable(connectivityManager)
                    if (!stillConnected) {
                        isConnected = false
                        lastNetwork = null
                        scope.launch(Dispatchers.IO) {
                            val freshAccounts = repo.accounts.first()
                            freshAccounts.filter { it.isEnabled }.forEach {
                                repo.updateRegStatus(it.id, RegStatus.ERROR, "Offline (no internet)")
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
                cancelIncomingNotification(this)
            }
            "ACTION_STOP_BANNER" -> {
                cancelIncomingNotification(this)
            }
            ACTION_SILENCE_RINGER -> {
                Log.d("SipService", "ACTION_SILENCE_RINGER: silencing ringtone and vibration")
                if (::ringtonePlayer.isInitialized) {
                    ringtonePlayer.stopRingtone()
                }
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

                            val finalUri = SipEngine.formatDialUri(number, acc)

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
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val hasNet = isNetworkAvailable(cm)
                isConnected = hasNet
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
                            if (hasNet) {
                                repo.updateRegStatus(account.id, RegStatus.REGISTERING, "Registering...")
                                SipEngine.addAccount(account)
                            } else {
                                repo.updateRegStatus(account.id, RegStatus.ERROR, "Offline (no internet)")
                            }
                        }
                    } else {
                        authFailedAccounts.remove(account.id)
                        if (activeConfigs.containsKey(account.id)) {
                            activeConfigs.remove(account.id)
                            SipEngine.removeAccount(account.id)
                        }
                        if (account.regStatus != RegStatus.UNREGISTERED) {
                            scope.launch {
                                repo.updateRegStatus(account.id, RegStatus.UNREGISTERED, "Disabled")
                            }
                        }
                    }
                }
            }
        }

        // Push global NAT traversal settings into the engine and re-add enabled
        // accounts whenever they change (Settings → Network). TURN is universal,
        // not per-account.
        scope.launch {
            combine(
                repo.turnServer,
                repo.turnUsername,
                repo.turnPassword,
                repo.turnTransport
            ) { server, user, pass, tp ->
                SipEngine.updateGlobalNatSettings(
                    server.ifBlank { null },
                    user.ifBlank { null },
                    pass,
                    tp
                )
            }.collect { }
        }

        scope.launch {
            SipEngine.registrationEvents.collect { event ->
                val (accountId, status, statusCode, reason) = event
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val hasNet = isNetworkAvailable(cm)
                isConnected = hasNet

                val effectiveStatus = if (!hasNet && status != RegStatus.UNREGISTERED) {
                    RegStatus.ERROR
                } else {
                    status
                }
                val statusText = when (effectiveStatus) {
                    RegStatus.REGISTERED -> "Registered (200 OK)"
                    RegStatus.REGISTERING -> "Registering..."
                    RegStatus.ERROR -> when {
                        !hasNet -> "Offline (no internet)"
                        statusCode > 0 && reason.isNotBlank() -> "Error ($statusCode): $reason"
                        statusCode > 0 -> "Error ($statusCode)"
                        reason.isNotBlank() -> reason
                        else -> "Error / Offline"
                    }
                    else -> "Unregistered"
                }
                repo.updateRegStatus(accountId, effectiveStatus, statusText)
                when {
                    effectiveStatus == RegStatus.REGISTERED -> {
                        if (authFailedAccounts.remove(accountId)) {
                            Log.i("SipService", "Account $accountId re-registered, removed from auth-failed watchlist")
                        }
                    }
                    effectiveStatus == RegStatus.ERROR && isPermanentAuthFailure(statusCode) -> {
                        authFailedAccounts.add(accountId)
                        Log.w("SipService", "Account $accountId permanent auth failure (code=$statusCode, reason=$reason). Pausing auto-retry until credentials change.")
                    }
                    effectiveStatus == RegStatus.UNREGISTERED -> {
                        authFailedAccounts.remove(accountId)
                        if (!hasNet) return@collect
                        val accounts = repo.accounts.first()
                        val targetAcc = accounts.firstOrNull { it.id == accountId }
                        if (targetAcc != null && targetAcc.isEnabled) {
                            Log.w("SipService", "Account $accountId unregistered. Scheduling quick retry in 5s.")
                            scope.launch {
                                delay(5_000)
                                if (!isNetworkAvailable(cm)) return@launch
                                val freshAccounts = repo.accounts.first()
                                val freshAcc = freshAccounts.firstOrNull { it.id == accountId }
                                if (freshAcc != null && freshAcc.isEnabled &&
                                    freshAcc.regStatus != RegStatus.REGISTERED &&
                                    accountId !in authFailedAccounts
                                ) {
                                    Log.i("SipService", "Quick retry re-adding account $accountId")
                                    repo.updateRegStatus(accountId, RegStatus.REGISTERING, "Registering...")
                                    SipEngine.addAccount(freshAcc)
                                }
                            }
                        }
                    }
                    effectiveStatus == RegStatus.ERROR -> {
                        // Non-permanent errors (e.g. DNS failure, 503 Network is unreachable)
                        authFailedAccounts.remove(accountId)
                        if (!hasNet) return@collect
                        val accounts = repo.accounts.first()
                        val targetAcc = accounts.firstOrNull { it.id == accountId }
                        if (targetAcc != null && targetAcc.isEnabled) {
                            Log.w("SipService", "Account $accountId error (code=$statusCode, reason=$reason). Scheduling retry in 10s.")
                            scope.launch {
                                delay(10_000)
                                if (!isNetworkAvailable(cm)) {
                                    repo.updateRegStatus(accountId, RegStatus.ERROR, "Offline (no internet)")
                                    return@launch
                                }
                                val freshAccounts = repo.accounts.first()
                                val freshAcc = freshAccounts.firstOrNull { it.id == accountId }
                                if (freshAcc != null && freshAcc.isEnabled &&
                                    freshAcc.regStatus != RegStatus.REGISTERED &&
                                    accountId !in authFailedAccounts
                                ) {
                                    val hasActiveCall = SipEngine.callSession.value != null &&
                                        SipEngine.callSession.value?.state != CallState.DISCONNECTED
                                    repo.updateRegStatus(accountId, RegStatus.REGISTERING, "Registering...")
                                    if (!hasActiveCall && (statusCode == 503 || statusCode == 408 || statusCode == 0)) {
                                        Log.i("SipService", "Rebuilding PJSIP transport on network error ($statusCode) for $accountId")
                                        SipEngine.reconnectOnNetworkChange(cm.activeNetwork, applicationContext)
                                    } else {
                                        Log.i("SipService", "Retrying account $accountId")
                                        SipEngine.addAccount(freshAcc)
                                    }
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
                    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    val hasNet = isNetworkAvailable(cm)
                    isConnected = hasNet
                    val accounts = repo.accounts.first()
                    val enabledAccounts = accounts.filter { it.isEnabled }
                    if (!hasNet) {
                        enabledAccounts.forEach { acc ->
                            if (acc.regStatus != RegStatus.ERROR || acc.regStatusText != "Offline (no internet)") {
                                repo.updateRegStatus(acc.id, RegStatus.ERROR, "Offline (no internet)")
                            }
                        }
                        continue
                    }

                    for (account in enabledAccounts) {
                        if (account.id in authFailedAccounts) continue
                        // Skip accounts that are already registered or registering
                        if (account.regStatus == RegStatus.REGISTERED || account.regStatus == RegStatus.REGISTERING) continue

                        Log.d("SipService", "Keep-alive: Account ${account.id} not registered (status=${account.regStatus}). Attempting re-registration.")
                        if (SipEngine.accountConfigs.containsKey(account.id)) {
                            SipEngine.reconnectAccount(account.id)
                        } else {
                            SipEngine.addAccount(account)
                        }
                        delay(15_000)
                        if (!isNetworkAvailable(cm)) continue
                        val freshAccounts = repo.accounts.first()
                        val freshAccount = freshAccounts.firstOrNull { it.id == account.id }
                        if (freshAccount != null && freshAccount.isEnabled &&
                            freshAccount.regStatus != RegStatus.REGISTERED &&
                            freshAccount.regStatus != RegStatus.REGISTERING
                        ) {
                            val hasActiveCall = SipEngine.callSession.value != null &&
                                SipEngine.callSession.value?.state != CallState.DISCONNECTED
                            repo.updateRegStatus(account.id, RegStatus.REGISTERING, "Registering...")
                            if (!hasActiveCall) {
                                Log.w("SipService", "Keep-alive: Rebuilding transport & re-registering for ${account.id}.")
                                SipEngine.reconnectOnNetworkChange(cm.activeNetwork, applicationContext)
                            } else {
                                SipEngine.addAccount(freshAccount)
                            }
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
                            id = sessionToLog.callLogId,
                            accountId = sessionToLog.accountId,
                            remoteUri = sessionToLog.remoteUri,
                            remoteDisplayName = sessionToLog.remoteDisplayName,
                            direction = sessionToLog.direction,
                            timestampMs = sessionToLog.startedAtMs,
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
                        // Task 7 — TURN/media failure graceful degradation: if the call
                        // was torn down because its media transport errored (ICE/TURN
                        // allocation failure, relay quota exhausted, UDP blocked), show
                        // a clear user-visible message rather than letting the call
                        // screen just vanish / silently fail. Only set for calls that
                        // actually needed the relay — direct calls never hit this.
                        if (sessionToLog.callId == SipEngine.mediaFailureToastShownFor) {
                            SipEngine.mediaFailureToastShownFor = -1
                            withContext(Dispatchers.Main) {
                                android.widget.Toast.makeText(
                                    applicationContext,
                                    "Call failed — network issue, please try again",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
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
                    audioRouter.restoreAudio()

                    wakeLockManager.releaseWakeLock()
                    cancelIncomingNotification(this@SipService)
                    sessionToLog?.callId?.let { SipConnectionService.disconnectCall(it) }
                    lastWasConfirmed = false
                    callStartTime = 0
                    activeCallStartTime = 0L
                    lastSession = null
                    autoRecordedCallId = -1
                    vibratedForCallId = -1

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
                        // INCOMING fires on the PJSIP worker thread while the session is
                        // created on the app PjsipThread; if those race, the StateFlow
                        // collector can skip INCOMING (180 is answered immediately, so the
                        // value lands on EARLY). Handle both as "ringing" for an incoming
                        // call so the ringtone/wake locks/notification are never skipped.
                        CallState.INCOMING,
                        CallState.EARLY -> if (session.direction == CallDirection.INCOMING) {
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
                        } else {
                            applyActiveCallProgress(session, stateChanged, speakerChanged)
                        }
                        CallState.CONFIRMED -> {
                            updateForegroundType(ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
                            if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
                                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                            }
                            audioManager.isMicrophoneMute = session.isMuted
                            ringtonePlayer.stopRingtone()

                            if (vibratedForCallId != session.callId && !SipEngine.isDndActive()) {
                                vibratedForCallId = session.callId
                                triggerCallConnectVibration()
                            }

                            if (stateChanged || speakerChanged) {
                                scope.launch {
                                    if (stateChanged) delay(300)
                                    audioRouter.routeAudioToDefault()
                                    // Give the OS 150 ms to switch the physical audio
                                    // device (setCommunicationDevice is asynchronous on
                                    // many OEMs), then tell PJSIP to re-open its
                                    // AudioRecord/AudioTrack so it captures from the
                                    // new device instead of the old one.
                                    if (speakerChanged) {
                                        delay(150)
                                        val callId = SipEngine.callSession.value?.callId ?: -1
                                        if (callId >= 0) {
                                            SipEngine.reconnectAudioPathForCall(callId)
                                        }
                                    }
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
                        CallState.CALLING, CallState.CONNECTING -> {
                            applyActiveCallProgress(session, stateChanged, speakerChanged)
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

    /** Applies the dialing/progress handling for OUTGOING calls (CALLING / EARLY / CONNECTING). */
    private fun applyActiveCallProgress(
        session: com.ipdial.data.model.CallSession,
        stateChanged: Boolean,
        speakerChanged: Boolean
    ) {
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

    private suspend fun autoStartRecordingIfEnabled(session: com.ipdial.data.model.CallSession) {
        try {
            if (autoRecordedCallId == session.callId) return
            if (session.isRecording || session.isRecordingPending) return

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
        try {
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
        } catch (e: Throwable) {
            Log.e("SipService", "startServiceForeground failed", e)
            // Never stay alive as a service started via startForegroundService()
            // without having promoted itself to foreground. The system would kill
            // the whole process anyway via ForegroundServiceDidNotStartInTimeException.
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Throwable) {
            }
            stopSelf()
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
        if (::ringtonePlayer.isInitialized) {
            ringtonePlayer.stopRingtone()
        }
        if (::wakeLockManager.isInitialized) {
            wakeLockManager.releaseWakeLock()
        }
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
