package com.ipdial.service

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.ipdial.data.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import org.pjsip.pjsua2.*

/**
 * PJSIP engine singleton.
 * Manages the Endpoint lifecycle, account registration, and call sessions.
 */
object SipEngine {

    private const val TAG = "SipEngine"

    @Volatile
    private var endpoint: Endpoint? = null
    private var isLibraryLoaded = false
    private val accountMap = mutableMapOf<String, PjAccount>()   // accountId -> PjAccount
    private val accountConfigs = mutableMapOf<String, SipAccount>() // accountId -> SipAccount configuration
    private val callMap = mutableMapOf<Int, PjCall>()             // callId -> PjCall
    private val registeredThreads = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

    private var udpTransportId: Int = -1
    private var tcpTransportId: Int = -1
    private var tlsTransportId: Int = -1

    private lateinit var audioManager: AudioManager

    private val initLock = Any()
    private var initCallCount = 0

    private val _callSession = MutableStateFlow<CallSession?>(null)
    val callSession: StateFlow<CallSession?> = _callSession.asStateFlow()

    private val _registrationEvents = MutableSharedFlow<Pair<String, RegStatus>>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val registrationEvents: SharedFlow<Pair<String, RegStatus>> = _registrationEvents.asSharedFlow()

    var onIncomingCall: ((CallSession) -> Unit)? = null

    /**
     * Invoked synchronously on the PJSIP thread the moment a call transitions to
     * PJSIP_INV_STATE_DISCONNECTED (or NULL).  SipService registers this lambda to
     * immediately stop the ringtone/vibration and dismiss the incoming-call UI without
     * having to wait for the [callSession] StateFlow to propagate on the collector's
     * coroutine dispatcher.
     *
     * @param callId the PJSIP call ID of the call that just ended
     */
    var onCallDisconnected: ((callId: Int) -> Unit)? = null

    private var recorder: AudioMediaRecorder? = null
    private var logWriter: LogWriter? = null

    // Volume Boost Factor (150%) — reduced to avoid clipping/distortion
    private const val VOLUME_BOOST_FACTOR = 1.5f

    // Per-account audio settings cached for call-time application
    private var currentEcEnabled = true
    private var currentNsEnabled = true
    private var currentAgcEnabled = true

    private fun log(message: String, isError: Boolean = false) {
        if (isError) {
            Log.e(TAG, message)
        } else {
            Log.d(TAG, message)
        }
        com.ipdial.util.SipLogger.log(TAG, message)
    }

    private fun registerCurrentThread() {
        val ep = endpoint ?: return
        val threadId = @Suppress("DEPRECATION") Thread.currentThread().id
        if (registeredThreads.contains(threadId)) {
            return
        }
        try {
            if (!ep.libIsThreadRegistered()) {
                val threadName = Thread.currentThread().name ?: "SipEngineThread"
                ep.libRegisterThread(threadName)
            }
            registeredThreads.add(threadId)
        } catch (e: Throwable) {
            log("Failed to register thread: ${e.message}", true)
        }
    }

