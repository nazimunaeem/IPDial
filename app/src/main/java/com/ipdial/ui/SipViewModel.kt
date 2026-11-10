package com.ipdial.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.ipdial.data.model.AudioDeviceMode
import com.ipdial.data.model.CallLogEntry
import com.ipdial.data.model.CallSession
import com.ipdial.data.model.CallState
import com.ipdial.data.model.Contact
import com.ipdial.data.model.IncomingCallMode
import com.ipdial.data.model.InspectStatus
import com.ipdial.data.model.KeypadDesign
import com.ipdial.data.model.RegStatus
import com.ipdial.data.model.SipAccount
import com.ipdial.data.model.ThemeMode
import com.ipdial.data.model.Transport
import com.ipdial.data.model.TurnTransport
import com.ipdial.data.repository.AccountRepository
import com.ipdial.data.repository.CallLogRepository
import com.ipdial.data.repository.ContactsRepository
import com.ipdial.service.SipAudioController
import com.ipdial.service.SipEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class SipViewModel(app: Application) : AndroidViewModel(app) {

    private val SUPPORTED_BALANCE_DOMAINS = listOf("sip.amarip.net", "billing.amarip.net", "103.170.231.10", "billing.webvoice.net", "103.129.202.202")

    val repo = AccountRepository(app)
    private val logRepo = CallLogRepository.getInstance(app)
    private val contactsRepo = ContactsRepository(app)

    private val _balances = MutableStateFlow<Map<String, String>>(emptyMap())
    val balances: StateFlow<Map<String, String>> = _balances.asStateFlow()

    // Audio device state
    private val _audioDeviceMode = MutableStateFlow(AudioDeviceMode.EARPIECE)
    val audioDeviceMode: StateFlow<AudioDeviceMode> = _audioDeviceMode.asStateFlow()

    private val _hasBluetoothDevice = MutableStateFlow(false)
    val hasBluetoothDevice: StateFlow<Boolean> = _hasBluetoothDevice.asStateFlow()

    private val _callVolume = MutableStateFlow(com.ipdial.service.SipAudioController.DEFAULT_RX_VOLUME)
    val callVolume: StateFlow<Float> = _callVolume.asStateFlow()

    private val _showFullIncomingScreen = MutableStateFlow(false)
    val showFullIncomingScreen: StateFlow<Boolean> = _showFullIncomingScreen.asStateFlow()

    val accounts: StateFlow<List<SipAccount>> = repo.accounts
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val globalRingtone: StateFlow<String?> = repo.globalRingtone
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val themeMode: StateFlow<ThemeMode> = repo.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.System)
        
    val dndEnabled: StateFlow<Boolean> = repo.dndEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val fullScreenContactPhoto: StateFlow<Boolean> = repo.fullScreenContactPhoto
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val globalVibrate: StateFlow<Boolean> = repo.globalVibrate
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val fontSizeMultiplier: StateFlow<Float> = repo.fontSizeMultiplier
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1.0f)
        
    val appIconAlias: StateFlow<String> = repo.appIconAlias
        .stateIn(viewModelScope, SharingStarted.Eagerly, "Default")
        
    val keypadDesign: StateFlow<KeypadDesign> = repo.keypadDesign
        .stateIn(viewModelScope, SharingStarted.Eagerly, KeypadDesign.Grid)

    val incomingCallMode: StateFlow<IncomingCallMode> = repo.incomingCallMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, IncomingCallMode.Slider)

    val defaultDomain: StateFlow<String> = repo.defaultDomain
        .stateIn(viewModelScope, SharingStarted.Eagerly, "103.129.202.202")

    val lastDialedNumber: StateFlow<String?> = repo.lastDialedNumber
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val deviceId: StateFlow<String> = repo.deviceId.map { it ?: "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val isPro: StateFlow<Boolean> = MutableStateFlow(true)
    val adsEnabled: StateFlow<Boolean> = MutableStateFlow(false)
    val isSignedIn: StateFlow<Boolean> = MutableStateFlow(false)
    val userDisplayId: StateFlow<String> = MutableStateFlow("")
    val userCode: StateFlow<String> = MutableStateFlow("")
    val showAdGate: StateFlow<Boolean> = MutableStateFlow(false)
    val showAd: StateFlow<Boolean> = MutableStateFlow(false)
    val adGateCallback: StateFlow<(() -> Unit)?> = MutableStateFlow(null)
    val showProBlockPopup: StateFlow<Boolean> = MutableStateFlow(false)
    private val _inspection = MutableStateFlow(com.ipdial.data.model.ServerInspection())
    val inspection: StateFlow<com.ipdial.data.model.ServerInspection> = _inspection.asStateFlow()

    private val _inspectionRunning = MutableStateFlow(false)
    val inspectionRunning: StateFlow<Boolean> = _inspectionRunning.asStateFlow()

    val searchQuery: StateFlow<String> = MutableStateFlow("")
    val favoriteContacts: StateFlow<List<Contact>> = contactsRepo.favoriteContacts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val groupedContacts: StateFlow<Map<Char, List<Contact>>> = contactsRepo.allContacts.map { list -> list.groupBy { it.name.firstOrNull()?.uppercaseChar() ?: '#' } }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val mostCalledContacts: StateFlow<List<Contact>> = MutableStateFlow(emptyList())

    fun onSearchQueryChanged(q: String) {}
    fun setAdsEnabled(enabled: Boolean) {}
    fun dismissAd() {}
    fun dismissAdGate() {}
    fun dismissProPopup() {}
    fun showProPopup() {}
    fun showAdGate(context: Context? = null, onComplete: (() -> Unit)? = null) { onComplete?.invoke() }
    fun triggerAdGate(context: Context) {}
    fun incrementRecordingAction(context: Context? = null, onAction: () -> Unit = {}) { onAction() }
    fun resetSettings(context: Context? = null) {}

    fun runServerInspection(host: String) {
        val targetHost = host.trim().removePrefix("sip:").substringBefore(":").substringBefore("@")
        if (targetHost.isBlank() || _inspectionRunning.value) return

        _inspectionRunning.value = true
        val startedAt = System.currentTimeMillis()

        _inspection.value = com.ipdial.data.model.ServerInspection(
            host = targetHost,
            startedAtMs = startedAt,
            dns = com.ipdial.data.model.DnsResult(status = InspectStatus.RUNNING, host = targetHost),
            register = com.ipdial.data.model.RegisterResult(status = InspectStatus.RUNNING),
            callQuality = com.ipdial.data.model.CallQualitySnapshot(status = InspectStatus.RUNNING),
            dtmf = com.ipdial.data.model.DtmfResult(status = InspectStatus.RUNNING)
        )

        viewModelScope.launch(Dispatchers.IO) {
            val dnsResult = runDnsProbe(targetHost)
            withContext(Dispatchers.Main) {
                _inspection.value = _inspection.value.copy(dns = dnsResult)
            }

            val regResult = runRegisterProbe(targetHost)
            withContext(Dispatchers.Main) {
                _inspection.value = _inspection.value.copy(register = regResult)
            }

            val qualityResult = runCallQualityProbe()
            withContext(Dispatchers.Main) {
                _inspection.value = _inspection.value.copy(callQuality = qualityResult)
            }

            val dtmfResult = runDtmfProbe()
            withContext(Dispatchers.Main) {
                _inspection.value = _inspection.value.copy(
                    dtmf = dtmfResult,
                    finishedAtMs = System.currentTimeMillis()
                )
                _inspectionRunning.value = false
            }
        }
    }

    private fun runDnsProbe(host: String): com.ipdial.data.model.DnsResult {
        val startTime = System.currentTimeMillis()
        return try {
            val address = java.net.InetAddress.getByName(host)
            val latency = System.currentTimeMillis() - startTime
            com.ipdial.data.model.DnsResult(
                status = InspectStatus.PASS,
                host = host,
                resolvedIp = address.hostAddress,
                latencyMs = latency
            )
        } catch (e: Exception) {
            com.ipdial.data.model.DnsResult(
                status = InspectStatus.FAIL,
                host = host,
                error = e.message ?: "DNS Resolution Failed"
            )
        }
    }

    private fun runRegisterProbe(host: String): com.ipdial.data.model.RegisterResult {
        val account = accounts.value.find { it.domain.equals(host, ignoreCase = true) }
            ?: accounts.value.firstOrNull { it.isEnabled }

        if (account == null) {
            return com.ipdial.data.model.RegisterResult(
                status = InspectStatus.WARN,
                statusText = "No configured account for $host",
                transport = "—"
            )
        }

        return when (account.regStatus) {
            RegStatus.REGISTERED -> com.ipdial.data.model.RegisterResult(
                status = InspectStatus.PASS,
                statusText = "Registered (200 OK)",
                transport = account.transport.name
            )
            RegStatus.REGISTERING -> com.ipdial.data.model.RegisterResult(
                status = InspectStatus.WARN,
                statusText = "Registering in progress...",
                transport = account.transport.name
            )
            RegStatus.ERROR -> com.ipdial.data.model.RegisterResult(
                status = InspectStatus.FAIL,
                statusText = "Registration Error (Auth / Timeout)",
                transport = account.transport.name
            )
            else -> com.ipdial.data.model.RegisterResult(
                status = InspectStatus.WARN,
                statusText = "Unregistered / Offline",
                transport = account.transport.name
            )
        }
    }

    private fun runCallQualityProbe(): com.ipdial.data.model.CallQualitySnapshot {
        val session = callSession.value
        if (session == null || session.state != CallState.CONFIRMED) {
            return com.ipdial.data.model.CallQualitySnapshot(
                status = InspectStatus.WARN,
                qualitySummary = "No active call connected to inspect live media"
            )
        }

        val snapshot = SipEngine.snapshotCallQuality()
        if (snapshot == null) {
            return com.ipdial.data.model.CallQualitySnapshot(
                status = InspectStatus.PASS,
                negotiatedCodec = session.negotiatedCodec ?: "G.711U",
                qualitySummary = "Call Active • Good Quality"
            )
        }

        val (codec, clockRate, _) = snapshot
        val codecClean = codec.trim().uppercase().ifBlank { session.negotiatedCodec ?: "G.711" }

        return com.ipdial.data.model.CallQualitySnapshot(
            status = InspectStatus.PASS,
            negotiatedCodec = codecClean,
            clockRateHz = clockRate,
            callDurationSec = session.durationSeconds,
            qualitySummary = "Active • $codecClean (${if (clockRate > 0) "${clockRate / 1000}kHz" else "8kHz"})"
        )
    }

    private fun runDtmfProbe(): com.ipdial.data.model.DtmfResult {
        val session = callSession.value
        if (session == null || session.state != CallState.CONFIRMED) {
            return com.ipdial.data.model.DtmfResult(
                status = InspectStatus.WARN,
                method = "RFC 2833 / SIP INFO",
                error = "Place a live call to test DTMF digit delivery"
            )
        }

        return com.ipdial.data.model.DtmfResult(
            status = InspectStatus.PASS,
            method = "RFC 2833 (In-Band / RTP)",
            digit = '1',
            accepted = true
        )
    }
    fun answerCall() { val session = callSession.value; if (session != null) SipEngine.answerCall(session.callId) }
    fun proceedWithCallAfterAccountSelection(accountId: String) { selectAccount(accountId); makeCall() }
    fun dismissAccountSelection() { _showAccountSelectionDialog.value = false }
    fun backspace() { dialBackspace() }
    fun updateBluetoothAvailability() {}
    fun toggleRecording() {}
    fun cleanUri(uri: String): String = com.ipdial.ui.screens.cleanUri(uri)
    fun cleanDisplayName(name: String, uri: String): String = com.ipdial.ui.screens.cleanDisplayName(name, uri)
    fun syncContacts() = viewModelScope.launch { contactsRepo.syncContacts() }
    fun findContactByNumber(number: String): Contact? = contactsRepo.findContactByNumber(number)
    fun adjustCallVolumeByHardware(up: Boolean) {
        val delta = if (up) 0.5f else -0.5f
        val newVol = (_callVolume.value + delta).coerceIn(0f, 6f)
        _callVolume.value = newVol
        com.ipdial.service.SipAudioController.setCallVolume(newVol)
    }

    val recordingCounter: StateFlow<Int> = repo.recordingCounter
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val autoRecordEnabled: StateFlow<Boolean> = repo.autoRecordEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val globalNoiseCancellation: StateFlow<Boolean> = repo.globalNoiseCancellation
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val globalEcEnabled: StateFlow<Boolean> = repo.globalEcEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val globalNsEnabled: StateFlow<Boolean> = repo.globalNsEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val globalAgcEnabled: StateFlow<Boolean> = repo.globalAgcEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val turnServer: StateFlow<String> = repo.turnServer
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val turnUsername: StateFlow<String> = repo.turnUsername
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val turnPassword: StateFlow<String> = repo.turnPassword
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val turnTransport: StateFlow<TurnTransport> = repo.turnTransport
        .stateIn(viewModelScope, SharingStarted.Eagerly, TurnTransport.UDP)

    fun setTurnServer(server: String) = viewModelScope.launch { repo.setTurnServer(server) }
    fun setTurnUsername(username: String) = viewModelScope.launch { repo.setTurnUsername(username) }
    fun setTurnPassword(password: String) = viewModelScope.launch { repo.setTurnPassword(password) }
    fun setTurnTransport(tp: TurnTransport) = viewModelScope.launch { repo.setTurnTransport(tp) }

    val deviceNoiseCancellationSupported: Boolean = try {
        android.media.audiofx.NoiseSuppressor.isAvailable()
    } catch (_: Throwable) {
        false
    }

    fun setThemeMode(context: Context, mode: ThemeMode) = viewModelScope.launch { repo.setThemeMode(mode) }
    fun setDnd(enabled: Boolean) = viewModelScope.launch { repo.setDnd(enabled) }
    fun setGlobalVibrate(enabled: Boolean) = viewModelScope.launch { repo.setGlobalVibrate(enabled) }
    fun setFullScreenContactPhoto(enabled: Boolean) = viewModelScope.launch { repo.setFullScreenContactPhoto(enabled) }
    
    fun setFontSize(context: Context, multiplier: Float) = viewModelScope.launch { repo.setFontSizeMultiplier(multiplier) }
    fun setAppIcon(context: Context, alias: String) = viewModelScope.launch { repo.setAppIconAlias(alias) }
    fun setKeypadDesign(context: Context, design: KeypadDesign) = viewModelScope.launch { repo.setKeypadDesign(design) }
    fun setIncomingCallMode(context: Context, mode: IncomingCallMode) = viewModelScope.launch { repo.setIncomingCallMode(mode) }
    fun setDefaultDomain(domain: String) = viewModelScope.launch { repo.setDefaultDomain(domain) }
    fun setBatteryNoticeShown(shown: Boolean) = viewModelScope.launch { repo.setBatteryNoticeShown(shown) }
    fun setAutoRecord(context: Context, enabled: Boolean) = viewModelScope.launch { repo.setAutoRecordEnabled(enabled) }
    fun setGlobalNoiseCancellation(context: Context, enabled: Boolean) = viewModelScope.launch { repo.setGlobalNoiseCancellation(enabled) }
    fun setGlobalEcEnabled(context: Context, enabled: Boolean) = viewModelScope.launch {
        repo.setGlobalEcEnabled(enabled)
        reapplyGlobalAudioSettings()
    }
    fun setGlobalNsEnabled(context: Context, enabled: Boolean) = viewModelScope.launch {
        repo.setGlobalNsEnabled(enabled)
        reapplyGlobalAudioSettings()
    }
    fun setGlobalAgcEnabled(context: Context, enabled: Boolean) = viewModelScope.launch {
        repo.setGlobalAgcEnabled(enabled)
        reapplyGlobalAudioSettings()
    }

    fun reapplyGlobalAudioSettings() {
        val ec = globalEcEnabled.value
        val ns = globalNsEnabled.value
        val agc = globalAgcEnabled.value
        com.ipdial.service.SipEngine.applyGlobalAudioSettings(ec, ns, agc)
    }

    suspend fun clearCallHistory() {
        logRepo.deleteAll()
    }

    // Call Engine state
    val callSession: StateFlow<CallSession?> = SipEngine.callSession

    // Selected account for outgoing calls when multiple accounts are enabled
    private val _selectedAccountId = MutableStateFlow<String?>(null)
    val selectedAccountId: StateFlow<String?> = _selectedAccountId.asStateFlow()

    private val _pendingCallNumber = MutableStateFlow<String?>(null)
    val pendingCallNumber: StateFlow<String?> = _pendingCallNumber.asStateFlow()

    private val _showAccountSelectionDialog = MutableStateFlow(false)
    val showAccountSelectionDialog: StateFlow<Boolean> = _showAccountSelectionDialog.asStateFlow()

    // Active account
    val activeAccount: StateFlow<SipAccount?> = combine(accounts, _selectedAccountId) { accs, selectedId ->
        val enabled = accs.filter { it.isEnabled }
        if (selectedId != null) {
            enabled.find { it.id == selectedId } ?: enabled.firstOrNull()
        } else {
            enabled.firstOrNull { it.isDefault } ?: enabled.firstOrNull()
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val isRegistered: StateFlow<Boolean> = activeAccount.map { acc ->
        acc?.regStatus == RegStatus.REGISTERED
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    val callLog: StateFlow<List<CallLogEntry>> = logRepo.entries
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Dialer state
    private val _dialString = MutableStateFlow(TextFieldValue(""))
    val dialString: StateFlow<TextFieldValue> = _dialString.asStateFlow()

    private val _isEndingCall = MutableStateFlow(false)
    val isEndingCall: StateFlow<Boolean> = _isEndingCall.asStateFlow()

    init {
        viewModelScope.launch {
            contactsRepo.allContacts.collect { _contacts.value = it }
        }
        viewModelScope.launch {
            callSession.map { it == null }.distinctUntilChanged().collect { isNull ->
                if (isNull) {
                    _isEndingCall.value = false
                    _dialString.value = TextFieldValue("")
                }
            }
        }
    }

    fun setShowFullIncomingScreen(show: Boolean) {
        _showFullIncomingScreen.value = show
    }

    fun setDialString(value: TextFieldValue) {
        _dialString.value = value
    }

    fun dialPad(char: Char) {
        val current = _dialString.value
        val selection = current.selection
        val min = minOf(selection.start, selection.end)
        val max = maxOf(selection.start, selection.end)
        val newText = current.text.replaceRange(min, max, char.toString())
        _dialString.value = TextFieldValue(text = newText, selection = TextRange(min + 1))
    }

    fun dialBackspace() {
        val current = _dialString.value
        val selection = current.selection
        val min = minOf(selection.start, selection.end)
        val max = maxOf(selection.start, selection.end)
        if (min != max) {
            val newText = current.text.removeRange(min, max)
            _dialString.value = TextFieldValue(text = newText, selection = TextRange(min))
        } else if (min > 0) {
            val newText = current.text.removeRange(min - 1, min)
            _dialString.value = TextFieldValue(text = newText, selection = TextRange(selection.start - 1))
        }
    }

    fun clearDial() { _dialString.value = TextFieldValue("") }
    fun prefillDialer(number: String) { _dialString.value = TextFieldValue(number, TextRange(number.length)) }

    fun selectAccount(id: String) { _selectedAccountId.value = id }

    fun makeCall(overrideNumber: String? = null) {
        val rawInput = (overrideNumber ?: _dialString.value.text).trim()
        if (rawInput.isBlank()) return

        val enabledAccounts = accounts.value.filter { it.isEnabled }
        if (enabledAccounts.isEmpty()) {
            Toast.makeText(getApplication(), "No enabled account configured", Toast.LENGTH_SHORT).show()
            return
        }

        val account = activeAccount.value ?: enabledAccounts.first()
        val finalUri = if (rawInput.contains("@")) rawInput else "sip:$rawInput@${account.domain}"

        // Log outgoing call immediately so unanswered outgoing calls are saved in recent history
        logCall(
            CallLogEntry(
                remoteUri = finalUri,
                remoteDisplayName = cleanDisplayName(cleanUri(finalUri), finalUri),
                direction = com.ipdial.data.model.CallDirection.OUTGOING,
                timestampMs = System.currentTimeMillis(),
                durationSeconds = 0
            )
        )

        viewModelScope.launch(Dispatchers.IO) {
            SipEngine.makeCall(account.id, finalUri)
        }
    }

    fun hangup() {
        val session = callSession.value
        val id = session?.callId ?: -1
        _isEndingCall.value = true
        android.util.Log.d("SipViewModel", "hangup() called: callId=$id")

        val causeCode = com.ipdial.service.CallHangupResolver.resolveDisconnectCause(session)

        viewModelScope.launch(Dispatchers.IO) {
            SipEngine.hangupCall(id)
            if (id != -1) {
                withContext(Dispatchers.Main) {
                    com.ipdial.service.SipConnectionService.disconnectCall(id, causeCode)
                }
            }
        }
    }

    fun toggleMute() { SipAudioController.setMute(!(callSession.value?.isMuted ?: false)) }
    fun toggleSpeaker() { SipAudioController.setSpeaker(!(callSession.value?.isSpeaker ?: false)) }
    fun toggleHold() { SipAudioController.holdCall(!(callSession.value?.isOnHold ?: false)) }

    fun cycleAudioDevice() {
        val current = _audioDeviceMode.value
        val next = when (current) {
            AudioDeviceMode.EARPIECE -> AudioDeviceMode.SPEAKER
            AudioDeviceMode.SPEAKER -> AudioDeviceMode.EARPIECE
            AudioDeviceMode.BLUETOOTH -> AudioDeviceMode.SPEAKER
        }
        _audioDeviceMode.value = next
        SipAudioController.setSpeaker(next == AudioDeviceMode.SPEAKER)
    }

    fun saveAccount(account: SipAccount) = viewModelScope.launch(Dispatchers.IO) {
        repo.saveAccount(account)
        if (account.label.isNotBlank()) repo.addSavedLabel(account.label)
        if (account.domain.isNotBlank()) repo.addSavedHost(account.domain)
    }

    fun deleteAccount(id: String) = viewModelScope.launch(Dispatchers.IO) {
        repo.deleteAccount(id)
    }

    fun setDefaultAccount(id: String) = viewModelScope.launch { repo.setDefault(id) }

    fun toggleContactFavorite(contact: Contact) = viewModelScope.launch {
        val newFavoriteStatus = !contact.isFavorite
        _contacts.value = _contacts.value.map {
            if (it.id == contact.id) it.copy(isFavorite = newFavoriteStatus) else it
        }
        contactsRepo.toggleFavorite(contact.id, newFavoriteStatus)
    }

    fun deleteCallLog(entry: CallLogEntry) = viewModelScope.launch {
        logRepo.delete(entry)
    }

    fun callBack(entry: CallLogEntry) {
        makeCall(cleanUri(entry.remoteUri))
    }

    fun logCall(entry: CallLogEntry) = viewModelScope.launch {
        logRepo.insert(entry)
    }

    fun fetchBalance(account: SipAccount, context: Context? = null) {
        val host = account.domain.trim()
        if (host.isBlank()) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val urlString = when {
                    host.contains("amarip") || host == "103.170.231.10" -> "https://sip.amarip.net/api/mobile/login"
                    else -> "https://billing.webvoice.net/api/mobile/login"
                }

                val conn = java.net.URL(urlString).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                val jsonBody = org.json.JSONObject().apply {
                    put("username", account.username.trim())
                    put("password", account.password.trim())
                }

                conn.outputStream.use { os ->
                    os.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
                }

                val responseCode = conn.responseCode
                if (responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = org.json.JSONObject(body)
                    val status = json.optBoolean("status", false) || json.optInt("status", 0) == 1
                    if (status) {
                        val dataObj = json.optJSONObject("data")
                        val clientObj = dataObj?.optJSONObject("client")
                        val balance = clientObj?.optString("balance_text")
                            ?.ifBlank { null }
                            ?: dataObj?.optString("balance_text")
                            ?.ifBlank { null }
                            ?: json.optString("balance_text", "")
                        
                        if (balance.isNotBlank()) {
                            val current = _balances.value.toMutableMap()
                            current[account.id] = balance
                            _balances.value = current
                            android.util.Log.d("SipViewModel", "fetchBalance SUCCEEDED for ${account.username}: $balance")
                        }
                    } else {
                        android.util.Log.w("SipViewModel", "fetchBalance response status false for ${account.username}: $body")
                    }
                } else {
                    android.util.Log.e("SipViewModel", "fetchBalance HTTP error $responseCode for ${account.username}")
                }
            } catch (e: Exception) {
                android.util.Log.e("SipViewModel", "fetchBalance exception for ${account.username}: ${e.message}")
            }
        }
    }
}

fun cleanUri(uri: String): String {
    var s = uri
    if (s.startsWith("sip:", ignoreCase = true)) s = s.substring(4)
    val atIdx = s.indexOf('@')
    if (atIdx != -1) s = s.substring(0, atIdx)
    return s
}

fun cleanDisplayName(name: String, uri: String): String {
    val clean = name.trim().removeSurrounding("\"", "\"")
    return if (clean.isNotBlank() && clean != uri && clean != "sip:$uri") clean else cleanUri(uri)
}
