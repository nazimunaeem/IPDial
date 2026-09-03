package com.ipdial.data.repository

import android.content.ContentResolver
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import com.ipdial.R
import java.lang.reflect.Type
import com.ipdial.data.model.IncomingCallMode
import com.ipdial.data.model.KeypadDesign
import com.ipdial.data.model.PreferredCodec
import com.ipdial.data.model.SipAccount
import com.ipdial.data.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ipdial_accounts")

class AccountRepository(private val context: Context) {

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(PreferredCodec::class.java, object : JsonDeserializer<PreferredCodec> {
            override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): PreferredCodec? {
                return try {
                    PreferredCodec.valueOf(json.asString)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
        })
        .create()
    private val accountsKey = stringPreferencesKey("accounts")
    private val ringtoneKey = stringPreferencesKey("global_ringtone")
    private val dndKey = booleanPreferencesKey("dnd_enabled")
    private val vibrateKey = booleanPreferencesKey("global_vibrate")
    private val themeKey = stringPreferencesKey("theme_mode")
    private val callingCardsKey = booleanPreferencesKey("calling_cards")
    private val fontSizeKey = stringPreferencesKey("font_size_multiplier")
    private val appIconKey = stringPreferencesKey("app_icon_alias")
    private val keypadDesignKey = stringPreferencesKey("keypad_design")
    private val incomingCallModeKey = stringPreferencesKey("incoming_call_mode")
    private val defaultDomainKey = stringPreferencesKey("default_domain")
    private val lastDialedKey = stringPreferencesKey("last_dialed")
    private val adsEnabledKey = booleanPreferencesKey("ads_enabled")
    private val deviceIdKey = stringPreferencesKey("device_id")
    private val proPointsKey = androidx.datastore.preferences.core.intPreferencesKey("pro_points")
    private val proExpirationKey = androidx.datastore.preferences.core.longPreferencesKey("pro_expiration")
    private val recordingCounterKey = androidx.datastore.preferences.core.intPreferencesKey("recording_counter")
    private val batteryNoticeShownKey = booleanPreferencesKey("battery_notice_shown")
    private val autoRecordKey = booleanPreferencesKey("auto_record_enabled")
    private val globalNoiseCancellationKey = booleanPreferencesKey("global_noise_cancellation")
    private val savedLabelsKey = stringPreferencesKey("saved_labels")
    private val savedHostsKey = stringPreferencesKey("saved_hosts")

    val savedLabels: Flow<List<String>> = context.dataStore.data.map { prefs ->
        val json = prefs[savedLabelsKey] ?: "[]"
        val type = object : TypeToken<List<String>>() {}.type
        gson.fromJson(json, type) ?: emptyList()
    }

    val savedHosts: Flow<List<String>> = context.dataStore.data.map { prefs ->
        val json = prefs[savedHostsKey] ?: "[]"
        val type = object : TypeToken<List<String>>() {}.type
        gson.fromJson(json, type) ?: emptyList()
    }

    suspend fun addSavedLabel(label: String) {
        if (label.isBlank()) return
        val current = savedLabels.first().toMutableSet()
        current.add(label)
        context.dataStore.edit { prefs ->
            prefs[savedLabelsKey] = gson.toJson(current.toList())
        }
    }

    suspend fun addSavedHost(host: String) {
        if (host.isBlank()) return
        val current = savedHosts.first().toMutableSet()
        current.add(host)
        context.dataStore.edit { prefs ->
            prefs[savedHostsKey] = gson.toJson(current.toList())
        }
    }

    val accounts: Flow<List<SipAccount>> = context.dataStore.data.map { prefs ->
        val json = prefs[accountsKey] ?: return@map emptyList()
        val type = object : TypeToken<List<SipAccount>>() {}.type
        val list: List<SipAccount> = gson.fromJson(json, type) ?: emptyList()
        list.map { unsecureAccount(it) }
    }

    val globalRingtone: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[ringtoneKey] ?: builtInRingtoneUri()
    }

