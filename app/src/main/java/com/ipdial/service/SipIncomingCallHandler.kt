package com.ipdial.service

import android.content.Context
import android.content.Intent
import android.util.Log
import com.ipdial.data.model.CallSession
import com.ipdial.data.repository.AccountRepository
import com.ipdial.data.repository.ContactsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class SipIncomingCallHandler(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repo: AccountRepository,
    private val contactsRepo: ContactsRepository
) {
    private val TAG = "SipService"

    fun handle(session: CallSession) {
        Log.d(TAG, "onIncomingCall lambda triggered for callId=${session.callId}")
        val isActive = SipEngine.callSession.value != null && SipEngine.hasActiveCall(session.callId)
        if (!isActive) {
            Log.d(TAG, "onIncomingCall: ignoring ghost delivery for callId=${session.callId} (session=${SipEngine.callSession.value?.state}, hasActive=${SipEngine.hasActiveCall(session.callId)})")
        }
        if (isActive) {
            com.ipdial.util.SipLogger.log("SipService", "Incoming call received: ${session.remoteUri}")
            scope.launch {
                val accountsNow = repo.accounts.first()
                val callAccount = accountsNow.firstOrNull { it.id == session.accountId }
                if (callAccount == null || !callAccount.isEnabled) {
                    Log.d(TAG, "Rejecting incoming call for disabled account ${session.accountId}")
                    SipEngine.hangupCall(session.callId)
                    return@launch
                }

                val isDnd = repo.dndEnabled.first()
                Log.d(TAG, "DND=$isDnd for callId=${session.callId}")

                val displayName = session.remoteDisplayName
                val cleanNum = session.remoteUri.replace("<", "").replace(">", "").removePrefix("sip:").substringBefore("@").substringBefore(";")

                Log.d(TAG, "Processing incoming call from $cleanNum")

                val cleanedSessionDigits = cleanNum.filter { it.isDigit() }

                var matchedContact: com.ipdial.data.model.Contact? = null
                if (cleanedSessionDigits.length >= 3) {
                    matchedContact = contactsRepo.findContactByNumber(cleanNum)
                }

                val finalDisplayName = matchedContact?.name ?: cleanNum.ifBlank { displayName }
                Log.d(TAG, "Final display name: $finalDisplayName")

                SipEngine.updateCallSessionName(finalDisplayName)

                if (isDnd && SipEngine.callSession.value?.callId == session.callId) {
                    SipEngine.setDndActive(true)
                }

                if (SipEngine.callSession.value?.callId != session.callId || !SipEngine.hasActiveCall(session.callId)) {
                    Log.d(TAG, "onIncomingCall: call $${session.callId} ended during contact lookup, skipping Telecom/banner")
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    if (SipEngine.callSession.value?.callId != session.callId || !SipEngine.hasActiveCall(session.callId)) {
                        Log.d(TAG, "onIncomingCall: call $${session.callId} ended before Telecom reporting, skipping")
                        return@withContext
                    }
                    Log.d(TAG, "Reporting incoming call to Telecom and showing notification")
                    TelecomHelper.reportIncomingCall(context, session.remoteUri, finalDisplayName, session.callId)
                    if (!isDnd) {
                        startPushingBanner(scope, context, finalDisplayName, session.callId)
                    }
                    if (!com.ipdial.AppState.isForeground) {
                        val activityIntent = Intent(context, com.ipdial.MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        context.startActivity(activityIntent)
                    }
                }
            }
        }
    }
}
