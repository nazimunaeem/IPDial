package com.ipdial.service

import android.content.Context
import android.os.PowerManager
import android.util.Log

class SipWakeLockManager(private val context: Context) {
    private val TAG = "SipService"

    private var wakeLock: PowerManager.WakeLock? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var proximityWakeLock: PowerManager.WakeLock? = null

    fun acquireWakeLock() {
        if (cpuWakeLock == null) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            cpuWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IPDial:cpu_call").apply {
                setReferenceCounted(false)
                acquire(60 * 60 * 1000L)
            }
        }
    }

    fun acquireWakeLockForIncoming() {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "IPDial:incoming_call_wake"
            )
            wl.acquire(10000L)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire incoming wake lock", e)
        }
    }

    fun acquireProximityWakeLock() {
        if (proximityWakeLock?.isHeld == true) return
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            proximityWakeLock = pm.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "IPDial:proximity_dialing"
            ).apply {
                setReferenceCounted(false)
                acquire(60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire proximity wake lock", e)
        }
    }

    fun releaseProximityWakeLock() {
        proximityWakeLock?.let { if (it.isHeld) it.release() }
        proximityWakeLock = null
    }

    fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        cpuWakeLock?.let { if (it.isHeld) it.release() }
        cpuWakeLock = null
        releaseProximityWakeLock()
    }
}
