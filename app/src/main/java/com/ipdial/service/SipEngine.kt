package com.ipdial.service

import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.util.Log
import com.ipdial.data.model.*
import com.ipdial.data.model.CallSession
import com.ipdial.data.model.CallState
import com.ipdial.data.model.SipAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import org.pjsip.pjsua2.*

object SipEngine {

    private const val TAG = "SipEngine"

    @Volatile
    internal var endpoint: Endpoint? = null
    private var isLibraryLoaded = false
    internal val accountMap = java.util.concurrent.ConcurrentHashMap<String, SipAccountDelegate>()
    internal val accountConfigs = java.util.concurrent.ConcurrentHashMap<String, SipAccount>()
    internal val callMap = java.util.concurrent.ConcurrentHashMap<Int, SipCallDelegate>()
    internal val registeredThreads = java.util.Collections.synchronizedSet(mutableSetOf<Long>())
    internal val localHangupCauses = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    private val transportManager = SipTransportManager()

    internal lateinit var audioManager: AudioManager

    private val initLock = Any()
    private var initCallCount = 0

    internal val _callSession = MutableStateFlow<CallSession?>(null)
    val callSession: StateFlow<CallSession?> = _callSession.asStateFlow()

    internal val _registrationEvents = MutableSharedFlow<Pair<String, RegStatus>>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val registrationEvents: SharedFlow<Pair<String, RegStatus>> = _registrationEvents.asSharedFlow()

    var onIncomingCall: ((CallSession) -> Unit)? = null
    var onCallDisconnected: ((callId: Int) -> Unit)? = null

    internal var recorder: org.pjsip.pjsua2.AudioMediaRecorder? = null
    private var logWriter: LogWriter? = null

    @Volatile private var _dndActive = false
    fun setDndActive(active: Boolean) { _dndActive = active }
    fun isDndActive(): Boolean = _dndActive

    private var currentEcEnabled = true
    private var currentNsEnabled = true
    private var currentAgcEnabled = true

    internal fun logEx(message: String, isError: Boolean = false) {
        if (isError) Log.e(TAG, message) else Log.d(TAG, message)
        com.ipdial.util.SipLogger.log(TAG, message)
    }

    internal fun registerCurrentThreadEx() {
        val ep = endpoint ?: return
        val threadId = @Suppress("DEPRECATION") Thread.currentThread().id
        if (registeredThreads.contains(threadId)) return
        try {
            if (!ep.libIsThreadRegistered()) {
                val threadName = Thread.currentThread().name ?: "SipEngineThread"
                ep.libRegisterThread(threadName)
            }
            registeredThreads.add(threadId)
        } catch (e: Throwable) {
            logEx("Failed to register thread: ${e.message}", true)
        }
    }

    private fun log(message: String, isError: Boolean = false) = logEx(message, isError)

    private fun registerCurrentThread() = registerCurrentThreadEx()

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

                endpoint = ep

