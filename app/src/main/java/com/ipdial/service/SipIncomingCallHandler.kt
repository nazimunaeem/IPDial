package com.ipdial.service

import android.content.Context
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
    private val contactsRepo: ContactsRepository,
    private val wakeLockManager: SipWakeLockManager? = null
) {
    private val TAG = "SipService"

    fun handle(session: CallSession) {
        Log.d(TAG, "onIncomingCall lambda triggered for callId=${session.callId}")
        val isActive = SipEngine.callSession.value != null && SipEngine.hasActiveCall(session.callId)
        if (!isActive) {
            Log.d(TAG, "onIncomingCall: ignoring ghost delivery for callId=${session.callId} (session=${SipEngine.callSession.value?.state}, hasActive=${SipEngine.hasActiveCall(session.callId)})")
        }
        if (isActive) {
            // Wake up CPU and screen immediately so the incoming call appears over the lock screen
            wakeLockManager?.acquireWakeLockForIncoming()
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
                if (isDnd && SipEngine.callSession.value?.callId == session.callId) {
                    SipEngine.setDndActive(true)
                }

                val presentOverLock = IncomingCallPresentationHelper.shouldPresentIncomingOverLockScreen(
                    com.ipdial.AppState.isForeground,
                    context
                )
                if (presentOverLock) {
                    // Post the actionable call notification (with fullScreenIntent) as soon as
                    // DND is known, and directly launch MainActivity if locked/screen-off.
                    showIncomingCallNotificationStatic(context, session.remoteDisplayName, session.callId)
                    IncomingCallPresentationHelper.launchIncomingCallUiIfLocked(context, session.callId)
                }

                val displayName = session.remoteDisplayName
                val cleanNum = com.ipdial.ui.screens.cleanUri(session.remoteUri)

                Log.d(TAG, "Processing incoming call from $cleanNum")

                val cleanedSessionDigits = cleanNum.filter { it.isDigit() }

                var matchedContact: com.ipdial.data.model.Contact? = null
                if (cleanedSessionDigits.length >= 3) {
                    matchedContact = contactsRepo.findContactByNumber(cleanNum)
                        ?: contactsRepo.lookupDeviceContactByNumber(cleanNum)
                }

                val finalDisplayName = matchedContact?.name ?: cleanNum.ifBlank { displayName }
                Log.d(TAG, "Final display name: $finalDisplayName")

                SipEngine.updateCallSessionName(finalDisplayName)

                if (SipEngine.callSession.value?.callId != session.callId || !SipEngine.hasActiveCall(session.callId)) {
                    Log.d(TAG, "onIncomingCall: call ${session.callId} ended during contact lookup, skipping Telecom")
                    return@launch
                }

                if (IncomingCallPresentationHelper.shouldPresentIncomingOverLockScreen(com.ipdial.AppState.isForeground, context)) {
                    // Refresh the already visible notification with the resolved
                    // contact name. setOnlyAlertOnce prevents another ring/vibrate.
                    showIncomingCallNotificationStatic(context, finalDisplayName, session.callId)
                }

                withContext(Dispatchers.Main) {
                    if (SipEngine.callSession.value?.callId != session.callId || !SipEngine.hasActiveCall(session.callId)) {
                        Log.d(TAG, "onIncomingCall: call $${session.callId} ended before Telecom reporting, skipping")
                        return@withContext
                    }
                    Log.d(TAG, "Reporting incoming call to Telecom")
                    TelecomHelper.reportIncomingCall(context, session.remoteUri, finalDisplayName, session.callId)
                }
            }
        }
    }
}
