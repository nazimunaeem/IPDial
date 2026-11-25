package com.ipdial.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator

class CryptoHelperTest {

    private fun testAesKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun aesGcmRoundTrip_preservesAsciiAndUnicode() {
        val key = testAesKey()
        val secrets = listOf(
            "mySecretSipPassword!@#123",
            "pass with spaces & symbols /+=",
            "পাসওয়ার্ড-unicode-🔐"
        )
        for (plain in secrets) {
            val encrypted = CryptoHelper.encryptWithKey(plain, key)
            assertTrue("Expected enc:v1: prefix", encrypted.startsWith("enc:v1:"))
            assertNotEquals(plain, encrypted)
            val decrypted = CryptoHelper.decryptWithKey(encrypted, key)
            assertEquals(plain, decrypted)
        }
    }

    @Test
    fun aesGcm_usesDistinctIvPerEncryption() {
        val key = testAesKey()
        val first = CryptoHelper.encryptWithKey("same-secret", key)
        val second = CryptoHelper.encryptWithKey("same-secret", key)
        assertNotEquals(first, second)
        assertEquals("same-secret", CryptoHelper.decryptWithKey(first, key))
        assertEquals("same-secret", CryptoHelper.decryptWithKey(second, key))
    }

    @Test
    fun legacyBase64Fallback_decodesLegacyAndKeepsPlaintextSafe() {
        val encoded = CryptoHelper.encodeLegacyBase64("legacyPass99")
        assertEquals("legacyPass99", CryptoHelper.decodeLegacyBase64(encoded))
        assertEquals("not!base64?text", CryptoHelper.decodeLegacyBase64("not!base64?text"))
    }
}
