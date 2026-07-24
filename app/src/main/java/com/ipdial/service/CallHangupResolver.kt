package com.ipdial.service

import android.telecom.DisconnectCause
import com.ipdial.data.model.CallDirection
import com.ipdial.data.model.CallSession
import com.ipdial.data.model.CallState

/**
 * Resolves the correct [android.telecom.DisconnectCause] and PJSIP SIP status code
 * for a hangup event depending on the call scenario:
 *
 * | Scenario                                           | SIP action           | DisconnectCause   |
 * |----------------------------------------------------|----------------------|-------------------|
 * | Incoming – not yet answered (ringing) → decline   | SIP 603 Decline      | REJECTED          |
 * | Incoming – answered (CONFIRMED) → local end        | SIP BYE              | LOCAL             |
 * | Outgoing – before answer (CALLING/EARLY) → cancel  | SIP CANCEL           | CANCELED          |
 * | Outgoing – after answer (CONFIRMED) → local end    | SIP BYE              | LOCAL             |
 * | Remote hangup (any state)                          | (received BYE/CANCEL)| REMOTE            |
 */
object CallHangupResolver {

    // Pre-answered ringing states for incoming calls
    private val incomingRingingStates = setOf(
        CallState.INCOMING,
        CallState.EARLY
    )

    // Pre-answer states for outgoing calls
    private val outgoingPreAnswerStates = setOf(
        CallState.CALLING,
        CallState.EARLY,
        CallState.CONNECTING
    )

    /**
     * Returns the Telecom [DisconnectCause] int for a *local* hangup action.
     * This is used to correctly populate [android.telecom.DisconnectCause] when we
     * tear down the Telecom connection ourselves (not because the remote side ended it).
     */
    fun resolveDisconnectCause(session: CallSession?): Int {
        if (session == null) return DisconnectCause.LOCAL

        return when {
            // ── Incoming, not yet answered → user declined ──────────────────────────
            session.direction == CallDirection.INCOMING &&
                    session.state in incomingRingingStates ->
                DisconnectCause.REJECTED

            // ── Outgoing, before remote answers → user cancelled ────────────────────
            session.direction == CallDirection.OUTGOING &&
                    session.state in outgoingPreAnswerStates ->
                DisconnectCause.CANCELED

            // ── Any confirmed call (either direction) → local BYE ──────────────────
            session.state == CallState.CONFIRMED ->
                DisconnectCause.LOCAL

            // ── Fallback: treat as local termination ────────────────────────────────
            else -> DisconnectCause.LOCAL
        }
    }

    /**
     * Returns the SIP status code integer (e.g., 603 for Decline) to use with
     * [org.pjsip.pjsua2.Call.hangup], or `null` to let PJSIP pick the appropriate default.
     *
     * - Incoming, not yet answered → 603 (SIP 603 Decline) so the remote side sees
     *   a clean rejection rather than an error.
     * - All other cases → `null` (PJSIP auto-selects CANCEL for pre-answer outgoing,
     *   BYE for any confirmed call).
     */
    fun resolveSipStatusCode(session: CallSession?): Int? {
        if (session == null) return null

        return when {
            // Incoming, not yet answered → decline with 603
            session.direction == CallDirection.INCOMING &&
                    session.state in incomingRingingStates ->
                org.pjsip.pjsua2.pjsip_status_code.PJSIP_SC_DECLINE

            // All other cases: let PJSIP decide (CANCEL for pre-answer outgoing, BYE otherwise)
            else -> null
        }
    }

    /**
     * Returns `true` when the hangup is for an outgoing call that hasn't been
     * answered yet — in that case PJSIP must send a CANCEL rather than a BYE.
     * (Informational; actual method selection is handled by PJSIP internally.)
     */
    fun isOutgoingCancel(session: CallSession?): Boolean =
        session != null &&
                session.direction == CallDirection.OUTGOING &&
                session.state in outgoingPreAnswerStates
}
