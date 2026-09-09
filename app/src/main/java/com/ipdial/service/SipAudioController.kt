package com.ipdial.service

import android.util.Log
import com.ipdial.data.model.CallSession
import com.ipdial.util.DeviceUtil
import kotlinx.coroutines.flow.MutableStateFlow
import org.pjsip.pjsua2.*

/**
 * Audio control for SIP calls.
 * Extracted from [SipEngine] — handles mute, speaker, volume, recording, DTMF, and hold.
 */
object SipAudioController {

    private const val TAG = "SipEngine"
    const val MIC_GAIN_REAL = 1.2f
    const val MIC_GAIN_EMULATOR = 2.5f

    /**
     * Default in-app listening volume on the 0..6 volume-bar scale. Maps to
     * PJSIP unity (1.0) via [callVolumeToPjsipLevel].
     */
    const val DEFAULT_RX_VOLUME = 3f

    /**
     * Maps the app's 0..6 volume-bar scale to PJSIP's conference-bridge level,
     * where 1.0 = no adjustment, 0 = muted, 2.0 = amplified two times. Feeding
     * the raw 0..6 factor into the bridge would over-amp/clip outgoing audio.
     */
    fun callVolumeToPjsipLevel(factor: Float): Float = (factor / 3f).coerceIn(0f, 2f)

    fun setMute(muted: Boolean) {
        SipEngine.runOnPjsipThread {
            SipEngine.registerCurrentThreadEx()
            SipEngine._callSession.value?.let { session ->
                SipEngine.callMap[session.callId]?.let { call ->
                    try {
                        val ci = call.info
                        for (i in 0 until ci.media.size.toInt()) {
                            val mi = ci.media.get(i)
                            if (mi.type == pjmedia_type.PJMEDIA_TYPE_AUDIO &&
                                mi.status == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE) {
                                val aud = AudioMedia.typecastFromMedia(call.getMedia(mi.index.toLong()))
                                if (muted) {
                                    // Mute OUR microphone (TX) so the remote stops hearing us.
                                    aud.adjustRxLevel(0f)
                                } else {
                                    val isEmulator = DeviceUtil.isEmulator()
                                    val baseGain = if (isEmulator) MIC_GAIN_EMULATOR else MIC_GAIN_REAL
                                    aud.adjustRxLevel(baseGain)
                                }
                            }
                        }
                        SipEngine._callSession.value = session.copy(isMuted = muted)
                    } catch (e: Throwable) {
                        SipEngine.logEx("setMute failed: ${e.message}", true)
                    }
                }
            }
        }
    }

    fun setSpeaker(enabled: Boolean) {
        SipEngine.logEx("setSpeaker: $enabled", false)
        SipEngine._callSession.value = SipEngine._callSession.value?.copy(isSpeaker = enabled)
    }

    fun setCallVolume(factor: Float) {
        SipEngine.runOnPjsipThread {
            SipEngine.registerCurrentThreadEx()
            SipEngine.logEx("Adjusting call volume (listening) to factor: $factor", false)
            val level = callVolumeToPjsipLevel(factor)
            SipEngine._callSession.value?.let { session ->
                SipEngine._callSession.value = session.copy(rxVolume = factor)
                SipEngine.callMap[session.callId]?.let { call ->
                    try {
                        val ci = call.info
                        for (i in 0 until ci.media.size.toInt()) {
                            val mi = ci.media.get(i)
                            if (mi.type == pjmedia_type.PJMEDIA_TYPE_AUDIO &&
                                mi.status == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE) {
                                val aud = AudioMedia.typecastFromMedia(call.getMedia(mi.index.toLong()))
                                // adjustTxLevel on the CALL port maps to pjsua_conf_adjust_rx_level
                                // (call -> bridge), i.e. the REMOTE's voice arriving to our speaker.
                                // adjustRxLevel would change the outgoing (TX) gain instead, which
                                // is what the OTHER caller hears — the classic swapped-direction bug.
                                aud.adjustTxLevel(level)
                            }
                        }
                    } catch (e: Throwable) {
                        SipEngine.logEx("setCallVolume failed: ${e.message}", true)
                    }
                }
            }
        }
    }

    fun startRecording(filePath: String) {
        SipEngine.startRecording(filePath)
        SipEngine._callSession.value = SipEngine._callSession.value?.copy(isRecording = true, isRecordingPending = false)
    }

    fun stopRecording() {
        SipEngine.stopRecording()
        SipEngine._callSession.value = SipEngine._callSession.value?.copy(isRecording = false, isRecordingPending = false)
    }

    /** Start recording as soon as the call becomes active, without interrupting dialing. */
    fun startRecordingWhenActive(filePath: String) {
        SipEngine._callSession.value = SipEngine._callSession.value?.copy(isRecordingPending = true)
        SipEngine.startRecordingWhenActive(filePath)
    }

    fun sendDtmf(digit: Char) {
        SipEngine.runOnPjsipThread {
            SipEngine.registerCurrentThreadEx()
            SipEngine._callSession.value?.let { session ->
                SipEngine.callMap[session.callId]?.let { call ->
                    try { call.dialDtmf(digit.toString()) } catch (e: Throwable) {
                        SipEngine.logEx("sendDtmf failed: ${e.message}", true)
                    }
                }
            }
        }
    }

    fun holdCall(onHold: Boolean) {
        SipEngine.runOnPjsipThread {
            SipEngine.registerCurrentThreadEx()
            SipEngine._callSession.value?.let { session ->
                SipEngine.callMap[session.callId]?.let { call ->
                    try {
                        val prm = CallOpParam()
                        if (onHold) {
                            call.setHold(prm)
                            SipEngine._callSession.value = session.copy(isOnHold = true)
                        } else {
                            // Re-INVITE to unhold; pjsip does not always re-fire
                            // onCallMediaState after the reintive completes, so we
                            // Reopen the device first, then restore the media bridge.
                            call.reinvite(prm)
                            SipEngine._callSession.value = session.copy(isOnHold = false)
                            SipEngine.forceAudioDevicesForCall()
                            SipEngine.forceEcForCallAudio()
                            SipEngine.reconnectAudioPathForCall(session.callId)
                        }
                    } catch (e: Throwable) {
                        SipEngine.logEx("holdCall failed: ${e.message}", true)
                    }
                }
            }
        }
    }
}
