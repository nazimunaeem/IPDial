package com.ipdial.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ipdial.data.model.IncomingCallMode
import com.ipdial.data.model.KeypadDesign
import com.ipdial.data.model.SipAccount
import com.ipdial.data.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ipdial_accounts")

class AccountRepository(private val context: Context) {

    private val gson = Gson()
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
    private val autoRecordKey = booleanPreferencesKey("auto_record_enabled")

    val accounts: Flow<List<SipAccount>> = context.dataStore.data.map { prefs ->
        val json = prefs[accountsKey] ?: return@map emptyList()
        val type = object : TypeToken<List<SipAccount>>() {}.type
        gson.fromJson(json, type) ?: emptyList()
    }

    val globalRingtone: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[ringtoneKey] ?: "android.resource://${context.packageName}/raw/ipdial_ringtone"
    }

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

    suspend fun getOrCreateDeviceId(): String {
        val current = context.dataStore.data.map { it[deviceIdKey] }.first()
        if (!current.isNullOrBlank()) return current
        
        // Try to get ANDROID_ID for better persistence across updates/reinstalls
        val androidId = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )
        
        // Use ANDROID_ID if available, otherwise fallback to UUID
        // "9774d56d682e549c" is a common bug ID on some devices to avoid
        val newId = if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
            androidId
        } else {
            UUID.randomUUID().toString()
        }

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
    suspend fun setAutoRecordEnabled(enabled: Boolean) = context.dataStore.edit { it[autoRecordKey] = enabled }

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

    suspend fun saveAccount(account: SipAccount) {
        context.dataStore.edit { prefs ->
            val current = getAccountsList(prefs).toMutableList()
            val idx = current.indexOfFirst { it.id == account.id }
            if (idx >= 0) current[idx] = account else current.add(account)
            prefs[accountsKey] = gson.toJson(current)
        }
    }

    suspend fun deleteAccount(accountId: String) {
        context.dataStore.edit { prefs ->
            val current = getAccountsList(prefs).filter { it.id != accountId }
            prefs[accountsKey] = gson.toJson(current)
        }
    }

    suspend fun setDefault(accountId: String) {
        context.dataStore.edit { prefs ->
            val current = getAccountsList(prefs).map { acc ->
                acc.copy(isDefault = acc.id == accountId)
            }
            prefs[accountsKey] = gson.toJson(current)
        }
    }

    suspend fun updateRegStatus(accountId: String, status: com.ipdial.data.model.RegStatus, text: String = "") {
        context.dataStore.edit { prefs ->
            val current = getAccountsList(prefs).map { acc ->
                if (acc.id == accountId) acc.copy(regStatus = status, regStatusText = text) else acc
            }
            prefs[accountsKey] = gson.toJson(current)
        }
    }

    private fun getAccountsList(prefs: Preferences): List<SipAccount> {
        val json = prefs[accountsKey] ?: return emptyList()
        val type = object : TypeToken<List<SipAccount>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
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
            prefs[accountsKey] = gson.toJson(existing)
        }
        return imported.size
    }
}
