package com.ipdial.service.sim

import com.ipdial.data.model.CallLogEntry
import com.ipdial.data.model.CallSession
import com.ipdial.data.model.CallTransport
import org.junit.Assert.assertEquals
import org.junit.Test

class SimPhoneAccountHelperTest {

    @Test
    fun cleanTelNumber_stripsSipSchemeAndDomain() {
        assertEquals(
            "+8801712345678",
            SimPhoneAccountHelper.cleanTelNumber("sip:+8801712345678@sip.amarip.net")
        )
        assertEquals(
            "01712345678",
            SimPhoneAccountHelper.cleanTelNumber("SIP:01712-345 678@example.com")
        )
    }

    @Test
    fun cleanTelNumber_stripsTelSchemeAndFormatting() {
        assertEquals(
            "+15551234567",
            SimPhoneAccountHelper.cleanTelNumber("tel:+1 (555) 123-4567;phone-context=+1")
        )
    }

    @Test
    fun cleanTelNumber_preservesUssdCodesAndLeadingPlusOnly() {
        assertEquals("*123*1#", SimPhoneAccountHelper.cleanTelNumber(" *123*1# "))
        assertEquals("+8801700000000", SimPhoneAccountHelper.cleanTelNumber("+880+1700-000000"))
        assertEquals("", SimPhoneAccountHelper.cleanTelNumber("abc-xyz"))
    }

    @Test
    fun callModels_defaultToSipAndSupportSimTransport() {
        val defaultSession = CallSession()
        assertEquals(CallTransport.SIP, defaultSession.transport)
        assertEquals("", defaultSession.lineLabel)

        val simSession = CallSession(
            remoteUri = "+8801712345678",
            transport = CallTransport.SIM,
            lineLabel = "SIM 1"
        )
        assertEquals(CallTransport.SIM, simSession.transport)
        assertEquals("SIM 1", simSession.lineLabel)

        val simEntry = CallLogEntry(
            remoteUri = "+8801712345678",
            transport = CallTransport.SIM,
            lineLabel = "SIM 2"
        )
        assertEquals(CallTransport.SIM, simEntry.transport)
        assertEquals("SIM 2", simEntry.lineLabel)
    }
}