    fun init(context: Context) {
        initCallCount++
        val callId = initCallCount
        @Suppress("DEPRECATION")
        val threadInfo = "${Thread.currentThread().name}[${Thread.currentThread().id}]"
        log("init() called (#$callId from $threadInfo)")

        if (endpoint != null) {
            log("init() skipped (#$callId) — endpoint already set")
            return
        }

        synchronized(initLock) {
            if (endpoint != null) {
                log("init() skipped in synchronized block (#$callId) — endpoint already set")
                return
            }

            try {
                if (!isLibraryLoaded) {
                    try {
                        System.loadLibrary("pjsua2")
                        isLibraryLoaded = true
                        log("#$callId: Native library pjsua2 loaded")
                    } catch (e: Throwable) {
                        log("#$callId: Failed to load pjsua2: ${e.message}", true)
                    }
                }

                audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

                log("#$callId: Creating PJSIP Endpoint...")

                val ep = try {
                    Endpoint()
                } catch (e: Throwable) {
                    log("#$callId: CRITICAL: Failed to create Endpoint instance: ${e.message}", true)
                    return
                }

                try {
                    ep.libCreate()
                    log("#$callId: PJSIP libCreate() successful")
                } catch (e: Throwable) {
                    log("#$callId: CRITICAL: PJSIP libCreate() failed: ${e.message}", true)
                    return
                }

                // Assign to global ref immediately to prevent GC/R8 from collecting the Endpoint
                endpoint = ep

                log("#$callId: Thread already registered (pj_init in Endpoint constructor)")

                // Define LogWriter locally to avoid class loading issues before libCreate
                val writer = object : LogWriter() {
                    override fun write(entry: LogEntry) {
                        val msg = entry.msg
                        if (!msg.isNullOrBlank()) {
                            val trimmed = msg.trim()
                            com.ipdial.util.SipLogger.log("PJSIP", trimmed)
                            Log.d("PJSIP", trimmed)
                        }
                    }
                }
                this.logWriter = writer

                ep.apply {
                    val epCfg = EpConfig().apply {
                        logConfig.level = 6
                        logConfig.consoleLevel = 6
                        logConfig.writer = writer

                        medConfig.apply {
                            clockRate = 48000        // Native high-quality rate
                            sndClockRate = 48000     // Android hardware-native rate (prevents resampling bugs)
                            
                            ecOptions = 1            // Use driver's default EC (Hardware AEC on Android)
                            ecTailLen = 200          // Standard tail
                            noVad = true             // Don't cut off quiet voices
                            quality = 5              // Good balance of quality/performance
                            channelCount = 1
                            audioFramePtime = 20
                        }
                        uaConfig.apply {
                            userAgent = "IPDial/1.0 (Android)"
                            maxCalls = 4
                            // Use Google STUN server
                            stunServer.add("stun.l.google.com:19302")
                        }
                    }
                    libInit(epCfg)

                    val sipTpCfg = TransportConfig()
                    sipTpCfg.port = 0
                    try {
                        udpTransportId = transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, sipTpCfg)
                    } catch (e: Exception) { log("#$callId: Failed to create UDP transport: ${e.message}", true) }

                    val tcpTpCfg = TransportConfig()
                    tcpTpCfg.port = 0
                    try {
                        tcpTransportId = transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TCP, tcpTpCfg)
                    } catch (e: Exception) { log("#$callId: Failed to create TCP transport: ${e.message}", true) }

                    val tlsTpCfg = TransportConfig()
                    tlsTpCfg.tlsConfig.verifyServer = true
                    tlsTpCfg.tlsConfig.verifyClient = false
                    try {
                        tlsTransportId = transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TLS, tlsTpCfg)
                    } catch (e: Exception) { log("#$callId: Failed to create TLS transport: ${e.message}", true) }

                    libStart()
                    log("#$callId: PJSIP started successfully")

                    // Explicitly switch to Java Audio (AudioRecord/AudioTrack)
                    // This is more reliable on Xiaomi/Realme devices than Native OpenSL
                    try {
                        val adm = ep.audDevManager()
                        val devs = adm.enumDev2()
                        var javaDevIndex = -1
                        
                        // Search for the "Android" driver device
                        for (i in 0 until devs.size.toInt()) {
                            val info = devs.get(i)
                            log("Audio Device [$i]: ${info.name} (Driver: ${info.driver})")
                            if (info.driver.contains("Android", ignoreCase = true)) {
                                javaDevIndex = i
                                break
                            }
                        }
                        
                        if (javaDevIndex != -1) {
                            log("Selecting Java Audio (Android) at index $javaDevIndex")
                            adm.setCaptureDev(javaDevIndex)
                            adm.setPlaybackDev(javaDevIndex)
                        } else {
                            log("WARNING: Java Audio driver not found. Using PJSIP defaults.")
                        }
                    } catch (e: Exception) {
                        log("Failed to configure audio devices: ${e.message}", true)
                    }

                    endpoint = ep
                }
            } catch (e: Throwable) {
                log("#$callId: PJSIP init failed: ${e.message}", true)
            }
        }
    }

    fun addAccount(account: SipAccount) {
        registerCurrentThread()
        try {
            val existingConfig = accountConfigs[account.id]
            if (existingConfig != null) {
                val hasChanged = existingConfig.username != account.username ||
                        existingConfig.password != account.password ||
                        existingConfig.domain != account.domain ||
                        existingConfig.proxy != account.proxy ||
                        existingConfig.port != account.port ||
                        existingConfig.transport != account.transport ||
                        existingConfig.codec != account.codec ||
                        existingConfig.ecEnabled != account.ecEnabled ||
                        existingConfig.nsEnabled != account.nsEnabled ||
                        existingConfig.agcEnabled != account.agcEnabled

                if (!hasChanged) {
                    log("Account ${account.id} configuration unchanged, triggering re-registration")
                    reconnectAccount(account.id)
                    return
                }
            }

            accountMap[account.id]?.let { removeAccount(account.id) }

            val acfg = AccountConfig().apply {
                idUri = "sip:${account.username}@${account.domain}"

                regConfig.registrarUri = if (account.port != null && account.port > 0) {
                    "sip:${account.domain}:${account.port}"
                } else {
                    "sip:${account.domain}"
                }

                regConfig.timeoutSec = 120
                regConfig.retryIntervalSec = 30

                val cred = AuthCredInfo("digest", "*", account.username, 0, account.password)
                sipConfig.authCreds.add(cred)

                if (account.proxy.isNotBlank()) {
                    sipConfig.proxies.add("sip:${account.proxy}")
                }

                sipConfig.transportId = when (account.transport) {
                    Transport.TCP -> tcpTransportId
                    Transport.TLS -> tlsTransportId
                    else -> udpTransportId
                }

                mediaConfig.apply {
                    srtpUse = if (account.transport == Transport.TLS)
                        pjmedia_srtp_use.PJMEDIA_SRTP_OPTIONAL
                    else
                        pjmedia_srtp_use.PJMEDIA_SRTP_DISABLED
                }

                natConfig.iceEnabled = true
                natConfig.turnEnabled = false
                natConfig.sipStunUse = pjsua_stun_use.PJSUA_STUN_USE_DEFAULT
                // Enable contact rewriting for NAT traversal
                natConfig.contactRewriteUse = 1
                natConfig.sipOutboundUse = 0
            }

            val pjAcc = PjAccount(account.id)
            try {
                // Configure codecs BEFORE creating account to ensure initial REGISTER/INVITE are small
                configureCodecs(account.codec, account.ecEnabled, account.nsEnabled, account.agcEnabled)
                
                pjAcc.create(acfg)
                accountMap[account.id] = pjAcc
                accountConfigs[account.id] = account
                log("Account added successfully: ${account.id} (${account.username})")

                // Cache this account's audio processing preferences for call-time use
                currentEcEnabled = account.ecEnabled
                currentNsEnabled = account.nsEnabled
                currentAgcEnabled = account.agcEnabled
                log("Audio settings cached: EC=$currentEcEnabled, NS=$currentNsEnabled, AGC=$currentAgcEnabled")
            } catch (e: Throwable) {
                pjAcc.delete()
                throw e
            }
        } catch (e: Throwable) {
            log("addAccount failed: ${e.message}", true)
        }
    }

    fun removeAccount(accountId: String) {
        registerCurrentThread()
        try {
            accountMap[accountId]?.delete()
            accountMap.remove(accountId)
            accountConfigs.remove(accountId)
            log("Account removed: $accountId")
        } catch (e: Throwable) {
            log("removeAccount failed: ${e.message}", true)
        }
    }

    fun reconnectAccount(accountId: String) {
        registerCurrentThread()
        try {
            accountMap[accountId]?.setRegistration(true)
        } catch (e: Throwable) {
            log("reconnectAccount failed: ${e.message}", true)
        }
    }

    fun forceReconnectAll() {
        registerCurrentThread()
        try {
            val configs = accountConfigs.values.toList()
            configs.forEach { config ->
                log("Force reconnecting account: ${config.id}")
                try {
                    accountMap[config.id]?.delete()
                } catch (e: Throwable) {
                    log("Error deleting account during force reconnect: ${e.message}", true)
                }
                accountMap.remove(config.id)
                accountConfigs.remove(config.id)
            }
            configs.forEach { config ->
                addAccount(config)
            }
        } catch (e: Throwable) {
            log("forceReconnectAll failed: ${e.message}", true)
        }
    }

    fun handleIpChange() {
        registerCurrentThread()
        val ep = endpoint ?: return
        try {
            log("Calling handleIpChange...")
            val changeParam = IpChangeParam()
            ep.handleIpChange(changeParam)
            log("handleIpChange completed successfully")
        } catch (e: Throwable) {
            log("handleIpChange failed: ${e.message}", true)
        }
    }

    fun updateCallSessionName(name: String) {
        _callSession.value = _callSession.value?.copy(remoteDisplayName = name)
    }

    fun makeCall(accountId: String, destination: String): Boolean {
        registerCurrentThread()
        return try {
            val pjAcc = accountMap[accountId] ?: run {
                log("makeCall failed: accountId $accountId not found in accountMap.", true)
                return false
            }
            val destUri = formatSipUri(destination, accountId)
            log("makeCall: destination=$destination -> destUri=$destUri")
            log("making call to $destUri")
            val call = PjCall(pjAcc)

            _callSession.value = CallSession(
                callId = -1,
                accountId = accountId,
                remoteUri = destUri,
                direction = CallDirection.OUTGOING,
                state = CallState.CALLING
            )

            val prm = CallOpParam(true).apply {
                opt.audioCount = 1
                opt.videoCount = 0
            }

            try {
                call.makeCall(destUri, prm)
                val realId = call.getId()
                callMap[realId] = call
                log("call.makeCall returned successfully. assigned call ID = $realId")

                _callSession.value?.let { currentSession ->
                    if (currentSession.state != CallState.DISCONNECTED) {
                        _callSession.value = currentSession.copy(callId = realId)
                    }
                }
                true
            } catch (e: Throwable) {
                call.delete()
                _callSession.value = null
                log("call.makeCall failed: ${e.message}", true)
                false
            }
        } catch (e: Throwable) {
            log("makeCall failed: ${e.message}", true)
            false
        }
    }

    fun answerCall(callId: Int) {
        registerCurrentThread()
        callMap[callId]?.let { call ->
            try {
                val prm = CallOpParam(true).apply { statusCode = pjsip_status_code.PJSIP_SC_OK }
                call.answer(prm)
            } catch (e: Throwable) {
                log("answerCall failed: ${e.message}", true)
            }
        }
    }

    fun hangupCall(callId: Int = -1) {
        registerCurrentThread()
        val id = if (callId >= 0) callId else _callSession.value?.callId ?: return
        log("Hangup requested for callId=$id")
        val call = callMap[id]
        if (call != null) {
            try {
                val prm = CallOpParam().apply { statusCode = pjsip_status_code.PJSIP_SC_DECLINE }
                call.hangup(prm)
            } catch (e: Throwable) {
                log("hangupCall failed: ${e.message}", true)
            }
        } else {
            log("Hangup failed: callId=$id not found in map")
            _callSession.value = null
        }
    }

    fun setMute(muted: Boolean) {
        registerCurrentThread()
        _callSession.value?.let { session ->
            callMap[session.callId]?.let { call ->
                try {
                    val ci = call.info
                    for (i in 0 until ci.media.size) {
                        val mi = ci.media.get(i)
                        if (mi.type == pjmedia_type.PJMEDIA_TYPE_AUDIO &&
                            mi.status == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE) {
                            val aud = AudioMedia.typecastFromMedia(call.getMedia(mi.index.toLong()))
                            if (muted) aud.adjustTxLevel(0f) else aud.adjustTxLevel(VOLUME_BOOST_FACTOR)
                        }
                    }
                    _callSession.value = session.copy(isMuted = muted)
                } catch (e: Throwable) {
                    log("setMute failed: ${e.message}", true)
                }
            }
        }
    }

    fun setSpeaker(enabled: Boolean) {
        log("setSpeaker: $enabled")
        _callSession.value = _callSession.value?.copy(isSpeaker = enabled)
    }

    fun setCallVolume(factor: Float) {
        registerCurrentThread()
        log("Adjusting call volume (Rx level) to factor: $factor")
        _callSession.value?.let { session ->
            _callSession.value = session.copy(rxVolume = factor)
            callMap[session.callId]?.let { call ->
                try {
                    val ci = call.info
                    for (i in 0 until ci.media.size) {
                        val mi = ci.media.get(i)
                        if (mi.type == pjmedia_type.PJMEDIA_TYPE_AUDIO &&
                            mi.status == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE) {
                            val aud = AudioMedia.typecastFromMedia(call.getMedia(mi.index.toLong()))
                            aud.adjustRxLevel(factor)
                        }
                    }
                } catch (e: Throwable) {
                    log("setCallVolume failed: ${e.message}", true)
                }
            }
        }
    }

    fun startRecording(filePath: String) {
        registerCurrentThread()
        try {
            recorder?.delete()
            recorder = AudioMediaRecorder()
            recorder?.createRecorder(filePath)

            _callSession.value?.let { session ->
                callMap[session.callId]?.let { call ->
                    val ci = call.info
                    for (i in 0 until ci.media.size) {
                        val mi = ci.media.get(i)
                        if (mi.type == pjmedia_type.PJMEDIA_TYPE_AUDIO &&
                            mi.status == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE) {
                            val aud = AudioMedia.typecastFromMedia(call.getMedia(mi.index.toLong()))
                            aud.startTransmit(recorder)
                            endpoint?.audDevManager()?.captureDevMedia?.startTransmit(recorder)
                        }
                    }
                }
                _callSession.value = session.copy(isRecording = true)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "startRecording failed: ${e.message}")
        }
    }

    fun stopRecording() {
        registerCurrentThread()
        try {
            recorder?.let {
                it.delete()
            }
            recorder = null
            _callSession.value = _callSession.value?.copy(isRecording = false)
        } catch (e: Throwable) {
            log("stopRecording failed: ${e.message}", true)
        }
    }

    fun sendDtmf(digit: Char) {
        registerCurrentThread()
        _callSession.value?.let { session ->
            callMap[session.callId]?.let { call ->
                try { call.dialDtmf(digit.toString()) } catch (e: Throwable) {
                    log("sendDtmf failed: ${e.message}", true)
                }
            }
        }
    }

    fun holdCall(onHold: Boolean) {
        registerCurrentThread()
        _callSession.value?.let { session ->
            callMap[session.callId]?.let { call ->
                try {
                    val prm = CallOpParam()
                    if (onHold) call.setHold(prm) else call.reinvite(prm)
                    _callSession.value = session.copy(isOnHold = onHold)
                } catch (e: Throwable) {
                    log("holdCall failed: ${e.message}", true)
                }
            }
        }
    }

    private fun configureCodecs(preferred: PreferredCodec, ecEnabled: Boolean, nsEnabled: Boolean, agcEnabled: Boolean) {
        val ep = endpoint ?: return
        try {
            val codecs = ep.codecEnum2()
            val targetCodecKeyword = when (preferred) {
                PreferredCodec.G729  -> "g729"
                PreferredCodec.OPUS  -> "opus"
                PreferredCodec.G722  -> "g722"
                PreferredCodec.G711U -> "pcmu"
                PreferredCodec.G711A -> "pcma"
            }

            log("Configuring codecs. Target: $targetCodecKeyword")

            for (i in 0 until codecs.size) {
                val codec = codecs.get(i)
                val codecId = codec.codecId
                val name = codecId.lowercase()

                // Only enable the EXACT target codec. 
                // We don't even add fallbacks to keep packet as small as possible.
                // SIP servers usually support PCMA/PCMU if everything else fails.
                val priority: Short = when {
                    name.contains(targetCodecKeyword) -> 250
                    name == "pcma/8000/1" -> 150
                    name == "pcmu/8000/1" -> 140
                    name.contains("g729") -> 100
                    else -> 0
                }
                
                ep.codecSetPriority(codecId, priority)
                if (priority > 0) log("Codec ENABLED: $codecId (priority $priority)")
            }
        } catch (e: Throwable) {
            log("Error configuring codecs: ${e.message}", true)
        }
    }

    fun getAvailableCodecs(): List<com.ipdial.data.model.CodecInfo> {
        val ep = endpoint ?: return emptyList()
        return try {
            val codecs = ep.codecEnum2()
            val result = mutableListOf<com.ipdial.data.model.CodecInfo>()
            for (i in 0 until codecs.size) {
                val codec = codecs.get(i)
                val codecId = codec.codecId
                val name = codecId.lowercase()
                val priority = codec.priority
                val isAvailable = priority > 0.toShort()

                val quality = when {
                    name.contains("opus") -> com.ipdial.data.model.CodecQuality.Excellent
                    name.contains("g722") -> com.ipdial.data.model.CodecQuality.Excellent
                    name.contains("g729") -> com.ipdial.data.model.CodecQuality.Good
                    name.contains("pcma") || name.contains("pcmu") -> com.ipdial.data.model.CodecQuality.Fair
                    name.contains("gsm") -> com.ipdial.data.model.CodecQuality.Low
                    else -> com.ipdial.data.model.CodecQuality.Minimal
                }

                var clockRate = 0L
                var channelCount = 0L
                var frameLength = 0L
                try {
                    val param = ep.codecGetParam(codecId)
                    val info = param.info
                    clockRate = info.clockRate
                    channelCount = info.channelCnt
                    frameLength = info.frameLen
                } catch (_: Exception) {}

                result.add(
                    com.ipdial.data.model.CodecInfo(
                        id = codecId,
                        name = codecId,
                        priority = priority,
                        isAvailable = isAvailable,
                        quality = quality,
                        clockRate = clockRate.toInt(),
                        channelCount = channelCount.toInt(),
                        frameLength = frameLength.toInt()
                    )
                )
            }
            result
        } catch (e: Exception) {
            log("Error enumerating codecs: ${e.message}", true)
            emptyList()
        }
    }

    fun setCodecPriority(codecId: String, priority: Short) {
        val ep = endpoint ?: return
        try {
            ep.codecSetPriority(codecId, priority)
            log("Codec priority set: $codecId -> $priority")
        } catch (e: Exception) {
            log("Error setting codec priority: ${e.message}", true)
        }
    }

    fun destroy() {
        try {
            registerCurrentThread()
            callMap.values.forEach { it.delete() }
            callMap.clear()
            accountMap.values.forEach { it.delete() }
            accountMap.clear()

            recorder?.delete()
            recorder = null

            endpoint?.libDestroy()
            endpoint?.delete()
            endpoint = null

            logWriter?.delete()
            logWriter = null

            registeredThreads.clear()

            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
        } catch (e: Throwable) {
            log("destroy failed: ${e.message}", true)
        }
    }

    class PjAccount(private val accountId: String) : Account() {
        override fun onRegState(prm: OnRegStateParam) {
            try {
                val ai = try { info } catch (e: Throwable) {
                    log("CRITICAL: Account $accountId info retrieval failed: ${e.message}", true)
                    return
                }

                if (ai == null) {
                    log("CRITICAL: Account $accountId info is null", true)
                    return
                }

                val status = when {
                    ai.regIsActive -> RegStatus.REGISTERED
                    ai.regStatus / 100 == 2 -> RegStatus.REGISTERED
                    ai.regStatus >= 300 -> RegStatus.ERROR
                    else -> RegStatus.UNREGISTERED
                }
                log("REG_UPDATE: Account $accountId status=$status (code=${ai.regStatus}, reason=${ai.regStatusText}, active=${ai.regIsActive})")
                _registrationEvents.tryEmit(Pair(accountId, status))
            } catch (e: Throwable) {
                log("onRegState failed for account $accountId: ${e.message}", true)
            }
        }

        override fun onIncomingCall(prm: OnIncomingCallParam) {
            log("onIncomingCall callback from PJSIP: callId=${prm.callId}")
            try {
                val call = PjCall(this, prm.callId)
                callMap[prm.callId] = call

                val opPrm = CallOpParam().apply { statusCode = pjsip_status_code.PJSIP_SC_RINGING }
                try {
                    log("Answering incoming call $${prm.callId} with RINGING")
                    call.answer(opPrm)
                } catch (e: Throwable) {
                    log("Failed to answer incoming call $${prm.callId} with RINGING: ${e.message}", true)
                    call.delete()
                    callMap.remove(prm.callId)
                    throw e
                }

                try {
                    val ci = call.info ?: run {
                        log("Call info is null for incoming call $${prm.callId}", true)
                        call.delete()
                        callMap.remove(prm.callId)
                        return
                    }

                    log("Incoming call from ${ci.remoteUri}, state=${ci.stateText}")

                    val session = CallSession(
                        callId = prm.callId,
                        accountId = accountId,
                        remoteUri = ci.remoteUri ?: "",
                        remoteDisplayName = ci.remoteContact ?: ci.remoteUri ?: "",
                        direction = CallDirection.INCOMING,
                        state = CallState.INCOMING
                    )
                    _callSession.value = session
                    
                    if (onIncomingCall == null) {
                        log("WARNING: onIncomingCall lambda is NULL in SipEngine", true)
                    }
                    onIncomingCall?.invoke(session)
                } catch (e: Throwable) {
                    log("Failed to process incoming call info: ${e.message}", true)
                    call.delete()
                    callMap.remove(prm.callId)
                    if (_callSession.value?.callId == prm.callId) {
                        _callSession.value = null
                    }
                }
            } catch (e: Throwable) {
                log("onIncomingCall failed: ${e.message}", true)
            }
        }
    }

    class PjCall(acct: Account, callId: Int = -1) : Call(acct, callId) {
        override fun onCallState(prm: OnCallStateParam) {
            try {
                val currentCallId = try { getId() } catch (e: Throwable) {
                    log("Failed to get call ID in onCallState: ${e.message}", true)
                    return
                }

                val ci = try { info } catch (e: Throwable) {
                    log("Failed to get call info for call $currentCallId: ${e.message}", true)
                    if (callMap.containsKey(currentCallId)) {
                        callMap.remove(currentCallId)
                        if (_callSession.value?.callId == currentCallId) {
                            _callSession.value = null
                        }
                    }
                    return
                }

                if (ci == null) {
                    log("Call info is null for call $currentCallId", true)
                    return
                }

                log("Call $currentCallId state changed to ${ci.stateText} (code=${ci.lastStatusCode}, reason=${ci.lastReason})")
                val newState = when (ci.state) {
                    pjsip_inv_state.PJSIP_INV_STATE_CALLING -> CallState.CALLING
                    pjsip_inv_state.PJSIP_INV_STATE_INCOMING -> CallState.INCOMING
                    pjsip_inv_state.PJSIP_INV_STATE_EARLY -> CallState.EARLY
                    pjsip_inv_state.PJSIP_INV_STATE_CONNECTING -> CallState.CONNECTING
                    pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED -> CallState.CONFIRMED
                    pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED,
                    pjsip_inv_state.PJSIP_INV_STATE_NULL -> CallState.DISCONNECTED
                    else -> CallState.IDLE
                }

                if (newState == CallState.DISCONNECTED || newState == CallState.IDLE) {
                    log("Call $currentCallId ending (state=$newState, code=${ci.lastStatusCode}, reason=${ci.lastReason})")

                    // Notify SipService immediately so it can stop the ringtone, cancel
                    // vibration and dismiss the incoming-call UI before the StateFlow
                    // propagates. This handles the case where the remote side hangs up
                    // before the call is answered.
                    try {
                        onCallDisconnected?.invoke(currentCallId)
                    } catch (e: Throwable) {
                        log("onCallDisconnected callback failed: ${e.message}", true)
                    }

                    try {
                        audioManager.mode = AudioManager.MODE_NORMAL
                        audioManager.isSpeakerphoneOn = false
                    } catch (e: Throwable) {
                        log("Failed to reset audio manager: ${e.message}", true)
                    }

                    callMap.remove(currentCallId)
                    if (_callSession.value?.callId == currentCallId || _callSession.value == null) {
                        _callSession.value = null
                    }

                    try {
                        recorder?.delete()
                        recorder = null
                    } catch (e: Throwable) {
                        log("Failed to delete recorder on disconnect: ${e.message}", true)
                    }

                    SipConnectionService.getConnection(currentCallId)?.let { conn ->
                        try {
                            conn.setDisconnected(android.telecom.DisconnectCause(android.telecom.DisconnectCause.REMOTE))
                            conn.destroy()
                            SipConnectionService.removeConnection(currentCallId)
                        } catch (e: Throwable) {
                            log("Failed to disconnect telecom connection: ${e.message}", true)
                        }
                    }

                    val callToDelete = this
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            callToDelete.delete()
                        } catch (e: Throwable) {
                            Log.e("SipEngine", "Failed to delete call on main loop", e)
                        }
                    }
                } else {
                    _callSession.value = _callSession.value?.copy(state = newState, callId = currentCallId)

                    SipConnectionService.getConnection(currentCallId)?.let { conn ->
                        try {
                            when (newState) {
                                CallState.CONFIRMED -> conn.setActive()
                                CallState.EARLY -> if (_callSession.value?.direction == CallDirection.OUTGOING) {
                                    conn.setRinging()
                                }
                                CallState.CONNECTING -> conn.setDialing()
                                else -> {}
                            }
                        } catch (e: Throwable) {
                            log("Failed to update telecom connection state: ${e.message}", true)
                        }
                    }
                }
            } catch (e: Throwable) {
                log("PjCall.onCallState failed: ${e.message}", true)
            }
        }

        override fun onCallMediaState(prm: OnCallMediaStateParam) {
            try {
                val ci = try { info } catch (e: Throwable) {
                    log("Failed to get call info in onCallMediaState: ${e.message}", true)
                    return
                }

                if (ci == null) {
                    log("Call info is null in onCallMediaState", true)
                    return
                }

                // Note: Speaker routing is handled by SipService via observing callSession.isSpeaker
                if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                }

                for (i in 0 until ci.media.size) {
                    try {
                        val mi = ci.media.get(i)
                        if (mi.type == pjmedia_type.PJMEDIA_TYPE_AUDIO &&
                            mi.status == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE) {
                            val aud = AudioMedia.typecastFromMedia(getMedia(mi.index.toLong()))

                            // Apply current mute/volume state
                            val currentSession = _callSession.value
                            val micLevel = if (currentSession?.isMuted == true) 0f else VOLUME_BOOST_FACTOR
                            val speakerLevel = currentSession?.rxVolume ?: VOLUME_BOOST_FACTOR
                            
                            // Tx = microphone (what remote hears), Rx = speaker (what local user hears)
                            aud.adjustTxLevel(micLevel)
                            aud.adjustRxLevel(speakerLevel)

                            aud.startTransmit(endpoint?.audDevManager()?.playbackDevMedia)
                            endpoint?.audDevManager()?.captureDevMedia?.startTransmit(aud)

                            recorder?.let {
                                aud.startTransmit(it)
                                endpoint?.audDevManager()?.captureDevMedia?.startTransmit(it)
                            }
                        }
                    } catch (e: Throwable) {
                        log("Failed to process media state for stream $i: ${e.message}", true)
                    }
                }
            } catch (e: Throwable) {
                log("onCallMediaState failed: ${e.message}", true)
            }
        }
    }

    private fun formatSipUri(destination: String, accountId: String? = null): String {
        // If it's already a full SIP URI with a domain, just return it
        if (destination.startsWith("sip:") && destination.contains("@")) return destination

        val cleanDestination = destination.removePrefix("sip:").substringBefore("@")
        val number = cleanDestination
        
        // Try to append the domain from the provided account or the active session
        val targetAccountId = accountId ?: _callSession.value?.accountId
        val domain = if (targetAccountId != null) accountConfigs[targetAccountId]?.domain else null
        
        return if (!domain.isNullOrBlank()) {
            "sip:$number@$domain"
        } else {
            "sip:$number"
        }
    }
}
