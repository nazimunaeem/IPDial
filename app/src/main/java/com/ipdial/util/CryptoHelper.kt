package com.ipdial.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts sensitive local preferences (SIP & TURN passwords) at rest using
 * an AES-256-GCM key managed by the Android Keystore, with seamless fallback
 * for legacy Base64-obfuscated values so upgrades remain non-destructive.
 */
object CryptoHelper {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "ipdial_prefs_aes_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREFIX = "enc:v1:"
    private const val IV_LENGTH_BYTES = 12
    private const val TAG_LENGTH_BITS = 128

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return plain
        if (plain.startsWith(PREFIX)) return plain
        return try {
            val key = getOrCreateKeystoreKey()
            if (key != null) {
                encryptWithKey(plain, key)
            } else {
                encodeLegacyBase64(plain)
            }
        } catch (_: Throwable) {
            encodeLegacyBase64(plain)
        }
    }

    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return stored
        if (stored.startsWith(PREFIX)) {
            return try {
                val key = getOrCreateKeystoreKey() ?: return stored
                decryptWithKey(stored, key)
            } catch (_: Throwable) {
                ""
            }
        }
        return decodeLegacyBase64(stored)
    }

    internal fun encryptWithKey(plain: String, key: SecretKey): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // Let AndroidKeyStore generate a fresh random IV per encryption (required by Keystore)
        val iv = ByteArray(IV_LENGTH_BYTES)
        val isAndroidKeyStore = key.javaClass.name.contains("AndroidKeyStore", ignoreCase = true)
        if (isAndroidKeyStore) {
            cipher.init(Cipher.ENCRYPT_MODE, key)
        } else {
            SecureRandom().nextBytes(iv)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        }
        val actualIv = cipher.iv
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(actualIv.size + ciphertext.size)
        System.arraycopy(actualIv, 0, combined, 0, actualIv.size)
        System.arraycopy(ciphertext, 0, combined, actualIv.size, ciphertext.size)
        return PREFIX + Base64.getEncoder().encodeToString(combined)
    }

    internal fun decryptWithKey(stored: String, key: SecretKey): String {
        if (!stored.startsWith(PREFIX)) return decodeLegacyBase64(stored)
        val payload = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
        if (payload.size <= IV_LENGTH_BYTES) return ""
        val iv = payload.copyOfRange(0, IV_LENGTH_BYTES)
        val ciphertext = payload.copyOfRange(IV_LENGTH_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    internal fun encodeLegacyBase64(plain: String): String = try {
        Base64.getEncoder().encodeToString(plain.toByteArray(Charsets.UTF_8))
    } catch (_: Throwable) {
        plain
    }

    internal fun decodeLegacyBase64(cipher: String): String {
        return try {
            val trimmed = cipher.trim()
            val decoded = Base64.getDecoder().decode(trimmed)
            val text = String(decoded, Charsets.UTF_8)
            // Verify round-trip Base64 encoding so arbitrary plaintext passwords containing
            // valid Base64 characters aren't garbled on first migration.
            if (Base64.getEncoder().encodeToString(decoded) == trimmed) text else cipher
        } catch (_: Throwable) {
            cipher
        }
    }

    private fun getOrCreateKeystoreKey(): SecretKey? {
        return try {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
            if (existing != null) return existing

            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            generator.init(spec)
            generator.generateKey()
        } catch (_: Throwable) {
            null
        }
    }
}
