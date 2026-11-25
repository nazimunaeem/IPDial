package com.ipdial.service.sim

import android.content.Intent
import android.os.IBinder
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService

/**
 * Telecom InCallService handling cellular SIM calls when IPDial is set as the Default Phone App.
 * Self-managed IPDial SIP calls are explicitly filtered out so they continue to be driven by SipEngine.
 */
class SimInCallService : InCallService() {

    override fun onBind(intent: Intent?): IBinder? {
        SimCallManager.onServiceBound(this)
        return super.onBind(intent)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        SimCallManager.onServiceUnbound(this)
        return super.onUnbind(intent)
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        if (call.details?.accountHandle?.componentName?.packageName == packageName) {
            return
        }
        SimCallManager.onCallAdded(this, call)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        if (call.details?.accountHandle?.componentName?.packageName == packageName) {
            return
        }
        SimCallManager.onCallRemoved(this, call)
    }

    @Deprecated("Deprecated in Java")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        @Suppress("DEPRECATION")
        super.onCallAudioStateChanged(audioState)
        SimCallManager.onCallAudioStateChanged(audioState)
    }
}
