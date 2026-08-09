package com.ipdial.service

import android.util.Log
import org.pjsip.pjsua2.Endpoint
import org.pjsip.pjsua2.TransportConfig
import org.pjsip.pjsua2.pjsip_transport_type_e

class SipTransportManager {
    private val TAG = "SipEngine"

    var udpTransportId = -1
    var tcpTransportId = -1
    var tlsTransportId = -1

    fun createTransports(ep: Endpoint, log: (String, Boolean) -> Unit) {
        val sipTpCfg = TransportConfig()
        sipTpCfg.port = 0
        try {
            udpTransportId = ep.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, sipTpCfg)
        } catch (e: Exception) { log("Failed to create UDP transport: ${e.message}", true) }

        val tcpTpCfg = TransportConfig()
        tcpTpCfg.port = 0
        try {
            tcpTransportId = ep.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TCP, tcpTpCfg)
        } catch (e: Exception) { log("Failed to create TCP transport: ${e.message}", true) }

        val tlsTpCfg = TransportConfig()
        tlsTpCfg.tlsConfig.verifyServer = true
        tlsTpCfg.tlsConfig.verifyClient = false
        try {
            tlsTransportId = ep.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TLS, tlsTpCfg)
        } catch (e: Exception) { log("Failed to create TLS transport: ${e.message}", true) }
    }

    fun closeTransports(ep: Endpoint, log: (String, Boolean) -> Unit) {
        try { if (udpTransportId != -1) { ep.transportClose(udpTransportId); udpTransportId = -1 } } catch (e: Throwable) { log("close UDP transport failed: ${e.message}", true) }
        try { if (tcpTransportId != -1) { ep.transportClose(tcpTransportId); tcpTransportId = -1 } } catch (e: Throwable) { log("close TCP transport failed: ${e.message}", true) }
        try { if (tlsTransportId != -1) { ep.transportClose(tlsTransportId); tlsTransportId = -1 } } catch (e: Throwable) { log("close TLS transport failed: ${e.message}", true) }
    }

    fun recreateTransports(ep: Endpoint, log: (String, Boolean) -> Unit) {
        val sipTpCfg = TransportConfig().apply { port = 0 }
        try { udpTransportId = ep.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, sipTpCfg) } catch (e: Throwable) { log("recreate UDP transport failed: ${e.message}", true) }

        val tcpTpCfg = TransportConfig().apply { port = 0 }
        try { tcpTransportId = ep.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TCP, tcpTpCfg) } catch (e: Throwable) { log("recreate TCP transport failed: ${e.message}", true) }

        val tlsTpCfg = TransportConfig().apply {
            tlsConfig.verifyServer = true
            tlsConfig.verifyClient = false
        }
        try { tlsTransportId = ep.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TLS, tlsTpCfg) } catch (e: Throwable) { log("recreate TLS transport failed: ${e.message}", true) }

        log("Transports recreated: UDP=$udpTransportId, TCP=$tcpTransportId, TLS=$tlsTransportId", false)
    }
}