    fun builtInRingtoneUri(): String =
        ContentResolver.SCHEME_ANDROID_RESOURCE + "://" +
            context.packageName + "/" + R.raw.ipdial_ringtone

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs -> 
        try { ThemeMode.valueOf(prefs[themeKey] ?: "System") } catch (_: Exception) { ThemeMode.System }
    }
    val callingCardsEnabled: Flow<Boolean> = context.dataStore.data.map { prefs -> prefs[callingCardsKey] ?: true }
    val dndEnabled: Flow<Boolean> = context.dataStore.data.map { prefs -> prefs[dndKey] ?: false }
    val globalVibrate: Flow<Boolean> = context.dataStore.data.map { prefs -> prefs[vibrateKey] ?: true }

    val fontSizeMultiplier: Flow<Float> = context.dataStore.data.map { prefs -> 
        prefs[fontSizeKey]?.toFloatOrNull() ?: 1.0f 
    }
    
    val appIconAlias: Flow<String> = context.dataStore.data.map { prefs -> 
        prefs[appIconKey] ?: "Default"
    }

    val keypadDesign: Flow<KeypadDesign> = context.dataStore.data.map { prefs ->
        try { KeypadDesign.valueOf(prefs[keypadDesignKey] ?: "Rounded") } catch (_: Exception) { KeypadDesign.Rounded }
    }

    val incomingCallMode: Flow<IncomingCallMode> = context.dataStore.data.map { prefs ->
        try { IncomingCallMode.valueOf(prefs[incomingCallModeKey] ?: "Slider") } catch (_: Exception) { IncomingCallMode.Slider }
    }

    val defaultDomain: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[defaultDomainKey] ?: "103.129.202.202"
    }

    val lastDialedNumber: Flow<String?> = context.dataStore.data.map { it[lastDialedKey] }

    val adsEnabled: Flow<Boolean> = context.dataStore.data.map { it[adsEnabledKey] ?: true }

    val deviceId: Flow<String?> = context.dataStore.data.map { it[deviceIdKey] }
    val autoRecordEnabled: Flow<Boolean> = context.dataStore.data.map { it[autoRecordKey] ?: false }

    val proPoints: Flow<Int> = context.dataStore.data.map { it[proPointsKey] ?: 3 }
    val proExpiration: Flow<Long> = context.dataStore.data.map { it[proExpirationKey] ?: 0L }
    val recordingCounter: Flow<Int> = context.dataStore.data.map { it[recordingCounterKey] ?: 0 }
    val batteryNoticeShown: Flow<Boolean> = context.dataStore.data.map { it[batteryNoticeShownKey] ?: false }
    val globalNoiseCancellation: Flow<Boolean> = context.dataStore.data.map { it[globalNoiseCancellationKey] ?: true }

    suspend fun getOrCreateDeviceId(): String {
        val current = context.dataStore.data.map { it[deviceIdKey] }.first()
        if (!current.isNullOrBlank()) return current

        // Use a fresh random UUID so the identifier is device-specific but not
        // linkable to ANDROID_ID (avoids persistent cross-app tracking exposure).
        val newId = UUID.randomUUID().toString()

        context.dataStore.edit { it[deviceIdKey] = newId }
        return newId
    }

    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[themeKey] = mode.name }
    suspend fun setCallingCards(enabled: Boolean) = context.dataStore.edit { it[callingCardsKey] = enabled }
    suspend fun setDnd(enabled: Boolean) = context.dataStore.edit { it[dndKey] = enabled }
    suspend fun setGlobalVibrate(enabled: Boolean) = context.dataStore.edit { it[vibrateKey] = enabled }
    
    suspend fun setFontSizeMultiplier(multiplier: Float) = context.dataStore.edit { it[fontSizeKey] = multiplier.toString() }
    suspend fun setAppIconAlias(alias: String) = context.dataStore.edit { it[appIconKey] = alias }
    suspend fun setKeypadDesign(design: KeypadDesign) = context.dataStore.edit { it[keypadDesignKey] = design.name }
    suspend fun setIncomingCallMode(mode: IncomingCallMode) = context.dataStore.edit { it[incomingCallModeKey] = mode.name }
    suspend fun setDefaultDomain(domain: String) = context.dataStore.edit { it[defaultDomainKey] = domain }
    suspend fun setLastDialedNumber(number: String) = context.dataStore.edit { it[lastDialedKey] = number }
    suspend fun setAdsEnabled(enabled: Boolean) = context.dataStore.edit { it[adsEnabledKey] = enabled }
    suspend fun setDeviceId(id: String) = context.dataStore.edit { it[deviceIdKey] = id }
    suspend fun setProPoints(points: Int) = context.dataStore.edit { it[proPointsKey] = points }
    suspend fun setProExpiration(expiration: Long) = context.dataStore.edit { it[proExpirationKey] = expiration }
    suspend fun setRecordingCounter(counter: Int) = context.dataStore.edit { it[recordingCounterKey] = counter }
    suspend fun setBatteryNoticeShown(shown: Boolean) = context.dataStore.edit { it[batteryNoticeShownKey] = shown }
    suspend fun setAutoRecordEnabled(enabled: Boolean) = context.dataStore.edit { it[autoRecordKey] = enabled }
    suspend fun setGlobalNoiseCancellation(enabled: Boolean) = context.dataStore.edit { it[globalNoiseCancellationKey] = enabled }

    suspend fun setGlobalRingtone(uri: String?) {
        context.dataStore.edit { prefs ->
            if (uri == null) prefs.remove(ringtoneKey)
            else prefs[ringtoneKey] = uri
        }
    }

    suspend fun resetSettings() {
        context.dataStore.edit { prefs ->
            prefs.remove(themeKey)
            prefs.remove(fontSizeKey)
            prefs.remove(ringtoneKey)
            prefs.remove(vibrateKey)
            prefs.remove(callingCardsKey)
            prefs.remove(dndKey)
            prefs.remove(keypadDesignKey)
            prefs.remove(appIconKey)
            prefs.remove(incomingCallModeKey)
        }
    }

    private object CryptoHelper {
        private const val KEY_ALIAS = "ipdial_sip_crypto_key"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        private fun getSecretKey(): java.security.Key? {
            return try {
                val keyStore = java.security.KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
                if (!keyStore.containsAlias(KEY_ALIAS)) {
                    val keyGenerator = javax.crypto.KeyGenerator.getInstance(
                        android.security.keystore.KeyProperties.KEY_ALGORITHM_AES,
                        ANDROID_KEY_STORE
                    )
                    val spec = android.security.keystore.KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                    keyGenerator.init(spec)
                    keyGenerator.generateKey()
                }
                keyStore.getKey(KEY_ALIAS, null)
            } catch (e: Throwable) {
                android.util.Log.e("CryptoHelper", "getSecretKey failed", e)
                null
            }
        }

        fun encrypt(plaintext: String): String {
            if (plaintext.isEmpty()) return ""
            return try {
                val key = getSecretKey() ?: return plaintext
                val cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION)
                cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
                val iv = cipher.iv
                val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
                val ivBase64 = android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP)
                val cipherBase64 = android.util.Base64.encodeToString(ciphertext, android.util.Base64.NO_WRAP)
                "AES:$ivBase64:$cipherBase64"
            } catch (e: Throwable) {
                android.util.Log.e("CryptoHelper", "Encryption failed", e)
                plaintext
            }
        }

        fun decrypt(encrypted: String): String {
            if (!encrypted.startsWith("AES:")) return encrypted
            return try {
                val parts = encrypted.split(":")
                if (parts.size != 3) {
                    android.util.Log.e("CryptoHelper", "Invalid AES format: parts=${parts.size}")
                    return encrypted
                }
                val iv = android.util.Base64.decode(parts[1], android.util.Base64.NO_WRAP)
                val ciphertext = android.util.Base64.decode(parts[2], android.util.Base64.NO_WRAP)
                
                val key = getSecretKey() ?: run {
                    android.util.Log.e("CryptoHelper", "KeyStore key is null during decryption")
                    return encrypted
                }
                val cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION)
                val spec = javax.crypto.spec.GCMParameterSpec(128, iv)
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, spec)
                String(cipher.doFinal(ciphertext), Charsets.UTF_8)
            } catch (e: Throwable) {
                android.util.Log.e("CryptoHelper", "Decryption failed: ${e.message}", e)
                encrypted
            }
        }
    }

    private fun decryptOldPassword(password: String): String {
        if (!password.startsWith("ENC:")) return password
        return try {
            val raw = password.removePrefix("ENC:")
            String(android.util.Base64.decode(raw, android.util.Base64.NO_WRAP), Charsets.UTF_8)
        } catch (_: Throwable) {
            password
        }
    }

    private fun decryptPassword(password: String): String {
        return when {
            password.startsWith("AES:") -> CryptoHelper.decrypt(password)
            password.startsWith("ENC:") -> decryptOldPassword(password)
            else -> password
        }
    }

    private fun encryptPassword(password: String): String {
        // Already-encrypted values (or empty passwords) are written through untouched
        // to avoid double-encrypting migrated data.
        if (password.isEmpty()) return password
        if (password.startsWith("AES:") || password.startsWith("ENC:")) return password
        return CryptoHelper.encrypt(password)
    }

    private fun secureAccount(acc: SipAccount): SipAccount = acc.copy(password = encryptPassword(acc.password))
    private fun unsecureAccount(acc: SipAccount): SipAccount = acc.copy(password = decryptPassword(acc.password))

    private fun getAccountsList(prefs: Preferences): List<SipAccount> {
        val json = prefs[accountsKey] ?: return emptyList()
        val type = object : TypeToken<List<SipAccount>>() {}.type
        val list: List<SipAccount> = gson.fromJson(json, type) ?: emptyList()
        // unsecureAccount here will migrate existing encrypted passwords to plain text in memory
        return list.map { unsecureAccount(it) }
    }

    private fun saveAccountsList(prefs: androidx.datastore.preferences.core.MutablePreferences, accountsList: List<SipAccount>) {
        // Encrypt SIP passwords with the AndroidKeyStore-backed key before persisting
        // so credentials are never stored in plain text.
        prefs[accountsKey] = gson.toJson(accountsList.map { secureAccount(it) })
    }

    suspend fun saveAccount(account: SipAccount) {
        context.dataStore.edit { prefs ->
            val current = getAccountsList(prefs).toMutableList()
            val idx = current.indexOfFirst { it.id == account.id }
            if (idx >= 0) current[idx] = account else current.add(account)
            saveAccountsList(prefs, current)
        }
    }

    suspend fun deleteAccount(accountId: String) {
        context.dataStore.edit { prefs ->
            val current = getAccountsList(prefs).filter { it.id != accountId }
            saveAccountsList(prefs, current)
        }
    }

    suspend fun setDefault(accountId: String) {
        context.dataStore.edit { prefs ->
            val current = getAccountsList(prefs).map { acc ->
                acc.copy(isDefault = acc.id == accountId)
            }
            saveAccountsList(prefs, current)
        }
    }

    suspend fun updateRegStatus(accountId: String, status: com.ipdial.data.model.RegStatus, text: String = "") {
        context.dataStore.edit { prefs ->
            val current = getAccountsList(prefs).map { acc ->
                if (acc.id == accountId) acc.copy(regStatus = status, regStatusText = text) else acc
            }
            saveAccountsList(prefs, current)
        }
    }

    suspend fun exportAccountsJson(): String {
        val accounts = context.dataStore.data.first().let { getAccountsList(it) }
        return gson.toJson(accounts)
    }

    suspend fun importAccountsJson(json: String): Int {
        val type = object : TypeToken<List<SipAccount>>() {}.type
        val imported: List<SipAccount> = gson.fromJson(json, type) ?: return 0
        context.dataStore.edit { prefs ->
            val existing = getAccountsList(prefs).toMutableList()
            for (acc in imported) {
                val idx = existing.indexOfFirst { it.id == acc.id }
                if (idx >= 0) existing[idx] = acc else existing.add(acc)
            }
            saveAccountsList(prefs, existing)
        }
        return imported.size
    }
}
