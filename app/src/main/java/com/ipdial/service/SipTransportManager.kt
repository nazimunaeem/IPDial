package com.ipdial.service

import android.util.Log
import com.ipdial.data.model.Transport
import org.pjsip.pjsua2.Endpoint
import org.pjsip.pjsua2.TransportConfig
import org.pjsip.pjsua2.pjsip_transport_type_e

class SipTransportManager {
    private val TAG = "SipEngine"

    var udpTransportId = -1
    var tcpTransportId = -1
    var tlsTransportId = -1

    /**
     * Create only the transport specified by [tp]. This avoids creating all
     * transports at startup when only one is actually needed by the accounts.
     */
    fun createTransport(ep: Endpoint, tp: Transport, log: (String, Boolean) -> Unit) {
        try {
            when (tp) {
                Transport.UDP -> {
                    if (udpTransportId != -1) return
                    val sipTpCfg = TransportConfig().apply { port = 0 }
                    udpTransportId = ep.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, sipTpCfg)
                    log("Created UDP transport id=$udpTransportId", false)
                }
                Transport.TCP -> {
                    if (tcpTransportId != -1) return
                    val tcpTpCfg = TransportConfig().apply { port = 0 }
                    tcpTransportId = ep.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TCP, tcpTpCfg)
                    log("Created TCP transport id=$tcpTransportId", false)
                }
                Transport.TLS -> {
                    if (tlsTransportId != -1) return
                    val tlsTpCfg = TransportConfig().apply {
                        // Disable strict TLS verification by default for better compatibility
                        // with common IP-based SIP providers.
                        tlsConfig.verifyServer = false
                        tlsConfig.verifyClient = false
                    }
                    tlsTransportId = ep.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TLS, tlsTpCfg)
                    log("Created TLS transport id=$tlsTransportId", false)
                }
            }
        } catch (e: Exception) {
            log("Failed to create $tp transport: ${e.message}", true)
        }
    }

    fun createTransports(ep: Endpoint, log: (String, Boolean) -> Unit) {
        // Backwards-compatible: create all transports. Prefer createTransport when possible.
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
        tlsTpCfg.tlsConfig.verifyServer = false
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
        // Only recreate transports that were previously created to avoid unnecessary work.
        if (udpTransportId != -1) {
            val sipTpCfg = TransportConfig().apply { port = 0 }
            try { udpTransportId = ep.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, sipTpCfg) } catch (e: Throwable) { log("recreate UDP transport failed: ${e.message}", true) }
        }

        if (tcpTransportId != -1) {
            val tcpTpCfg = TransportConfig().apply { port = 0 }
            try { tcpTransportId = ep.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TCP, tcpTpCfg) } catch (e: Throwable) { log("recreate TCP transport failed: ${e.message}", true) }
        }

        if (tlsTransportId != -1) {
            val tlsTpCfg = TransportConfig().apply {
                tlsConfig.verifyServer = false
                tlsConfig.verifyClient = false
            }
            try { tlsTransportId = ep.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TLS, tlsTpCfg) } catch (e: Throwable) { log("recreate TLS transport failed: ${e.message}", true) }
        }

        log("Transports recreated: UDP=$udpTransportId, TCP=$tcpTransportId, TLS=$tlsTransportId", false)
    }
}