                log("#$callId: Thread already registered (pj_init in Endpoint constructor)")

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
                            clockRate = 48000
                            sndClockRate = 48000
                            ecOptions = 1
                            ecTailLen = 200
                            noVad = true
                            quality = 5
                            channelCount = 1
                            audioFramePtime = 20
                        }
                        uaConfig.apply {
                            userAgent = "IPDial/1.0 (Android)"
                            maxCalls = 4
                        }
                    }
                    libInit(epCfg)

                    transportManager.createTransports(this, ::log)

                    libStart()
                    log("#$callId: PJSIP started successfully")

                    try {
                        val adm = ep.audDevManager()
                        val devs = adm.enumDev2()
                        var javaDevIndex = -1

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

                val chosenTpId = when (account.transport) {
                    Transport.TCP -> transportManager.tcpTransportId
                    Transport.TLS -> transportManager.tlsTransportId
                    else -> transportManager.udpTransportId
                }
                if (chosenTpId != -1) {
                    sipConfig.transportId = chosenTpId
                }

                mediaConfig.apply {
                    srtpUse = if (account.transport == Transport.TLS)
                        pjmedia_srtp_use.PJMEDIA_SRTP_OPTIONAL
                    else
                        pjmedia_srtp_use.PJMEDIA_SRTP_DISABLED
                }

                natConfig.iceEnabled = false
                natConfig.turnEnabled = false
                natConfig.sipStunUse = pjsua_stun_use.PJSUA_STUN_USE_DEFAULT
                natConfig.contactRewriteUse = 1
                natConfig.sipOutboundUse = 0
            }

            val pjAcc = SipAccountDelegate(
                accountId = account.id,
                callMap = callMap,
                accountConfigs = accountConfigs,
                _callSession = _callSession,
                _registrationEvents = _registrationEvents,
                onIncomingCall = onIncomingCall,
                log = ::log
            )
            try {
                configureCodecs(account.codec ?: PreferredCodec.G711U, account.ecEnabled, account.nsEnabled, account.agcEnabled)

                pjAcc.create(acfg)
                accountMap[account.id] = pjAcc
                accountConfigs[account.id] = account
                log("Account added successfully: ${account.id} (${account.username})")

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

    fun reconnectOnNetworkChange(network: Network?, context: Context) {
        registerCurrentThread()
        val ep = endpoint ?: return

        log("reconnectOnNetworkChange: network=$network")

        val savedConfigs = accountConfigs.values.toList()
        accountMap.keys.toList().forEach { accId ->
            try {
                accountMap[accId]?.delete()
            } catch (e: Throwable) {
                log("Error deleting account $accId prior to network switch: ${e.message}", true)
            }
        }
        accountMap.clear()

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val boundToNetwork = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cm.bindProcessToNetwork(network)
                log("Bound process to network: $network")
                true
            } else false
        } catch (e: Throwable) {
            log("bindProcessToNetwork failed: ${e.message}", true)
            false
        }

        transportManager.closeTransports(ep, ::log)

        try {
            transportManager.recreateTransports(ep, ::log)
        } finally {
            if (boundToNetwork) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        cm.bindProcessToNetwork(null)
                    }
                } catch (_: Throwable) {}
                log("Released process network binding")
            }
        }

        try {
            val changeParam = IpChangeParam()
            ep.handleIpChange(changeParam)
            log("PJSIP handleIpChange executed")
        } catch (e: Throwable) {
            log("handleIpChange failed: ${e.message}", true)
        }

        savedConfigs.forEach { config ->
            accountConfigs[config.id] = config
            try {
                addAccount(config)
            } catch (e: Throwable) {
                log("Failed to re-add account ${config.id} after network change: ${e.message}", true)
            }
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

    fun hasActiveCall(callId: Int): Boolean = callMap.containsKey(callId)

    fun nullSessionIfStale() {
        val session = _callSession.value ?: return
        if (session.callId == -1) return

        val inMap = callMap.containsKey(session.callId)
        log("nullSessionIfStale: session callId=${session.callId} state=${session.state} inCallMap=$inMap callMapKeys=${callMap.keys}")
        if (!inMap) {
            log("nullSessionIfStale: force-nulling session for callId=${session.callId}")
            _callSession.value = null
            SipConnectionService.disconnectCall(session.callId)
            return
        }
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
            val call = SipCallDelegate(
                acct = pjAcc,
                callMap = callMap,
                _callSession = _callSession,
                audioManager = audioManager,
                endpoint = { endpoint },
                log = ::logEx
            )

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
                val realId: Int
                try {
                    realId = call.getId()
                } catch (e: Throwable) {
                    log("makeCall: getId() failed after makeCall returned: ${e.message}", true)
                    callMap.entries.removeAll { it.value === call }
                    _callSession.value = null
                    return false
                }

                callMap[realId] = call
                log("call.makeCall returned successfully. assigned call ID = $realId")

                _callSession.value?.let { currentSession ->
                    if (currentSession.state != CallState.DISCONNECTED) {
                        _callSession.value = currentSession.copy(callId = realId)
                    }
                }
                true
            } catch (e: Throwable) {
                log("call.makeCall failed with exception: ${e.message}", true)

                val wasAlreadyCleanedUp = !callMap.entries.any { it.value === call }
                if (!wasAlreadyCleanedUp) {
                    callMap.entries.removeAll { it.value === call }
                    try { call.delete() } catch (_: Throwable) {}
                } else {
                    log("makeCall: onCallState already handled cleanup for this call — skipping delete")
                }
                _callSession.value = null
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
                val stateText = try { call.info.stateText } catch (_: Throwable) { "Unknown" }
                val session = _callSession.value
                log("hangupCall execution: callId=$id state=$stateText direction=${session?.direction} callState=${session?.state}")

                val sipStatusCode = CallHangupResolver.resolveSipStatusCode(session)
                log("hangupCall: resolved sipStatusCode=$sipStatusCode for session state=${session?.state}")

                val telecomCause = CallHangupResolver.resolveDisconnectCause(session)
                localHangupCauses[id] = telecomCause
                log("hangupCall: recording local telecomCause=$telecomCause for callId=$id")

                val prm = CallOpParam().apply {
                    val pjCode = sipStatusCode
                    if (pjCode != null) {
                        statusCode = pjCode
                    }
                }
                call.hangup(prm)
                log("hangupCall: call.hangup() sent successfully for callId=$id (sipStatusCode=$sipStatusCode, telecomCause=$telecomCause)")
            } catch (e: Throwable) {
                log("hangupCall failed: ${e.message}", true)
            }
        } else {
            log("Hangup: callId=$id not in callMap — session cleanup only")
            if (_callSession.value?.callId == id) {
                _callSession.value = null
            }
        }
    }

    private fun configureCodecs(preferred: PreferredCodec, ecEnabled: Boolean, nsEnabled: Boolean, agcEnabled: Boolean) {
        val ep = endpoint ?: return
        try {
            val codecs = ep.codecEnum2()
            val targetCodecKeyword = when (preferred) {
                PreferredCodec.G729  -> "g729"
                PreferredCodec.G722  -> "g722"
                PreferredCodec.G711U -> "pcmu"
                PreferredCodec.G711A -> "pcma"
            }

            log("Configuring codecs. Target: $targetCodecKeyword")

            for (i in 0 until codecs.size.toInt()) {
                val codec = codecs.get(i)
                val codecId = codec.codecId
                val name = codecId.lowercase()

                val priority: Short = when {
                    name.contains(targetCodecKeyword) -> 250
                    name.contains("g729") && targetCodecKeyword != "g729" -> 160
                    name == "pcma/8000/1" -> 150
                    name == "pcmu/8000/1" -> 140
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
            for (i in 0 until codecs.size.toInt()) {
                val codec = codecs.get(i)
                val codecId = codec.codecId
                val name = codecId.lowercase()
                val priority = codec.priority
                val isAvailable = priority > 0.toShort()

                val quality = when {
                    name.contains("g722") -> com.ipdial.data.model.CodecQuality.Excellent
                    name.contains("g729") -> com.ipdial.data.model.CodecQuality.Good
                    name.contains("pcma") || name.contains("pcmu") -> com.ipdial.data.model.CodecQuality.Fair
                    name.contains("gsm") -> com.ipdial.data.model.CodecQuality.Low
                    else -> com.ipdial.data.model.CodecQuality.Minimal
                }

                val (bandwidth, mos) = when {
                    name.contains("opus") -> 6 to 4.3f
                    name.contains("g722") -> 48 to 4.0f
                    name.contains("g729") -> 8 to 3.9f
                    name.contains("pcma") -> 64 to 4.1f
                    name.contains("pcmu") -> 64 to 4.1f
                    name.contains("gsm") -> 13 to 3.5f
                    else -> 0 to 0f
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
                        frameLength = frameLength.toInt(),
                        bandwidthKbps = bandwidth,
                        estimatedMOS = mos
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

            if (::audioManager.isInitialized) {
                audioManager.mode = AudioManager.MODE_NORMAL
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
            }
        } catch (e: Throwable) {
            log("destroy failed: ${e.message}", true)
        }
    }

    private fun formatSipUri(destination: String, accountId: String? = null): String {
        if (destination.startsWith("sip:") && destination.contains("@")) return destination

        val cleanDestination = destination.removePrefix("sip:").substringBefore("@")
        val number = cleanDestination

        val targetAccountId = accountId ?: _callSession.value?.accountId
        val domain = if (targetAccountId != null) accountConfigs[targetAccountId]?.domain else null

        return if (!domain.isNullOrBlank()) {
            "sip:$number@$domain"
        } else {
            "sip:$number"
        }
    }
}
