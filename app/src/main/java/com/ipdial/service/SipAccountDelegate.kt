package com.ipdial.service

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
    private val _registrationEvents: MutableSharedFlow<Pair<String, RegStatus>>,
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

            val status = when {
                ai.regIsActive -> RegStatus.REGISTERED
                ai.regStatus / 100 == 2 -> RegStatus.REGISTERED
                ai.regStatus >= 300 -> RegStatus.ERROR
                else -> RegStatus.UNREGISTERED
            }
            log("REG_UPDATE: Account $accountId status=$status (code=${ai.regStatus}, reason=${ai.regStatusText}, active=${ai.regIsActive})", false)
            _registrationEvents.tryEmit(Pair(accountId, status))
        } catch (e: Throwable) {
            log("onRegState failed for account $accountId: ${e.message}", true)
        }
    }

    override fun onIncomingCall(prm: OnIncomingCallParam) {
        log("onIncomingCall callback from PJSIP: callId=${prm.callId}", false)
        try {
            val call = SipCallDelegate(
                acct = this,
                callId = prm.callId,
                callMap = callMap,
                _callSession = _callSession,
                audioManager = SipEngine.audioManager,
                endpoint = { SipEngine.endpoint },
                log = log
            )
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
                log("Answering incoming call #${prm.callId} with RINGING", false)
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

                log("Incoming call from ${ci.remoteUri}, state=${ci.stateText}", false)

                val session = CallSession(
                    callId = prm.callId,
                    accountId = accountId,
                    remoteUri = ci.remoteUri ?: "",
                    remoteDisplayName = ci.remoteContact ?: ci.remoteUri ?: "",
                    direction = CallDirection.INCOMING,
                    state = CallState.INCOMING
                )
                _callSession.value = session

                val handler = SipEngine.onIncomingCall ?: onIncomingCall
                if (handler == null) {
                    log("WARNING: onIncomingCall lambda is NULL in SipEngine", true)
                }
                handler?.invoke(session)
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
