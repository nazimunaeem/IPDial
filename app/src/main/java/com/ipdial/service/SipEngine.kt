package com.ipdial.service

import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.util.Log
import com.ipdial.data.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
    private val callMap = java.util.concurrent.ConcurrentHashMap<Int, PjCall>() // callId -> PjCall (thread-safe)
    private val registeredThreads = java.util.Collections.synchronizedSet(mutableSetOf<Long>())
    /**
     * Tracks the [android.telecom.DisconnectCause] for calls we are hanging up *locally*.
     * Written by [hangupCall] before the PJSIP hangup, consumed (and removed) by
     * [PjCall.onCallState] when the DISCONNECTED callback fires.
     * If a callId has no entry here the disconnect is treated as REMOTE.
     */
    private val localHangupCauses = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    private var udpTransportId: Int = -1
    private var tcpTransportId: Int = -1
    private var tlsTransportId: Int = -1

    private lateinit var audioManager: AudioManager

    private val initLock = Any()
    private var initCallCount = 0

    private val _callSession = MutableStateFlow<CallSession?>(null)
    val callSession: StateFlow<CallSession?> = _callSession.asStateFlow()

    // A2: the audio route as CONFIRMED by Telecom via onCallAudioStateChanged.
    // This only fires once the framework has actually switched the route
    // (i.e. the Bluetooth SCO link is established), so it is the reliable signal
    // for "Bluetooth audio is live" instead of assuming it from device presence.
    private val _confirmedAudioRoute = MutableStateFlow<AudioDeviceMode?>(null)
    val confirmedAudioRoute: StateFlow<AudioDeviceMode?> = _confirmedAudioRoute.asStateFlow()

    fun setConfirmedAudioRoute(route: AudioDeviceMode) {
        _confirmedAudioRoute.value = route
    }

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
    private const val VOLUME_BOOST_FACTOR = 1.0f

    // DND state — set by SipService when DND is active, checked by playRingtone
    @Volatile private var _dndActive = false
    fun setDndActive(active: Boolean) { _dndActive = active }
    fun isDndActive(): Boolean = _dndActive

    // Per-account audio settings cached for call-time application
    private var currentEcEnabled = true
    private var currentNsEnabled = true
    private var currentAgcEnabled = true

    // D5: reliable side-channel for the final SIP disconnect code/reason.
    // StateFlow conflates intermediate values, so the DISCONNECTED-stamped
    // session can be skipped for slow collectors. This field is set on the
    // PJSIP thread right before the session is nulled, and consumed by
    // SipService.observeCallState() when it sees session == null.
    @Volatile private var pendingDisconnectInfo: Pair<Int?, String?>? = null

    fun consumeDisconnectInfo(): Pair<Int?, String?>? {
        val v = pendingDisconnectInfo
        pendingDisconnectInfo = null
        return v
    }

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

                            // Q6: Tune the jitter buffer for bursty mobile-network RTP.
                            jbInit = 100
                            jbMinPre = 40
                            jbMax = 250
                        }
                        uaConfig.apply {
                            userAgent = "IPDial/1.0 (Android)"
                            maxCalls = 4

                            // Q5: STUN for NAT traversal so the far end can reach our RTP.
                            // ICE stays disabled; STUN alone is safe behind most routers.
                            try {
                                stunServer.add("stun.l.google.com:19302")
                            } catch (_: Throwable) {
                                log("Failed to add STUN server", isError = true)
                            }
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

                val chosenTpId = when (account.transport) {
                    Transport.TCP -> tcpTransportId
                    Transport.TLS -> tlsTransportId
                    else -> udpTransportId
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
                // Enable contact rewriting for NAT traversal
                natConfig.contactRewriteUse = 1
                natConfig.sipOutboundUse = 0
            }

            val pjAcc = PjAccount(account.id)
            try {
                // Configure codecs BEFORE creating account to ensure initial REGISTER/INVITE are small
                configureCodecs(account.codec ?: PreferredCodec.G711U, account.ecEnabled, account.nsEnabled, account.agcEnabled)

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

    fun reconnectOnNetworkChange(network: Network?, context: Context) {
        registerCurrentThread()
        val ep = endpoint ?: return

        log("reconnectOnNetworkChange: network=$network")

        // First delete active account instances safely to avoid PJSIP transportClose assertion crashes
        val savedConfigs = accountConfigs.values.toList()
        accountMap.keys.toList().forEach { accId ->
            try {
                accountMap[accId]?.delete()
            } catch (e: Throwable) {
                log("Error deleting account $accId prior to network switch: ${e.message}", true)
            }
        }
        accountMap.clear()
        accountConfigs.clear()

        // Bind process to network if provided
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

        // Close and recreate transports safely
        try { if (udpTransportId != -1) { ep.transportClose(udpTransportId); udpTransportId = -1 } } catch (e: Throwable) { log("close UDP transport failed: ${e.message}", true) }
        try { if (tcpTransportId != -1) { ep.transportClose(tcpTransportId); tcpTransportId = -1 } } catch (e: Throwable) { log("close TCP transport failed: ${e.message}", true) }
        try { if (tlsTransportId != -1) { ep.transportClose(tlsTransportId); tlsTransportId = -1 } } catch (e: Throwable) { log("close TLS transport failed: ${e.message}", true) }

        try {
            val sipTpCfg = TransportConfig().apply { port = 0 }
            try { udpTransportId = ep.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, sipTpCfg) } catch (e: Throwable) { log("recreate UDP transport failed: ${e.message}", true) }

            val tcpTpCfg = TransportConfig().apply { port = 0 }
            try { tcpTransportId = ep.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TCP, tcpTpCfg) } catch (e: Throwable) { log("recreate TCP transport failed: ${e.message}", true) }

            val tlsTpCfg = TransportConfig().apply {
                tlsConfig.verifyServer = true
                tlsConfig.verifyClient = false
            }
            try { tlsTransportId = ep.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TLS, tlsTpCfg) } catch (e: Throwable) { log("recreate TLS transport failed: ${e.message}", true) }

            log("Transports recreated: UDP=$udpTransportId, TCP=$tcpTransportId, TLS=$tlsTransportId")
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

        // Re-add saved accounts
        savedConfigs.forEach { config ->
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

    /**
     * Force-null the session if it references a callId that is no longer in callMap.
     * Called by the ViewModel watchdog to catch zombie sessions that survived past
     * onCallState(DISCONNECTED) due to threading or exception edge-cases.
     *
     * CRITICAL: This runs on the coroutine watchdog thread (Dispatchers.Default from
     * viewModelScope), NOT the PJSIP thread.  We NEVER call PJSIP methods like
     * [org.pjsip.pjsua2.Call.info] or [org.pjsip.pjsua2.Call.delete] here because
     * they require a registered PJSIP thread and can trigger use-after-free races
     * with [onCallState] which runs on the PJSIP worker thread.
     *
     * We only check [callMap] (thread-safe ConcurrentHashMap) and the
     * [_callSession] StateFlow. That is sufficient for the zombie-detection
     * invariants we care about.
     */
    fun nullSessionIfStale() {
        val session = _callSession.value ?: return
        // Ignore session if it's a brand new outgoing call (assigned -1 until PJSIP returns a real ID)
        if (session.callId == -1) return

        val inMap = callMap.containsKey(session.callId)
        log("nullSessionIfStale: session callId=${session.callId} state=${session.state} inCallMap=$inMap callMapKeys=${callMap.keys}")
        if (!inMap) {
            log("nullSessionIfStale: force-nulling session for callId=${session.callId}")
            _callSession.value = null
            SipConnectionService.disconnectCall(session.callId)
            return
        }

        // NOTE: We deliberately do NOT call PJSIP's call.info() or call.delete() here.
        // Those require a registered PJSIP thread and can race with onCallState.
        // If the session is stale according to callMap or session state, the
        // onCallState(DISCONNECTED) path (which runs on the PJSIP worker thread)
        // will eventually clean up both callMap and the native object.
        // We only handle the case where the call is already removed from callMap
        // but the session was left dangling.
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

            // D5: clear any stale disconnect info from a previous call so the next
            // call's log entry doesn't inherit the old reason.
            pendingDisconnectInfo = null

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
                    // call.getId() can throw if PJSIP internally moved the call to
                    // DISCONNECTED (e.g. network unreachable, DNS failure) and this
                    // call's native memory was already released by onCallState.
                    // If the callMap already has the call (from onCallState), it means
                    // cleanup is already happening — bail out.
                    log("makeCall: getId() failed after makeCall returned: ${e.message}", true)
                    // Remove from callMap in case partial registration happened
                    callMap.entries.removeAll { it.value === call }
                    _callSession.value = null
                    // onCallState may already have posted delete to main; do NOT call
                    // call.delete() here — that's the double-free bug.
                    return false
                }

                // Overwrite any entry that onCallState may have put into callMap
                // during the synchronous callback inside makeCall().
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

                // CRITICAL: onCallState(DISCONNECTED) may have already fired
                // synchronously during call.makeCall(). If it did, it removed
                // the call from callMap and posted call.delete() to the main
                // looper.  We must NOT call call.delete() here because that
                // would be a double-free of the native PJSIP Call object.
                // Only delete if the call is still in callMap (meaning
                // onCallState did NOT fire for it).
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

                // Resolve the correct SIP status code for this hangup scenario:
                //   • 603 Decline  → incoming call not yet answered (ringing)
                //   • 0 (auto)     → outgoing pre-answer: PJSIP sends CANCEL automatically
                //   • 0 (auto)     → any confirmed call: PJSIP sends BYE automatically
                val sipStatusCode = CallHangupResolver.resolveSipStatusCode(session)
                log("hangupCall: resolved sipStatusCode=$sipStatusCode for session state=${session?.state}")

                // Record the Telecom DisconnectCause *before* sending the SIP hangup so that
                // onCallState(DISCONNECTED) can retrieve it and pass the correct cause to Telecom.
                val telecomCause = CallHangupResolver.resolveDisconnectCause(session)
                localHangupCauses[id] = telecomCause
                log("hangupCall: recording local telecomCause=$telecomCause for callId=$id")

                val prm = CallOpParam().apply {
                    val pjCode = sipStatusCode
                    if (pjCode != null) {
                        statusCode = pjCode
                    }
                    // reason string is optional; PJSIP fills it automatically
                }
                call.hangup(prm)
                log("hangupCall: call.hangup() sent successfully for callId=$id (sipStatusCode=$sipStatusCode, telecomCause=$telecomCause)")
            } catch (e: Throwable) {
                log("hangupCall failed: ${e.message}", true)
            }
        } else {
            // H2 fix: never leave native calls alive when the requested callId is
            // missing from callMap. A stale/ghost session must not survive a hangup
            // and later overwrite a new call. If any native calls are still running,
            // hang them all up so PJSIP actually tears down media (CANCEL/BYE/DECLINE
            // is chosen by PJSIP based on each call's current state).
            if (callMap.isNotEmpty()) {
                log("Hangup: callId=$id not in callMap but ${callMap.size} native call(s) still alive — hanging them all up")
                callMap.toMap().forEach { (cid, nativeCall) ->
                    try {
                        val prm = CallOpParam()
                        nativeCall.hangup(prm)
                        log("Hangup: sent hangup for callId=$cid")
                    } catch (e: Throwable) {
                        log("Hangup: failed for callId=$cid: ${e.message}", true)
                    }
                }
            }
            if (_callSession.value?.callId == id) {
                _callSession.value = null
            }
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
                PreferredCodec.G722  -> "g722"
                PreferredCodec.G711U -> "pcmu"
                PreferredCodec.G711A -> "pcma"
            }

            log("Configuring codecs. Target: $targetCodecKeyword")

            for (i in 0 until codecs.size) {
                val codec = codecs.get(i)
                val codecId = codec.codecId
                val name = codecId.lowercase()

                // Priority logic:
                // - Target codec gets highest priority (250)
                // - G729 gets 160 as smart fallback (low bandwidth)
                // - PCMA/PCMU get 150/140 as universal fallback
                // - Everything else disabled
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
            for (i in 0 until codecs.size) {
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

                // Bandwidth and MOS estimates
                val (bandwidth, mos) = when {
                    name.contains("opus") -> 6 to 4.3f    // OPUS: 6–51 kbps variable
                    name.contains("g722") -> 48 to 4.0f   // G.722: ~48–64 kbps
                    name.contains("g729") -> 8 to 3.9f    // G.729: ~8–12 kbps
                    name.contains("pcma") -> 64 to 4.1f   // G.711A: 64–87 kbps
                    name.contains("pcmu") -> 64 to 4.1f   // G.711U: 64–87 kbps
                    name.contains("gsm") -> 13 to 3.5f    // GSM: ~13 kbps
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

                if (!accountConfigs.containsKey(accountId)) {
                    log("Rejecting incoming call #${prm.callId} for disabled account $accountId", true)
                    val busyPrm = CallOpParam().apply { statusCode = pjsip_status_code.PJSIP_SC_DECLINE }
                    try { call.answer(busyPrm) } catch (_: Throwable) {}
                    call.delete()
                    callMap.remove(prm.callId)
                    return
                }

                val opPrm = CallOpParam().apply { statusCode = pjsip_status_code.PJSIP_SC_RINGING }
                try {
                    log("Answering incoming call #${prm.callId} with RINGING")
                    call.answer(opPrm)
                } catch (e: Throwable) {
                    log("Failed to answer incoming call #${prm.callId} with RINGING: ${e.message}", true)
                    call.delete()
                    callMap.remove(prm.callId)
                    throw e
                }

                try {
                    val ci = call.info ?: run {
                        log("Call info is null for incoming call #${prm.callId}", true)
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
        private var _isDeleteScheduled = false

        override fun onCallState(prm: OnCallStateParam) {
            val currentCallId = try { getId() } catch (e: Throwable) {
                log("ONCALLSTATE ENTRY: getId() failed: ${e.message}", true)
                _callSession.value = null
                return
            }
            @Suppress("DEPRECATION")
            val threadInfo = "${Thread.currentThread().name}[${Thread.currentThread().id}]"
            log("ONCALLSTATE ENTRY: callId=$currentCallId thread=$threadInfo session=${_callSession.value?.callId}/${_callSession.value?.state} callMapSize=${callMap.size} callMapHasId=${callMap.containsKey(currentCallId)}")
            try {

                val ci = try { info } catch (e: Throwable) {
                    log("Failed to get call info for call $currentCallId: ${e.message}", true)
                    try { onCallDisconnected?.invoke(currentCallId) } catch (_: Throwable) {}
                    callMap.remove(currentCallId)
                    if (_callSession.value?.callId == currentCallId) {
                        _callSession.value = null
                    }
                    SipConnectionService.disconnectCall(currentCallId, android.telecom.DisconnectCause.REMOTE)
                    return
                }

                if (ci == null) {
                    log("Call info is null for call $currentCallId", true)
                    try { onCallDisconnected?.invoke(currentCallId) } catch (_: Throwable) {}
                    callMap.remove(currentCallId)
                    if (_callSession.value?.callId == currentCallId) {
                        _callSession.value = null
                    }
                    SipConnectionService.disconnectCall(currentCallId, android.telecom.DisconnectCause.REMOTE)
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
                    log("ONCALLSTATE DISCONNECT BLOCK: callId=$currentCallId state=$newState code=${ci.lastStatusCode} reason=${ci.lastReason}")

                    try {
                        onCallDisconnected?.invoke(currentCallId)
                    } catch (e: Throwable) {
                        log("onCallDisconnected callback failed: ${e.message}", true)
                    }

                    callMap.remove(currentCallId)
                    log("ONCALLSTATE DISCONNECT: callId=$currentCallId removed from callMap, callMapSize=${callMap.size}")

                    // D5: stamp the disconnect code/reason onto the session BEFORE nulling
                    // it, so SipService.observeCallState() can persist it to the call log.
                    _callSession.value = _callSession.value?.copy(
                        state = CallState.DISCONNECTED,
                        disconnectCode = ci.lastStatusCode,
                        disconnectReason = ci.lastReason
                    )
                    log("ONCALLSTATE DISCONNECT: callId=$currentCallId session nulled (code=${ci.lastStatusCode}, reason=${ci.lastReason})")
                    // Reliable side-channel: StateFlow conflates the DISCONNECTED value, so
                    // also stash the info here for SipService to consume on session == null.
                    pendingDisconnectInfo = ci.lastStatusCode to (ci.lastReason ?: "")
                    _callSession.value = null

                    // A2: reset the confirmed route so a stale value (e.g. SPEAKER from
                    // the previous call) does not leak into the next call or app launch.
                    _confirmedAudioRoute.value = null

                    // Post thread-sensitive cleanup to Main — these no longer gate the null.
                    CoroutineScope(Dispatchers.Main).launch {
                        try {
                            audioManager.mode = AudioManager.MODE_NORMAL
                            audioManager.isSpeakerphoneOn = false
                        } catch (e: Throwable) {
                            log("Failed to reset audio manager: ${e.message}", true)
                        }
                    }

                    try {
                        recorder?.delete()
                        recorder = null
                    } catch (e: Throwable) {
                        log("Failed to delete recorder on disconnect: ${e.message}", true)
                    }

                    // Always tear down the Telecom connection so the system dialer
                    // and notification are dismissed even if onCallState fires late.
                    // Use the stored local cause if we initiated the hangup ourselves;
                    // otherwise treat as REMOTE (the far end sent BYE or CANCEL).
                    val disconnectCause = localHangupCauses.remove(currentCallId)
                        ?: android.telecom.DisconnectCause.REMOTE
                    log("ONCALLSTATE DISCONNECT: callId=$currentCallId telecomCause=$disconnectCause (wasLocal=${disconnectCause != android.telecom.DisconnectCause.REMOTE})")
                    SipConnectionService.disconnectCall(currentCallId, disconnectCause)

                    // Guard: only schedule native delete once, even if onCallState fires
                    // multiple times for the same callId (which PJSIP can do for
                    // DISCONNECTED→NULL transitions).
                    if (!_isDeleteScheduled) {
                        _isDeleteScheduled = true
                        val callToDelete = this
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            try {
                                callToDelete.delete()
                            } catch (e: Throwable) {
                                Log.e("SipEngine", "Failed to delete call on main loop", e)
                            }
                        }
                    }
                } else {
                    log("ONCALLSTATE ELSE: callId=$currentCallId newState=$newState sessionBefore=${_callSession.value?.state}")
                    if (_callSession.value != null) {
                        _callSession.value = _callSession.value?.copy(state = newState, callId = currentCallId)
                        log("ONCALLSTATE ELSE: callId=$currentCallId sessionAfter=${_callSession.value?.state}")
                    }

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
                            log("ONCALLSTATE ELSE: callId=$currentCallId telecom connection updated to $newState")
                        } catch (e: Throwable) {
                            log("Failed to update telecom connection state: ${e.message}", true)
                        }
                    } ?: log("ONCALLSTATE ELSE: callId=$currentCallId no telecom connection found")
                }
            } catch (e: Throwable) {
                log("ONCALLSTATE EXCEPTION: callId=$currentCallId error=${e.message}", true)
                try {
                    if (_callSession.value != null) {
                        log("onCallState error safety net: force-nulling callSession")
                        _callSession.value = null
                    }
                } catch (f: Throwable) {
                    log("Failed to null session in safety net: ${f.message}", true)
                }
            }
        }

        override fun onCallTsxState(prm: OnCallTsxStateParam) {
            try {
                val currentCallId = try { getId() } catch (_: Throwable) { -1 }
                if (currentCallId != -1) {
                    val ci = try { info } catch (_: Throwable) { null }
                    if (ci == null || ci.state == pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED || ci.state == pjsip_inv_state.PJSIP_INV_STATE_NULL) {
                        val session = _callSession.value
                        if (session != null && session.callId == currentCallId) {
                            log("ONCALLTSXSTATE: Disconnect detected for callId=$currentCallId, executing local hangup cleanup")
                            try { onCallDisconnected?.invoke(currentCallId) } catch (_: Throwable) {}
                            callMap.remove(currentCallId)
                            _callSession.value = null
                            SipConnectionService.disconnectCall(currentCallId, android.telecom.DisconnectCause.REMOTE)
                        }
                    }
                }
            } catch (e: Throwable) {
                log("onCallTsxState exception: ${e.message}", true)
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
