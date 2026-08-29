package com.ipdial.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import com.ipdial.data.model.KeypadDesign
import com.ipdial.data.model.RegStatus
import com.ipdial.data.model.SipAccount
import com.ipdial.data.model.ThemeMode
import com.ipdial.data.model.Transport
import com.ipdial.data.repository.AccountRepository
import com.ipdial.data.repository.CallLogRepository
import com.ipdial.data.repository.ContactsRepository
import com.ipdial.data.repository.FirestorePointsSync
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

    private val SUPPORTED_BALANCE_DOMAINS = listOf("sip.amarip.net", "103.170.231.10", "103.129.202.202")

    val repo = AccountRepository(app)
    private val logRepo = CallLogRepository.getInstance(app)
    private val contactsRepo = ContactsRepository(app)
    // Firestore sync manager (initialized in init)
    private var firestoreSync: FirestorePointsSync? = null

    private val _balances = MutableStateFlow<Map<String, String>>(emptyMap())
    val balances: StateFlow<Map<String, String>> = _balances.asStateFlow()

    // Audio device state
    private val _audioDeviceMode = MutableStateFlow(AudioDeviceMode.EARPIECE)
    val audioDeviceMode: StateFlow<AudioDeviceMode> = _audioDeviceMode.asStateFlow()

    private val _hasBluetoothDevice = MutableStateFlow(false)
    val hasBluetoothDevice: StateFlow<Boolean> = _hasBluetoothDevice.asStateFlow()

    private val _callVolume = MutableStateFlow(2.5f)
    val callVolume: StateFlow<Float> = _callVolume.asStateFlow()

    private val _showFullIncomingScreen = MutableStateFlow(false)
    val showFullIncomingScreen: StateFlow<Boolean> = _showFullIncomingScreen.asStateFlow()

    val accounts: StateFlow<List<SipAccount>> = repo.accounts
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val globalRingtone: StateFlow<String?> = repo.globalRingtone
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val themeMode: StateFlow<ThemeMode> = repo.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.System)
        
    val callingCardsEnabled: StateFlow<Boolean> = repo.callingCardsEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
        
    val dndEnabled: StateFlow<Boolean> = repo.dndEnabled
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

    val adsEnabled: StateFlow<Boolean> = repo.adsEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val deviceId: StateFlow<String> = repo.deviceId.map { it ?: "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val proPoints: StateFlow<Int> = repo.proPoints
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)
        
    val proExpiration: StateFlow<Long> = repo.proExpiration
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    private val _timeTicker = kotlinx.coroutines.flow.flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(30_000)
        }
    }

    val isPro: StateFlow<Boolean> = combine(proExpiration, _timeTicker) { exp, now ->
        exp > now
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
        
    val recordingCounter: StateFlow<Int> = repo.recordingCounter
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val autoRecordEnabled: StateFlow<Boolean> = repo.autoRecordEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setThemeMode(context: Context, mode: ThemeMode) = viewModelScope.launch { 
        repo.setThemeMode(mode)
        if (!isPro.value) triggerAd(context)
    }
    fun setCallingCards(enabled: Boolean) = viewModelScope.launch { repo.setCallingCards(enabled) }
    fun setDnd(enabled: Boolean) = viewModelScope.launch { repo.setDnd(enabled) }
    fun setGlobalVibrate(enabled: Boolean) = viewModelScope.launch { repo.setGlobalVibrate(enabled) }
    
    fun setFontSize(context: Context, multiplier: Float) = viewModelScope.launch { 
        repo.setFontSizeMultiplier(multiplier)
        if (!isPro.value) triggerAd(context)
    }
    fun setAppIcon(context: Context, alias: String) = viewModelScope.launch { 
        repo.setAppIconAlias(alias)
        if (!isPro.value) triggerAd(context)
    }
    fun setKeypadDesign(context: Context, design: KeypadDesign) = viewModelScope.launch { 
        repo.setKeypadDesign(design)
        if (!isPro.value) triggerAd(context)
    }
    fun setIncomingCallMode(context: Context, mode: IncomingCallMode) = viewModelScope.launch {
        repo.setIncomingCallMode(mode)
        if (!isPro.value) triggerAd(context)
    }
    fun setDefaultDomain(domain: String) = viewModelScope.launch { repo.setDefaultDomain(domain) }
    fun setAdsEnabled(enabled: Boolean) = viewModelScope.launch { repo.setAdsEnabled(enabled) }
    fun setBatteryNoticeShown(shown: Boolean) = viewModelScope.launch { repo.setBatteryNoticeShown(shown) }
    fun setAutoRecord(context: Context, enabled: Boolean) = viewModelScope.launch { 
        repo.setAutoRecordEnabled(enabled)
        if (enabled && !isPro.value) triggerAd(context)
    }

    suspend fun clearCallHistory() {
        logRepo.deleteAll()
    }

    fun getReferralCode(): String = deviceId.value

    fun claimReferral(code: String, onComplete: (Boolean, String) -> Unit) {
        try {
            firestoreSync?.claimReferral(code, onComplete) ?: onComplete(false, "Service unavailable")
        } catch (e: Exception) {
            onComplete(false, e.message ?: "error")
        }
    }

    fun redeemPoints(days: Int) = viewModelScope.launch {
        val cost = when(days) {
            1 -> 1
            7 -> 5
            30 -> 20
            90 -> 50
            else -> return@launch
        }
        if (proPoints.value >= cost) {
            val newPoints = maxOf(0, proPoints.value - cost)
            repo.setProPoints(newPoints)
            val currentExp = maxOf(proExpiration.value, System.currentTimeMillis())
            val newExp = currentExp + (days * 24 * 60 * 60 * 1000L)
            repo.setProExpiration(newExp)
            // Atomic update to Firestore
            try { firestoreSync?.redeemPoints(cost, newExp) } catch (_: Exception) {}
        }
    }

    private val _adCooldownSeconds = MutableStateFlow(0)
    val adCooldownSeconds: StateFlow<Int> = _adCooldownSeconds.asStateFlow()

    private fun startAdCooldown() {
        viewModelScope.launch {
            _adCooldownSeconds.value = 7
            while (_adCooldownSeconds.value > 0) {
                delay(1000)
                _adCooldownSeconds.value -= 1
            }
        }
    }

    fun watchRewardedAd(context: Context, onReward: () -> Unit) {
        if (_isLoadingAd.value || _adCooldownSeconds.value > 0) return
        _isLoadingAd.value = true
        android.util.Log.d("SipViewModel", "Starting rewarded ad flow")

        val rewardedAd = com.startapp.sdk.adsbase.StartAppAd(context)
        
        // Define common success logic
        val grantReward = {
            viewModelScope.launch {
                android.util.Log.d("SipViewModel", "Granting 1 point reward")
                val newPoints = proPoints.value + 1
                repo.setProPoints(newPoints)
                // Atomic increment in Firestore instead of overwriting with local total
                try { firestoreSync?.incrementPoints(1) } catch (_: Exception) {}
                onReward()
                _isLoadingAd.value = false
                startAdCooldown()
            }
        }

        rewardedAd.setVideoListener(object : com.startapp.sdk.adsbase.adlisteners.VideoListener {
            override fun onVideoCompleted() {
                android.util.Log.d("SipViewModel", "Rewarded video completed")
                grantReward()
            }
        })

        rewardedAd.loadAd(com.startapp.sdk.adsbase.StartAppAd.AdMode.REWARDED_VIDEO, object : com.startapp.sdk.adsbase.adlisteners.AdEventListener {
            override fun onReceiveAd(ad: com.startapp.sdk.adsbase.Ad) {
                android.util.Log.d("SipViewModel", "Rewarded ad received, showing...")
                val showed = rewardedAd.showAd(object : com.startapp.sdk.adsbase.adlisteners.AdDisplayListener {
                    override fun adDisplayed(ad: com.startapp.sdk.adsbase.Ad?) {}
                    override fun adNotDisplayed(ad: com.startapp.sdk.adsbase.Ad?) {
                        android.util.Log.w("SipViewModel", "Rewarded ad not displayed, trying interstitial fallback")
                        triggerInterstitialAd(context, ignorePro = true) { success ->
                            if (success) grantReward()
                            else _isLoadingAd.value = false
                        }
                    }
                    override fun adClicked(ad: com.startapp.sdk.adsbase.Ad?) {}
                    override fun adHidden(ad: com.startapp.sdk.adsbase.Ad?) {
                        // For non-video rewarded ads (if any), handle completion here if VideoListener isn't triggered
                    }
                })
                if (!showed) {
                    android.util.Log.w("SipViewModel", "showAd() returned false for rewarded, trying interstitial fallback")
                    triggerInterstitialAd(context, ignorePro = true) { success ->
                        if (success) grantReward()
                        else _isLoadingAd.value = false
                    }
                }
            }
            override fun onFailedToReceiveAd(ad: com.startapp.sdk.adsbase.Ad?) {
                android.util.Log.w("SipViewModel", "Failed to receive rewarded ad, trying interstitial fallback")
                // Allow triggerInterstitialAd to run by not being blocked by _isLoadingAd check (which we remove below)
                triggerInterstitialAd(context, ignorePro = true) { success ->
                    if (success) grantReward()
                    else _isLoadingAd.value = false
                }
            }
        })
    }

    fun triggerInterstitialAd(context: Context, ignorePro: Boolean = false, onComplete: ((Boolean) -> Unit)? = null) {
        if (isPro.value && !ignorePro) {
            onComplete?.invoke(true)
            return
        }
        
        _isLoadingAd.value = true

        val startAppAd = com.startapp.sdk.adsbase.StartAppAd(context)
        startAppAd.loadAd(object : com.startapp.sdk.adsbase.adlisteners.AdEventListener {
            override fun onReceiveAd(ad: com.startapp.sdk.adsbase.Ad) {
                startAppAd.showAd(object : com.startapp.sdk.adsbase.adlisteners.AdDisplayListener {
                    override fun adDisplayed(ad: com.startapp.sdk.adsbase.Ad?) {
                        android.util.Log.d("SipViewModel", "Interstitial ad displayed")
                    }
                    override fun adNotDisplayed(ad: com.startapp.sdk.adsbase.Ad?) { 
                        android.util.Log.w("SipViewModel", "Interstitial ad not displayed")
                        _isLoadingAd.value = false
                        onComplete?.invoke(false) 
                    }
                    override fun adClicked(ad: com.startapp.sdk.adsbase.Ad?) {}
                    override fun adHidden(ad: com.startapp.sdk.adsbase.Ad?) { 
                        android.util.Log.d("SipViewModel", "Interstitial ad hidden")
                        _isLoadingAd.value = false
                        onComplete?.invoke(true) 
                    }
                })
            }
            override fun onFailedToReceiveAd(ad: com.startapp.sdk.adsbase.Ad?) {
                android.util.Log.e("SipViewModel", "Failed to receive interstitial ad")
                _isLoadingAd.value = false
                onComplete?.invoke(false)
            }
        })
    }

    fun showProPopup() {
        _showProBlockPopup.value = true
    }

    fun dismissProPopup() {
        _showProBlockPopup.value = false
    }

    fun showAdGate(onAdWatched: () -> Unit) {
        if (isPro.value) {
            onAdWatched()
        } else {
            _adGateCallback.value = onAdWatched
        }
    }

    fun dismissAdGate() {
        _adGateCallback.value = null
    }

    fun triggerAdGate(context: Context) {
        val callback = _adGateCallback.value
        _adGateCallback.value = null
        if (callback != null) {
            triggerInterstitialAd(context) { _ ->
                callback()
            }
        }
    }

    fun incrementRecordingAction(onAction: () -> Unit) = viewModelScope.launch {
        if (isPro.value) {
            onAction()
            return@launch
        }
        val next = recordingCounter.value + 1
        if (next >= 5) {
            showAdGate {
                viewModelScope.launch {
                    repo.setRecordingCounter(0)
                    onAction()
                }
            }
        } else {
            repo.setRecordingCounter(next)
            onAction()
        }
    }

    fun checkCodecChange(context: Context, onConfirm: () -> Unit) {
        if (isPro.value) {
            onConfirm()
        } else {
            triggerAd(context)
            onConfirm()
        }
    }


    val callLog: StateFlow<List<CallLogEntry>> = logRepo.entries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val callSession: StateFlow<CallSession?> = SipEngine.callSession

    // Contacts state
    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val groupedContacts: StateFlow<Map<Char, List<Contact>>> =
        combine(_contacts, _searchQuery) { allContacts, query ->
            val filtered = if (query.isBlank()) allContacts
            else allContacts.filter {
                it.name.contains(query, ignoreCase = true) ||
                it.numbers.any { num -> num.contains(query) }
            }
            filtered.sortedBy { it.name.trim().lowercase() }
                .groupBy { contact ->
                    val first = contact.name.trim().firstOrNull()?.uppercaseChar() ?: '#'
                    if (first in 'A'..'Z') first else '#'
                }
                .toSortedMap()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // Dialer state
    private val _dialString = MutableStateFlow(TextFieldValue(""))
    val dialString: StateFlow<TextFieldValue> = _dialString.asStateFlow()

     private val _selectedAccountId = MutableStateFlow<String?>(null)
     val selectedAccountId: StateFlow<String?> = _selectedAccountId.asStateFlow()

     private val _showAccountSelectionDialog = MutableStateFlow(false)
     val showAccountSelectionDialog: StateFlow<Boolean> = _showAccountSelectionDialog.asStateFlow()

     private val _pendingCallNumber = MutableStateFlow<String?>(null)
     val pendingCallNumber: StateFlow<String?> = _pendingCallNumber.asStateFlow()

     private val _isConnected = MutableStateFlow(true)
     val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

     private val _showAd = MutableStateFlow(false)
     val showAd: StateFlow<Boolean> = _showAd.asStateFlow()

     private val _showProBlockPopup = MutableStateFlow(false)
     val showProBlockPopup: StateFlow<Boolean> = _showProBlockPopup.asStateFlow()

     private val _adGateCallback = MutableStateFlow<(() -> Unit)?>(null)
     val adGateCallback: StateFlow<(() -> Unit)?> = _adGateCallback.asStateFlow()

     private val _isLoadingAd = MutableStateFlow(false)
     val isLoadingAd: StateFlow<Boolean> = _isLoadingAd.asStateFlow()

     private var adTimerJob: Job? = null

     private fun showAdBriefly(durationMs: Long = 15000L) {
         adTimerJob?.cancel()
         _showAd.value = true
         adTimerJob = viewModelScope.launch {
             delay(durationMs)
             _showAd.value = false
         }
     }

    val favoriteContacts: StateFlow<List<Contact>> = _contacts.map { list ->
        list.filter { it.isFavorite }.sortedBy { it.name.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val mostCalledContacts: StateFlow<List<Contact>> = combine(callLog, contacts) { logs, allContacts ->
        val frequencyMap = logs.groupingBy { 
            cleanUri(it.remoteUri)
        }.eachCount()
        
        frequencyMap.entries
            .sortedByDescending { it.value }
            .mapNotNull { entry ->
                val cleanedCallLogNumber = entry.key.filter { it.isDigit() }
                if (cleanedCallLogNumber.length < 3) { // Ignore extremely short/empty numbers
                    null
                } else {
                    allContacts.find { contact ->
                        contact.numbers.any { num ->
                            val cleanedContactNumber = num.filter { it.isDigit() }
                            cleanedContactNumber.length >= 3 &&
                            (cleanedCallLogNumber == cleanedContactNumber ||
                             (cleanedCallLogNumber.length >= 7 && cleanedContactNumber.length >= 7 &&
                              (cleanedCallLogNumber.contains(cleanedContactNumber) || cleanedContactNumber.contains(cleanedCallLogNumber))))
                        }
                    }
                }
            }
            .distinctBy { it.id }
            .take(5)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeAccount: StateFlow<SipAccount?> = combine(accounts, _selectedAccountId) { list, id ->
        list.firstOrNull { it.isEnabled && it.isDefault } ?: list.find { it.id == id }
            ?: list.firstOrNull { it.isEnabled } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private var searchJob: Job? = null

    init {
        observeCallSession()

        // Keep the UI audio-device mode in sync with the route Telecom actually
        // confirmed via onCallAudioStateChanged (e.g. BT SCO link established).
        // This prevents the UI showing "Bluetooth" while audio is still on the
        // earpiece/speaker, and vice versa.
        viewModelScope.launch {
            SipEngine.confirmedAudioRoute.collect { confirmed ->
                if (confirmed != null && callSession.value != null &&
                    callSession.value?.state == CallState.CONFIRMED) {
                    _audioDeviceMode.value = confirmed
                    SipAudioController.setSpeaker(confirmed == AudioDeviceMode.SPEAKER)
                }
            }
        }

        val connectivityManager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        // Initial check for internet connectivity
        val activeNet = connectivityManager.activeNetwork
        val caps = connectivityManager.getNetworkCapabilities(activeNet)
        _isConnected.value = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        
        connectivityManager.registerNetworkCallback(networkRequest, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _isConnected.value = true
            }

            override fun onLost(network: Network) {
                // Instead of assuming everything is lost, check if ANY network still has internet
                val currentActive = connectivityManager.activeNetwork
                val currentCaps = connectivityManager.getNetworkCapabilities(currentActive)
                _isConnected.value = currentCaps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            }
            
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    _isConnected.value = true
                }
            }
        })

        // Auto-select default/enabled account
        viewModelScope.launch(Dispatchers.IO) {
            accounts.collectLatest { list ->
                withContext(Dispatchers.Main) {
                    val currentSelected = list.find { it.id == _selectedAccountId.value }
                    if (currentSelected == null || !currentSelected.isEnabled) {
                        _selectedAccountId.value = list.firstOrNull { it.isEnabled && it.isDefault }?.id
                            ?: list.firstOrNull { it.isEnabled }?.id
                            ?: list.firstOrNull()?.id
                    }
                }
            }
        }

        viewModelScope.launch {
            contactsRepo.allContacts.collect {
                _contacts.value = it
            }
        }
        refreshContacts()

        // Ensure deviceId is created
        viewModelScope.launch {
            repo.getOrCreateDeviceId()
        }

        // Initialize Firestore sync for points/expiration
        try {
            firestoreSync = FirestorePointsSync(repo)
            firestoreSync?.startListening()
        } catch (e: Throwable) {
            android.util.Log.e("SipViewModel", "FirestorePointsSync init failed", e)
        }

        // Clear keypad after call ends
        viewModelScope.launch {
            callSession.map { it == null }.distinctUntilChanged().collect { isNull ->
                if (isNull) {
                    _dialString.value = TextFieldValue("")
                }
            }
        }
    }

    private var callTimeoutJob: Job? = null

    private fun observeCallSession() {
        viewModelScope.launch {
            callSession.collect { session ->
                callTimeoutJob?.cancel()
                callTimeoutJob = null

                if (session != null && session.state != CallState.DISCONNECTED) {
                    _showFullIncomingScreen.value = true
                    if (session.state == CallState.INCOMING || session.state == CallState.CALLING) {
                        // Update bluetooth availability when a call starts/comes in
                        updateBluetoothAvailability()
                        
                        // If we are in EARPIECE mode and Bluetooth is available, switch to it
                        if (_audioDeviceMode.value == AudioDeviceMode.EARPIECE && _hasBluetoothDevice.value) {
                            setAudioDevice(AudioDeviceMode.BLUETOOTH)
                        }
                    }

                    // Start a timeout for outgoing calls stuck in CALLING/EARLY
                    if (session.direction == com.ipdial.data.model.CallDirection.OUTGOING &&
                        (session.state == CallState.CALLING || session.state == CallState.EARLY)) {
                        callTimeoutJob = viewModelScope.launch {
                            delay(60_000)
                            if (callSession.value?.state == CallState.CALLING ||
                                callSession.value?.state == CallState.EARLY) {
                                android.util.Log.w("SipViewModel", "Call timeout: no response after 60s, hanging up")
                                hangup()
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(getApplication(), "Call timed out", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                } else {
                    _showFullIncomingScreen.value = false
                    // Reset to EARPIECE when call ends
                    _audioDeviceMode.value = AudioDeviceMode.EARPIECE
                }
            }
        }

        // Zombie session watchdog: periodically check if the session references a
        // callId that is no longer in SipEngine's callMap.  This catches edge-cases
        // where onCallState(DISCONNECTED) failed to null the session (e.g. exception
        // in the disconnect block, or conn.destroy() threading issue causing the
        // framework to re-enter and resurrect the session).
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1_000) // Check more frequently
                try {
                    SipEngine.nullSessionIfStale()
                } catch (_: Throwable) {}
            }
        }
    }

    fun refreshContacts() {
        viewModelScope.launch {
            contactsRepo.syncContacts()
            contactsRepo.buildNumberIndex()
        }
    }

    fun findContactByNumber(phoneNumber: String): Contact? {
        return contactsRepo.findContactByNumber(phoneNumber)
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            contactsRepo.buildNumberIndex()
        }
    }

    fun setDialString(value: TextFieldValue) {
        _dialString.value = value
    }

    fun dialPad(char: Char) {
        val current = _dialString.value
        val text = current.text
        val selection = current.selection
        val newText = text.substring(0, selection.start) + char + text.substring(selection.end)
        val newSelection = selection.start + 1
        _dialString.value = TextFieldValue(text = newText, selection = TextRange(newSelection))
        if (callSession.value?.state == CallState.CONFIRMED) {
            SipAudioController.sendDtmf(char)
        }
    }

    fun backspace() {
        val current = _dialString.value
        val text = current.text
        val selection = current.selection
        if (selection.start != selection.end) {
            val min = minOf(selection.start, selection.end)
            val max = maxOf(selection.start, selection.end)
            val newText = text.substring(0, min) + text.substring(max)
            _dialString.value = TextFieldValue(text = newText, selection = TextRange(min))
        } else if (selection.start > 0) {
            val newText = text.substring(0, selection.start - 1) + text.substring(selection.start)
            _dialString.value = TextFieldValue(text = newText, selection = TextRange(selection.start - 1))
        }
    }

    fun clearDial() { _dialString.value = TextFieldValue("") }

    fun prefillDialer(number: String) { _dialString.value = TextFieldValue(number, TextRange(number.length)) }

    fun deleteCallLog(entry: CallLogEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            logRepo.delete(entry)
        }
    }

     fun selectAccount(id: String) { _selectedAccountId.value = id }

     fun showAccountSelection(number: String) {
         _pendingCallNumber.value = number
         _showAccountSelectionDialog.value = true
     }

     fun dismissAccountSelection() {
         _showAccountSelectionDialog.value = false
         _pendingCallNumber.value = null
     }

     fun proceedWithCallAfterAccountSelection(accountId: String) {
         val number = _pendingCallNumber.value ?: return
         _selectedAccountId.value = accountId
         _showAccountSelectionDialog.value = false
         makeCall(number)
         _pendingCallNumber.value = null
     }

     fun makeCall(overrideNumber: String? = null) {
         val rawInput = (overrideNumber ?: _dialString.value.text).trim()
         if (rawInput.isBlank()) {
             com.ipdial.util.SipLogger.log("SipViewModel", "makeCall: ignored blank input")
             Toast.makeText(getApplication(), "Please enter a number", Toast.LENGTH_SHORT).show()
             return
         }

         // Check if there are multiple enabled accounts
         val enabledAccounts = accounts.value.filter { it.isEnabled }
         if (enabledAccounts.size > 1 && _pendingCallNumber.value == null) {
             // Show dialog and store the number for later
             com.ipdial.util.SipLogger.log("SipViewModel", "makeCall: multiple enabled accounts -> account selection dialog")
             showAccountSelection(rawInput)
             return
         }

         // Clean formatting characters (spaces, dashes, parentheses)
         val cleanedInput = rawInput.replace(" ", "")
             .replace("-", "")
             .replace("(", "")
             .replace(")", "")

         var account = accounts.value.find { it.id == _selectedAccountId.value }
         if (account == null || !account.isEnabled) {
             account = accounts.value.firstOrNull { it.isEnabled }
             if (account != null) {
                 _selectedAccountId.value = account.id
             }
         }

         if (account == null) {
             com.ipdial.util.SipLogger.log("SipViewModel", "makeCall: no enabled SIP account configured")
             Toast.makeText(getApplication(), "No enabled SIP account configured", Toast.LENGTH_SHORT).show()
             return
         }

         if (account.regStatus != RegStatus.REGISTERED) {
             com.ipdial.util.SipLogger.log("SipViewModel", "makeCall: account ${account.id} status=${account.regStatus} not REGISTERED")
             Toast.makeText(getApplication(), "Account is not registered", Toast.LENGTH_SHORT).show()
             return
         }

         if (!_isConnected.value) {
             com.ipdial.util.SipLogger.log("SipViewModel", "makeCall: no internet connection")
             Toast.makeText(getApplication(), "No internet connection", Toast.LENGTH_SHORT).show()
             return
         }

         if (callSession.value != null) {
             com.ipdial.util.SipLogger.log("SipViewModel", "makeCall: call already in progress, ignoring (state=${callSession.value?.state})")
             Toast.makeText(getApplication(), "A call is already in progress", Toast.LENGTH_SHORT).show()
             return
         }

         val transportSuffix = when (account.transport) {
             Transport.TCP -> ";transport=tcp"
             Transport.TLS -> ";transport=tls"
             else -> ""
         }

         val finalUri = if (cleanedInput.contains("@")) {
             val base = if (cleanedInput.startsWith("sip:")) cleanedInput else "sip:$cleanedInput"
             if (!base.contains("transport=") && transportSuffix.isNotEmpty()) {
                 base + transportSuffix
             } else {
                 base
             }
         } else {
             val num = cleanedInput.removePrefix("sip:")

             val host = if (account.port != null && account.port > 0 && !account.domain.contains(":")) {
                 "${account.domain}:${account.port}"
             } else {
                 account.domain
             }
             "sip:$num@$host$transportSuffix"
         }

         android.util.Log.d("SipViewModel", "Direct Dialing: $finalUri")

         // Save as last dialed (the raw number)
         viewModelScope.launch {
             repo.setLastDialedNumber(rawInput)
         }

         if (callSession.value == null) {
             // Default to Bluetooth if available
             if (_hasBluetoothDevice.value) {
                 setAudioDevice(AudioDeviceMode.BLUETOOTH)
             } else {
                 setAudioDevice(AudioDeviceMode.EARPIECE)
             }

             // HACK: Emulators often have broken Telecom integration for self-managed calls.
             // If we detect an emulator, bypass Telecom and call direct to PJSIP.
             val isEmulator = com.ipdial.util.DeviceUtil.isEmulator()
             com.ipdial.util.SipLogger.log(
                 "SipViewModel",
                 "makeCall: emulator=$isEmulator (product=${android.os.Build.PRODUCT}, model=${android.os.Build.MODEL}, manufacturer=${android.os.Build.MANUFACTURER}, hardware=${android.os.Build.HARDWARE}, brand=${android.os.Build.BRAND})"
             )

            var success = false
            if (!isEmulator) {
                android.util.Log.d("SipViewModel", "Placing call via TelecomManager...")
                com.ipdial.util.SipLogger.log("SipViewModel", "Placing call via TelecomManager: $finalUri")
                success = try {
                    com.ipdial.service.TelecomHelper.placeOutgoingCall(getApplication(), finalUri, account.id)
                } catch (e: Exception) {
                    android.util.Log.e("SipViewModel", "TelecomManager failure, falling back", e)
                    com.ipdial.util.SipLogger.log("SipViewModel", "TelecomManager threw, falling back to direct call")
                    false
                }
            } else {
                android.util.Log.i("SipViewModel", "Emulator detected, bypassing TelecomManager")
                com.ipdial.util.SipLogger.log("SipViewModel", "Emulator detected, bypassing TelecomManager")
            }

            if (success) {
                // Telecom accepted the call. On some devices and emulators (including
                // MuMu with fully spoofed Build props) Telecom accepts an outgoing call
                // but never delivers it to our ConnectionService, so no SIP session is
                // ever created. Watch for a session briefly; if none appears, fall back
                // to a direct engine call. SipConnectionService rejects a late Telecom
                // delivery, so this can never double-place the call.
                viewModelScope.launch {
                    val acc = account
                    val uri = finalUri
                    val sessionSeen = withTimeoutOrNull(3000) {
                        callSession.filter { it != null }.first()
                        true
                    } ?: false
                    if (!sessionSeen) {
                        com.ipdial.util.SipLogger.log(
                            "SipViewModel",
                            "Telecom accepted call but no SIP session in 3s - falling back to direct SipEngine.makeCall"
                        )
                        launchDirectCall(acc, uri)
                    } else {
                        com.ipdial.util.SipLogger.log(
                            "SipViewModel",
                            "Telecom call confirmed - session created (state=${callSession.value?.state})"
                        )
                    }
                }
            } else {
                com.ipdial.util.SipLogger.log("SipViewModel", "Calling direct via SipEngine")
                launchDirectCall(account, finalUri)
            }
         }
     }

    private fun launchDirectCall(account: SipAccount, uri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val engineStarted = SipEngine.makeCall(account.id, uri)
            if (!engineStarted) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Call not sent", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun cleanUri(uri: String): String = com.ipdial.ui.screens.cleanUri(uri)

    fun cleanDisplayName(name: String, uri: String): String = com.ipdial.ui.screens.cleanDisplayName(name, uri)

    fun answerCall() {
        val id = callSession.value?.callId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            SipEngine.answerCall(id)
            withContext(Dispatchers.Main) {
                com.ipdial.service.SipConnectionService.getConnection(id)?.setActive()
                // Default to Bluetooth if available
                if (_hasBluetoothDevice.value) {
                    setAudioDevice(AudioDeviceMode.BLUETOOTH)
                } else {
                    setAudioDevice(AudioDeviceMode.EARPIECE)
                }
                // CRITICAL FIX: Force audio devices and EC for the answered call
                // This ensures the audio path is properly established
                SipEngine.forceAudioDevicesForCall()
                SipEngine.forceEcForCallAudio()
            }
        }
    }

    fun hangup() {
        val session = callSession.value
        val id = session?.callId ?: -1
        android.util.Log.d("SipViewModel", "hangup() called: callId=$id, state=${session?.state}, direction=${session?.direction}")

        val causeCode = com.ipdial.service.CallHangupResolver.resolveDisconnectCause(session)

        viewModelScope.launch(Dispatchers.IO) {
            SipEngine.hangupCall(id)

            // Also tear down the Telecom connection so the system dialer
            // notification is dismissed.
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

     fun setCallVolume(factor: Float) {
         _callVolume.value = factor
         SipAudioController.setCallVolume(factor)
     }

     fun setShowFullIncomingScreen(show: Boolean) {
         _showFullIncomingScreen.value = show
     }

     fun cycleAudioDevice() {
         viewModelScope.launch {
             try {
                 val currentMode = _audioDeviceMode.value
                 val hasBt = _hasBluetoothDevice.value

                 val nextMode = when (currentMode) {
                     AudioDeviceMode.EARPIECE -> AudioDeviceMode.SPEAKER
                     AudioDeviceMode.SPEAKER -> if (hasBt) AudioDeviceMode.BLUETOOTH else AudioDeviceMode.EARPIECE
                     AudioDeviceMode.BLUETOOTH -> AudioDeviceMode.EARPIECE
                 }

                 setAudioDevice(nextMode)
                 
                 // Force audio path re-establishment after device change
                 withContext(Dispatchers.Main) {
                     SipEngine.forceAudioDevicesForCall()
                     SipEngine.forceEcForCallAudio()
                 }
             } catch (e: Exception) {
                 android.util.Log.e("SipViewModel", "Failed to cycle audio device", e)
             }
         }
     }

     fun setAudioDevice(mode: AudioDeviceMode) {
         // Keep SipEngine's state in sync for UI and routing logic
         com.ipdial.service.SipAudioController.setSpeaker(mode == AudioDeviceMode.SPEAKER)
         
         viewModelScope.launch {
             try {
                 _audioDeviceMode.value = mode
                 val app = getApplication<Application>()
                 val serviceIntent = Intent(app, com.ipdial.service.SipService::class.java).apply {
                     action = "com.ipdial.SET_AUDIO_DEVICE"
                     putExtra("mode", mode.name)
                 }
                 app.startService(serviceIntent)
                 android.util.Log.d("SipViewModel", "Requested audio device: $mode")
             } catch (e: Exception) {
                 android.util.Log.e("SipViewModel", "Failed to set audio device: $mode", e)
             }
         }
     }

     fun updateBluetoothAvailability() {
         viewModelScope.launch {
             try {
                 val audioManager = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                 val devices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                 val hasBt = devices.any {
                     it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                             it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                 }
                 _hasBluetoothDevice.value = hasBt
                 
                 // If Bluetooth was lost and we were in BLUETOOTH mode, fallback to EARPIECE
                 if (!hasBt && _audioDeviceMode.value == AudioDeviceMode.BLUETOOTH) {
                     setAudioDevice(AudioDeviceMode.EARPIECE)
                 }
             } catch (e: Exception) {
                 android.util.Log.e("SipViewModel", "Failed to check Bluetooth availability", e)
             }
         }
     }

    fun toggleRecording() {
        val session = callSession.value ?: return
        if (session.isRecording) {
            SipAudioController.stopRecording()
        } else {
            // Priority: Internal storage as requested
            val baseDir = getApplication<Application>().getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC)
            val folder = java.io.File(baseDir, "IPDialRecordings")
            try {
                if (!folder.exists()) folder.mkdirs()
                val sdf = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US)
                val dateStr = sdf.format(java.util.Date())
                val num = session.remoteUri.replace("<", "").replace(">", "").removePrefix("sip:").substringBefore("@").substringBefore(";")
                val cleanNum = num.filter { it.isLetterOrDigit() || it == '+' }
                val recFile = java.io.File(folder, "IPDial_${cleanNum}_${dateStr}.wav")
                // Using PJSIP internal WAV recorder (AAC natively locked by SIP mic)
                SipAudioController.startRecording(recFile.absolutePath)
            } catch (e: Exception) {
                android.util.Log.e("SipViewModel", "Recording failed", e)
            }
        }
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

    fun callBack(entry: CallLogEntry) {
        val accId = entry.accountId.ifBlank {
            _selectedAccountId.value ?: accounts.value.firstOrNull { it.isEnabled }?.id ?: accounts.value.firstOrNull()?.id ?: return
        }
        _selectedAccountId.value = accId
        makeCall(cleanUri(entry.remoteUri))
    }

    fun logCall(entry: CallLogEntry) = viewModelScope.launch {
        logRepo.insert(entry)
        // Maintain a maximum of 50 entries in the call log
        val logs = logRepo.entries.first()
        if (logs.size > 50) {
            val toDelete = logs.sortedByDescending { it.timestampMs }.drop(50)
            toDelete.forEach { logEntry ->
                logRepo.delete(logEntry)
            }
        }
    }

    private var adJob: Job? = null

    private var interstitialAd: com.startapp.sdk.adsbase.StartAppAd? = null

    fun dismissAd() {
        adJob?.cancel()
        _showAd.value = false
    }

    fun triggerAd(context: Context, durationMs: Long = 10000L, autoDismiss: Boolean = true) {
        if (isPro.value) return
        // Replace interstitial usage with banner display: set showAd flag and let UI show banner composable
        try { interstitialAd = null } catch (_: Exception) {}
        adJob?.cancel()
        _showAd.value = true
        if (autoDismiss) {
            adJob = viewModelScope.launch {
                delay(durationMs)
                _showAd.value = false
            }
        }
    }

    fun onCodecAction(context: Context) {
        // Ads dropped
    }

    fun fetchBalance(account: SipAccount, context: Context) {
        val host = account.domain.lowercase().trim()
        if (!SUPPORTED_BALANCE_DOMAINS.contains(host)) {
            android.util.Log.d("SipViewModel", "fetchBalance: domain $host not supported for balance fetch")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Determine API URL based on host. sip.amarip.net and billing.webvoice.net
                // are the DNS names for 103.170.231.10 and 103.129.202.202 respectively.
                // We use the DNS names for valid TLS, but if DNS fails, we could retry with IP.
                val urlString = when (host) {
                    "103.129.202.202", "billing.webvoice.net" -> "https://billing.webvoice.net/api/mobile/login"
                    else -> "https://sip.amarip.net/api/mobile/login"
                }
                
                val url = java.net.URL(urlString)
                val conn = url.openConnection() as java.net.HttpURLConnection

                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true

                val loginData = mapOf(
                    "username" to account.username,
                    "password" to account.password
                )
                val body = Gson().toJson(loginData)
                android.util.Log.d("SipViewModel", "fetchBalance: host=$host url=$urlString")
                
                conn.outputStream.use { it.write(body.toByteArray()) }

                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    android.util.Log.d("SipViewModel", "fetchBalance: Success")
                    val json = org.json.JSONObject(response)
                    val balance = json.getJSONObject("data")
                        .getJSONObject("client")
                        .getString("balance_text")
                    
                    withContext(Dispatchers.Main) {
                        val current = _balances.value.toMutableMap()
                        current[account.id] = balance
                        _balances.value = current
                        showAdBriefly()
                    }
                } else {
                    val errorBody = try {
                        conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    } catch (_: Exception) {
                        ""
                    }
                    android.util.Log.e(
                        "SipViewModel",
                        "fetchBalance: FAILED HTTP ${conn.responseCode} body=$errorBody"
                    )
                    withContext(Dispatchers.Main) {
                        val msg = if (account.password.isEmpty()) {
                            "Balance failed: password is empty"
                        } else {
                            "Balance failed (HTTP ${conn.responseCode})"
                        }
                        Toast.makeText(getApplication(), msg, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("SipViewModel", "fetchBalance: EXCEPTION", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Balance fetch error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun onAudioAction(context: Context, onAction: () -> Unit) {
        onAction()
    }
}
