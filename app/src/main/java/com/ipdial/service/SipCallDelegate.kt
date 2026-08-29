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
                    SipEngine.recorder?.delete()
                    SipEngine.recorder = null
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
                    Handler(Looper.getMainLooper()).post {
                        try {
                            SipEngine.registerCurrentThreadEx()
                            callToDelete.delete()
                        } catch (e: Throwable) {
                            Log.e("SipEngine", "Failed to delete call on main loop", e)
                        }
                    }
                }
            } else {
                log("ONCALLSTATE ELSE: callId=$currentCallId newState=$newState sessionBefore=${_callSession.value?.state}", false)
                if (_callSession.value != null) {
                    _callSession.value = _callSession.value?.copy(state = newState, callId = currentCallId)
                    log("ONCALLSTATE ELSE: callId=$currentCallId sessionAfter=${_callSession.value?.state}", false)
                }

                if (newState == CallState.CONFIRMED && !mediaDumpScheduled) {
                    mediaDumpScheduled = true
                    log("ONCALLSTATE CONFIRMED: scheduling media dump in 6s", false)
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            SipEngine.registerCurrentThreadEx()
                            log("=== MEDIA DUMP (6s post-CONFIRMED) callId=$currentCallId ===", false)
                            val dump = try { this.dump(true, "") } catch (e: Throwable) { "call dump failed: ${e.message}" }
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
                        } catch (e: Throwable) {
                            log("media dump failed: ${e.message}", true)
                        }
                    }, 6000L)
                }

                if ((newState == CallState.EARLY || newState == CallState.CONFIRMED) && !audioWatchdogActive) {
                    startAudioWatchdog()
                }

                // CRITICAL FIX: Force audio devices and EC setup when call becomes CONFIRMED
                // This ensures the audio path is properly established
                if (newState == CallState.CONFIRMED) {
                    log("ONCALLSTATE: Call confirmed, forcing audio devices and EC setup", false)
                    try {
                        SipEngine.forceAudioDevicesForCall()
                        SipEngine.forceEcForCallAudio()
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
            try {
                if (_callSession.value != null) {
                    log("onCallState error safety net: force-nulling callSession", false)
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
                        log("ONCALLTSXSTATE: Disconnect detected for callId=$currentCallId, executing local hangup cleanup", false)
                        val statusCode = if (ci != null) safeStatusCode(ci) else 0
                        val statusReason = ci?.lastReason
                        if (statusCode > 0 || !statusReason.isNullOrBlank()) {
                            SipEngine.pendingDisconnectInfo = Pair(if (statusCode > 0) statusCode else null, statusReason)
                        }
                        try { SipEngine.onCallDisconnected?.invoke(currentCallId) } catch (_: Throwable) {}
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
