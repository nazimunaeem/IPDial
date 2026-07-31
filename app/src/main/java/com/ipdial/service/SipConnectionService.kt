package com.ipdial.service

import android.telecom.*
import android.util.Log
import kotlinx.coroutines.*

class SipConnectionService : ConnectionService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "SipConnectionService"
        private val activeConnections = mutableMapOf<Int, SipConnection>()

        fun getConnection(callId: Int): SipConnection? = activeConnections[callId]
        
        fun registerConnection(callId: Int, connection: SipConnection) {
            activeConnections[callId] = connection
        }
        
        fun removeConnection(callId: Int) {
            activeConnections.remove(callId)
        }

        /**
         * Idempotent teardown: setDisconnected + destroy + remove, but only once per callId.
         * Safe against Telecom framework re-delivering the connection lifecycle after
         * destroy() on a self-managed Connection.
         *
         * Thread-safe: CAS on [SipConnection.isDestroyed] prevents races between
         * onCallState (PJSIP thread) calling this and the ViewModel watchdog (coroutine
         * thread) also calling this.
         */
        fun disconnectCall(callId: Int, causeCode: Int = DisconnectCause.REMOTE) {
            val conn = activeConnections[callId]
            Log.d(TAG, "disconnectCall: callId=$callId cause=$causeCode activeConnections=${activeConnections.keys}")
            if (conn == null) {
                Log.d(TAG, "disconnectCall: no active connection for callId=$callId (already cleaned up or never registered)")
                return
            }
            // Atomic CAS on the destroyed flag to ensure only one thread wins the
            // race to tear down this connection.
            synchronized(conn) {
                if (conn.isDestroyed) {
                    Log.d(TAG, "disconnectCall: connection for callId=$callId already marked destroyed")
                    return
                }
                conn.isDestroyed = true
            }
            activeConnections.remove(callId)
            try {
                conn.setDisconnected(DisconnectCause(causeCode))
            } catch (e: Throwable) {
                Log.e(TAG, "disconnectCall: setDisconnected failed for callId=$callId: ${e.message}")
            }
            // conn.destroy() must run on the Main thread.
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    conn.destroy()
                    Log.d(TAG, "disconnectCall: tore down callId=$callId (cause=$causeCode)")
                } catch (e: Throwable) {
                    Log.e(TAG, "disconnectCall: destroy failed for callId=$callId: ${e.message}")
                }
            }
        }

        fun destroyAll() {
            Log.d(TAG, "destroyAll: tearing down ${activeConnections.size} active connection(s)")
            for ((callId, conn) in activeConnections.toMap()) {
                if (!conn.isDestroyed) {
                    conn.isDestroyed = true
                    try {
                        conn.setDisconnected(android.telecom.DisconnectCause(android.telecom.DisconnectCause.REMOTE))
                    } catch (e: Throwable) {
                        Log.e(TAG, "destroyAll: setDisconnected failed for callId=$callId: ${e.message}")
                    }
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            conn.destroy()
                        } catch (e: Throwable) {
                            Log.e(TAG, "destroyAll: destroy failed for callId=$callId: ${e.message}")
                        }
                    }
                }
            }
            activeConnections.clear()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        Log.d(TAG, "onCreateOutgoingConnection")
        com.ipdial.util.SipLogger.log(TAG, "onCreateOutgoingConnection called by Telecom framework")
        val connection = SipConnection()
        val address = request?.address
        address?.let { connection.setAddress(it, TelecomManager.PRESENTATION_ALLOWED) }
        connection.setInitializing()
        connection.connectionCapabilities = Connection.CAPABILITY_MUTE or Connection.CAPABILITY_SUPPORT_HOLD
        
        // Try getting accountId from root extras or nested outgoing call extras
        var accountId = request?.extras?.getString("com.ipdial.EXTRA_ACCOUNT_ID")
        if (accountId == null) {
            accountId = request?.extras?.getBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS)?.getString("com.ipdial.EXTRA_ACCOUNT_ID")
        }
        
        val number = address?.schemeSpecificPart
        Log.d(TAG, "Attempting outgoing call: accountId=$accountId, number=$number")
        com.ipdial.util.SipLogger.log(TAG, "Attempting outgoing call via ConnectionService: accountId=$accountId, number=$number")
        
        if (accountId != null && number != null) {
            serviceScope.launch {
                val success = SipEngine.makeCall(accountId, number)
                withContext(Dispatchers.Main) {
                    if (success) {
                        val session = SipEngine.callSession.value
                        if (session != null) {
                            connection.callId = session.callId
                            registerConnection(session.callId, connection)
                            connection.setDialing()
                            com.ipdial.util.SipLogger.log(TAG, "Connection registered successfully with callId=${session.callId}")
                        } else {
                            Log.e(TAG, "SipEngine.makeCall succeeded but callSession is null (call failed immediately)")
                            com.ipdial.util.SipLogger.log(TAG, "SipEngine.makeCall succeeded but callSession is null (call failed immediately)")
                            connection.setDisconnected(DisconnectCause(DisconnectCause.ERROR))
                            connection.destroy()
                        }
                    } else {
                        Log.e(TAG, "SipEngine.makeCall failed")
                        com.ipdial.util.SipLogger.log(TAG, "SipEngine.makeCall failed")
                        connection.setDisconnected(DisconnectCause(DisconnectCause.ERROR))
                        connection.destroy()
                    }
                }
            }
        } else {
            Log.e(TAG, "Cannot start call: accountId or number is null")
            com.ipdial.util.SipLogger.log(TAG, "Cannot start call: accountId or number is null (accountId=$accountId, number=$number)")
            connection.setDisconnected(DisconnectCause(DisconnectCause.ERROR))
            connection.destroy()
        }

        return connection
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        Log.d(TAG, "onCreateIncomingConnection from Telecom framework")
        com.ipdial.util.SipLogger.log(TAG, "onCreateIncomingConnection called by Telecom framework")
        val connection = SipConnection()
        request?.address?.let { connection.setAddress(it, TelecomManager.PRESENTATION_ALLOWED) }
        
        // Set caller display name from extras if available
        val incomingExtras = request?.extras?.getBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS)
        val name = incomingExtras?.getString("com.ipdial.EXTRA_CALLER_NAME")
        if (name != null) {
            connection.setCallerDisplayName(name, TelecomManager.PRESENTATION_ALLOWED)
        }

        connection.setInitializing()
        connection.setRinging()
        connection.connectionCapabilities = Connection.CAPABILITY_MUTE or Connection.CAPABILITY_SUPPORT_HOLD
        
        // Try to get callId from extras first, fall back to session
        val callId = incomingExtras?.getInt("com.ipdial.EXTRA_CALL_ID", -1) ?: -1
        if (callId != -1) {
            // Reject a re-delivered / ghost incoming connection for a call that is no
            // longer active in SipEngine. This happens when a self-managed ACTIVE
            // connection's destroy() re-enters the framework and it re-emits the call.
            // If the call already ended (remote BYE / local hangup), do NOT reuse or
            // register it — otherwise the in-app CallScreen can be resurrected.
            if (!SipEngine.hasActiveCall(callId)) {
                Log.w(TAG, "onCreateIncomingConnection: REJECTING ghost/late incoming for ended callId=$callId (not in SipEngine.callMap)")
                com.ipdial.util.SipLogger.log(TAG, "onCreateIncomingConnection: rejecting ghost incoming callId=$callId (call already ended)")
                // Return a throwaway connection that is immediately destroyed so the
                // framework does not keep a dangling call around.
                connection.setDisconnected(DisconnectCause(DisconnectCause.CANCELED))
                connection.destroy()
                return connection
            }
            // If a connection for this callId already exists, reuse it rather than
            // creating a duplicate that could resurrect the UI after remote hang-up.
            val existing = activeConnections[callId]
            if (existing != null) {
                Log.d(TAG, "onCreateIncomingConnection: reusing existing connection for callId=$callId")
                return existing
            }
            connection.callId = callId
            registerConnection(callId, connection)
            Log.d(TAG, "Incoming Connection registered with callId=$callId (from extras)")
            com.ipdial.util.SipLogger.log(TAG, "Incoming Connection registered with callId=$callId (from extras)")
        } else {
            // Fallback: assume the most recent incoming session
            SipEngine.callSession.value?.let { session ->
                connection.callId = session.callId
                registerConnection(session.callId, connection)
                Log.d(TAG, "Incoming Connection registered with callId=${session.callId} (from session)")
                com.ipdial.util.SipLogger.log(TAG, "Incoming Connection registered with callId=${session.callId} (from session)")
            } ?: run {
                Log.w(TAG, "onCreateIncomingConnection: No callId in extras and SipEngine.callSession is NULL")
            }
        }
        
        return connection
    }
}

