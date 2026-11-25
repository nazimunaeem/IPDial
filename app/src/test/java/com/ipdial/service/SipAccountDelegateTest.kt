package com.ipdial.service

import com.ipdial.data.model.RegStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class SipAccountDelegateTest {

    @Test
    fun classifyRegStatus_mapsAuthAndNetworkErrorsCorrectly() {
        assertEquals(RegStatus.ERROR, SipAccountDelegate.classifyRegStatus(403, isActive = false))
        assertEquals(RegStatus.ERROR, SipAccountDelegate.classifyRegStatus(401, isActive = false))
        assertEquals(RegStatus.ERROR, SipAccountDelegate.classifyRegStatus(503, isActive = false))
        assertEquals(RegStatus.ERROR, SipAccountDelegate.classifyRegStatus(408, isActive = false))
    }

    @Test
    fun classifyRegStatus_mapsTransientAndRegisteredStatesCorrectly() {
        assertEquals(RegStatus.REGISTERING, SipAccountDelegate.classifyRegStatus(423, isActive = false))
        assertEquals(RegStatus.REGISTERING, SipAccountDelegate.classifyRegStatus(0, isActive = false))
        assertEquals(RegStatus.REGISTERING, SipAccountDelegate.classifyRegStatus(100, isActive = false))
        assertEquals(RegStatus.REGISTERED, SipAccountDelegate.classifyRegStatus(200, isActive = true))
        assertEquals(RegStatus.UNREGISTERED, SipAccountDelegate.classifyRegStatus(200, isActive = false))
    }
}
