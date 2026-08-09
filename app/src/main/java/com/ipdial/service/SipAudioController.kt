package com.ipdial.service

import android.util.Log
import com.ipdial.data.model.CallSession
import kotlinx.coroutines.flow.MutableStateFlow
import org.pjsip.pjsua2.*

/**
 * Audio control for SIP calls.
 * Extracted from [SipEngine] — handles mute, speaker, volume, recording, DTMF, and hold.
 */
object SipAudioController {

    private const val TAG = "SipEngine"
    const val VOLUME_BOOST_FACTOR = 1.0f

    fun setMute(muted: Boolean) {
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
                            if (muted) aud.adjustTxLevel(0f) else aud.adjustTxLevel(VOLUME_BOOST_FACTOR)
                        }
                    }
                    SipEngine._callSession.value = session.copy(isMuted = muted)
                } catch (e: Throwable) {
                    SipEngine.logEx("setMute failed: ${e.message}", true)
                }
            }
        }
    }

    fun setSpeaker(enabled: Boolean) {
        SipEngine.logEx("setSpeaker: $enabled", false)
        SipEngine._callSession.value = SipEngine._callSession.value?.copy(isSpeaker = enabled)
    }

    fun setCallVolume(factor: Float) {
        SipEngine.registerCurrentThreadEx()
        SipEngine.logEx("Adjusting call volume (Rx level) to factor: $factor", false)
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
                            aud.adjustRxLevel(factor)
                        }
                    }
                } catch (e: Throwable) {
                    SipEngine.logEx("setCallVolume failed: ${e.message}", true)
                }
            }
        }
    }

    fun startRecording(filePath: String) {
        SipEngine.startRecording(filePath)
        SipEngine._callSession.value = SipEngine._callSession.value?.copy(isRecording = true)
    }

    fun stopRecording() {
        SipEngine.stopRecording()
        SipEngine._callSession.value = SipEngine._callSession.value?.copy(isRecording = false)
    }

    fun sendDtmf(digit: Char) {
        SipEngine.registerCurrentThreadEx()
        SipEngine._callSession.value?.let { session ->
            SipEngine.callMap[session.callId]?.let { call ->
                try { call.dialDtmf(digit.toString()) } catch (e: Throwable) {
                    SipEngine.logEx("sendDtmf failed: ${e.message}", true)
                }
            }
        }
    }

    fun holdCall(onHold: Boolean) {
        SipEngine.registerCurrentThreadEx()
        SipEngine._callSession.value?.let { session ->
            SipEngine.callMap[session.callId]?.let { call ->
                try {
                    val prm = CallOpParam()
                    if (onHold) call.setHold(prm) else call.reinvite(prm)
                    SipEngine._callSession.value = session.copy(isOnHold = onHold)
                } catch (e: Throwable) {
                    SipEngine.logEx("holdCall failed: ${e.message}", true)
                }
            }
        }
    }
}
