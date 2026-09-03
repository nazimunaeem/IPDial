package com.ipdial.service

import android.os.Handler
import android.os.Looper
import com.ipdial.data.model.CallDirection
import com.ipdial.data.model.CallSession
import com.ipdial.data.model.CallState
import com.ipdial.data.model.RegStatus
import org.pjsip.pjsua2.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * PJSIP Account callback delegate.
 * Extracted from [SipEngine] inner class to top-level.
 */
class SipAccountDelegate(
    private val accountId: String,
    private val callMap: MutableMap<Int, SipCallDelegate>,
    private val accountConfigs: MutableMap<String, com.ipdial.data.model.SipAccount>,
    private val _callSession: MutableStateFlow<CallSession?>,
    private val _registrationEvents: MutableSharedFlow<Triple<String, RegStatus, Int>>,
    private val onIncomingCall: ((CallSession) -> Unit)?,
    private val log: (String, Boolean) -> Unit
) : Account() {

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

            val statusCode = try {
                ai.regStatus?.swigValue() ?: 0
            } catch (e: Throwable) {
                0
            }
            log("onRegState: Account $accountId code=$statusCode reason=${ai.regStatusText} active=${ai.regIsActive} activeFlag=${ai.regIsActive}", false)
            
            val status = when {
                ai.regIsActive || statusCode in 200..299 -> RegStatus.REGISTERED
                statusCode == 0 || statusCode in 100..199 -> RegStatus.REGISTERING
                statusCode == 401 || statusCode == 403 -> {
                    log("REG_AUTH_FAILED: Account $accountId auth failed (code=$statusCode). Check credentials.", true)
                    RegStatus.ERROR
                }
                statusCode == 408 || statusCode == 423 || statusCode in 500..599 || statusCode == 480 -> {
                    log("REG_TRANSIENT: Account $accountId code=$statusCode (${ai.regStatusText}) - stack will retry", false)
                    RegStatus.REGISTERING
                }
                statusCode >= 300 -> {
                    log("REG_ERROR: Account $accountId code=$statusCode (${ai.regStatusText})", true)
                    RegStatus.ERROR
                }
                else -> RegStatus.UNREGISTERED
            }
            log("REG_UPDATE: Account $accountId -> $status (code=$statusCode)", false)
            _registrationEvents.tryEmit(Triple(accountId, status, statusCode))
        } catch (e: Throwable) {
            log("onRegState failed for account $accountId: ${e.message}", true)
        }
    }

    override fun onIncomingCall(prm: OnIncomingCallParam) {
        // CRITICAL: PJSIP reuses/mutates the OnIncomingCallParam object once this
        // callback returns, so any value read asynchronously inside the deferred
        // Handler block would be corrupted (e.g. prm.callId 0 -> 1799220516 ->
        // 913576). Capture it synchronously now and use the local copy everywhere.
        val callId = prm.callId
        log("onIncomingCall callback from PJSIP: callId=$callId", false)
        try {
            val call = SipCallDelegate(
                acct = this,
                callId = callId,
                callMap = callMap,
                _callSession = _callSession,
                audioManager = SipEngine.audioManager,
                endpoint = { SipEngine.endpoint },
                log = log
            )
            callMap[callId] = call

            // PJSIP 2.5 asserts inv->last_answer in pjsip_inv_answer, and the
            // initial 100 answer is only created after this callback returns on
            // the PJSIP thread. Run the answer + info + session creation on the
            // single PJSIP thread (serialized with the worker via pjsipLock) so
            // native operations never race the worker's transaction processing.
            SipEngine.runOnPjsipThread {
                try {
                    if (!accountConfigs.containsKey(accountId)) {
                        log("Rejecting incoming call #$callId for disabled account $accountId", true)
                        val busyPrm = CallOpParam().apply { statusCode = pjsip_status_code.PJSIP_SC_DECLINE }
                        try { call.answer(busyPrm) } catch (_: Throwable) {}
                        synchronized(SipEngine.pjsipLock) { call.delete() }
                        callMap.remove(callId)
                        return@runOnPjsipThread
                    }

                    val opPrm = CallOpParam().apply { statusCode = pjsip_status_code.PJSIP_SC_RINGING }
                    try {
                        log("Answering incoming call #$callId with RINGING", false)
                        call.answer(opPrm)
                    } catch (e: Throwable) {
                        log("Failed to answer incoming call #$callId with RINGING: ${e.message}", true)
                        synchronized(SipEngine.pjsipLock) { call.delete() }
                        callMap.remove(callId)
                        return@runOnPjsipThread
                    }

                    try {
                        val ci = call.info ?: run {
                            log("Call info is null for incoming call #$callId", true)
                            synchronized(SipEngine.pjsipLock) { call.delete() }
                            callMap.remove(callId)
                            return@runOnPjsipThread
                        }

                        log("Incoming call from ${ci.remoteUri}, state=${ci.stateText}", false)

                        // Clear any stale disconnect info from a previous call so this
                        // call's log entry doesn't inherit the old reason.
                        SipEngine.pendingDisconnectInfo = null

                        val session = CallSession(
                            callId = callId,
                            accountId = accountId,
                            remoteUri = ci.remoteUri ?: "",
                            remoteDisplayName = ci.remoteContact ?: ci.remoteUri ?: "",
                            direction = CallDirection.INCOMING,
                            state = CallState.INCOMING
                        )
                        _callSession.value = session

                        val handler = SipEngine.onIncomingCall
                        if (handler == null) {
                            log("WARNING: onIncomingCall lambda is NULL in SipEngine", true)
                        }
                        handler?.invoke(session)
                    } catch (e: Throwable) {
                        log("Failed to process incoming call info: ${e.message}", true)
                        synchronized(SipEngine.pjsipLock) { call.delete() }
                        callMap.remove(callId)
                        if (_callSession.value?.callId == callId) {
                            _callSession.value = null
                        }
                    }
                } catch (e: Throwable) {
                    log("onIncomingCall failed: ${e.message}", true)
                }
            }
        } catch (e: Throwable) {
            log("onIncomingCall failed: ${e.message}", true)
        }
    }
}
