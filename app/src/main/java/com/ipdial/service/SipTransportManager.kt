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

    private var hadUdp = false
    private var hadTcp = false
    private var hadTls = false

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
                    hadUdp = true
                    log("Created UDP transport id=$udpTransportId", false)
                }
                Transport.TCP -> {
                    if (tcpTransportId != -1) return
                    val tcpTpCfg = TransportConfig().apply { port = 0 }
                    tcpTransportId = ep.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TCP, tcpTpCfg)
                    hadTcp = true
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
                    hadTls = true
                    log("Created TLS transport id=$tlsTransportId", false)
                }
            }
        } catch (e: Exception) {
            log("Failed to create $tp transport: ${e.message}", true)
        }
    }

    fun createTransports(ep: Endpoint, log: (String, Boolean) -> Unit) {
        createTransport(ep, Transport.UDP, log)
        createTransport(ep, Transport.TCP, log)
        createTransport(ep, Transport.TLS, log)
    }

    fun closeTransports(ep: Endpoint, log: (String, Boolean) -> Unit) {
        if (udpTransportId != -1) {
            try { ep.transportClose(udpTransportId) } catch (e: Throwable) { log("close UDP transport failed: ${e.message}", true) }
            udpTransportId = -1
        }
        if (tcpTransportId != -1) {
            try { ep.transportClose(tcpTransportId) } catch (e: Throwable) { log("close TCP transport failed: ${e.message}", true) }
            tcpTransportId = -1
        }
        if (tlsTransportId != -1) {
            try { ep.transportClose(tlsTransportId) } catch (e: Throwable) { log("close TLS transport failed: ${e.message}", true) }
            tlsTransportId = -1
        }
    }

    fun recreateTransports(ep: Endpoint, log: (String, Boolean) -> Unit) {
        if (hadUdp) createTransport(ep, Transport.UDP, log)
        if (hadTcp) createTransport(ep, Transport.TCP, log)
        if (hadTls) createTransport(ep, Transport.TLS, log)
        log("Transports recreated: UDP=$udpTransportId, TCP=$tcpTransportId, TLS=$tlsTransportId", false)
    }
}
