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
import com.ipdial.data.model.withSanitizedCodecs
import com.ipdial.data.backup.BackupFrequency
import com.ipdial.data.backup.BackupOptions
import com.ipdial.data.backup.BackupSettings
import com.ipdial.util.CryptoHelper
import com.google.gson.JsonObject
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
    private val lastDialLineKey = stringPreferencesKey("last_dial_line")
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
    private val alwaysAskDialLineKey = booleanPreferencesKey("always_ask_dial_line")
    private val dialpadVibrateKey = booleanPreferencesKey("dialpad_vibrate")
    private val dialpadBeepKey = booleanPreferencesKey("dialpad_beep")
    private val hideDialpadDigitsKey = booleanPreferencesKey("hide_dialpad_digits")
    private val openDialpadAtLaunchKey = booleanPreferencesKey("open_dialpad_at_launch")
    private val groupSubsequentCallsKey = booleanPreferencesKey("group_subsequent_calls")
    private val disableProximityKey = booleanPreferencesKey("disable_proximity_sensor")
    private val savedLabelsKey = stringPreferencesKey("saved_labels")
    private val savedHostsKey = stringPreferencesKey("saved_hosts")

    private val turnServerKey = stringPreferencesKey("turn_server")
    private val turnUsernameKey = stringPreferencesKey("turn_username")
    private val turnPasswordKey = stringPreferencesKey("turn_password")
    private val turnTransportKey = androidx.datastore.preferences.core.stringPreferencesKey("turn_transport")

    private val backupIncludeSettingsKey = booleanPreferencesKey("backup_include_settings")
    private val backupIncludeSipAccountsKey = booleanPreferencesKey("backup_include_sip_accounts")
    private val backupIncludeCallHistoryKey = booleanPreferencesKey("backup_include_call_history")
    private val backupIncludeRecordingsKey = booleanPreferencesKey("backup_include_recordings")
    private val backupAutoEnabledKey = booleanPreferencesKey("backup_auto_enabled")
    private val backupFrequencyKey = stringPreferencesKey("backup_frequency")
    private val backupTreeUriKey = stringPreferencesKey("backup_tree_uri")
    private val backupFolderLabelKey = stringPreferencesKey("backup_folder_label")
    private val backupLastAtMsKey = androidx.datastore.preferences.core.longPreferencesKey("backup_last_at_ms")
    private val backupLastStatusKey = stringPreferencesKey("backup_last_status")
    private val backupLastDestinationKey = stringPreferencesKey("backup_last_destination")

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

    companion object {
        private val _liveRegStatus = MutableStateFlow<Map<String, Pair<RegStatus, String>>>(emptyMap())
    }

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
            if (!acc.isEnabled) {
                acc.copy(regStatus = RegStatus.UNREGISTERED, regStatusText = "Disabled")
            } else {
                val live = statusMap[acc.id]
                if (live != null) {
                    acc.copy(regStatus = live.first, regStatusText = live.second)
                } else {
                    acc.copy(regStatus = RegStatus.UNREGISTERED, regStatusText = "")
                }
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
    val lastDialLineId: Flow<String?> = context.dataStore.data.map { it[lastDialLineKey] }

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
    val alwaysAskDialLine: Flow<Boolean> = context.dataStore.data.map { it[alwaysAskDialLineKey] ?: true }
    val dialpadVibrate: Flow<Boolean> = context.dataStore.data.map { it[dialpadVibrateKey] ?: true }
    val dialpadBeep: Flow<Boolean> = context.dataStore.data.map { it[dialpadBeepKey] ?: false }
    val hideDialpadDigits: Flow<Boolean> = context.dataStore.data.map { it[hideDialpadDigitsKey] ?: false }
    val openDialpadAtLaunch: Flow<Boolean> = context.dataStore.data.map { it[openDialpadAtLaunchKey] ?: false }
    val groupSubsequentCalls: Flow<Boolean> = context.dataStore.data.map { it[groupSubsequentCallsKey] ?: true }
    val disableProximitySensor: Flow<Boolean> = context.dataStore.data.map { it[disableProximityKey] ?: false }

    val backupSettings: Flow<BackupSettings> = context.dataStore.data.map { prefs ->
        BackupSettings(
            options = BackupOptions(
                includeSettings = prefs[backupIncludeSettingsKey] ?: true,
                includeSipAccounts = prefs[backupIncludeSipAccountsKey] ?: true,
                includeCallHistory = prefs[backupIncludeCallHistoryKey] ?: true,
                includeRecordings = prefs[backupIncludeRecordingsKey] ?: true
            ),
            autoBackupEnabled = prefs[backupAutoEnabledKey] ?: false,
            frequency = BackupFrequency.fromNameOrDefault(prefs[backupFrequencyKey]),
            autoBackupTreeUri = prefs[backupTreeUriKey] ?: "",
            autoBackupFolderLabel = prefs[backupFolderLabelKey] ?: "",
            lastBackupAtMs = prefs[backupLastAtMsKey] ?: 0L,
            lastBackupStatus = prefs[backupLastStatusKey] ?: "",
            lastBackupDestination = prefs[backupLastDestinationKey] ?: ""
        )
    }

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
    suspend fun setLastDialLineId(id: String?) = context.dataStore.edit { prefs ->
        if (id.isNullOrBlank()) prefs.remove(lastDialLineKey) else prefs[lastDialLineKey] = id
    }
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
    suspend fun setAlwaysAskDialLine(ask: Boolean) = context.dataStore.edit { it[alwaysAskDialLineKey] = ask }
    suspend fun setDialpadVibrate(enabled: Boolean) = context.dataStore.edit { it[dialpadVibrateKey] = enabled }
    suspend fun setDialpadBeep(enabled: Boolean) = context.dataStore.edit { it[dialpadBeepKey] = enabled }
    suspend fun setHideDialpadDigits(hidden: Boolean) = context.dataStore.edit { it[hideDialpadDigitsKey] = hidden }
    suspend fun setOpenDialpadAtLaunch(enabled: Boolean) = context.dataStore.edit { it[openDialpadAtLaunchKey] = enabled }
    suspend fun setGroupSubsequentCalls(enabled: Boolean) = context.dataStore.edit { it[groupSubsequentCallsKey] = enabled }
    suspend fun setDisableProximitySensor(disabled: Boolean) = context.dataStore.edit { it[disableProximityKey] = disabled }

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
        _liveRegStatus.value = _liveRegStatus.value - id
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
        val sanitized = acc.copy(regStatus = RegStatus.UNREGISTERED, regStatusText = "")
        if (sanitized.password.isBlank()) return sanitized
        return sanitized.copy(password = encryptPassword(sanitized.password))
    }

    private fun unsecureAccount(acc: SipAccount): SipAccount {
        val clean = acc.withSanitizedCodecs()
        if (clean.password.isBlank()) return clean
        return clean.copy(password = decryptPassword(clean.password))
    }

    private fun encryptPassword(plain: String): String = CryptoHelper.encrypt(plain)

    private fun decryptPassword(cipher: String): String = CryptoHelper.decrypt(cipher)

    suspend fun setBackupOptions(options: BackupOptions) = context.dataStore.edit { prefs ->
        prefs[backupIncludeSettingsKey] = options.includeSettings
        prefs[backupIncludeSipAccountsKey] = options.includeSipAccounts
        prefs[backupIncludeCallHistoryKey] = options.includeCallHistory
        prefs[backupIncludeRecordingsKey] = options.includeRecordings
    }

    suspend fun setAutoBackupEnabled(enabled: Boolean) = context.dataStore.edit {
        it[backupAutoEnabledKey] = enabled
    }

    suspend fun setBackupFrequency(frequency: BackupFrequency) = context.dataStore.edit {
        it[backupFrequencyKey] = frequency.name
    }

    suspend fun setAutoBackupFolder(treeUri: String, label: String) = context.dataStore.edit { prefs ->
        prefs[backupTreeUriKey] = treeUri
        prefs[backupFolderLabelKey] = label
    }

    suspend fun recordBackupStatus(timestampMs: Long, status: String, destination: String) =
        context.dataStore.edit { prefs ->
            prefs[backupLastAtMsKey] = timestampMs
            prefs[backupLastStatusKey] = status
            prefs[backupLastDestinationKey] = destination
        }

    suspend fun exportAllSettingsJson(): JsonObject {
        val prefs = context.dataStore.data.first()
        return JsonObject().apply {
            addProperty("themeMode", prefs[themeKey] ?: ThemeMode.System.name)
            addProperty("fontSizeMultiplier", prefs[fontSizeKey]?.toFloatOrNull()?.toDouble() ?: 1.0)
            addProperty("appIconAlias", prefs[appIconKey] ?: "Default")
            addProperty("keypadDesign", prefs[keypadDesignKey] ?: KeypadDesign.Rounded.name)
            addProperty("incomingCallMode", prefs[incomingCallModeKey] ?: IncomingCallMode.Slider.name)
            addProperty("defaultDomain", prefs[defaultDomainKey] ?: "103.129.202.202")
            addProperty("dndEnabled", prefs[dndKey] ?: false)
            addProperty("globalVibrate", prefs[vibrateKey] ?: true)
            addProperty("autoRecordEnabled", prefs[autoRecordKey] ?: false)
            addProperty("globalNoiseCancellation", prefs[globalNoiseCancellationKey] ?: true)
            addProperty("globalEcEnabled", prefs[globalEcEnabledKey] ?: true)
            addProperty("globalNsEnabled", prefs[globalNsEnabledKey] ?: true)
            addProperty("globalAgcEnabled", prefs[globalAgcEnabledKey] ?: true)
            addProperty("sipEcEnabled", prefs[sipEcEnabledKey] ?: false)
            addProperty("sipNsEnabled", prefs[sipNsEnabledKey] ?: false)
            addProperty("turnServer", prefs[turnServerKey] ?: "")
            addProperty("turnUsername", prefs[turnUsernameKey] ?: "")
            addProperty("turnPassword", decryptPassword(prefs[turnPasswordKey] ?: ""))
            addProperty("turnTransport", prefs[turnTransportKey] ?: TurnTransport.UDP.name)
            addProperty("fullScreenContactPhoto", prefs[fullScreenContactPhotoKey] ?: false)
            addProperty("alwaysAskDialLine", prefs[alwaysAskDialLineKey] ?: true)
            addProperty("dialpadVibrate", prefs[dialpadVibrateKey] ?: true)
            addProperty("dialpadBeep", prefs[dialpadBeepKey] ?: false)
            addProperty("hideDialpadDigits", prefs[hideDialpadDigitsKey] ?: false)
            addProperty("openDialpadAtLaunch", prefs[openDialpadAtLaunchKey] ?: false)
            addProperty("groupSubsequentCalls", prefs[groupSubsequentCallsKey] ?: true)
            addProperty("disableProximitySensor", prefs[disableProximityKey] ?: false)
            prefs[ringtoneKey]?.let { addProperty("globalRingtone", it) }
        }
    }

    suspend fun restoreSettingsFromJson(json: JsonObject) {
        fun str(key: String, default: String = ""): String =
            json.get(key)?.takeIf { !it.isJsonNull }?.asString ?: default

        fun bool(key: String, default: Boolean): Boolean =
            runCatching { json.get(key)?.takeIf { !it.isJsonNull }?.asBoolean }.getOrNull() ?: default

        context.dataStore.edit { prefs ->
            if (json.has("themeMode")) {
                val mode = runCatching { ThemeMode.valueOf(str("themeMode")) }.getOrNull()
                if (mode != null) prefs[themeKey] = mode.name
            }
            if (json.has("fontSizeMultiplier")) {
                val mult = runCatching { json.get("fontSizeMultiplier").asFloat }.getOrDefault(1.0f)
                    .coerceIn(0.75f, 1.5f)
                prefs[fontSizeKey] = mult.toString()
            }
            if (json.has("appIconAlias")) {
                prefs[appIconKey] = str("appIconAlias", "Default").ifBlank { "Default" }
            }
            if (json.has("keypadDesign")) {
                val design = runCatching { KeypadDesign.valueOf(str("keypadDesign")) }.getOrNull()
                if (design != null) prefs[keypadDesignKey] = design.name
            }
            if (json.has("incomingCallMode")) {
                val mode = runCatching { IncomingCallMode.valueOf(str("incomingCallMode")) }.getOrNull()
                if (mode != null) prefs[incomingCallModeKey] = mode.name
            }
            if (json.has("defaultDomain")) {
                prefs[defaultDomainKey] = str("defaultDomain", "103.129.202.202")
            }
            if (json.has("dndEnabled")) prefs[dndKey] = bool("dndEnabled", false)
            if (json.has("globalVibrate")) prefs[vibrateKey] = bool("globalVibrate", true)
            if (json.has("autoRecordEnabled")) prefs[autoRecordKey] = bool("autoRecordEnabled", false)
            if (json.has("globalNoiseCancellation")) prefs[globalNoiseCancellationKey] = bool("globalNoiseCancellation", true)
            if (json.has("globalEcEnabled")) prefs[globalEcEnabledKey] = bool("globalEcEnabled", true)
            if (json.has("globalNsEnabled")) prefs[globalNsEnabledKey] = bool("globalNsEnabled", true)
            if (json.has("globalAgcEnabled")) prefs[globalAgcEnabledKey] = bool("globalAgcEnabled", true)
            if (json.has("sipEcEnabled")) prefs[sipEcEnabledKey] = bool("sipEcEnabled", false)
            if (json.has("sipNsEnabled")) prefs[sipNsEnabledKey] = bool("sipNsEnabled", false)
            if (json.has("turnServer")) prefs[turnServerKey] = str("turnServer").trim()
            if (json.has("turnUsername")) prefs[turnUsernameKey] = str("turnUsername").trim()
            if (json.has("turnPassword")) {
                val pass = str("turnPassword").trim()
                prefs[turnPasswordKey] = if (pass.isEmpty()) "" else encryptPassword(pass)
            }
            if (json.has("turnTransport")) {
                val tp = runCatching { TurnTransport.valueOf(str("turnTransport")) }.getOrNull()
                if (tp != null) prefs[turnTransportKey] = tp.name
            }
            if (json.has("fullScreenContactPhoto")) prefs[fullScreenContactPhotoKey] = bool("fullScreenContactPhoto", false)
            if (json.has("alwaysAskDialLine")) prefs[alwaysAskDialLineKey] = bool("alwaysAskDialLine", true)
            if (json.has("dialpadVibrate")) prefs[dialpadVibrateKey] = bool("dialpadVibrate", true)
            if (json.has("dialpadBeep")) prefs[dialpadBeepKey] = bool("dialpadBeep", false)
            if (json.has("hideDialpadDigits")) prefs[hideDialpadDigitsKey] = bool("hideDialpadDigits", false)
            if (json.has("openDialpadAtLaunch")) prefs[openDialpadAtLaunchKey] = bool("openDialpadAtLaunch", false)
            if (json.has("groupSubsequentCalls")) prefs[groupSubsequentCallsKey] = bool("groupSubsequentCalls", true)
            if (json.has("disableProximitySensor")) prefs[disableProximityKey] = bool("disableProximitySensor", false)
            if (json.has("globalRingtone")) {
                val uri = str("globalRingtone")
                if (uri.isNotBlank()) {
                    prefs[ringtoneKey] = uri
                }
            }
        }
    }

    suspend fun mergeRestoredAccounts(restored: List<SipAccount>): Int {
        if (restored.isEmpty()) return 0
        val current = accounts.first().toMutableList()
        var mergedCount = 0
        for (rawIncoming in restored) {
            val incoming = rawIncoming.withSanitizedCodecs()
            if (incoming.username.isBlank() && incoming.domain.isBlank()) continue
            val sanitizedIncoming = incoming.copy(
                id = incoming.id.ifBlank { UUID.randomUUID().toString() },
                regStatus = RegStatus.UNREGISTERED,
                regStatusText = ""
            )
            val existingIndex = current.indexOfFirst {
                it.id == sanitizedIncoming.id ||
                    (it.username.equals(sanitizedIncoming.username, ignoreCase = true) &&
                        it.domain.equals(sanitizedIncoming.domain, ignoreCase = true) &&
                        it.port == sanitizedIncoming.port)
            }
            if (existingIndex >= 0) {
                current[existingIndex] = sanitizedIncoming.copy(id = current[existingIndex].id)
            } else {
                current.add(sanitizedIncoming)
            }
            mergedCount++
        }
        if (mergedCount > 0) {
            saveAccounts(current)
        }
        return mergedCount
    }
}
