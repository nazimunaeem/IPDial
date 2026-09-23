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
     * Default in-app listening volume on the 0..6 volume-bar scale. Set to the
     * top of the range so a fresh/receiving call is audible out of the box:
     * the received signal is only as loud as the PJSIP RX gain times the OS
     * voice stream, and real-world reports showed unity gain (1.0) was too quiet
     * to hear until the user raised the in-app volume. Mapping 6f -> 2.0x via
     * [callVolumeToPjsipLevel] restores the audible level immediately on answer.
     */
    const val DEFAULT_RX_VOLUME = 6f

    /** DTMF digits keyed while the call media was not yet ACTIVE (CALLING/EARLY or
     * re-INVITE unhold), flushed by [flushPendingDtmf] once the audio path is up. */
    private val pendingDtmfChars = java.util.Collections.synchronizedList(mutableListOf<Char>())

    /**
     * Domains on the webvoice/amarip platform family (SIGNAL/1.0 stack). These
     * servers advertise RFC 2833 telephone-event in the SDP, so PJSIP's
     * `dialDtmf()` succeeds — but their IVR ignores RTP telephone-events and
     * silently drops the digits. DTMF must therefore be sent over the SIP
     * signaling path (in-dialog SIP INFO with `application/dtmf-relay`), which
     * their call-through/PBX gateway does honor.
     */
    private val SIP_INFO_ONLY_DOMAINS = setOf(
        "sip.amarip.net",
        "103.170.231.10",
        "103.129.202.202",
        "billing.webvoice.net",
    )

    /** Public view used by the Server Inspector's DTMF probe. */
    val SIP_INFO_ONLY_DOMAINS_PUBLIC: Set<String> get() = SIP_INFO_ONLY_DOMAINS

    /** Domain of the account that owns the current call session, lowercased. */
    private fun activeAccountDomain(): String? {
        val accountId = SipEngine._callSession.value?.accountId ?: return null
        return SipEngine.accountConfigs[accountId]?.domain?.lowercase()?.trim()
    }

    /** Whether the active call's provider requires SIP-INFO DTMF exclusively. */
    private fun shouldForceSipInfo(): Boolean {
        val domain = activeAccountDomain() ?: return false
        return SIP_INFO_ONLY_DOMAINS.any { domain == it }
    }

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
                                    // Empirically, adjustTxLevel controls mic → remote (verified live).
                                    aud.adjustTxLevel(0f)
                                } else {
                                    val isEmulator = DeviceUtil.isEmulator()
                                    val baseGain = if (isEmulator) MIC_GAIN_EMULATOR else MIC_GAIN_REAL
                                    aud.adjustTxLevel(baseGain)
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
                                // Empirically, adjustRxLevel controls remote → us (listening volume); adjustTxLevel
                                // controls mic → remote. (Verified live: v1.1.5 used adjustRxLevel for volume,
                                // remote could hear us; the swap in v1.1.6 broke remote audio.)
                                aud.adjustRxLevel(level)
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
                    if (!shouldForceSipInfo() && !isCallMediaActive(call)) {
                        // Media is not established yet (CALLING/EARLY, or re-INVITE
                        // unhold in progress). PJSIP drops RFC2833 DTMF silently in
                        // this window, so buffer the digit and flush it when the audio
                        // path becomes ACTIVE (see flushPendingDtmf). SIP-INFO DTMF
                        // rides the signaling path and is safe to send immediately.
                        pendingDtmfChars.add(digit)
                        SipEngine.logEx("sendDtmf: media not active, buffering '$digit' (state=${session.state})", false)
                        return@let
                    }
                    if (!trySendDtmf(call, digit)) {
                        // Media reported ACTIVE but the digit was not delivered —
                        // e.g. no negotiated telephone-event channel (RFC2833) or
                        // the audio path was just torn down. Re-buffer so the next
                        // flush retries it instead of silently dropping the key press.
                        pendingDtmfChars.add(digit)
                    }
                }
            }
        }
    }

    /**
     * Delivers a single digit automatically. By default it prefers RFC 4733/2833
     * telephone-event RTP (PJSIP `dialDtmf`), and if that is unavailable the
     * digit is resent as an in-dialog SIP INFO request
     * (`application/dtmf-relay` body), which providers that ignore RTP
     * telephone-events act on.
     *
     * For the webvoice/amarip platform family the order is reversed: RTP
     * telephone-events are silently dropped by their IVR even though the SDP
     * advertises them, so those accounts go straight to SIP INFO.
     * Returns true when the digit was accepted for sending.
     */
    private fun trySendDtmf(call: Call, digit: Char): Boolean {
        if (shouldForceSipInfo()) {
            try {
                sendSipInfoDtmf(call, digit)
                return true
            } catch (e: Throwable) {
                SipEngine.logEx("sendDtmf (SIP INFO) failed for '$digit': ${e.message}", true)
                return false
            }
        }
        return try {
            call.dialDtmf(digit.toString())
            true
        } catch (fallbackCause: Throwable) {
            SipEngine.logEx("sendDtmf: RFC2833 unavailable (${fallbackCause.message}) — switching to SIP INFO for '$digit'", true)
            try {
                sendSipInfoDtmf(call, digit)
                true
            } catch (e: Throwable) {
                SipEngine.logEx("sendDtmf failed for '$digit': ${e.message} (buffering for retry)", true)
                false
            }
        }
    }

    /**
     * Sends a single digit as an in-dialog SIP INFO request using the classic
     * `application/dtmf-relay` body (`Signal=<digit>`). Widely understood by
     * call-through/PBX gateways (Asterisk, most VAS platforms) that do not
     * interpret RFC 2833 telephone-event RTP.
     */
    private fun sendSipInfoDtmf(call: Call, digit: Char) {
        val prm = CallSendRequestParam().apply { method = "INFO" }
        prm.txOption = SipTxOption().apply {
            contentType = "application/dtmf-relay"
            msgBody = "Signal=$digit\r\nDuration=160"
        }
        call.sendRequest(prm)
    }

    /** Whether the call's audio stream is ACTIVE and can carry DTMF (RFC2833). */
    private fun isCallMediaActive(call: Call): Boolean {
        return try {
            val ci = call.info
            for (i in 0 until ci.media.size.toInt()) {
                val mi = ci.media.get(i)
                if (mi.type == pjmedia_type.PJMEDIA_TYPE_AUDIO &&
                    mi.status == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE) {
                    return true
                }
            }
            false
        } catch (e: Throwable) {
            SipEngine.logEx("isCallMediaActive failed: ${e.message}", true)
            false
        }
    }

    /**
     * Sends any DTMF digits that were buffered while the call media was not yet
     * ACTIVE (e.g. digits keyed during the CALLING/EARLY phase). Safe to call
     * whenever the audio path is (re)established; it is a no-op when there is no
     * active media or nothing is buffered.
     *
     * Digits are removed from the buffer one-at-a-time ONLY after PJSIP reports a
     * successful send. If a digit fails, the remaining (and failed) digits stay
     * buffered so the next flush retries them instead of losing the key press.
     */
    fun flushPendingDtmf() {
        SipEngine.runOnPjsipThread {
            SipEngine.registerCurrentThreadEx()
            SipEngine._callSession.value?.let { session ->
                SipEngine.callMap[session.callId]?.let { call ->
                    if (!isCallMediaActive(call)) return@let
                    if (pendingDtmfChars.isEmpty()) return@let
                    SipEngine.logEx("flushPendingDtmf: sending buffered digits ${pendingDtmfChars.joinToString("")}", false)
                    val iterator = pendingDtmfChars.iterator()
                    while (iterator.hasNext()) {
                        val d = iterator.next()
                        // Digits are removed one-at-a-time ONLY after a successful
                        // send. If a digit fails, the remaining (and failed) digits
                        // stay buffered so the next flush retries them instead of
                        // losing the key press.
                        if (trySendDtmf(call, d)) iterator.remove()
                    }
                }
            }
        }
    }

    /** Drops any buffered DTMF digits (call ended / new call about to start). */
    fun clearPendingDtmf() {
        pendingDtmfChars.clear()
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
