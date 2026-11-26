package com.ipdial.service

import android.telecom.Call
import android.telephony.TelephonyManager
import com.ipdial.data.model.CallSession
import com.ipdial.data.model.CallTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallAudioQualityHelperTest {

    @Test
    fun isTelecomHdAudioFromProperties_detectsPropertyHighDefAudioBit() {
        assertFalse(CallAudioQualityHelper.isTelecomHdAudioFromProperties(0))
        assertFalse(CallAudioQualityHelper.isTelecomHdAudioFromProperties(Call.Details.PROPERTY_WIFI))
        assertTrue(CallAudioQualityHelper.isTelecomHdAudioFromProperties(Call.Details.PROPERTY_HIGH_DEF_AUDIO))
        assertTrue(
            CallAudioQualityHelper.isTelecomHdAudioFromProperties(
                Call.Details.PROPERTY_WIFI or Call.Details.PROPERTY_HIGH_DEF_AUDIO
            )
        )
    }

    @Test
    fun isSipCodecHd_recognizesWidebandAndRejectsNarrowband() {
        assertTrue(CallAudioQualityHelper.isSipCodecHd("OPUS/48000/2"))
        assertTrue(CallAudioQualityHelper.isSipCodecHd("G722/16000/1"))
        assertTrue(CallAudioQualityHelper.isSipCodecHd("AMR-WB/16000/1"))
        assertTrue(CallAudioQualityHelper.isSipCodecHd("EVS/16000/1"))

        assertFalse(CallAudioQualityHelper.isSipCodecHd("PCMA/8000/1"))
        assertFalse(CallAudioQualityHelper.isSipCodecHd("PCMU/8000/1"))
        assertFalse(CallAudioQualityHelper.isSipCodecHd("G729/8000/1"))
        assertFalse(CallAudioQualityHelper.isSipCodecHd("GSM/8000/1"))
        assertFalse(CallAudioQualityHelper.isSipCodecHd(null))
        assertFalse(CallAudioQualityHelper.isSipCodecHd(""))
    }

    @Test
    fun hdBadgeText_formatsSimAndSipCorrectly() {
        val simNonHd = CallSession(transport = CallTransport.SIM, isHdAudio = false)
        assertNull(CallAudioQualityHelper.hdBadgeText(simNonHd))

        val simHd = CallSession(transport = CallTransport.SIM, isHdAudio = true)
        assertEquals("HD", CallAudioQualityHelper.hdBadgeText(simHd))

        val sipNarrow = CallSession(
            transport = CallTransport.SIP,
            negotiatedCodec = "PCMA/8000/1",
            isHdAudio = false
        )
        assertNull(CallAudioQualityHelper.hdBadgeText(sipNarrow))

        val sipOpus = CallSession(
            transport = CallTransport.SIP,
            negotiatedCodec = "OPUS/48000/2",
            isHdAudio = true
        )
        assertEquals("HD · Opus", CallAudioQualityHelper.hdBadgeText(sipOpus))
    }

    @Test
    fun isVoiceNetworkHdCapable_trueForLteNrIwlanOnly() {
        assertTrue(CallAudioQualityHelper.isVoiceNetworkHdCapable(TelephonyManager.NETWORK_TYPE_LTE))
        assertTrue(CallAudioQualityHelper.isVoiceNetworkHdCapable(TelephonyManager.NETWORK_TYPE_NR))
        assertTrue(CallAudioQualityHelper.isVoiceNetworkHdCapable(TelephonyManager.NETWORK_TYPE_IWLAN))
        assertFalse(CallAudioQualityHelper.isVoiceNetworkHdCapable(TelephonyManager.NETWORK_TYPE_UMTS))
        assertFalse(CallAudioQualityHelper.isVoiceNetworkHdCapable(TelephonyManager.NETWORK_TYPE_GSM))
    }
}
