package com.ipdial.service

import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.ipdial.data.model.*
import com.ipdial.data.model.CallSession
import com.ipdial.data.model.CallState
import com.ipdial.data.model.SipAccount
import com.ipdial.util.DeviceUtil
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

    // ------------------------------------------------------------------
    // PJSIP thread-safety serialization.
    //
    // pjsua2 (PJSIP 2.5) is NOT thread-safe: native callbacks arrive on the
    // internal SIP worker thread, and ANY concurrent call into a pjsua2 object
    // from the main thread or a coroutine thread (call.info, audDevManager,
    // startTransmit, delete, hangup, ...) can corrupt dialog mutex ownership and
    // abort the process:
    //
    //   os_core_unix.c:1309 pj_mutex_unlock(): assertion "mutex->owner ==
    //   pj_thread_this()" failed   (SIGABRT, seen repeatedly in logcat)
    //
    // The correct pattern (used by Linphone) is a single "PJSIP thread": one
    // dedicated serialized executor that owns the endpoint until it is fully
    // initialized, and through which all app-initiated calls are funneled. That
    // way only two threads ever touch native objects — the SIP worker (callbacks)
    // and this single app-side thread (API calls) — and they never run the same
    // dialog operation at the same time.
    //
    // We keep the endpoint creation/init racing-free by posting init to this
    // thread; every public API (makeCall, answerCall, hangupCall, mute, volume,
    // DTMF, hold, recording, device/EC forced setup) is queued here too.
    // ------------------------------------------------------------------
    private var pjsipThread: HandlerThread? = null
    private var pjsipHandler: Handler? = null
    private val pjsipOps = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * Global monitor that serializes EVERY native pjsua2 call — API calls on the
     * app threads AND callbacks on the SIP worker thread. PJSIP dialog mutexes are
     * not safe across threads; holding this lock while touching any pjsua2 object
     * guarantees no two threads are ever inside native code at the same moment.
     */
    internal val pjsipLock = Any()

    /** Queue [block] to run on the single PJSIP thread (serialized). */
    internal fun runOnPjsipThread(block: () -> Unit) {
        val h = pjsipHandler
        if (h != null) {
            h.post(block)
        } else {
            // No thread yet (pre-init or after destroy): run inline on a best-effort
            // basis. During destroy the endpoint is null, so native calls will no-op.
            try {
                block()
            } catch (e: Throwable) {
                log("runOnPjsipThread (no thread): ${e.message}", true)
            }
        }
    }

    /**
     * Blocking variant used by callers that need the result (makeCall, addAccount).
     * Only call from a non-main thread — the callers (Telecom ConnectionService and
     * SipService coroutines) all run on Dispatchers.IO.
     */
    internal fun <T> runOnPjsipThreadAndWait(block: () -> T): T? {
        val h = pjsipHandler
        if (h == null) {
            return try { block() } catch (e: Throwable) {
                log("runOnPjsipThreadAndWait (no thread): ${e.message}", true)
                null
            }
        }
        if (Thread.currentThread() === pjsipThread) {
            return try { block() } catch (e: Throwable) {
                log("runOnPjsipThreadAndWait (already on thread): ${e.message}", true)
                null
            }
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: T? = null
        var error: Throwable? = null
        h.post {
            try {
                result = block()
            } catch (e: Throwable) {
                error = e
            } finally {
                latch.countDown()
            }
        }
        try {
            latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            log("runOnPjsipThreadAndWait interrupted: ${e.message}", true)
            Thread.currentThread().interrupt()
        }
        error?.let {
            log("runOnPjsipThreadAndWait error: ${it.message}", true)
        }
        return result
    }

    private fun ensurePjsipThread() {
        if (pjsipThread != null && pjsipThread?.isAlive == true && pjsipHandler != null) return
        val t = HandlerThread("PjsipThread").apply { start() }
        pjsipThread = t
        pjsipHandler = Handler(t.looper)
        // Post registration so the constant thread the worker sees is this one.
        Handler(t.looper).post {
            registerCurrentThreadEx()
            log("PJSIP app thread ready: ${Thread.currentThread().name}", false)
        }
    }

    private fun stopPjsipThread() {
        try {
            pjsipHandler?.removeCallbacksAndMessages(null)
        } catch (_: Throwable) {}
        try {
            pjsipThread?.quitSafely()
        } catch (_: Throwable) {}
        pjsipThread = null
        pjsipHandler = null
    }

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
    internal var audioRouter: SipAudioRouter? = null
    internal var appContext: android.content.Context? = null

    internal fun probeDir(): java.io.File? =
        appContext?.let { ctx ->
            java.io.File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "probes")
                .apply { if (!exists()) mkdirs() }
        }

    private val initLock = Any()
    private var initCallCount = 0

    internal val _callSession = MutableStateFlow<CallSession?>(null)
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

    internal val _registrationEvents = MutableSharedFlow<Triple<String, RegStatus, Int>>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val registrationEvents: SharedFlow<Triple<String, RegStatus, Int>> = _registrationEvents.asSharedFlow()

    @Volatile
    var onIncomingCall: ((CallSession) -> Unit)? = null
    @Volatile
    var onCallDisconnected: ((callId: Int) -> Unit)? = null

    // Recorder is written/deleted from two threads: the PJSIP worker (call
    // callbacks under pjsipLock) and the PJSIP handler thread (startRecording/
    // stopRecording). Making the reference volatile ensures every reader sees
    // the latest value, and all native delete() calls are serialized under
    // pjsipLock (see startRecording/stopRecording) to prevent double-delete.
    @Volatile
    internal var recorder: org.pjsip.pjsua2.AudioMediaRecorder? = null
    private var logWriter: LogWriter? = null

    @Volatile private var _dndActive = false
    fun setDndActive(active: Boolean) { _dndActive = active }
    fun isDndActive(): Boolean = _dndActive

    private var currentEcEnabled = true
    private var currentNsEnabled = true
    private var currentAgcEnabled = true

    // D5: reliable side-channel for the final SIP disconnect code/reason.
    // StateFlow conflates intermediate values, so the DISCONNECTED-stamped
    // session can be skipped for slow collectors. This field is set on the
    // PJSIP thread right before the session is nulled, and consumed by
    // SipService.observeCallState() when it sees session == null.
    @Volatile internal var pendingDisconnectInfo: Pair<Int?, String?>? = null

    fun consumeDisconnectInfo(): Pair<Int?, String?>? {
        val v = pendingDisconnectInfo
        pendingDisconnectInfo = null
        return v
    }

    internal fun logEx(message: String, isError: Boolean = false) {
        if (isError) Log.e(TAG, message) else Log.d(TAG, message)
        com.ipdial.util.SipLogger.log(TAG, message)
    }

    internal fun registerCurrentThreadEx() {
        val ep = endpoint ?: return
        val threadId = @Suppress("DEPRECATION") Thread.currentThread().id
        if (registeredThreads.contains(threadId)) return
        try {
            // Some older PJSIP wrappers don't have libIsThreadRegistered().
            // libRegisterThread is usually safe to call multiple times or 
            // handles already-registered threads internally.
            val threadName = Thread.currentThread().name ?: "SipEngineThread"
            ep.libRegisterThread(threadName)
            registeredThreads.add(threadId)
        } catch (e: Throwable) {
            // Only log if it's not a "Thread already registered" type error
            val msg = e.message ?: ""
            if (!msg.contains("already registered", ignoreCase = true)) {
                logEx("Thread registration info: $msg", false)
            }
            // Still mark as registered to avoid repeated attempts if it failed for this reason
            registeredThreads.add(threadId)
        }
    }

    private fun log(message: String, isError: Boolean = false) = logEx(message, isError)

    private fun registerCurrentThread() = registerCurrentThreadEx()

    fun init(context: Context) {
        initCallCount++
        appContext = context.applicationContext
        val callId = initCallCount
        @Suppress("DEPRECATION")
        val threadInfo = "${Thread.currentThread().name}[${Thread.currentThread().id}]"
        log("init() called (#$callId from $threadInfo)")

        if (endpoint != null) {
            log("init() skipped (#$callId) — endpoint already set")
            return
        }

        // The whole PJSIP endpoint lifecycle belongs on the dedicated PJSIP thread:
        // create + libCreate + libInit + libStart all run there so no other thread
        // can race the native init.
        ensurePjsipThread()
        val h = pjsipHandler ?: return
        h.post {
            doInitLocked(context, callId)
        }
    }

    private fun doInitLocked(context: Context, callId: Int) {
        registerCurrentThreadEx()
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
                    val isEmulator = DeviceUtil.isEmulator()
                    val epCfg = EpConfig().apply {
                        logConfig.level = 4
                        logConfig.consoleLevel = 4
                        logConfig.writer = writer

                        medConfig.apply {
                            clockRate = 16000
                            // BlueStacks' virtual HAL only advertises 48 kHz; a 16 kHz OpenSL
                            // capture stream returns digital silence there. Force 48 kHz so the
                            // OpenSL input is served a supported rate (48 kHz is universally
                            // supported on real devices too, so this is safe everywhere).
                            sndClockRate = 48000
                            val hasHwAec = try { android.media.audiofx.AcousticEchoCanceler.isAvailable() } catch(e: Exception) { false }
                            
                            // The bundled software EC crashes in pjmedia_echo_capture
                            // on some Android AudioRecord implementations. Rely only on
                            // the device audio processing path and keep PJSIP processing off.
                            ecOptions = 0
                            ecTailLen = 0
                            log("PJSIP software audio processing disabled; using device audio processing only.")

                            // Stream VAD off. With VAD on, a quiet/low-gain microphone was
                            // classified as silence and the far end received no speech at all.
                            noVad = true
                            // DSP quality 7 (was 5). Safe for any device released after ~2018;
                            // improves the resampler quality on non-standard rates used by
                            // MediaTek/Unisoc/Spreadtrum SoCs.
                            quality = 7
                            channelCount = 1
                            audioFramePtime = 20

                            // Jitter buffer tuned for bursty mobile-network RTP delivery.
                            // MIUI/EMUI/ColorOS can batch-deliver RTP packets after screen-off;
                            // a higher max (400 ms) and larger min pre-buffer (60 ms = 3 frames)
                            // absorb the bursts without dropping packets.
                            jbInit = 60
                            jbMinPre = 60
                            jbMax = 400
                        }
                        uaConfig.apply {
                            userAgent = "IPDial/1.1 (Android)"
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

                    libStart()
                    log("#$callId: PJSIP started successfully")

                    try {
                        val adm = ep.audDevManager()
                        val devs = adm.enumDev()
                        // Log available audio drivers for diagnostics. We intentionally
                        // do NOT force the Java Audio driver here: forcing it breaks mic
                        // capture on platforms where OpenSL ES is the working driver
                        // (e.g. emulators where Linphone works). Let PJSIP auto-select
                        // the device the platform actually supports.
                        for (i in 0 until devs.size.toInt()) {
                            val info = try { devs.get(i) } catch (_: Throwable) { null }
                            log("Audio Device [$i]: ${info?.name} (Driver: ${info?.driver})")
                        }
                        adm.setCaptureDev(-1)
                        adm.setPlaybackDev(-1)
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

    fun addAccount(account: SipAccount): Boolean {
        logEx("addAccount requested: id=${account.id} user=${account.username}")
        return runOnPjsipThreadAndWait {
            addAccountOnPjsipThread(account)
        } ?: false
    }

    private fun addAccountOnPjsipThread(account: SipAccount): Boolean {
        registerCurrentThreadEx()
        logEx("addAccount: id=${account.id} user=${account.username} passLen=${account.password.length}")
        val ep = endpoint ?: run {
            logEx("addAccount failed: endpoint is null. PJSIP might not be initialized.", true)
            return false
        }
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
                    // Check if the PJSIP account still exists before trying re-registration
                    val existingPjAcc = accountMap[account.id]
                    if (existingPjAcc != null) {
                        try {
                            existingPjAcc.setRegistration(true)
                        } catch (e: Throwable) {
                            // PJSIP_EBUSY (171001) means a REGISTER transaction is already
                            // in flight, so the account is registering on its own. Destroying
                            // and re-adding it here only churns the transport/transaction.
                            val isBusy = e.message?.contains("PJSIP_EBUSY", ignoreCase = true) == true ||
                                    e.message?.contains("Object is busy", ignoreCase = true) == true
                            if (isBusy) {
                                log("Account ${account.id} already has a registration in flight, skipping re-registration")
                                return false
                            }
                            log("Re-registration failed for ${account.id}, force re-adding: ${e.message}", true)
                            // Account exists in our map but PJSIP internal state is lost
                            // Force remove and re-add
                            try { existingPjAcc.delete() } catch (_: Throwable) {}
                            accountMap.remove(account.id)
                            // Fall through to full addAccount below
                        }
                    } else {
                        log("Account ${account.id} not in accountMap, force adding", true)
                        // Fall through to full addAccount below
                    }
                    // Only return if re-registration succeeded (account still exists)
                    if (accountMap.containsKey(account.id)) {
                        return true
                    }
                }
            }

            accountMap[account.id]?.let { removeAccount(account.id) }

            val cleanUsername = account.username.trim()
            var cleanDomain = account.domain.trim().removePrefix("sip:").removePrefix("sips:")
            var explicitPort = account.port
            if (cleanDomain.contains(":")) {
                val parts = cleanDomain.split(":")
                cleanDomain = parts[0]
                if (explicitPort == null || explicitPort <= 0) {
                    explicitPort = parts.getOrNull(1)?.toIntOrNull()
                }
            }
            val cleanProxy = account.proxy.trim().removePrefix("sip:").removePrefix("sips:")

            val acfg = AccountConfig().apply {
                idUri = "sip:$cleanUsername@$cleanDomain"

                val transportSuffix = when (account.transport) {
                    Transport.TCP -> ";transport=tcp"
                    Transport.TLS -> ";transport=tls"
                    else -> ""
                }

                regConfig.registrarUri = if (explicitPort != null && explicitPort > 0) {
                    "sip:$cleanDomain:$explicitPort$transportSuffix"
                } else {
                    "sip:$cleanDomain$transportSuffix"
                }

                regConfig.timeoutSec = 180
                regConfig.retryIntervalSec = 30
                regConfig.firstRetryIntervalSec = 15
                regConfig.delayBeforeRefreshSec = 90

                val cred = AuthCredInfo("digest", "*", cleanUsername, 0, account.password)
                sipConfig.authCreds.add(cred)

                if (cleanProxy.isNotBlank()) {
                    val proxyUri = if (cleanProxy.contains(";lr")) {
                        "sip:$cleanProxy"
                    } else {
                        "sip:$cleanProxy$transportSuffix;lr"
                    }
                    sipConfig.proxies.add(proxyUri)
                }

                transportManager.createTransport(ep, account.transport, ::log)

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
                natConfig.udpKaIntervalSec = 15
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
                // HACK: Add config BEFORE create to ensure callbacks have access to it
                accountConfigs[account.id] = account
                
                configureCodecs(account)

                pjAcc.create(acfg)
                accountMap[account.id] = pjAcc
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
        return true
    }

    fun removeAccount(accountId: String) {
        runOnPjsipThread {
            registerCurrentThreadEx()
            try {
                accountMap[accountId]?.delete()
                accountMap.remove(accountId)
                accountConfigs.remove(accountId)
                log("Account removed: $accountId")
            } catch (e: Throwable) {
                log("removeAccount failed: ${e.message}", true)
            }
        }
    }

    fun reconnectAccount(accountId: String) {
        runOnPjsipThread {
            registerCurrentThreadEx()
            try {
                accountMap[accountId]?.setRegistration(true)
            } catch (e: Throwable) {
                log("reconnectAccount failed: ${e.message}", true)
            }
        }
    }

    fun forceReconnectAll() {
        runOnPjsipThread {
            registerCurrentThreadEx()
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
                    addAccountOnPjsipThread(config)
                }
            } catch (e: Throwable) {
                log("forceReconnectAll failed: ${e.message}", true)
            }
        }
    }

    fun reconnectOnNetworkChange(network: Network?, context: Context) {
        runOnPjsipThread {
            registerCurrentThreadEx()
            reconnectOnNetworkChangeOnThread(network, context)
        }
    }

    private fun reconnectOnNetworkChangeOnThread(network: Network?, context: Context) {
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

        savedConfigs.forEach { config ->
            accountConfigs[config.id] = config
            try {
                addAccountOnPjsipThread(config)
            } catch (e: Throwable) {
                log("Failed to re-add account ${config.id} after network change: ${e.message}", true)
            }
        }
    }

    fun handleIpChange() {
        runOnPjsipThread {
            registerCurrentThreadEx()
            val ep = endpoint ?: return@runOnPjsipThread
            try {
                log("handleIpChange: pjsip 2.5 has no IpChangeParam API; re-registering accounts")
                accountMap.values.forEach { acc ->
                    try {
                        acc.setRegistration(true)
                    } catch (e: Throwable) {
                        log("handleIpChange: re-register failed: ${e.message}", true)
                    }
                }

                // Re-establish media for any in-progress call so a network switch
                // (Wi-Fi → cellular) doesn't leave audio one-way or silent.
                callMap.keys.toList().forEach { callId ->
                    try {
                        // ORDER MATTERS: forceAudioDevicesForCall()/forceEcForCallAudio()
                        // restart the sound device (pjsua_set_snd_dev / pjsua_set_ec),
                        // which tears down any previously-established startTransmit
                        // bridges. So the device/EC must be forced FIRST, and the
                        // bridges re-established AFTER with fresh media objects.
                        forceAudioDevicesForCall()
                        forceEcForCallAudio()
                        reconnectAudioPathForCall(callId)
                        log("handleIpChange: reconnected audio for active callId=$callId", false)
                    } catch (e: Throwable) {
                        log("handleIpChange: failed to reconnect audio for callId=$callId: ${e.message}", true)
                    }
                }
            } catch (e: Throwable) {
                log("handleIpChange failed: ${e.message}", true)
            }
        }
    }

    fun startRecording(filePath: String) {
        runOnPjsipThread {
            registerCurrentThreadEx()
            synchronized(pjsipLock) {
                if (recorder != null) {
                    log("startRecording: already recording, stopping first.")
                    stopRecordingLocked()
                }

                try {
                    val rec = AudioMediaRecorder()
                    rec.createRecorder(filePath)
                    recorder = rec
                    log("startRecording: recorder created for $filePath")

                    // If a call is already active and confirmed, connect its media now.
                    // (New calls will connect in onCallMediaState).
                    callMap.values.forEach { call ->
                        try {
                            val ci = call.info
                            for (i in 0 until ci.media.size.toInt()) {
                                val mi = ci.media.get(i)
                                if (mi.type == pjmedia_type.PJMEDIA_TYPE_AUDIO &&
                                    mi.status == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE) {
                                    val aud = AudioMedia.typecastFromMedia(call.getMedia(mi.index.toLong()))
                                    aud.startTransmit(rec)
                                    endpoint?.audDevManager()?.captureDevMedia?.startTransmit(rec)
                                    log("startRecording: connected active call ${ci.id} media to recorder")
                                }
                            }
                        } catch (e: Throwable) {
                            log("startRecording: failed to connect call media: ${e.message}", true)
                        }
                    }
                } catch (e: Throwable) {
                    log("startRecording failed: ${e.message}", true)
                    recorder = null
                }
            }
        }
    }

    fun stopRecording() {
        runOnPjsipThread {
            registerCurrentThreadEx()
            synchronized(pjsipLock) {
                stopRecordingLocked()
            }
        }
    }

    /**
     * Deletes the native recorder under pjsipLock. Must be called with
     * pjsipLock held (the SipCallDelegate disconnect path also deletes the
     * recorder under pjsipLock on the PJSIP worker thread — serializing both
     * prevents a concurrent native double-delete / use-after-delete).
     */
    private fun stopRecordingLocked() {
        recorder?.let {
            try {
                it.delete()
                log("stopRecording: recorder stopped and deleted")
            } catch (e: Throwable) {
                log("stopRecording failed: ${e.message}", true)
            } finally {
                recorder = null
            }
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
        
        // Additional check: if session is DISCONNECTED but still in callMap, clean it up
        if (session.state == CallState.DISCONNECTED) {
            log("nullSessionIfStale: session is DISCONNECTED but still in callMap, cleaning up callId=${session.callId}")
            callMap.remove(session.callId)
            _callSession.value = null
            SipConnectionService.disconnectCall(session.callId)
        }
    }

    fun makeCall(accountId: String, destination: String): Boolean {
        return runOnPjsipThreadAndWait {
            registerCurrentThreadEx()
            makeCallOnThread(accountId, destination)
        } ?: false
    }

    private fun makeCallOnThread(accountId: String, destination: String): Boolean {
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
                    log("makeCall: getId() failed after makeCall returned: ${e.message}", true)
                    callMap.entries.removeAll { it.value === call }
                    _callSession.value = null
                    return false
                }

                callMap[realId] = call
                log("call.makeCall returned successfully. assigned call ID = $realId")

                val currentSession = _callSession.value
                if (currentSession == null || currentSession.state == CallState.DISCONNECTED) {
                    log("makeCall: user or remote already hung up before makeCall returned — hanging up realId=$realId")
                    try { call.hangup(CallOpParam()) } catch (_: Throwable) {}
                    callMap.remove(realId)
                    return false
                }

                _callSession.value = currentSession.copy(callId = realId)
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
        runOnPjsipThread {
            registerCurrentThreadEx()
            callMap[callId]?.let { call ->
                try {
                    val prm = CallOpParam(true).apply { statusCode = pjsip_status_code.PJSIP_SC_OK }
                    call.answer(prm)
                } catch (e: Throwable) {
                    log("answerCall failed: ${e.message}", true)
                }
            }
        }
    }

    fun hangupCall(callId: Int = -1) {
        runOnPjsipThread {
            registerCurrentThreadEx()
            hangupCallOnThread(callId)
        }
    }

    private fun hangupCallOnThread(callId: Int) {
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
                // Force immediate local cleanup if native call.hangup threw an exception
                callMap.remove(id)
                if (_callSession.value?.callId == id) {
                    _callSession.value = null
                }
                try { onCallDisconnected?.invoke(id) } catch (_: Throwable) {}
                SipConnectionService.disconnectCall(id, CallHangupResolver.resolveDisconnectCause(_callSession.value))
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
                        callMap.remove(cid)
                    }
                }
            }
            if (_callSession.value != null && (_callSession.value?.callId == id || id == -1)) {
                _callSession.value = null
            }
        }
    }

    private fun PreferredCodec.toCodecId(): String = when (this) {
        PreferredCodec.G729  -> "g729"
        PreferredCodec.G722  -> "g722"
        PreferredCodec.G711U -> "pcmu"
        PreferredCodec.G711A -> "pcma"
        PreferredCodec.Opus  -> "opus"
    }

    private fun configureCodecs(account: SipAccount) {
        val ep = endpoint ?: return
        val preferred = account.codec ?: PreferredCodec.G711U
        // Keep the user's codec choice when the peer requires G.729. The RTP
        // payload must match the negotiated SDP; rejecting payload 18 while the
        // peer sends G.729 causes every incoming audio packet to be discarded.
        val effectivePreferred = preferred
        val targetCodecId = effectivePreferred.toCodecId()
        // Codecs the user can toggle in settings. Anything not in this set (e.g. gsm,
        // ilbc, speex) stays auto-enabled as a universal fallback so connectivity is
        // never worse than the old always-on behaviour.
        val controllableIds = PreferredCodec.entries.map { it.toCodecId() }.toSet()
        val enabled = account.enabledCodecs.ifEmpty { DEFAULT_ENABLED_CODECS }

        try {
            val codecs = ep.codecEnum()
            log("Configuring codecs. Preferred: $targetCodecId, enabled: ${enabled.joinToString { it.toCodecId() }}")

            // Linphone-style multi-codec offering: we ENABLE several codecs and order
            // them by priority. pjsip lists codecs highest-first in the SDP offer and
            // the remote picks the first one it also supports, so the best
            // mutually-available codec wins automatically.
            //
            // Priority scheme (higher = preferred first in SDP):
            //  Opus  -> 260 (best quality/adaptivity; Linphone & modern PBXs support it)
            //  User's preferred codec -> 250 (explicit user choice wins local ordering)
            //  G.722 -> 220 (wideband, good quality)
            //  G.711A -> 180 (narrowband, universally supported)
            //  G.711U -> 170 (narrowband, universally supported)
            //  G.729 ->  160 (narrowband low-bitrate, software BCG729)
            //  GSM  ->  140 (fallback)
            //  everything else -> 0 (disabled — keeps the SIP INVITE compact)
            for (i in 0 until codecs.size.toInt()) {
                val codec = codecs.get(i)
                val codecId = codec.codecId
                val nameLower = codecId.lowercase()

                val base: Int = when {
                    nameLower.startsWith("opus") -> 260
                    nameLower.startsWith("g722") -> 220
                    nameLower.startsWith("pcma") -> 180
                    nameLower.startsWith("pcmu") -> 170
                    nameLower.startsWith("g729") -> 160
                    nameLower.startsWith("gsm") -> 140
                    else -> 0
                }

                val isControllable = controllableIds.any { nameLower.startsWith(it) }
                val userEnabled = enabled.any { nameLower.startsWith(it.toCodecId()) }
                val isEnabled = if (isControllable) userEnabled else base > 0

                var priority = if (isEnabled) base else 0
                if (isEnabled && nameLower.contains(targetCodecId)) priority = maxOf(priority, 250)
                ep.codecSetPriority(codecId, priority.toShort())
                if (priority > 0) log("Codec ENABLED: $codecId (priority $priority)")
                else log("Codec DISABLED: $codecId")
            }

            try {
                val adm = ep.audDevManager()
                // Keep both software echo cancellation and noise suppression off.
                // Android's selected communication device may apply its own hardware
                // processing; the PJSIP wrapper does not expose its AudioRecord session
                // for safely attaching an Android NoiseSuppressor effect here.
                adm.setEcOptions(0, 0)
            } catch (e: Throwable) {
                log("Note on EC settings: ${e.message}")
            }

            log("Codec & Audio configuration complete. Preferred=$targetCodecId (configured=${preferred.toCodecId()})")
        } catch (e: Throwable) {
            log("Error configuring codecs: ${e.message}", true)
        }
    }

    /**
     * Ensures the sound device is open and capture is routed through the platform's
     * WORKING audio driver, without hard-coding a specific driver.
     *
     * History: this previously forced the "Java Audio (Android)" driver at call time
     * as a Samsung workaround. That override breaks mic capture on devices (and
     * emulators) where the OpenSL ES driver is the one that actually provides usable
     * capture — the far end hears only DTMF/RFC2833 but no speech. Linphone, which
     * works on those same platforms, lets PJSIP auto-select the sound device.
     *
     * So: we let PJSIP auto-select capture/playback (its default device is the one
     * the platform/media framework actually provides), and only restart the sound
     * device if it isn't active, so the media bridge in onCallMediaState() operates
     * against a valid, live capture device.
     */
    fun forceAudioDevicesForCall() {
        runOnPjsipThread {
            registerCurrentThreadEx()
            synchronized(pjsipLock) {
                try {
                    val adm = endpoint?.audDevManager() ?: return@runOnPjsipThread
                    val devs = adm.enumDev()
                    val names = (0 until devs.size.toInt()).map { i ->
                        val info = try { devs.get(i) } catch (_: Throwable) { null }
                        "${i}:${info?.name}"
                    }.joinToString(", ")
                    val preferredDevice = if (DeviceUtil.isEmulator()) {
                        -1
                    } else {
                        (0 until devs.size.toInt()).firstOrNull { i ->
                            val name = try { devs.get(i)?.name ?: "" } catch (_: Throwable) { "" }
                            name.contains("Android JNI", ignoreCase = true)
                        } ?: -1
                    }
                    // Re-selecting the device also reopens capture after Telecom or an
                    // OEM audio policy has taken over the microphone.
                    adm.setCaptureDev(preferredDevice)
                    adm.setPlaybackDev(preferredDevice)
                    log("Call audio: selected device=$preferredDevice. Available: [$names]", false)
                } catch (e: Throwable) {
                    log("forceAudioDevicesForCall failed: ${e.message}", true)
                }
            }
        }
    }

    /**
     * pjsua only honors EC options when the sound device opens, and logcat showed a
     * default software EC (33 ms tail) running on top of Samsung's in-call HAL AEC —
     * the double-EC condition that makes the far end hear nothing. The init-time
     * medConfig/audDevManager EC settings were evidently not applied. Re-apply the
     * correct value AFTER the call's sound device is open; pjsua2's setEcOptions
     * (pjsua_set_ec) restarts the sound device so the setting always takes effect.
     */
    fun forceEcForCallAudio() {
        runOnPjsipThread {
            registerCurrentThreadEx()
            synchronized(pjsipLock) {
                try {
                    val adm = endpoint?.audDevManager() ?: return@runOnPjsipThread
                    adm.setEcOptions(0, 0)
                    log("Call audio: PJSIP software EC/NS disabled; device processing only")
                } catch (e: Throwable) {
                    log("forceEcForCallAudio failed: ${e.message}", true)
                }
            }
        }
    }

    /**
     * Re-establishes the bidirectional audio path (device → call, call → device)
     * for an active call. Used after un-hold / re-INVITE and after network switches
     * where pjsip does not always re-fire onCallMediaState with a usable media state.
     */
    fun reconnectAudioPathForCall(callId: Int) {
        runOnPjsipThread {
            registerCurrentThreadEx()
            synchronized(pjsipLock) {
                try {
                    val call = callMap[callId] ?: return@runOnPjsipThread
                    val ci = call.info
                    val adm = endpoint?.audDevManager() ?: return@runOnPjsipThread
                    val captureMedia = adm.captureDevMedia
                    val playbackMedia = adm.playbackDevMedia

                    SipEngine.audioRouter?.requestAudioFocus()

                    for (i in 0 until ci.media.size.toInt()) {
                        val mi = ci.media.get(i)
                        if (mi.type != pjmedia_type.PJMEDIA_TYPE_AUDIO) continue
                        val aud = AudioMedia.typecastFromMedia(call.getMedia(mi.index.toLong()))
                        // Correct PJSIP directions:
                        //   RX: call media -> playback (speaker)
                        //   TX: capture (mic) -> call media
                        aud.startTransmit(playbackMedia)
                        captureMedia.startTransmit(aud)

                        // Re-apply TX/RX gains so a previous mute/hold state is respected.
                        val session = _callSession.value
                        val isEmulator = com.ipdial.util.DeviceUtil.isEmulator()
                        val baseGain = if (isEmulator) SipAudioController.MIC_GAIN_EMULATOR else SipAudioController.MIC_GAIN_REAL
                        aud.adjustTxLevel(if (session?.isMuted == true) 0f else baseGain)
                        aud.adjustRxLevel(session?.rxVolume ?: 2.5f)

                        // Recording: bridge both directions into the recorder if active.
                        recorder?.let { rec ->
                            aud.startTransmit(rec)
                            captureMedia.startTransmit(rec)
                        }

                        log("reconnectAudioPathForCall: re-established audio path for callId=$callId stream=$i", false)
                    }

                    // Refresh the negotiated codec too (media may have been re-negotiated).
                    try {
                        for (i in 0 until ci.media.size.toInt()) {
                            val mi = ci.media.get(i)
                            if (mi.type != pjmedia_type.PJMEDIA_TYPE_AUDIO) continue
                            val codec = try { call.getStreamInfo(mi.index.toLong())?.codecName } catch (_: Throwable) { "" }
                            if (!codec.isNullOrBlank()) {
                                val clean = codec.trim().uppercase()
                                _callSession.value = _callSession.value?.copy(negotiatedCodec = clean)
                                log("reconnectAudioPathForCall: re-negotiated codec = $clean", false)
                            }
                            break
                        }
                    } catch (e: Throwable) {
                        log("reconnectAudioPathForCall: codec refresh failed: ${e.message}", true)
                    }
                } catch (e: Throwable) {
                    log("reconnectAudioPathForCall failed for callId=$callId: ${e.message}", true)
                }
            }
        }
    }

    fun getAvailableCodecs(): List<com.ipdial.data.model.CodecInfo> {
        val ep = endpoint ?: return emptyList()
        // May be called from UI/IO threads (ViewModel). The native pjsua2 call
        // requires the calling thread to be registered with PJSIP — otherwise
        // pj_mutex assertions can abort the process.
        registerCurrentThreadEx()
        return synchronized(pjsipLock) {
            try {
                val codecs = ep.codecEnum()
                val result = mutableListOf<com.ipdial.data.model.CodecInfo>()
                for (i in 0 until codecs.size.toInt()) {
                    val codec = codecs.get(i)
                    val codecId = codec.codecId
                    val nameLower = codecId.lowercase()
                    val priority = codec.priority
                    val isAvailable = priority > 0.toShort()

                    val quality = when {
                        nameLower.contains("g722") -> com.ipdial.data.model.CodecQuality.Excellent
                        nameLower.contains("pcma") || nameLower.contains("pcmu") -> com.ipdial.data.model.CodecQuality.Fair
                        nameLower.contains("gsm") -> com.ipdial.data.model.CodecQuality.Low
                        else -> com.ipdial.data.model.CodecQuality.Minimal
                    }

                    val (bandwidth, mos) = when {
                        nameLower.contains("opus") -> 6 to 4.3f
                        nameLower.contains("g722") -> 48 to 4.0f
                        nameLower.contains("pcma") -> 64 to 4.1f
                        nameLower.contains("pcmu") -> 64 to 4.1f
                        nameLower.contains("gsm") -> 13 to 3.5f
                        else -> 0 to 0f
                    }

                    // pjsip 2.5.0 exposes no CodecParam via pjsua2; derive display metadata from id.
                    var clockRate = 0L
                    var channelCount = 1L
                    var frameLength = 20L
                    when {
                        nameLower.contains("pcma") || nameLower.contains("pcmu") -> clockRate = 8000
                        nameLower.contains("g722") -> clockRate = 16000
                        nameLower.contains("g729") || nameLower.contains("gsm") -> clockRate = 8000
                        nameLower.contains("opus") -> clockRate = 48000
                    }

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
    }

    /**
     * Closes the PJSIP sound device so the mic (AudioRecord) is released.
     * Called after a call ends so the mic isn't held until the next call.
     * Must run on a PJSIP-registered thread; the next call's
     * forceAudioDevicesForCall() will re-open it automatically.
     */
    fun releaseSoundDevice() {
        runOnPjsipThread {
            registerCurrentThreadEx()
            try {
                val ep = endpoint ?: return@runOnPjsipThread
                val adm = ep.audDevManager()
                if (adm.sndIsActive()) {
                    adm.setNullDev()
                    logEx("releaseSoundDevice: sound device closed — mic released", false)
                }
            } catch (e: Throwable) {
                logEx("releaseSoundDevice failed: ${e.message}", true)
            }
        }
    }

    fun setCodecPriority(codecId: String, priority: Short) {
        // May be called from UI/IO threads (ViewModel). Register the calling
        // thread with PJSIP before touching native endpoint objects.
        registerCurrentThreadEx()
        synchronized(pjsipLock) {
            val ep = endpoint ?: return
            try {
                ep.codecSetPriority(codecId, priority)
                log("Codec priority set: $codecId -> $priority")
            } catch (e: Exception) {
                log("Error setting codec priority: ${e.message}", true)
            }
        }
    }

    fun destroy() {
        val threadToStop = pjsipThread
        runOnPjsipThread {
            registerCurrentThreadEx()
            try {
                callMap.values.forEach { it.delete() }
                callMap.clear()
                accountMap.values.forEach { it.delete() }
                accountMap.clear()

                recorder?.delete()
                recorder = null

                transportManager.udpTransportId = -1
                transportManager.tcpTransportId = -1
                transportManager.tlsTransportId = -1

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
                log("destroy: PJSIP endpoint destroyed", false)
            } catch (e: Throwable) {
                log("destroy failed: ${e.message}", true)
            } finally {
                // Teardown complete; stop the worker thread so the process can exit.
                stopPjsipThread()
            }
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
