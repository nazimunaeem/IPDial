package com.ipdial.service

import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ipdial.data.model.CallDirection
import com.ipdial.data.model.CallSession
import com.ipdial.data.model.CallState
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

            log("Call $currentCallId state changed to ${ci.stateText} (code=${ci.lastStatusCode}, reason=${ci.lastReason})", false)
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
                log("ONCALLSTATE DISCONNECT BLOCK: callId=$currentCallId state=$newState code=${ci.lastStatusCode} reason=${ci.lastReason}", false)

                try {
                    SipEngine.onCallDisconnected?.invoke(currentCallId)
                } catch (e: Throwable) {
                    log("onCallDisconnected callback failed: ${e.message}", true)
                }

                callMap.remove(currentCallId)
                log("ONCALLSTATE DISCONNECT: callId=$currentCallId removed from callMap, callMapSize=${callMap.size}", false)

                _callSession.value = null
                log("ONCALLSTATE DISCONNECT: callId=$currentCallId session nulled", false)

                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        audioManager.mode = AudioManager.MODE_NORMAL
                        audioManager.isSpeakerphoneOn = false
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

            if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            }

            for (i in 0 until ci.media.size.toInt()) {
                try {
                    val mi = ci.media.get(i)
                    if (mi.type == pjmedia_type.PJMEDIA_TYPE_AUDIO &&
                        mi.status == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE) {
                        val aud = AudioMedia.typecastFromMedia(getMedia(mi.index.toLong()))

                        val currentSession = _callSession.value
                        val micLevel = if (currentSession?.isMuted == true) 0f else SipAudioController.VOLUME_BOOST_FACTOR
                        val speakerLevel = currentSession?.rxVolume ?: SipAudioController.VOLUME_BOOST_FACTOR

                        aud.adjustTxLevel(micLevel)
                        aud.adjustRxLevel(speakerLevel)

                        aud.startTransmit(endpoint()?.audDevManager()?.playbackDevMedia)
                        endpoint()?.audDevManager()?.captureDevMedia?.startTransmit(aud)

                        SipEngine.recorder?.let {
                            aud.startTransmit(it)
                            endpoint()?.audDevManager()?.captureDevMedia?.startTransmit(it)
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