class SipConnection : Connection() {
    var callId: Int = -1
    @Volatile var isDestroyed: Boolean = false
    private val connectionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onAnswer() {
        Log.d("SipConnection", "onAnswer(id=$callId)")
        com.ipdial.util.SipLogger.log("SipConnection", "onAnswer called for callId=$callId")
        setActive()
        connectionScope.launch {
            SipEngine.answerCall(callId)
        }
    }

    override fun onDisconnect() {
        Log.d("SipConnection", "onDisconnect(id=$callId)")
        com.ipdial.util.SipLogger.log("SipConnection", "onDisconnect called for callId=$callId")
        if (isDestroyed) return
        isDestroyed = true
        // Resolve the correct cause based on current session state so Telecom can
        // display the right reason (e.g. "Call Ended" for confirmed, "Declined" for ringing).
        val session = SipEngine.callSession.value
        val causeCode = com.ipdial.service.CallHangupResolver.resolveDisconnectCause(session)
        Log.d("SipConnection", "onDisconnect: resolvedCause=$causeCode for callId=$callId (state=${session?.state}, direction=${session?.direction})")
        setDisconnected(DisconnectCause(causeCode))
        connectionScope.launch {
            SipEngine.hangupCall(callId)
            withContext(Dispatchers.Main) {
                SipConnectionService.removeConnection(callId)
                destroy()
                connectionScope.cancel()
            }
        }
    }

