package com.ipdial.ui.screens

import com.ipdial.data.model.SipAccount
import com.ipdial.data.model.Transport
import com.ipdial.service.SipEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class UtilsAndDialUriTest {

    @Test
    fun cleanUriHandlesBracketsSchemesAndParams() {
        assertEquals("01711223344", cleanUri("\"John Doe\" <sip:01711223344@sip.example.com;transport=tcp>"))
        assertEquals("01711223344", cleanUri("SIPS:01711223344@pbx.company.com:5061"))
        assertEquals("+8801711223344", cleanUri("<tel:+8801711223344>"))
        assertEquals("1001", cleanUri("1001"))
    }

    @Test
    fun cleanDisplayNameExtractsNameOrFallsBackToNumber() {
        assertEquals("John Doe", cleanDisplayName("\"John Doe\" <sip:1001@example.com>", "sip:1001@example.com"))
        assertEquals("1001", cleanDisplayName("sip:1001@example.com", "sip:1001@example.com"))
        assertEquals("01711223344", cleanDisplayName("", "<sips:01711223344@pbx.example.com>"))
    }

    @Test
    fun formatDialUriSanitizesPhoneNumbersAndAddsPortAndTransport() {
        val udpAccount = SipAccount(
            domain = "sip.example.com",
            port = null,
            transport = Transport.UDP
        )
        assertEquals(
            "sip:+8801711223344@sip.example.com",
            SipEngine.formatDialUri("+880 1711-223344", udpAccount)
        )

        val tcpCustomPort = SipAccount(
            domain = "pbx.example.com",
            port = 5080,
            transport = Transport.TCP
        )
        assertEquals(
            "sip:1001@pbx.example.com:5080;transport=tcp",
            SipEngine.formatDialUri("1001", tcpCustomPort)
        )

        val tlsAccount = SipAccount(
            domain = "secure.example.com",
            port = 5061,
            transport = Transport.TLS
        )
        assertEquals(
            "sip:alice@secure.example.com;transport=tls",
            SipEngine.formatDialUri("sip:alice@secure.example.com", tlsAccount)
        )
    }

    @Test
    fun formatDisplayNumberPreservesSipUsernames() {
        assertEquals("01728-867896", formatDisplayNumber("01728867896"))
        assertEquals("+88017-28867896", formatDisplayNumber("+8801728867896"))
        assertEquals("alice", formatDisplayNumber("alice"))
        assertEquals("alice@sip.example.com", formatDisplayNumber("alice@sip.example.com"))
    }

    @Test
    fun formatHistoryDurationSuffixOmitsUnconnectedCalls() {
        assertEquals("", com.ipdial.ui.screens.call.formatHistoryDurationSuffix(missed = false, durationSeconds = 0L))
        assertEquals("", com.ipdial.ui.screens.call.formatHistoryDurationSuffix(missed = true, durationSeconds = 15L))
        assertEquals(" • 00:42", com.ipdial.ui.screens.call.formatHistoryDurationSuffix(missed = false, durationSeconds = 42L))
    }
}
