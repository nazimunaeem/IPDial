package com.ipdial.service.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UssdHelperTest {

    @Test
    fun isUssdCode_detectsCarrierUssdAndMmiCodes() {
        assertTrue(UssdHelper.isUssdCode("*121#"))
        assertTrue(UssdHelper.isUssdCode("*247#"))
        assertTrue(UssdHelper.isUssdCode("*123*1*4#"))
        assertTrue(UssdHelper.isUssdCode("#31#"))
        assertTrue(UssdHelper.isUssdCode("*#06#"))
        assertTrue(UssdHelper.isUssdCode("*#*#4636#*#*"))
        assertTrue(UssdHelper.isUssdCode("**21*+8801712345678#"))
    }

    @Test
    fun isUssdCode_rejectsNormalPhoneNumbersAndIncompletePrefixes() {
        assertFalse(UssdHelper.isUssdCode("+8801712345678"))
        assertFalse(UssdHelper.isUssdCode("01712345678"))
        assertFalse(UssdHelper.isUssdCode("#31#01712345678"))
        assertFalse(UssdHelper.isUssdCode("*6701712345678"))
        assertFalse(UssdHelper.isUssdCode("*#"))
        assertFalse(UssdHelper.isUssdCode(""))
    }

    @Test
    fun extractSecretCode_extractsInnerCodeOnly() {
        assertEquals("4636", UssdHelper.extractSecretCode("*#*#4636#*#*"))
        assertEquals("64663", UssdHelper.extractSecretCode(" *#*#64663#*#* "))
        assertNull(UssdHelper.extractSecretCode("*121#"))
        assertNull(UssdHelper.extractSecretCode("*#06#"))
        assertNull(UssdHelper.extractSecretCode("*#*##*#*"))
    }

    @Test
    fun isLocalDeviceInfoCode_matchesImeiAndSarOnly() {
        assertTrue(UssdHelper.isLocalDeviceInfoCode("*#06#"))
        assertTrue(UssdHelper.isLocalDeviceInfoCode(" *#07# "))
        assertFalse(UssdHelper.isLocalDeviceInfoCode("*121#"))
        assertFalse(UssdHelper.isLocalDeviceInfoCode("*#21#"))
    }

    @Test
    fun encodeTelUssdUriString_percentEncodesHash() {
        assertEquals("tel:*121%23", UssdHelper.encodeTelUssdUriString("*121#"))
        assertEquals("tel:*123*1*4%23", UssdHelper.encodeTelUssdUriString(" *123*1*4# "))
        assertEquals("tel:*%2306%23", UssdHelper.encodeTelUssdUriString("*#06#"))
    }
}
