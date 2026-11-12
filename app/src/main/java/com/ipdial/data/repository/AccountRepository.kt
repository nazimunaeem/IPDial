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
import com.ipdial.data.model.RegStatus
import com.ipdial.data.model.SipAccount
import com.ipdial.data.model.ThemeMode
import com.ipdial.data.model.TurnTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
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
    private val fontSizeKey = stringPreferencesKey("font_size_multiplier")
    private val appIconKey = stringPreferencesKey("app_icon_alias")
    private val keypadDesignKey = stringPreferencesKey("keypad_design")
    private val incomingCallModeKey = stringPreferencesKey("incoming_call_mode")
    private val defaultDomainKey = stringPreferencesKey("default_domain")
    private val lastDialedKey = stringPreferencesKey("last_dialed")
    private val deviceIdKey = stringPreferencesKey("device_id")
    private val recordingCounterKey = androidx.datastore.preferences.core.intPreferencesKey("recording_counter")
    private val batteryNoticeShownKey = booleanPreferencesKey("battery_notice_shown")
    private val autoRecordKey = booleanPreferencesKey("auto_record_enabled")
    private val globalNoiseCancellationKey = booleanPreferencesKey("global_noise_cancellation")
    private val globalEcEnabledKey = booleanPreferencesKey("global_ec_enabled")
    private val globalNsEnabledKey = booleanPreferencesKey("global_ns_enabled")
    private val globalAgcEnabledKey = booleanPreferencesKey("global_agc_enabled")
    private val sipEcEnabledKey = booleanPreferencesKey("sip_ec_enabled")
    private val sipNsEnabledKey = booleanPreferencesKey("sip_ns_enabled")
    private val fullScreenContactPhotoKey = booleanPreferencesKey("full_screen_contact_photo")
    private val savedLabelsKey = stringPreferencesKey("saved_labels")
    private val savedHostsKey = stringPreferencesKey("saved_hosts")

    private val turnServerKey = stringPreferencesKey("turn_server")
    private val turnUsernameKey = stringPreferencesKey("turn_username")
    private val turnPasswordKey = stringPreferencesKey("turn_password")
    private val turnTransportKey = androidx.datastore.preferences.core.stringPreferencesKey("turn_transport")

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

    private val _liveRegStatus = MutableStateFlow<Map<String, Pair<RegStatus, String>>>(emptyMap())

    val accounts: Flow<List<SipAccount>> = combine(
        context.dataStore.data.map { prefs ->
            val json = prefs[accountsKey] ?: return@map emptyList()
            val type = object : TypeToken<List<SipAccount>>() {}.type
            val list: List<SipAccount> = gson.fromJson(json, type) ?: emptyList()
            list.map { unsecureAccount(it) }
        },
        _liveRegStatus
    ) { accountList, statusMap ->
        accountList.map { acc ->
            val live = statusMap[acc.id]
            if (live != null) {
                acc.copy(regStatus = live.first, regStatusText = live.second)
            } else {
                acc
            }
        }
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

    val deviceId: Flow<String?> = context.dataStore.data.map { it[deviceIdKey] }
    val autoRecordEnabled: Flow<Boolean> = context.dataStore.data.map { it[autoRecordKey] ?: false }

    val recordingCounter: Flow<Int> = context.dataStore.data.map { it[recordingCounterKey] ?: 0 }
    val batteryNoticeShown: Flow<Boolean> = context.dataStore.data.map { it[batteryNoticeShownKey] ?: false }
    val globalNoiseCancellation: Flow<Boolean> = context.dataStore.data.map { prefs -> prefs[globalNoiseCancellationKey] ?: true }
    val globalEcEnabled: Flow<Boolean> = context.dataStore.data.map { prefs -> prefs[globalEcEnabledKey] ?: true }
    val globalNsEnabled: Flow<Boolean> = context.dataStore.data.map { prefs -> prefs[globalNsEnabledKey] ?: true }
    val globalAgcEnabled: Flow<Boolean> = context.dataStore.data.map { prefs -> prefs[globalAgcEnabledKey] ?: true }

    val turnServer: Flow<String> = context.dataStore.data.map { it[turnServerKey] ?: "" }
    val turnUsername: Flow<String> = context.dataStore.data.map { it[turnUsernameKey] ?: "" }
    val turnPassword: Flow<String> = context.dataStore.data.map { decryptPassword(it[turnPasswordKey] ?: "") }
    val turnTransport: Flow<TurnTransport> = context.dataStore.data.map { prefs ->
        try { TurnTransport.valueOf(prefs[turnTransportKey] ?: "UDP") } catch (_: Exception) { TurnTransport.UDP }
    }

    val sipEcEnabled: Flow<Boolean> = context.dataStore.data.map { it[sipEcEnabledKey] ?: false }
    val sipNsEnabled: Flow<Boolean> = context.dataStore.data.map { it[sipNsEnabledKey] ?: false }
    val fullScreenContactPhoto: Flow<Boolean> = context.dataStore.data.map { it[fullScreenContactPhotoKey] ?: false }

    suspend fun getOrCreateDeviceId(): String {
        val current = context.dataStore.data.map { it[deviceIdKey] }.first()
        if (!current.isNullOrBlank()) return current
        val newId = UUID.randomUUID().toString()
        context.dataStore.edit { it[deviceIdKey] = newId }
        return newId
    }

    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[themeKey] = mode.name }
    suspend fun setDnd(enabled: Boolean) = context.dataStore.edit { it[dndKey] = enabled }
    suspend fun setGlobalVibrate(enabled: Boolean) = context.dataStore.edit { it[vibrateKey] = enabled }
    suspend fun setFontSizeMultiplier(multiplier: Float) = context.dataStore.edit { it[fontSizeKey] = multiplier.toString() }
    suspend fun setAppIconAlias(alias: String) = context.dataStore.edit { it[appIconKey] = alias }
    suspend fun setKeypadDesign(design: KeypadDesign) = context.dataStore.edit { it[keypadDesignKey] = design.name }
    suspend fun setIncomingCallMode(mode: IncomingCallMode) = context.dataStore.edit { it[incomingCallModeKey] = mode.name }
    suspend fun setDefaultDomain(domain: String) = context.dataStore.edit { it[defaultDomainKey] = domain }
    suspend fun setLastDialedNumber(number: String) = context.dataStore.edit { it[lastDialedKey] = number }
    suspend fun setDeviceId(id: String) = context.dataStore.edit { it[deviceIdKey] = id }
    suspend fun setRecordingCounter(counter: Int) = context.dataStore.edit { it[recordingCounterKey] = counter }
    suspend fun setBatteryNoticeShown(shown: Boolean) = context.dataStore.edit { it[batteryNoticeShownKey] = shown }
    suspend fun setAutoRecordEnabled(enabled: Boolean) = context.dataStore.edit { it[autoRecordKey] = enabled }
    suspend fun setGlobalNoiseCancellation(enabled: Boolean) = context.dataStore.edit { it[globalNoiseCancellationKey] = enabled }
    suspend fun setGlobalEcEnabled(enabled: Boolean) = context.dataStore.edit { it[globalEcEnabledKey] = enabled }
    suspend fun setGlobalNsEnabled(enabled: Boolean) = context.dataStore.edit { it[globalNsEnabledKey] = enabled }
    suspend fun setGlobalAgcEnabled(enabled: Boolean) = context.dataStore.edit { it[globalAgcEnabledKey] = enabled }

    suspend fun setTurnServer(server: String) = context.dataStore.edit { it[turnServerKey] = server.trim() }
    suspend fun setTurnUsername(username: String) = context.dataStore.edit { it[turnUsernameKey] = username.trim() }
    suspend fun setTurnPassword(password: String) = context.dataStore.edit {
        it[turnPasswordKey] = encryptPassword(password.trim())
    }
    suspend fun setTurnTransport(tp: TurnTransport) = context.dataStore.edit { it[turnTransportKey] = tp.name }

    suspend fun setSipEcEnabled(enabled: Boolean) = context.dataStore.edit { it[sipEcEnabledKey] = enabled }
    suspend fun setSipNsEnabled(enabled: Boolean) = context.dataStore.edit { it[sipNsEnabledKey] = enabled }
    suspend fun setFullScreenContactPhoto(enabled: Boolean) = context.dataStore.edit { it[fullScreenContactPhotoKey] = enabled }

    suspend fun setGlobalRingtone(uri: String?) {
        context.dataStore.edit { prefs ->
            if (uri == null) prefs.remove(ringtoneKey)
            else prefs[ringtoneKey] = uri
        }
    }

    suspend fun resetSettings() = context.dataStore.edit { prefs ->
        prefs.remove(themeKey)
        prefs.remove(fontSizeKey)
        prefs.remove(appIconKey)
        prefs.remove(keypadDesignKey)
        prefs.remove(incomingCallModeKey)
        prefs.remove(defaultDomainKey)
    }

    suspend fun saveAccounts(list: List<SipAccount>) {
        val secured = list.map { secureAccount(it) }
        val json = gson.toJson(secured)
        context.dataStore.edit { prefs ->
            prefs[accountsKey] = json
        }
    }

    suspend fun saveAccount(account: SipAccount) {
        val current = accounts.first().toMutableList()
        val index = current.indexOfFirst { it.id == account.id }
        if (index >= 0) {
            current[index] = account
        } else {
            current.add(account)
        }
        saveAccounts(current)
    }

    suspend fun deleteAccount(id: String) {
        val current = accounts.first().filter { it.id != id }
        saveAccounts(current)
    }

    suspend fun setDefault(id: String) {
        val current = accounts.first().map { it.copy(isDefault = (it.id == id)) }
        saveAccounts(current)
    }

    suspend fun updateRegStatus(accountId: String, status: RegStatus, text: String = "") {
        val current = _liveRegStatus.value.toMutableMap()
        current[accountId] = Pair(status, text)
        _liveRegStatus.value = current
    }

    private fun secureAccount(acc: SipAccount): SipAccount {
        if (acc.password.isBlank()) return acc
        return acc.copy(password = encryptPassword(acc.password))
    }

    private fun unsecureAccount(acc: SipAccount): SipAccount {
        if (acc.password.isBlank()) return acc
        return acc.copy(password = decryptPassword(acc.password))
    }

    private fun encryptPassword(plain: String): String {
        return try {
            val bytes = plain.toByteArray(Charsets.UTF_8)
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } catch (_: Exception) {
            plain
        }
    }

    private fun decryptPassword(cipher: String): String {
        return try {
            val decoded = android.util.Base64.decode(cipher, android.util.Base64.NO_WRAP)
            String(decoded, Charsets.UTF_8)
        } catch (_: Exception) {
            cipher
        }
    }
}
