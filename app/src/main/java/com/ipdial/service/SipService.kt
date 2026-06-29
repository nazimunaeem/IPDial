package com.ipdial.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.*
import android.util.Log
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.ipdial.MainActivity
import com.ipdial.R
import com.ipdial.data.model.*
import com.ipdial.data.repository.AccountRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first

class SipService : Service() {

    companion object {
        const val NOTIF_CHANNEL_SIP = "sip_service"
        const val NOTIF_CHANNEL_CALL = "incoming_call"
        const val NOTIF_ID_SERVICE = 1001
        const val NOTIF_ID_INCOMING = 1002

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
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.e("SipService", "startForegroundService failed, trying regular startService", e)
                context.startService(intent)
            }
        }

        fun showIncomingCallNotificationStatic(context: Context, callerName: String, callId: Int) {
            if (com.ipdial.AppState.isForeground) return

            val fullscreenIntent = Intent(context, MainActivity::class.java).apply {
                action = "com.ipdial.ACTION_INCOMING_CALL"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val fullscreenPi = PendingIntent.getActivity(context, 0, fullscreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val answerPi = PendingIntent.getService(context, 1,
                Intent(context, SipService::class.java).apply {
                    action = ACTION_ANSWER
                    putExtra("callId", callId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val declinePi = PendingIntent.getService(context, 2,
                Intent(context, SipService::class.java).apply {
                    action = ACTION_DECLINE
                    putExtra("callId", callId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val callerPerson = androidx.core.app.Person.Builder()
                .setName(callerName)
                .setImportant(true)
                .build()

            val answerText = android.text.SpannableString("Answer")
            answerText.setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#4CAF50")), 0, answerText.length, 0)
            val declineText = android.text.SpannableString("Decline")
            declineText.setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#F44336")), 0, declineText.length, 0)

            val notif = NotificationCompat.Builder(context, NOTIF_CHANNEL_CALL)
                .setContentTitle("Incoming Call")
                .setContentText(callerName)
                .setSmallIcon(R.drawable.ic_notif_call)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(fullscreenPi, true)
                .setStyle(NotificationCompat.CallStyle.forIncomingCall(callerPerson, declinePi, answerPi))
                .setAutoCancel(false)
                .setOngoing(true)
                .build()

            val nm = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID_INCOMING, notif)
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var audioManager: AudioManager
    private lateinit var repo: AccountRepository
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
        createNotificationChannels()
        TelecomHelper.registerPhoneAccount(applicationContext)
        
        startServiceForeground()

        scope.launch {
            // 1. Initialize PJSIP
            SipEngine.init(applicationContext)
            
            Handler(Looper.getMainLooper()).post {
                SipEngine.onIncomingCall = { session -> 
                    scope.launch {
                        val isDnd = repo.dndEnabled.first()
                        if (isDnd) {
                            SipEngine.hangupCall(session.callId)
                        } else {
                            // Resolve contact name or clean number
                            val displayName = session.remoteDisplayName
                            val cleanNum = session.remoteUri.replace("<", "").replace(">", "").removePrefix("sip:").substringBefore("@").substringBefore(";")
                            
                            val contactsRepo = com.ipdial.data.repository.ContactsRepository(applicationContext)
                            val contacts = contactsRepo.getContacts("")
                            val cleanedSessionDigits = cleanNum.filter { it.isDigit() }
                            
                            var matchedContact: com.ipdial.data.model.Contact? = null
                            if (cleanedSessionDigits.length >= 10) {
                                matchedContact = contacts.find { c ->
                                    c.numbers.any { n ->
                                        val cleanedContactDigits = n.filter { it.isDigit() }
                                        cleanedContactDigits.length >= 10 && (cleanedSessionDigits.contains(cleanedContactDigits) || cleanedContactDigits.contains(cleanedSessionDigits))
                                    }
                                }
                            }
                            
                            val finalDisplayName = matchedContact?.name ?: cleanNum.ifBlank { displayName }
                            
                            withContext(Dispatchers.Main) {
                                TelecomHelper.reportIncomingCall(applicationContext, session.remoteUri, finalDisplayName)
                                showIncomingCallNotification(finalDisplayName, session.callId)
                            }
                        }
                    }
                }
            }
            // 3. Register accounts flow
            registerAccountsFromDataStore()

            // 4. Register default network callback
            registerDefaultNetworkCallback()
        }

        observeCallState()
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
                            
                            SipEngine.handleIpChange()
                            delay(1000)
                            SipEngine.forceReconnectAll()
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
        val delayStartForeground = intent?.getBooleanExtra("delayStartForeground", false) ?: false

        if (delayStartForeground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Delay foreground service promotion to allow system to stabilize after boot
            try {
                // First, try to start as regular background service
                Log.d("SipService", "Starting in background mode with delayed foreground promotion")
                // This ensures we can start without FGS restrictions
                Handler(Looper.getMainLooper()).postDelayed({
                    startServiceForeground()
                }, 2000) // 2 second delay
            } catch (e: Exception) {
                Log.e("SipService", "Failed to schedule delayed foreground promotion", e)
                startServiceForeground()
            }
        } else {
            startServiceForeground()
        }

        when (intent?.action) {
            ACTION_ANSWER -> {
                val callId = intent.getIntExtra("callId", -1)
                SipEngine.answerCall(callId)
                routeAudioToEarpiece()
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
                SipEngine.hangupCall(callId)
                cancelIncomingNotification()
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
            ACTION_HANGUP -> SipEngine.hangupCall()
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
                                var num = number.removePrefix("sip:")
                                if (num.all { it.isDigit() }) {
                                    if (num.length == 11 && num.startsWith("0")) {
                                        num = "+880${num.drop(1)}"
                                    } else if (num.length == 10 && num.startsWith("1")) {
                                        num = "+880$num"
                                    }
                                }

                                val host = if (acc.port != null && acc.port > 0 && !acc.domain.contains(":")) {
                                    "${acc.domain}:${acc.port}"
                                } else {
                                    acc.domain
                                }
                                "sip:$num@$host$transportSuffix"
                            }
                            
                            Log.d("SipService", "Dialing URI: $finalUri")
                            
                            Handler(Looper.getMainLooper()).post {
                                SipEngine.makeCall(acc.id, finalUri)
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
    }

    private var lastWasConfirmed = false
    private var callStartTime = 0L

    private var lastSession: com.ipdial.data.model.CallSession? = null
    
    private var ringtone: Ringtone? = null
    private var mediaPlayer: android.media.MediaPlayer? = null

    private fun playRingtone() {
        if (ringtone?.isPlaying == true || mediaPlayer?.isPlaying == true) return
        scope.launch {
            try {
                val ringtoneUriStr = repo.globalRingtone.first()
                val vibrateEnabled = repo.globalVibrate.first()
                
                withContext(Dispatchers.Main) {
                    if (ringtoneUriStr == "android.resource://$packageName/raw/ipdial_ringtone") {
                        mediaPlayer = android.media.MediaPlayer.create(applicationContext, R.raw.ipdial_ringtone)
                        mediaPlayer?.isLooping = true
                        mediaPlayer?.start()
                    } else {
                        val ringtoneUri = ringtoneUriStr?.let { android.net.Uri.parse(it) }
                            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                        
                        ringtone = RingtoneManager.getRingtone(applicationContext, ringtoneUri)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            ringtone?.isLooping = true
                        }
                        ringtone?.play()
                    }
                    
                    if (vibrateEnabled) {
                        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 1000, 1000), 0))
                    }
                }
            } catch (e: Exception) {
                Log.e("SipService", "Failed to play ringtone", e)
            }
        }
    }

    private fun stopRingtone() {
        try {
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
                    }
                    stopRingtone()
                    restoreAudio()
                    releaseWakeLock()
                    cancelIncomingNotification()
                    lastWasConfirmed = false
                    callStartTime = 0
                    lastSession = null
                } else {
                    lastSession = session
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    
                    when (session.state) {
                        CallState.INCOMING -> {
                            playRingtone()
                            acquireWakeLockForIncoming()
                            updateForegroundType(ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
                        }
                        CallState.CONFIRMED -> {
                            stopRingtone()
                            cancelIncomingNotification()
                            routeAudioToDefault()
                            acquireWakeLock()
                            lastWasConfirmed = true
                            if (callStartTime == 0L) callStartTime = System.currentTimeMillis()
                            updateForegroundType(ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
                        }
                        CallState.CALLING, CallState.EARLY, CallState.CONNECTING -> {
                            routeAudioToDefault()
                            updateForegroundType(ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    private fun startServiceForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                // Try phoneCall type first
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    androidx.core.app.ServiceCompat.startForeground(
                        this,
                        NOTIF_ID_SERVICE,
                        buildServiceNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                    )
                } else {
                    startForeground(NOTIF_ID_SERVICE, buildServiceNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
                }
                Log.d("SipService", "Started FGS with type phoneCall")
            } catch (e: Exception) {
                // Fallback to dataSync if phoneCall is not allowed (common on BOOT_COMPLETED for Android 14+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    try {
                        androidx.core.app.ServiceCompat.startForeground(
                            this,
                            NOTIF_ID_SERVICE,
                            buildServiceNotification(),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                        )
                        Log.d("SipService", "Started FGS with type dataSync fallback")
                    } catch (ex: Exception) {
                        Log.e("SipService", "Failed to start FGS with dataSync fallback", ex)
                    }
                } else {
                    Log.e("SipService", "Failed to start FGS with type phoneCall", e)
                    try {
                        startForeground(NOTIF_ID_SERVICE, buildServiceNotification())
                    } catch (lastEx: Exception) {
                        Log.e("SipService", "Absolute FGS failure", lastEx)
                    }
                }
            }
        } else {
            startForeground(NOTIF_ID_SERVICE, buildServiceNotification())
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
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        
        // Proximity sensor to turn off screen when near ear
        wakeLock = pm.newWakeLock(
            PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
            "IPDial:call"
        ).apply { 
            setReferenceCounted(false)
            acquire(60 * 60 * 1000L) 
        }

        // Partial wake lock to keep CPU alive during call even if screen is off
        if (cpuWakeLock == null) {
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

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        cpuWakeLock?.let { if (it.isHeld) it.release() }
        cpuWakeLock = null
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        nm.createNotificationChannel(
            NotificationChannel(NOTIF_CHANNEL_SIP, "SIP Service", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Background SIP registration"
                setShowBadge(false)
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(NOTIF_CHANNEL_CALL, "Incoming Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming VoIP call alerts"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 500)
                setSound(
                    android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE),
                    android.media.AudioAttributes.Builder()
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .build()
                )
            }
        )
    }

    private fun buildServiceNotification(): Notification {
        val intent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_SIP)
            .setContentTitle("IPDial")
            .setContentText("Ready to receive calls")
            .setSmallIcon(R.drawable.ic_notif_call)
            .setContentIntent(intent)
            .setSilent(true)
            .setOngoing(true)
            .build()
    }

    private fun showIncomingCallNotification(callerName: String, callId: Int) {
        showIncomingCallNotificationStatic(this, callerName, callId)
    }

    private fun cancelIncomingNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID_INCOMING)
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        SipEngine.destroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
