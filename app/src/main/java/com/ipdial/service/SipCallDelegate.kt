package com.ipdial.service

import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ipdial.data.model.CallDirection
import com.ipdial.data.model.CallSession
import com.ipdial.data.model.CallState
import com.ipdial.util.DeviceUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.pjsip.pjsua2.*

/**
 * PJSIP Call callback delegate.
 * Extracted from [SipEngine] inner class to top-level.
 */
class SipCallDelegate(
    acct: Account,
    callId: Int = -1,
    private val callMap: MutableMap<Int, SipCallDelegate>,
    private val _callSession: MutableStateFlow<CallSession?>,
    private val audioManager: AudioManager,
    private val endpoint: () -> Endpoint?,
    private val log: (String, Boolean) -> Unit
) : Call(acct, callId) {

    private var _isDeleteScheduled = false
    private var ecEnforcedForCallId = -1
    private var mediaDumpScheduled = false
    private var audioWatchdogActive = false
    // Last codec detected for this call — used to avoid redundant state updates.
    private var detectedCodec: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Samsung OneUI Telecom flips the device into the cellular "SIM CALL" audio mode
     * the moment a connection becomes ACTIVE (logcat: "Audio focus entering SIM CALL state").
     * In that mode the telephony HAL owns the microphone and our OpenSL capture reads
     * digital silence, so the far end hears nothing (DTMF still passes since it bypasses
     * the mic). This watchdog reclaims the audio path for VoIP for the duration of the call.
     */
    private val audioWatchdog = object : Runnable {
        override fun run() {
            if (!audioWatchdogActive) return
            try {
                val curMode = audioManager.mode
                if (curMode != AudioManager.MODE_IN_COMMUNICATION) {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    log("AUDIO WATCHDOG: mode was $curMode, restored MODE_IN_COMMUNICATION", false)
                }
                if (audioManager.isMicrophoneMute &&
                    _callSession.value?.isMuted != true) {
                    audioManager.isMicrophoneMute = false
                    log("AUDIO WATCHDOG: cleared microphone mute", false)
                }
            } catch (e: Throwable) {
                log("audio watchdog error: ${e.message}", true)
            }
            if (audioWatchdogActive) mainHandler.postDelayed(this, 700L)
        }
    }

    private fun startAudioWatchdog() {
        if (audioWatchdogActive) return
        audioWatchdogActive = true
        log("AUDIO WATCHDOG: started", false)
        mainHandler.post(audioWatchdog)
    }

    private fun safeStatusCode(ci: org.pjsip.pjsua2.CallInfo): Int {
        return try {
            ci.lastStatusCode?.swigValue() ?: 0
        } catch (e: Throwable) {
            0
        }
    }

    private fun stopAudioWatchdog() {
        if (!audioWatchdogActive) return
        audioWatchdogActive = false
        mainHandler.removeCallbacks(audioWatchdog)
        log("AUDIO WATCHDOG: stopped", false)
    }

    /**
     * Null the session after a short delay so the user hears the busy tone or a
     * remote announcement before the screen closes (Busy 486 / Request Terminated 487
     * gets 2s, everything else 500ms). Registers a grace deadline on SipEngine so the
     * 1s stale-session watchdog in the ViewModel does not preempt the hold. The
     * callId re-check ensures a pending null from an earlier call can never close a
     * newer call's session.
     */
    private fun scheduleSessionNull(callId: Int, statusCode: Int) {
        val isBusy = statusCode == 486 || statusCode == 487
        val delayMs = if (isBusy) 2000L else 500L
        SipEngine.beginDisconnectHold(delayMs)
        mainHandler.postDelayed({
            SipEngine.disconnectGraceUntilMs = 0L
            // Only null if the current session still belongs to this call.
            if (_callSession.value?.callId == callId) {
                _callSession.value = null
                log("scheduleSessionNull: callId=$callId session nulled after ${delayMs}ms", false)
            }
        }, delayMs)
    }

    /**
     * Schedules the optional 2s-delayed native delete once and only once, no matter
     * which callback (onCallState or onCallTsxState) observes the disconnect first.
     * Without this, a disconnect seen only by onCallTsxState (e.g. remote BYE where
     * onCallState's info() throws) would never free the native Call object — and a
     * second lifecycle would clone the same dialog.
     */
    internal fun scheduleNativeDelete(callId: Int) {
        if (_isDeleteScheduled) return
        _isDeleteScheduled = true
        // Delay native delete by 2000ms (2s) so PJSIP worker finishes sending
        // 200 OK for remote BYE over UDP/TCP before destroying internal C++ struct.
        // 500ms was too short for unreliable UDP networks; 2s provides
        // sufficient margin for retransmissions.
        val callToDelete = this
        mainHandler.postDelayed({
            SipEngine.runOnPjsipThread {
                try {
                    val ep = SipEngine.endpoint
                    if (ep == null) {
                        log("DELAYED DELETE: endpoint already destroyed — skipping native delete for callId=$callId", false)
                        return@runOnPjsipThread
                    }
                    SipEngine.registerCurrentThreadEx()
                    synchronized(SipEngine.pjsipLock) {
                        try {
                            callToDelete.delete()
                            log("DELAYED DELETE: native call $callId deleted", false)
                        } catch (e: Throwable) {
                            // Double-delete from a raced hangup is benign now that the
                            // shared _isDeleteScheduled guard serializes the schedule.
                            log("DELAYED DELETE: delete threw for callId=$callId: ${e.message}", true)
                        }
                    }
                } catch (e: Throwable) {
                    Log.e("SipEngine", "Failed to delete call on PJSIP thread", e)
                }
            }
        }, 2000L)
    }

    override fun onCallState(prm: OnCallStateParam) {
        val currentCallId = try { getId() } catch (e: Throwable) {
            log("ONCALLSTATE ENTRY: getId() failed: ${e.message}", true)
            _callSession.value = null
            return
        }
        @Suppress("DEPRECATION")
        val threadInfo = "${Thread.currentThread().name}[${Thread.currentThread().id}]"
        log("ONCALLSTATE ENTRY: callId=$currentCallId thread=$threadInfo session=${_callSession.value?.callId}/${_callSession.value?.state} callMapSize=${callMap.size} callMapHasId=${callMap.containsKey(currentCallId)}", false)
        try {

            val ci = try { info } catch (e: Throwable) {
                log("Failed to get call info for call $currentCallId: ${e.message}", true)
                // Don't aggressively clean up here — info() often throws during
                // remote BYE processing because the call is mid-destruction.
                // Let onCallTsxState handle the cleanup after SIP transaction completes.
                log("ONCALLSTATE: info() threw, deferring cleanup to onCallTsxState", false)
                return
            }

            if (ci == null) {
                log("Call info is null for call $currentCallId", true)
                // Same as above: defer to onCallTsxState
                log("ONCALLSTATE: ci is null, deferring cleanup to onCallTsxState", false)
                return
            }

            log("Call $currentCallId state changed to ${ci.stateText} (code=${safeStatusCode(ci)}, reason=${ci.lastReason})", false)
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
                val statusCode = safeStatusCode(ci)
                val statusReason = ci.lastReason
                log("ONCALLSTATE DISCONNECT BLOCK: callId=$currentCallId state=$newState code=$statusCode reason=$statusReason", false)
                stopAudioWatchdog()
                // Clear any DTMF digits still buffered for this call so they are not
                // replayed into the next call.
                SipAudioController.clearPendingDtmf()

                // Preserve disconnect code/reason for SipService.observeCallState() to log & toast
                if (statusCode > 0 || !statusReason.isNullOrBlank()) {
                    SipEngine.pendingDisconnectInfo = Pair(if (statusCode > 0) statusCode else null, statusReason)
                }

                try {
                    SipEngine.onCallDisconnected?.invoke(currentCallId)
                } catch (e: Throwable) {
                    log("onCallDisconnected callback failed: ${e.message}", true)
                }

                // Stamp session DISCONNECTED immediately so UI dismisses.
                // Do NOT remove from callMap or null _callSession here —
                // let scheduleSessionNull handle cleanup after grace period.
                // This ensures the native call object stays alive long enough
                // for PJSIP to send 200 OK to remote BYE.
                if (_callSession.value?.callId == currentCallId) {
                    _callSession.value = _callSession.value?.copy(
                        state = CallState.DISCONNECTED,
                        disconnectCode = if (statusCode > 0) statusCode else null,
                        disconnectReason = statusReason?.takeIf { it.isNotBlank() }
                    )
                    log("ONCALLSTATE DISCONNECT: session stamped DISCONNECTED for callId=$currentCallId, UI will dismiss", false)
                }

                // Keep session alive briefly so user hears busy tone/announcement before screen closes.
                // Especially important for BUSY (486) and REQUEST TERMINATED (487).
                scheduleSessionNull(currentCallId, statusCode)

                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        audioManager.mode = AudioManager.MODE_NORMAL
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            audioManager.clearCommunicationDevice()
                        } else {
                            @Suppress("DEPRECATION")
                            audioManager.isSpeakerphoneOn = false
                        }
                    } catch (e: Throwable) {
                        log("Failed to reset audio manager: ${e.message}", true)
                    }
                }

                try {
                    synchronized(SipEngine.pjsipLock) {
                        SipEngine.recorder?.delete()
                        SipEngine.recorder = null
                    }
                } catch (e: Throwable) {
                    log("Failed to delete recorder on disconnect: ${e.message}", true)
                }

                val disconnectCause = SipEngine.localHangupCauses.remove(currentCallId)
                    ?: android.telecom.DisconnectCause.REMOTE
                log("ONCALLSTATE DISCONNECT: callId=$currentCallId telecomCause=$disconnectCause (wasLocal=${disconnectCause != android.telecom.DisconnectCause.REMOTE})", false)
                SipConnectionService.disconnectCall(currentCallId, disconnectCause)

                scheduleNativeDelete(currentCallId)
            } else {
                log("ONCALLSTATE ELSE: callId=$currentCallId newState=$newState sessionBefore=${_callSession.value?.state}", false)
                if (_callSession.value != null) {
                    _callSession.value = _callSession.value?.copy(state = newState, callId = currentCallId)
                    log("ONCALLSTATE ELSE: callId=$currentCallId sessionAfter=${_callSession.value?.state}", false)
                }

                // The media-dump diagnostic is a debug-only tool that records mic/TX
                // probes and samples levels 6s after the call goes CONFIRMED.
                //
                // It MUST NOT run in production:
                //  * It calls native `this.dump()` on the Main thread and then blocks the
                //    Main thread for ~7s (300ms + 12 x 500ms sleeps). Blocking the Main
                //    thread for that long after a call triggers an ANR ("app isn't
                //    responding") and the process gets stopped — exactly the
                //    "stops after making a call" symptom.
                //  * It touches the native Call after the call may have disconnected and
                //    been deleted, which can SIGSEGV the process.
                //
                // So: gate it to debug builds, run it on a background thread, and bail
                // out as soon as the call is no longer active.
                if (newState == CallState.CONFIRMED && !mediaDumpScheduled && com.ipdial.BuildConfig.DEBUG
                        && System.getProperty("ipdial.mediaDump") == "1") {
                    mediaDumpScheduled = true
                    log("ONCALLSTATE CONFIRMED: scheduling media dump in 6s (debug only; ipdial.mediaDump=1)", false)
                    val dumpCall = this
                    val dumpCallId = currentCallId
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            delay(6000)
                            // Only proceed if the call is still live; otherwise the native
                            // Call has already been deleted and touching it would crash.
                            if (!callMap.containsKey(dumpCallId)) {
                                log("MEDIA DUMP: call $dumpCallId already disconnected — skipping", false)
                                return@launch
                            }
                            if (SipEngine.endpoint == null) {
                                log("MEDIA DUMP: endpoint already destroyed — skipping", false)
                                return@launch
                            }
                            // The dump touches native pjsua2 objects from a coroutine
                            // thread. It MUST be serialized with everything else that
                            // touches pjsua2 (worker callbacks are guarded by the same
                            // monitor), otherwise it races the SIP worker and aborts the
                            // process with a pj_mutex_unlock assertion.
                            synchronized(SipEngine.pjsipLock) {
                                SipEngine.registerCurrentThreadEx()
                                log("=== MEDIA DUMP (6s post-CONFIRMED) callId=$dumpCallId ===", false)
                                val dump = try { dumpCall.dump(true, "") } catch (e: Throwable) { "call dump failed: ${e.message}" }
                                dump.split('\n').forEach { line -> log(line, false) }
                                Thread.sleep(300)
                                log("=== MIC VS TX LEVELS (sample every 500ms while speaking) ===", false)
                                val capMedia = try { SipEngine.endpoint?.audDevManager()?.captureDevMedia } catch (e: Throwable) { null }
                                val callMedia = try { SipEngine.endpoint?.audDevManager()?.playbackDevMedia } catch (e: Throwable) { null }
                                var recMic: org.pjsip.pjsua2.AudioMediaRecorder? = null
                                var recTx: org.pjsip.pjsua2.AudioMediaRecorder? = null
                                try {
                                    val probeDir = SipEngine.probeDir()
                                    if (probeDir != null) {
                                        recMic = org.pjsip.pjsua2.AudioMediaRecorder().also { it.createRecorder(java.io.File(probeDir, "probe_mic.wav").absolutePath) }
                                        recTx = org.pjsip.pjsua2.AudioMediaRecorder().also { it.createRecorder(java.io.File(probeDir, "probe_tx.wav").absolutePath) }
                                        capMedia?.startTransmit(recMic)
                                        callMedia?.startTransmit(recTx)
                                        log("PROBE: recording mic+tx to ${probeDir.absolutePath}", false)
                                    } else {
                                        log("PROBE: probeDir unavailable", false)
                                    }
                                } catch (e: Throwable) {
                                    log("PROBE: recorder setup failed: ${e.message}", true)
                                }
                                if (capMedia != null || callMedia != null) {
                                    repeat(12) { i ->
                                        if (!callMap.containsKey(dumpCallId)) {
                                            log("MEDIA DUMP: call $dumpCallId ended mid-sampling — stopping", false)
                                            return@synchronized
                                        }
                                        try {
                                            val capRx = capMedia?.getRxLevel() ?: -1
                                            val callTx = callMedia?.getTxLevel() ?: -1
                                            log("LEVELS[$i]: captureRx=$capRx callTx=$callTx", false)
                                        } catch (e: Throwable) {
                                            log("level sample $i failed: ${e.message}", true)
                                        }
                                        try { Thread.sleep(500) } catch (e: InterruptedException) { /* ignore */ }
                                    }
                                } else {
                                    log("LEVELS: could not obtain capture or call media", false)
                                }
                                try {
                                    recMic?.let { r ->
                                        try { capMedia?.stopTransmit(r) } catch (e: Throwable) {}
                                        r.delete()
                                    }
                                    recTx?.let { r ->
                                        try { callMedia?.stopTransmit(r) } catch (e: Throwable) {}
                                        r.delete()
                                    }
                                    log("PROBE: cleaned up recorders", false)
                                } catch (e: Throwable) {
                                    log("PROBE: cleanup failed: ${e.message}", true)
                                }
                            }
                        } catch (e: Throwable) {
                            log("media dump failed: ${e.message}", true)
                        }
                    }
                }

                if ((newState == CallState.EARLY || newState == CallState.CONFIRMED) && !audioWatchdogActive) {
                    startAudioWatchdog()
                }

                if (newState == CallState.CONFIRMED) {
                    log("ONCALLSTATE: Call confirmed, ensuring audio path is active", false)
                    // A recording armed during dialing starts now that the call is
                    // received: clear the pending flag so the UI shows "Recording".
                    _callSession.value = _callSession.value?.copy(isRecordingPending = false)
                    try {
                        // Restarting the sound device at CONFIRMED time is essential:
                        // during EARLY→CONFIRMED the Android audio framework may re-route
                        // or re-open the mic, which silently invalidates the
                        // startTransmit bridges that onCallMediaState() set up during
                        // EARLY. Re-selecting the capture/playback device (auto = -1)
                        // restarts PJSIP's sound device and re-applies EC settings,
                        // then reconnectAudioPathForCall() re-establishes the bridges
                        // with fresh media objects against the live capture device.
                        SipEngine.forceAudioDevicesForCall()
                        SipEngine.forceEcForCallAudio()
                        SipEngine.reconnectAudioPathForCall(currentCallId)
                        mainHandler.post {
                            SipEngine.audioRouter?.routeAudioToDefault()
                        }
                    } catch (e: Throwable) {
                        log("Failed to verify audio setup on CONFIRMED: ${e.message}", true)
                    }
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
                        log("ONCALLSTATE ELSE: callId=$currentCallId telecom connection updated to $newState", false)
                    } catch (e: Throwable) {
                        log("Failed to update telecom connection state: ${e.message}", true)
                    }
                } ?: log("ONCALLSTATE ELSE: callId=$currentCallId no telecom connection found", false)
            }
        } catch (e: Throwable) {
            log("ONCALLSTATE EXCEPTION: callId=$currentCallId error=${e.message}", true)
            // Removed: DO NOT null _callSession.value here! It hides the UI for a live call!
        }
    }

    override fun onCallTsxState(prm: OnCallTsxStateParam) {
        try {
            val currentCallId = try { getId() } catch (_: Throwable) { -1 }
            if (currentCallId != -1) {
                val callInfo = try { info } catch (_: Throwable) { null }
                if (callInfo == null || callInfo.state == pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED || callInfo.state == pjsip_inv_state.PJSIP_INV_STATE_NULL) {
                    val session = _callSession.value
                    if (session != null && session.callId == currentCallId) {
                        log("ONCALLTSXSTATE: Disconnect detected for callId=$currentCallId, executing cleanup", false)
                        SipAudioController.clearPendingDtmf()
                        val statusCode = if (callInfo != null) safeStatusCode(callInfo) else 0
                        val statusReason = callInfo?.lastReason
                        if (statusCode > 0 || !statusReason.isNullOrBlank()) {
                            SipEngine.pendingDisconnectInfo = Pair(if (statusCode > 0) statusCode else null, statusReason)
                        }
                        try { SipEngine.onCallDisconnected?.invoke(currentCallId) } catch (_: Throwable) {}
                        callMap.remove(currentCallId)
                        // IMMEDIATELY stamp DISCONNECTED (same as onCallState) so the
                        // remote call screen dismisses without waiting for the delayed null.
                        if (session.callId == currentCallId) {
                            _callSession.value = session.copy(
                                state = CallState.DISCONNECTED,
                                disconnectCode = if (statusCode > 0) statusCode else null,
                                disconnectReason = statusReason?.takeIf { it.isNotBlank() }
                            )
                            log("ONCALLTSXSTATE: session stamped DISCONNECTED for callId=$currentCallId, UI will dismiss", false)
                        }
                        // Use the shared delayed-null so the busy tone delay is respected here
                        // too, instead of closing the screen and cutting audio immediately.
                        scheduleSessionNull(currentCallId, statusCode)
                        // Check localHangupCauses first — if this was a local hangup (BYE/CANCEL sent by us),
                        // use the locally recorded cause instead of always defaulting to REMOTE.
                        val disconnectCause = SipEngine.localHangupCauses.remove(currentCallId)
                            ?: android.telecom.DisconnectCause.REMOTE
                        log("ONCALLTSXSTATE: callId=$currentCallId telecomCause=$disconnectCause (wasLocal=${disconnectCause != android.telecom.DisconnectCause.REMOTE})", false)
                        SipConnectionService.disconnectCall(currentCallId, disconnectCause)
                        // onCallState may have already handled the native delete; the
                        // guarded scheduleNativeDelete makes this safe either way. When
                        // this path is the ONLY disconnect signal (onCallState's info()
                        // threw and deferred), this is what frees the native Call object.
                        scheduleNativeDelete(currentCallId)
                    }
                }
            }
        } catch (e: Throwable) {
            log("onCallTsxState exception: ${e.message}", true)
        }
    }

    override fun onCallMediaState(prm: OnCallMediaStateParam) {
        // This callback runs on the PJSIP worker thread. It touches native call
        // media objects, so it must be serialized against any concurrent API calls
        // (mute/volume/DTMF on the PJSIP thread, speaker EC on the main thread).
        synchronized(SipEngine.pjsipLock) {
            onCallMediaStateLocked(prm)
        }
    }

    private fun onCallMediaStateLocked(prm: OnCallMediaStateParam) {
        try {
            val ci = try { info } catch (e: Throwable) {
                log("Failed to get call info in onCallMediaState: ${e.message}", true)
                return
            }

            if (ci == null) {
                log("Call info is null in onCallMediaState", true)
                return
            }

            try {
                if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                }
                audioManager.isMicrophoneMute = false
                SipEngine.audioRouter?.requestAudioFocus()
            } catch (e: Throwable) {
                log("Audio setup failed in onCallMediaState: ${e.message}", true)
            }

            val currentCallId = try { getId() } catch (_: Throwable) { -1 }
            log("onCallMediaState: callId=$currentCallId state=${ci.stateText} mediaCount=${ci.media.size}", false)

            val adm = endpoint()?.audDevManager()
            val captureMedia = adm?.captureDevMedia
            val playbackMedia = adm?.playbackDevMedia

            for (i in 0 until ci.media.size.toInt()) {
                try {
                    val mi = ci.media.get(i)
                    if (mi.type == pjmedia_type.PJMEDIA_TYPE_AUDIO &&
                        mi.status == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE) {
                        val aud = AudioMedia.typecastFromMedia(getMedia(mi.index.toLong()))

                        // NOTE: getMedTransportInfo() was REMOVED entirely — it causes
                        // a native SIGABRT (pj_sockaddr_get_addr assertion:
                        // "sa_family == PJ_AF_INET || PJ_AF_INET6") on this PJSIP 2.5
                        // build no matter when it is called (onCallMediaState OR
                        // CONFIRMED). The transport's socket address is never fully
                        // initialized in this build when ICE is enabled, so ANY call
                        // to getMedTransportInfo() aborts the process. ICE candidate
                        // diagnostics via this API are therefore impossible; the call
                        // flow must never touch it.

                        // Local audio-device-open failure signature (task 6):
                        // media went ACTIVE but the app could not open the
                        // capture/playback device (AudioRecord/AudioTrack error,
                        // permission, or a stale sound-device state). This is a
                        // LOCAL issue — NOT a network/NAT problem — and is a
                        // candidate cause of two-way silence even when signaling
                        // and RTP are fine.
                        if (captureMedia == null || playbackMedia == null) {
                            val missing = listOfNotNull(
                                "capture" to (captureMedia == null),
                                "playback" to (playbackMedia == null)
                            ).filter { it.second }.joinToString { it.first }
                            log(
                                "onCallMediaState: WARNING callId=$currentCallId media stream ACTIVE " +
                                    "but local audio device is NULL (missing: $missing) — " +
                                    "this is a local audio-device-open failure, NOT a network/NAT issue. " +
                                    "Fix the device-open path (AudioRecord/AudioTrack/permissions) — TURN will not fix this.",
                                true
                            )
                        }

                        val currentSession = _callSession.value
                        val isEmulator = DeviceUtil.isEmulator()
                        val baseGain = if (isEmulator) SipAudioController.MIC_GAIN_EMULATOR else SipAudioController.MIC_GAIN_REAL
                        val micLevel = if (currentSession?.isMuted == true) 0f else baseGain
                        val speakerLevel = currentSession?.rxVolume ?: SipAudioController.DEFAULT_RX_VOLUME

                        // Empirical pjsua2 gain direction (verified live on OPPO):
                        //   adjustTxLevel  = mic → remote (what the OTHER caller hears).
                        //   adjustRxLevel  = remote → us (listening volume).
                        // v1.1.5 used these exact directions and remote audio worked;
                        // the 1.1.6 swap broke remote audio. Restoring v1.1.5 mapping
                        // while keeping the /3 scale mapping on listening side.
                        aud.adjustTxLevel(micLevel)
                        aud.adjustRxLevel(SipAudioController.callVolumeToPjsipLevel(speakerLevel))

                        // CRITICAL FIX: Ensure bidirectional audio path
                        // 1. Remote audio (RX) -> local speaker
                        playbackMedia?.let { aud.startTransmit(it) }
                        // 2. Local mic (TX) -> remote
                        captureMedia?.let { it.startTransmit(aud) }

                        // Recording: both directions
                        SipEngine.recorder?.let { recorder ->
                            aud.startTransmit(recorder)
                            captureMedia?.let { it.startTransmit(recorder) }
                        }
                        
                        log("onCallMediaState: Audio path established for callId=$currentCallId (captureMedia=$captureMedia, playbackMedia=$playbackMedia)", false)

                        // Re-apply speaker/earpiece/BT routing AFTER the PJSIP sound
                        // device is open. The SipService observer fires routeAudioToDefault()
                        // 300ms after stateChanged, but PJSIP may open its AudioRecord
                        // LATER (after SDP exchange). If we don't re-route here, the OS
                        // hands the new AudioRecord to the earpiece regardless of what
                        // routeAudioToDefault() already set, causing silent speaker calls.
                        mainHandler.post {
                            SipEngine.audioRouter?.routeAudioToDefault()
                        }

                        // Detect and surface the negotiated codec to the UI.
                        val codecName = try {
                            getStreamInfo(mi.index.toLong())?.codecName ?: ""
                        } catch (_: Throwable) { "" }
                        if (codecName.isNotBlank()) {
                            val clean = codecName.trim().uppercase()
                            if (clean != detectedCodec) {
                                detectedCodec = clean
                                log("onCallMediaState: negotiated codec for callId=$currentCallId = $clean", false)
                                _callSession.value = _callSession.value?.copy(negotiatedCodec = clean)
                            }
                        }
                    }
                } catch (e: Throwable) {
                    log("Failed to process media state for stream $i: ${e.message}", true)
                }
            }

            // Flush any DTMF digits that were keyed while the media was still
            // being established (CALLING/EARLY). The stream is ACTIVE here, so
            // RFC2833 can now be sent.
            SipAudioController.flushPendingDtmf()

            // Task 7 — graceful degradation on media/turn failures.
            //
            // When ICE is enabled and a call NEEDS the TURN relay (symmetric NAT,
            // CGNAT, UDP blocked), media carries its own error state:
            // PJSUA_CALL_MEDIA_ERROR fires when ICE/TURN allocation or negotiation
            // fails (e.g. the 20 GB/month quota is exhausted, or TURN is
            // unreachable). That is exactly the scenario the task calls "silently
            // hang or stuck call screen" — the call is up in the signaling sense,
            // but NO media can ever flow.
            //
            // Note: we only act on media ERROR (native, tie-lifetime) — we never
            // touch the established call-disconnect handling in onCallState /
            // onCallTsxState, and we do NOT treat "capture/playback device null"
            // as a network error (that is a separate local-audio failure, logged
            // as WARNING above).
            if (ci.media.size > 0) {
                val anyMediaError = (0 until ci.media.size.toInt()).any { idx ->
                    try {
                        ci.media.get(idx).status == pjsua_call_media_status.PJSUA_CALL_MEDIA_ERROR
                    } catch (_: Throwable) { false }
                }
                val session = _callSession.value
                if (anyMediaError && session != null && session.callId == currentCallId) {
                    log(
                        "onCallMediaState: MEDIA ERROR callId=$currentCallId — " +
                            "ICE/TURN transport failed (quota exhausted, relay unreachable, or UDP blocked). " +
                            "Failing call with user-visible error instead of leaving a stuck call screen.",
                        true
                    )
                    SipEngine.mediaFailureToastShownFor = currentCallId
                    try {
                        hangup(CallOpParam(false))
                    } catch (e: Throwable) {
                        log("onCallMediaState: hangup after media error failed: ${e.message}", true)
                    }
                }
            }
        } catch (e: Throwable) {
            log("onCallMediaState failed: ${e.message}", true)
        }
    }
}
