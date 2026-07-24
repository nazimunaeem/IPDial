package com.ipdial.service

import android.telecom.DisconnectCause
import com.ipdial.data.model.CallDirection
import com.ipdial.data.model.CallSession
import com.ipdial.data.model.CallState
import org.junit.Assert.assertEquals
import org.junit.Test

class CallHangupResolverTest {

    @Test
    fun incomingNotAnsweredUsesRejectedCause() {
        val session = CallSession(
            callId = 1,
            direction = CallDirection.INCOMING,
            state = CallState.INCOMING
        )

        assertEquals(DisconnectCause.REJECTED, CallHangupResolver.resolveDisconnectCause(session))
    }

    @Test
    fun incomingAnsweredUsesLocalCause() {
        val session = CallSession(
            callId = 2,
            direction = CallDirection.INCOMING,
            state = CallState.CONFIRMED
        )

        assertEquals(DisconnectCause.LOCAL, CallHangupResolver.resolveDisconnectCause(session))
    }

    @Test
    fun outgoingBeforeAnswerUsesCanceledCause() {
        val session = CallSession(
            callId = 3,
            direction = CallDirection.OUTGOING,
            state = CallState.CALLING
        )

        assertEquals(DisconnectCause.CANCELED, CallHangupResolver.resolveDisconnectCause(session))
    }

    @Test
    fun outgoingAfterAnswerUsesLocalCause() {
        val session = CallSession(
            callId = 4,
            direction = CallDirection.OUTGOING,
            state = CallState.CONFIRMED
        )

        assertEquals(DisconnectCause.LOCAL, CallHangupResolver.resolveDisconnectCause(session))
    }
}