    override fun onAbort() {
        Log.d("SipConnection", "onAbort(id=$callId)")
        com.ipdial.util.SipLogger.log("SipConnection", "onAbort called for callId=$callId")
        if (isDestroyed) return
        isDestroyed = true
        setDisconnected(DisconnectCause(DisconnectCause.CANCELED))
        connectionScope.launch {
            SipEngine.hangupCall(callId)
            withContext(Dispatchers.Main) {
                SipConnectionService.removeConnection(callId)
                destroy()
                connectionScope.cancel()
            }
        }
    }

    override fun onHold() {
        Log.d("SipConnection", "onHold(id=$callId)")
        com.ipdial.util.SipLogger.log("SipConnection", "onHold called for callId=$callId")
        setOnHold()
        connectionScope.launch {
            SipEngine.holdCall(true)
        }
    }

    override fun onUnhold() {
        Log.d("SipConnection", "onUnhold(id=$callId)")
        com.ipdial.util.SipLogger.log("SipConnection", "onUnhold called for callId=$callId")
        setActive()
        connectionScope.launch {
            SipEngine.holdCall(false)
        }
    }

    override fun onReject() {
        Log.d("SipConnection", "onReject(id=$callId)")
        com.ipdial.util.SipLogger.log("SipConnection", "onReject called for callId=$callId")
        if (isDestroyed) return
        isDestroyed = true
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
        connectionScope.launch {
            SipEngine.hangupCall(callId)
            withContext(Dispatchers.Main) {
                SipConnectionService.removeConnection(callId)
                destroy()
                connectionScope.cancel()
            }
        }
    }

    @Deprecated("Deprecated in Java", ReplaceWith("onCallAudioStateChanged(state)"))
    override fun onCallAudioStateChanged(state: CallAudioState?) {
        Log.d("SipConnection", "onCallAudioStateChanged: $state")
        // A2: this fires only when Telecom has actually switched the audio route
        // (e.g. the BT SCO link is established), so reflect the confirmed route
        // in the UI instead of assuming Bluetooth works just because a headset is
        // paired. The ViewModel collects confirmedAudioRoute to sync its mode.
        if (state == null) return
        val route = state.route
        val mode = when {
            route and CallAudioState.ROUTE_BLUETOOTH != 0 -> com.ipdial.data.model.AudioDeviceMode.BLUETOOTH
            route and CallAudioState.ROUTE_SPEAKER != 0 -> com.ipdial.data.model.AudioDeviceMode.SPEAKER
            else -> com.ipdial.data.model.AudioDeviceMode.EARPIECE
        }
        Log.d("SipConnection", "onCallAudioStateChanged: confirmed route -> $mode")
        SipEngine.setConfirmedAudioRoute(mode)
    }
}
