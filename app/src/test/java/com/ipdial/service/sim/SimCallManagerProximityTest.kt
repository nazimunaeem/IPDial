package com.ipdial.service.sim

import com.ipdial.data.model.AudioDeviceMode
import com.ipdial.data.model.CallDirection
import com.ipdial.data.model.CallSession
import com.ipdial.data.model.CallState
import com.ipdial.data.model.CallTransport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimCallManagerProximityTest {

    private fun simSession(
        state: CallState,
        direction: CallDirection = CallDirection.OUTGOING,
        isOnHold: Boolean = false
    ) = CallSession(
        callId = 10001,
        remoteUri = "+8801712345678",
        direction = direction,
        state = state,
        isOnHold = isOnHold,
        transport = CallTransport.SIM,
        lineLabel = "SIM 1"
    )

    @Test
    fun acquiresProximityDuringActiveOrDialingEarpieceCall() {
        assertTrue(
            SimCallManager.shouldAcquireProximityWakeLock(
                simSession(CallState.CALLING),
                AudioDeviceMode.EARPIECE,
                disableProximitySensor = false
            )
        )
        assertTrue(
            SimCallManager.shouldAcquireProximityWakeLock(
                simSession(CallState.CONNECTING),
                AudioDeviceMode.EARPIECE,
                disableProximitySensor = false
            )
        )
        assertTrue(
            SimCallManager.shouldAcquireProximityWakeLock(
                simSession(CallState.CONFIRMED),
                AudioDeviceMode.EARPIECE,
                disableProximitySensor = false
            )
        )
    }

    @Test
    fun releasesProximityForSpeakerBluetoothHoldIncomingAndDisabledSetting() {
        assertFalse(
            SimCallManager.shouldAcquireProximityWakeLock(
                simSession(CallState.CONFIRMED),
                AudioDeviceMode.SPEAKER,
                disableProximitySensor = false
            )
        )
        assertFalse(
            SimCallManager.shouldAcquireProximityWakeLock(
                simSession(CallState.CONFIRMED),
                AudioDeviceMode.BLUETOOTH,
                disableProximitySensor = false
            )
        )
        assertFalse(
            SimCallManager.shouldAcquireProximityWakeLock(
                simSession(CallState.CONFIRMED, isOnHold = true),
                AudioDeviceMode.EARPIECE,
                disableProximitySensor = false
            )
        )
        assertFalse(
            SimCallManager.shouldAcquireProximityWakeLock(
                simSession(CallState.INCOMING, direction = CallDirection.INCOMING),
                AudioDeviceMode.EARPIECE,
                disableProximitySensor = false
            )
        )
        assertFalse(
            SimCallManager.shouldAcquireProximityWakeLock(
                simSession(CallState.CONFIRMED),
                AudioDeviceMode.EARPIECE,
                disableProximitySensor = true
            )
        )
        assertFalse(
            SimCallManager.shouldAcquireProximityWakeLock(
                simSession(CallState.DISCONNECTED),
                AudioDeviceMode.EARPIECE,
                disableProximitySensor = false
            )
        )
    }
}
