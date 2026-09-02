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
                try { SipEngine.onCallDisconnected?.invoke(currentCallId) } catch (_: Throwable) {}
                callMap.remove(currentCallId)
                if (_callSession.value?.callId == currentCallId) {
                    _callSession.value = null
                }
                SipConnectionService.disconnectCall(currentCallId, android.telecom.DisconnectCause.REMOTE)
                return
            }

            if (ci == null) {
                log("Call info is null for call $currentCallId", true)
                try { SipEngine.onCallDisconnected?.invoke(currentCallId) } catch (_: Throwable) {}
                callMap.remove(currentCallId)
                if (_callSession.value?.callId == currentCallId) {
                    _callSession.value = null
                }
                SipConnectionService.disconnectCall(currentCallId, android.telecom.DisconnectCause.REMOTE)
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

                // Preserve disconnect code/reason for SipService.observeCallState() to log & toast
                if (statusCode > 0 || !statusReason.isNullOrBlank()) {
                    SipEngine.pendingDisconnectInfo = Pair(if (statusCode > 0) statusCode else null, statusReason)
                }

                try {
                    SipEngine.onCallDisconnected?.invoke(currentCallId)
                } catch (e: Throwable) {
                    log("onCallDisconnected callback failed: ${e.message}", true)
                }

                // CRITICAL FIX: Remove from callMap BEFORE nulling session to prevent race conditions
                callMap.remove(currentCallId)
                log("ONCALLSTATE DISCONNECT: callId=$currentCallId removed from callMap, callMapSize=${callMap.size}", false)

                // Null the session immediately
                _callSession.value = null
                log("ONCALLSTATE DISCONNECT: callId=$currentCallId session nulled", false)

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

                if (!_isDeleteScheduled) {
                    _isDeleteScheduled = true
                    val callToDelete = this
                    // Native delete() must run on the PJSIP thread (serialized), NOT
                    // on the main thread: the main thread performing a native Call
                    // delete while the SIP worker is inside transaction processing
                    // corrupts dialog mutex ownership and aborts the process.
                    SipEngine.runOnPjsipThread {
                        try {
                            // Guard: if the endpoint is already destroyed (e.g. SipEngine.destroy()
                            // ran from SipService.onDestroy), deleting the native Call would
                            // SIGSEGV. The native object will be cleaned up by
                            // SipEngine.destroy() anyway — skip the delete here.
                            val ep = SipEngine.endpoint
                            if (ep == null) {
                                log("ONCALLSTATE DISCONNECT: endpoint already destroyed — skipping native delete for callId=$currentCallId", false)
                                return@runOnPjsipThread
                            }
                            SipEngine.registerCurrentThreadEx()
                            synchronized(SipEngine.pjsipLock) {
                                callToDelete.delete()
                            }
                        } catch (e: Throwable) {
                            Log.e("SipEngine", "Failed to delete call on PJSIP thread", e)
                        }
                    }
                }
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

                // CRITICAL FIX: Force audio devices and EC setup when call becomes CONFIRMED
                // This ensures the audio path is properly established.
                //
                // IMPORTANT: forceEcForCallAudio() calls pjsua_set_ec (setEcOptions),
                // and forceAudioDevicesForCall() may call setCaptureDev/setPlaybackDev.
                // Both RESTART the sound device, which destroys any startTransmit
                // bridges that onCallMediaState established earlier (e.g. during EARLY).
                // onCallMediaState does not re-fire after CONFIRMED, so without
                // re-bridging here the microphone stops reaching the remote — the far
                // end hears nothing after the call connects. So re-establish the
                // audio path with fresh media AFTER forcing.
                if (newState == CallState.CONFIRMED) {
                    log("ONCALLSTATE: Call confirmed, forcing audio devices and EC setup", false)
                    try {
                        SipEngine.forceAudioDevicesForCall()
                        SipEngine.forceEcForCallAudio()
                        // Re-bridge mic→call and call→speaker with the freshly opened
                        // sound device (the forces above restarted it).
                        SipEngine.reconnectAudioPathForCall(currentCallId)
                    } catch (e: Throwable) {
                        log("Failed to force audio setup on CONFIRMED: ${e.message}", true)
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
                val ci = try { info } catch (_: Throwable) { null }
                if (ci == null || ci.state == pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED || ci.state == pjsip_inv_state.PJSIP_INV_STATE_NULL) {
                    val session = _callSession.value
                    if (session != null && session.callId == currentCallId) {
                        log("ONCALLTSXSTATE: Disconnect detected for callId=$currentCallId, executing cleanup", false)
                        val statusCode = if (ci != null) safeStatusCode(ci) else 0
                        val statusReason = ci?.lastReason
                        if (statusCode > 0 || !statusReason.isNullOrBlank()) {
                            SipEngine.pendingDisconnectInfo = Pair(if (statusCode > 0) statusCode else null, statusReason)
                        }
                        try { SipEngine.onCallDisconnected?.invoke(currentCallId) } catch (_: Throwable) {}
                        callMap.remove(currentCallId)
                        _callSession.value = null
                        // Check localHangupCauses first — if this was a local hangup (BYE/CANCEL sent by us),
                        // use the locally recorded cause instead of always defaulting to REMOTE.
                        val disconnectCause = SipEngine.localHangupCauses.remove(currentCallId)
                            ?: android.telecom.DisconnectCause.REMOTE
                        log("ONCALLTSXSTATE: callId=$currentCallId telecomCause=$disconnectCause (wasLocal=${disconnectCause != android.telecom.DisconnectCause.REMOTE})", false)
                        SipConnectionService.disconnectCall(currentCallId, disconnectCause)
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

            if (currentCallId != ecEnforcedForCallId) {
                ecEnforcedForCallId = currentCallId
                SipEngine.forceAudioDevicesForCall()
                SipEngine.forceEcForCallAudio()
            }

            val adm = endpoint()?.audDevManager()
            val captureMedia = adm?.captureDevMedia
            val playbackMedia = adm?.playbackDevMedia

            for (i in 0 until ci.media.size.toInt()) {
                try {
                    val mi = ci.media.get(i)
                    if (mi.type == pjmedia_type.PJMEDIA_TYPE_AUDIO &&
                        mi.status == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE) {
                        val aud = AudioMedia.typecastFromMedia(getMedia(mi.index.toLong()))

                        val currentSession = _callSession.value
                        val isEmulator = DeviceUtil.isEmulator()
                        val baseGain = if (isEmulator) SipAudioController.MIC_GAIN_EMULATOR else SipAudioController.MIC_GAIN_REAL
                        val micLevel = if (currentSession?.isMuted == true) 0f else baseGain
                        val speakerLevel = currentSession?.rxVolume ?: 2.5f

                        aud.adjustTxLevel(micLevel)
                        aud.adjustRxLevel(speakerLevel)

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
        } catch (e: Throwable) {
            log("onCallMediaState failed: ${e.message}", true)
        }
    }
}
