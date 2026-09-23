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
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
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
import com.ipdial.data.repository.FirestorePointsSync
import com.ipdial.data.repository.AuthRepository
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

    // Auth repository. Initialized on first access (lazy) so R8 constructor
    // inlining cannot reorder a mid-constructor assignment ahead of its reads.
    val authRepo: AuthRepository by lazy { AuthRepository(app) }

    // Firestore instance used for single-device session claiming.
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    val isSignedIn: StateFlow<Boolean>
    val currentUser: StateFlow<com.google.firebase.auth.FirebaseUser?>

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

    val googleSignInBannerDismissed: StateFlow<Boolean> = repo.googleSignInBannerDismissed
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

    val userCode: StateFlow<String> = repo.userCode.map { it ?: "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    // Assigned in the init block once authRepo is ready.
    val userEmail: StateFlow<String>
    val userName: StateFlow<String>

    // Single source of truth for the account ID shown to the user (About screen,
    // GetPro referral card, profile chip). Uses the account-bound Firestore code
    // (shortId) once synced, otherwise falls back to the Firebase UID prefix.
    // Lazy, not lateinit: `isSignedIn`/`deviceId` are assigned in the init block,
    // and R8 constructor inlining must not reorder this access ahead of them.
    val userDisplayId: StateFlow<String> by lazy {
        combine(userCode, isSignedIn, deviceId) { code, signedIn, devId ->
            when {
                code.isNotEmpty() -> code
                signedIn -> authRepo.referralCode
                else -> devId.take(6)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    }

    // Whether THIS device has Pro access (within first N logged-in devices).
    // null = unknown / not signed in; false = signed in but no Pro access (beyond slot limit).
    private val _currentDeviceHasPro = MutableStateFlow<Boolean?>(null)
    val currentDeviceHasPro: StateFlow<Boolean?> = _currentDeviceHasPro.asStateFlow()

    // Total number of device slots the signed-in account can hold (default 2).
    private val _allowedDeviceCount = MutableStateFlow(2)
    val allowedDeviceCount: StateFlow<Int> = _allowedDeviceCount.asStateFlow()

    // All logged-in devices for this account.
    private val _loggedInDevices = MutableStateFlow<List<String>>(emptyList())
    val loggedInDevices: StateFlow<List<String>> = _loggedInDevices.asStateFlow()

    // Number of logged-in devices.
    val loggedInDeviceCount: StateFlow<Int> = loggedInDevices.map { it.size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    // Whether this device has Pro access.
    val currentDeviceHasProFlow: StateFlow<Boolean> = currentDeviceHasPro
        .map { it == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Free slots still available = total slots minus logged-in devices.
    val availableSlots: StateFlow<Int> = combine(allowedDeviceCount, loggedInDeviceCount) { allowed, used ->
        maxOf(0, allowed - used)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private suspend fun refreshDeviceSlots() {
        _allowedDeviceCount.value = firestoreSync?.getAllowedDeviceCount() ?: 2
    }

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

    // Pro follows the ACCOUNT's subscription: usable wherever the signed-in
    // account's expiration is still in the future. Device-slot bookkeeping
    // (free/purchased whitelisted devices) is informational only — it must never
    // revoke an already-paid-for expiration, otherwise a single account that
    // signs in on a second device (or after its deviceId changed) would
    // inexplicably lose Pro / be nagged to "buy a slot".
    val isPro: StateFlow<Boolean> = combine(proExpiration, _timeTicker) { exp, now ->
        exp > now
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
        
    val recordingCounter: StateFlow<Int> = repo.recordingCounter
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val autoRecordEnabled: StateFlow<Boolean> = repo.autoRecordEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val globalNoiseCancellation: StateFlow<Boolean> = repo.globalNoiseCancellation
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // Global audio processing preferences (applied to all accounts)
    val globalEcEnabled: StateFlow<Boolean> = repo.globalEcEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val globalNsEnabled: StateFlow<Boolean> = repo.globalNsEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val globalAgcEnabled: StateFlow<Boolean> = repo.globalAgcEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // Global NAT traversal (TURN relay) settings — applied to ALL accounts.
    // Blank username/password means TURN is disabled; ICE+STUN+IPv6 still handle
    // most NAT types for free.
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

    fun setThemeMode(context: Context, mode: ThemeMode) = viewModelScope.launch { 
        repo.setThemeMode(mode)
        if (!isPro.value) triggerAd(context)
    }
    fun setDnd(enabled: Boolean) = viewModelScope.launch { repo.setDnd(enabled) }
    fun setGlobalVibrate(enabled: Boolean) = viewModelScope.launch { repo.setGlobalVibrate(enabled) }
    fun setFullScreenContactPhoto(enabled: Boolean) = viewModelScope.launch { repo.setFullScreenContactPhoto(enabled) }
    fun dismissGoogleSignInBanner() = viewModelScope.launch { repo.dismissGoogleSignInBanner() }
    
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
    fun setGlobalNoiseCancellation(context: Context, enabled: Boolean) = viewModelScope.launch { 
        repo.setGlobalNoiseCancellation(enabled)
        if (!isPro.value) triggerAd(context)
    }
    fun setGlobalEcEnabled(context: Context, enabled: Boolean) = viewModelScope.launch {
        repo.setGlobalEcEnabled(enabled)
        if (!isPro.value) triggerAd(context)
        reapplyGlobalAudioSettings()
    }
    fun setGlobalNsEnabled(context: Context, enabled: Boolean) = viewModelScope.launch {
        repo.setGlobalNsEnabled(enabled)
        if (!isPro.value) triggerAd(context)
        reapplyGlobalAudioSettings()
    }
    fun setGlobalAgcEnabled(context: Context, enabled: Boolean) = viewModelScope.launch {
        repo.setGlobalAgcEnabled(enabled)
        if (!isPro.value) triggerAd(context)
        reapplyGlobalAudioSettings()
    }

    /**
     * Pushes the global audio-processing preferences into the SIP engine so new
     * registrations/calls pick them up without requiring a re-registration.
     */
    fun reapplyGlobalAudioSettings() {
        val ec = globalEcEnabled.value
        val ns = globalNsEnabled.value
        val agc = globalAgcEnabled.value
        com.ipdial.service.SipEngine.applyGlobalAudioSettings(ec, ns, agc)
    }

    suspend fun clearCallHistory() {
        logRepo.deleteAll()
    }

    fun getReferralCode(): String {
        // Use the account-bound 6-char user code if available, otherwise fall back
        // to Firebase UID prefix or anonymous deviceId prefix.
        val code = userCode.value
        if (code.isNotEmpty()) return code
        if (authRepo.isSignedIn) return authRepo.referralCode
        return deviceId.value.take(6)
    }

    fun signIn(activityContext: Context, onComplete: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val result = authRepo.signIn(activityContext)
            if (result.isSuccess) {
                val token = result.getOrNull().orEmpty()
                if (token.isBlank()) {
                    com.ipdial.util.SipLogger.log("SipViewModel", "Sign-in failed: no ID token returned by Credential Manager")
                    withContext(Dispatchers.Main) { onComplete(false, "Sign-in failed: no ID token returned") }
                    return@launch
                }

                // The Google credential was obtained, but Firebase must still
                // exchange the ID token. If this silently fails (common on
                // Android 15+ OEM devices such as OnePlus) the user appears
                // signed-out even though the account picker "completed".
                val authResult = authRepo.firebaseAuthWithGoogle(token)
                if (authResult.isFailure) {
                    val msg = authResult.exceptionOrNull()?.message ?: "unknown error"
                    com.ipdial.util.SipLogger.log("SipViewModel", "firebaseAuthWithGoogle failed: $msg")
                    withContext(Dispatchers.Main) { onComplete(false, "Sign-in failed: $msg") }
                    return@launch
                }

                val uid = authRepo.currentUser.value?.uid
                if (uid != null) {
                    val myDeviceId = repo.getOrCreateDeviceId()

                    // Record this device in the account's device-slot bookkeeping
                    // (free first slot / optionally purchased slots). This is purely
                    // bookkeeping: Pro eligibility is the account's expiration, so a
                    // device that is not whitelisted still keeps Pro. We deliberately
                    // do NOT claim a "sessions" document — that single-active-session
                    // mechanism used to force sign-out of the other device, making
                    // BOTH devices signed-out-but-points-syncing messes.
                    val canUsePro = firestoreSync?.addDeviceAndCheckPro(myDeviceId) == true
                    _currentDeviceHasPro.value = canUsePro
                    if (canUsePro) {
                        repo.setProDeviceAuthorized(true, myDeviceId)
                    } else {
                        repo.setProDeviceAuthorized(false, null)
                    }
                    refreshDeviceSlots()

                    // Generate or retrieve the account-bound 6-char user code and
                    // upsert the user document (named by the new uid) storing the id,
                    // device slot number, email and user name. Done after the free
                    // first-device slot is claimed so the stored device slot is correct.
                    try {
                        val code = firestoreSync?.getOrCreateUserCode(uid) ?: ""
                        if (code.isNotEmpty()) {
                            repo.setUserCode(code)
                            com.ipdial.util.SipLogger.log("SipViewModel", "User code: $code")
                        }
                    } catch (e: Exception) {
                        com.ipdial.util.SipLogger.log("SipViewModel", "getOrCreateUserCode failed: ${e.message}")
                    }
                }

                // Restart Firestore listening with new UID
                firestoreSync?.startListening()
                withContext(Dispatchers.Main) { onComplete(true, "Signed in") }
            } else {
                val reason = result.exceptionOrNull()?.message ?: "Sign-in cancelled"
                com.ipdial.util.SipLogger.log("SipViewModel", "Credential Manager sign-in failed: $reason")
                withContext(Dispatchers.Main) { onComplete(false, if (reason.isBlank()) "Sign-in cancelled" else reason) }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            val myDeviceId = repo.getOrCreateDeviceId()

            // Remove this device from the logged-in devices list
            if (myDeviceId.isNotEmpty()) {
                firestoreSync?.removeLoggedInDevice(myDeviceId)
            }

            forceSignOutLocally()
        }
    }

    /**
     * Local sign-out. Stops cloud listeners, signs out Firebase, and wipes the
     * locally cached pro points/days/UID so they cannot be carried over. The next
     * sign-in re-syncs the account's points/expiration from Firestore.
     */
    private suspend fun forceSignOutLocally() {
        _currentDeviceHasPro.value = null
        _allowedDeviceCount.value = 2
        _loggedInDevices.value = emptyList()
        firestoreSync?.stopListening()
        authRepo.signOut()
        repo.clearFirebaseUserData()
    }

    fun deleteAccount(activityContext: Context?, onComplete: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val result = if (activityContext != null) {
                authRepo.deleteAccount(activityContext)
            } else {
                authRepo.deleteAccount()
            }
            if (result.isSuccess) {
                repo.setFirebaseUserId(null)
                repo.setProPoints(0)
                repo.setProExpiration(0L)
                withContext(Dispatchers.Main) { onComplete(true, "Account deleted") }
            } else {
                withContext(Dispatchers.Main) { onComplete(false, result.exceptionOrNull()?.message ?: "Delete failed") }
            }
        }
    }

    fun claimReferral(code: String, onComplete: (Boolean, String) -> Unit) {
        try {
            firestoreSync?.claimReferral(code, onComplete) ?: onComplete(false, "Service unavailable")
        } catch (e: Exception) {
            onComplete(false, e.message ?: "error")
        }
    }

    fun redeemPoints(days: Int, onComplete: (Boolean, String) -> Unit = { _, _ -> }) {
        if (!authRepo.isSignedIn) {
            onComplete(false, "Please sign in to buy Pro")
            return
        }
        viewModelScope.launch {
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
                onComplete(true, "Pro activated")
            }
        }
    }

    private val _adCooldownSeconds = MutableStateFlow(0)
    val adCooldownSeconds: StateFlow<Int> = _adCooldownSeconds.asStateFlow()

    /**
     * Purchase authorization for the CURRENT device to use Pro, at
     * [FirestorePointsSync.DEVICE_SLOT_COST] points. If successful this device
     * becomes authorized and can claim the active session.
     */
    fun removeDevice(onComplete: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val uid = authRepo.currentUser.value?.uid ?: return@launch
            val myDeviceId = repo.getOrCreateDeviceId()
            val success = firestoreSync?.removeLoggedInDevice(myDeviceId) ?: false
            if (success) {
                onComplete(true, "Device removed and signed out")
            } else {
                onComplete(false, "Failed to remove device")
            }
        }
    }

    fun clearAllDevices() {
        viewModelScope.launch {
            val uid = authRepo.currentUser.value?.uid ?: return@launch
            val success = firestoreSync?.clearAllDevices(uid) ?: false
            if (success) {
                _currentDeviceHasPro.value = false
                refreshDeviceSlots()
            }
        }
    }

    fun removeDevice(deviceEntry: String) {
        viewModelScope.launch {
            val myDeviceId = repo.getOrCreateDeviceId()
            firestoreSync?.removeLoggedInDevice(deviceEntry)
        }
    }

    private fun startAdCooldown() {
        viewModelScope.launch {
            _adCooldownSeconds.value = 5
            while (_adCooldownSeconds.value > 0) {
                delay(1000)
                _adCooldownSeconds.value -= 1
            }
        }
    }

    private fun grantRewardedAdPoint() {
        viewModelScope.launch {
            try { firestoreSync?.incrementPoints(1) } catch (_: Exception) {
                // Firestore unavailable — still grant the point locally.
                repo.setProPoints(maxOf(0, proPoints.value + 1))
            }
            _isLoadingAd.value = false
            startAdCooldown()
        }
    }

    fun watchRewardedAd(context: Context, onReward: () -> Unit) {
        if (!authRepo.isSignedIn) {
            return
        }
        if (_isLoadingAd.value || _adCooldownSeconds.value > 0) return
        _isLoadingAd.value = true
        android.util.Log.d("SipViewModel", "Starting rewarded video flow")

        val grantReward = {
            android.util.Log.d("SipViewModel", "Granting 1 point for ad")
            grantRewardedAdPoint()
            onReward()
        }

        // Prefer a rewarded video. If no video is available, fall back to an
        // interstitial and still grant the reward when it is closed.
        val rewardedAd = com.startapp.sdk.adsbase.StartAppAd(context)
        rewardedAd.setVideoListener(object : com.startapp.sdk.adsbase.adlisteners.VideoListener {
            override fun onVideoCompleted() {
                android.util.Log.d("SipViewModel", "Rewarded video completed")
                grantReward()
            }
        })

        rewardedAd.loadAd(
            com.startapp.sdk.adsbase.StartAppAd.AdMode.REWARDED_VIDEO,
            object : com.startapp.sdk.adsbase.adlisteners.AdEventListener {
                override fun onReceiveAd(ad: com.startapp.sdk.adsbase.Ad) {
                    android.util.Log.d("SipViewModel", "Rewarded video received, showing...")
                    rewardedAd.showAd(object : com.startapp.sdk.adsbase.adlisteners.AdDisplayListener {
                        override fun adDisplayed(ad: com.startapp.sdk.adsbase.Ad?) {}
                        override fun adNotDisplayed(ad: com.startapp.sdk.adsbase.Ad?) {
                            android.util.Log.w("SipViewModel", "Rewarded video not displayed")
                            _isLoadingAd.value = false
                        }
                        override fun adClicked(ad: com.startapp.sdk.adsbase.Ad?) {}
                        override fun adHidden(ad: com.startapp.sdk.adsbase.Ad?) {
                            android.util.Log.d("SipViewModel", "Rewarded video closed")
                            _isLoadingAd.value = false
                        }
                    })
                }
                override fun onFailedToReceiveAd(ad: com.startapp.sdk.adsbase.Ad?) {
                    android.util.Log.w("SipViewModel", "No rewarded video available, falling back to interstitial")
                    showFallbackInterstitialForReward(context, grantReward)
                }
            }
        )
    }

    // Fallback when a rewarded video could not be loaded: show an interstitial
    // and treat its dismissal as the completed reward gate.
    private fun showFallbackInterstitialForReward(context: Context, grantReward: () -> Unit) {
        val interstitial = com.startapp.sdk.adsbase.StartAppAd(context)
        interstitial.loadAd(
            com.startapp.sdk.adsbase.StartAppAd.AdMode.OVERLAY,
            object : com.startapp.sdk.adsbase.adlisteners.AdEventListener {
                override fun onReceiveAd(ad: com.startapp.sdk.adsbase.Ad) {
                    android.util.Log.d("SipViewModel", "Fallback interstitial received, showing...")
                    interstitial.showAd(object : com.startapp.sdk.adsbase.adlisteners.AdDisplayListener {
                        override fun adDisplayed(ad: com.startapp.sdk.adsbase.Ad?) {}
                        override fun adNotDisplayed(ad: com.startapp.sdk.adsbase.Ad?) {
                            android.util.Log.w("SipViewModel", "Fallback interstitial not displayed")
                            _isLoadingAd.value = false
                        }
                        override fun adClicked(ad: com.startapp.sdk.adsbase.Ad?) {}
                        override fun adHidden(ad: com.startapp.sdk.adsbase.Ad?) {
                            android.util.Log.d("SipViewModel", "Fallback interstitial closed, granting reward")
                            grantReward()
                        }
                    })
                }
                override fun onFailedToReceiveAd(ad: com.startapp.sdk.adsbase.Ad?) {
                    android.util.Log.e("SipViewModel", "Fallback interstitial also failed to load")
                    _isLoadingAd.value = false
                }
            }
        )
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

     // Set synchronously the moment the user requests a call, so a rapid double-tap
     // on the dial button cannot race the asynchronous session creation in
     // SipEngine.makeCallOnThread (which would spawn two concurrent PJSIP calls).
     private val _isMakingCall = java.util.concurrent.atomic.AtomicBoolean(false)

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

        if (!deviceNoiseCancellationSupported) {
            viewModelScope.launch {
                repo.setGlobalNoiseCancellation(false)
            }
        }

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
        
        try {
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
        } catch (e: Exception) {
            // Connectivity updates are advisory; a restricted OEM implementation must
            // not prevent the main screen from opening.
            android.util.Log.w("SipViewModel", "Network callback unavailable", e)
        }

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

        // Give fresh installs a one-time three-day Pro welcome offer before
        // creating the device ID used to distinguish existing installations.
        viewModelScope.launch(Dispatchers.IO) {
            repo.initializeProWelcomeOffer()
            repo.getOrCreateDeviceId()
        }

        // Initialize default call volume from current system STREAM_VOICE_CALL level
        try {
            val am = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            val current = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            if (max > 0) {
                _callVolume.value = (current.toFloat() / max.toFloat() * 6f).coerceIn(0f, 6f)
            }
        } catch (_: Exception) {}

        // Initialize Auth-backed state flows. authRepo itself is lazy (see its
        // declaration) and initializes on first access here.
        isSignedIn = authRepo.currentUser.map { it != null }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)
        currentUser = authRepo.currentUser
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

        userEmail = authRepo.currentUser.map { it?.email ?: "" }
            .stateIn(viewModelScope, SharingStarted.Eagerly, "")
        userName = authRepo.currentUser.map { it?.displayName ?: "" }
            .stateIn(viewModelScope, SharingStarted.Eagerly, "")

        // Initialize Firestore sync for points/expiration
        try {
            firestoreSync = FirestorePointsSync(repo)
            // Live device-slot state → UI. Fires from the snapshot listener
            // whenever loggedInDevices / allowedDevices change.
            firestoreSync?.onDeviceSlotsChanged = { loggedIn, pending, allowed ->
                _loggedInDevices.value = loggedIn
                _allowedDeviceCount.value = allowed
                val myDeviceId = deviceId.value
                if (myDeviceId.isNotEmpty()) {
                    val hasPro = loggedIn.indexOfFirst { com.ipdial.data.repository.FirestorePointsSync.isSameDevice(it, myDeviceId) }.let { index ->
                        index >= 0 && index < allowed
                    }
                    _currentDeviceHasPro.value = hasPro
                }
                android.util.Log.d("SipViewModel", "slots: allowed=$allowed loggedIn=${loggedIn.size} me=$myDeviceId hasPro=${_currentDeviceHasPro.value}")
            }
            firestoreSync?.startListening()
        } catch (e: Throwable) {
            android.util.Log.e("SipViewModel", "FirestorePointsSync init failed", e)
        }

        // Fast Pro on relaunch: if this device was already in the first N logged-in devices,
        // unlock it locally right away (before the Firestore round-trip confirms).
        viewModelScope.launch {
            val myDeviceId = repo.getOrCreateDeviceId()
            val cachedAuthorized = repo.proDeviceAuthorized.first() &&
                repo.proDeviceAuthorizedFor.first() == myDeviceId
            if (cachedAuthorized) {
                _currentDeviceHasPro.value = true
            }
        }

        // Check for migration on first sign-in
        viewModelScope.launch {
            val firebaseUid = authRepo.userId
            val deviceId = repo.getOrCreateDeviceId()
            val savedFirebaseId = repo.firebaseUserId.first()

            if (firebaseUid != null && savedFirebaseId == null) {
                // First sign-in - migrate device data
                firestoreSync?.migrateFromDeviceId(deviceId, firebaseUid)
                repo.setFirebaseUserId(firebaseUid)
            }

            // If the user is already authenticated (auth persists across launches),
            // reflect authorization state. `signIn()` also does this on a fresh sign-in.
            // No "sessions" doc is claimed: Pro follows the account, so multiple
            // devices can be signed in simultaneously without kicking each other out.
            if (firebaseUid != null) {
                val myDeviceId = repo.getOrCreateDeviceId()
                // addDeviceAndCheckPro() adds this device to logged-in list and returns Pro access status.
                val authorized = firestoreSync?.addDeviceAndCheckPro(myDeviceId) == true
                _currentDeviceHasPro.value = authorized
                if (authorized) {
                    repo.setProDeviceAuthorized(true, myDeviceId)
                }
                refreshDeviceSlots()
            }
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

    // Device call-volume bridge: the physical volume buttons drive both the app's
    // PJSIP RX gain (guaranteed audible change) and the device's STREAM_VOICE_CALL
    // volume (native HUD + OEM fallback), keeping the two in sync in both directions.
    // On OEM builds (ColorOS/EMUI/MIUI) the system intercepts volume keys before the
    // Activity ever sees them AND silently drops ContentObserver notifications, so a
    // lightweight poller samples the streams directly while a call is active and
    // bridges WHATEVER stream the OS actually moved into the PJSIP listening gain.
    private var callVolumeObserverRegistered = false
    @Volatile private var applyingDeviceVolume = false
    // Timestamp of our own last volume change (hardware press, slider, or mirror).
    // While we're actively changing volume ourselves the poller/observer must NOT
    // re-bridge the stream echoes our writes produce, otherwise one press can
    // apply 2-3 steps at once.
    @Volatile private var lastSelfVolumeChangeAt = 0L
    private var lastKnownVoiceVolume = -1
    private var lastKnownMusicVolume = -1
    private val mainHandler = Handler(Looper.getMainLooper())
    private var volumePollerJob: Job? = null

    // Map device 0..max -> in-app 0..6 and apply to the listening gain so the
    // change stays audible even if the OS swallowed the key events.
    private fun bridgeDeviceVolumeChange(idx: Int, max: Int, which: String) {
        if (max <= 1) return
        val factor = (idx.toFloat() / max.toFloat() * 6f).coerceIn(0f, 6f)
        android.util.Log.d("SipViewModel", "Device volume ($which) changed to $idx/$max -> factor $factor")
        _callVolume.value = factor
        SipAudioController.setCallVolume(factor)
    }

    // Read both streams and bridge whichever changed since the last sample.
    // Shared by the ContentObserver fast-path and the in-call poller; baselines
    // guarantee an external change is applied exactly once. Any movement produced
    // by our own writes within the last 800ms is ignored (baselines still adopt
    // the values) so an echo can never compound into a multi-step jump.
    private fun monitorDeviceVolume() {
        if (applyingDeviceVolume) return
        val active = callSession.value
        if (active == null || active.state == CallState.DISCONNECTED) return
        try {
            val am = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val voiceMax = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            val voiceIdx = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            val musicMax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val musicIdx = am.getStreamVolume(AudioManager.STREAM_MUSIC)

            val voiceChanged = voiceMax > 1 && voiceIdx != lastKnownVoiceVolume
            val musicChanged = musicMax > 1 && musicIdx != lastKnownMusicVolume
            lastKnownVoiceVolume = voiceIdx
            lastKnownMusicVolume = musicIdx

            when {
                voiceChanged -> bridgeDeviceVolumeChange(voiceIdx, voiceMax, "voice")
                musicChanged -> bridgeDeviceVolumeChange(musicIdx, musicMax, "music")
            }
        } catch (e: Exception) {
            android.util.Log.w("SipViewModel", "Failed to monitor device volume", e)
        }
    }

    private inner class VoiceVolumeObserver : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            if (selfChange) return
            monitorDeviceVolume()
        }
    }
    private val voiceVolumeObserver = VoiceVolumeObserver()

    private fun registerVoiceVolumeObserver() {
        if (callVolumeObserverRegistered) return
        callVolumeObserverRegistered = true
        try {
            getApplication<Application>().contentResolver
                .registerContentObserver(Settings.System.CONTENT_URI, true, voiceVolumeObserver)
            val am = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager
            lastKnownVoiceVolume = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            lastKnownMusicVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            android.util.Log.d("SipViewModel", "Volume observer registered, voice=$lastKnownVoiceVolume music=$lastKnownMusicVolume")
        } catch (e: Exception) {
            android.util.Log.w("SipViewModel", "Failed to register voice-volume observer", e)
        }
    }

    // Some OEMs never deliver Settings.System notifications for volume changes, so
    // the active-call poller is the reliable fast-enough fallback that catches
    // physical presses on every device.
    private fun startVolumePoller() {
        volumePollerJob?.cancel()
        volumePollerJob = viewModelScope.launch {
            while (true) {
                monitorDeviceVolume()
                kotlinx.coroutines.delay(200)
            }
        }
    }

    private fun stopVolumePoller() {
        volumePollerJob?.cancel()
        volumePollerJob = null
    }

    private fun unregisterVoiceVolumeObserver() {
        if (!callVolumeObserverRegistered) return
        callVolumeObserverRegistered = false
        try {
            getApplication<Application>().contentResolver
                .unregisterContentObserver(voiceVolumeObserver)
        } catch (e: Exception) {
            android.util.Log.w("SipViewModel", "Failed to unregister voice-volume observer", e)
        }
    }

    private fun mirrorToDeviceVoiceVolume(factor: Float) {
        try {
            lastSelfVolumeChangeAt = android.os.SystemClock.elapsedRealtime()
            val am = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            if (max <= 0) return
            val pjsipLevel = com.ipdial.service.SipAudioController.callVolumeToPjsipLevel(factor)
            val idx = (pjsipLevel / 2f * max).toInt().coerceIn(0, max)
            applyingDeviceVolume = true
            try {
                // FLAG_SHOW_UI surfaces the system volume panel so it visibly tracks
                // the change; stream write may be silently ignored by some OEMs.
                am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, idx, AudioManager.FLAG_SHOW_UI)
            } finally {
                applyingDeviceVolume = false
            }
            // Adopt the ACTUAL value the system ended up with, not our target. If the
            // OEM ignored the write this keeps the baseline honest so the in-call
            // poller never treats our own (failed) write as a physical press.
            lastKnownVoiceVolume = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        } catch (e: Exception) {
            android.util.Log.w("SipViewModel", "Failed to mirror to device voice volume", e)
        }
    }

    // Mirrors the physical-button direction into the system's voice-call stream so
    // the OEM's volume panel/bar visibly rises and falls with each press, then
    // re-baselines the poller against whatever value the system actually applied.
    private fun nudgeDeviceVoiceVolume(up: Boolean) {
        try {
            lastSelfVolumeChangeAt = android.os.SystemClock.elapsedRealtime()
            val am = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.adjustStreamVolume(
                AudioManager.STREAM_VOICE_CALL,
                if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI
            )
            lastKnownVoiceVolume = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        } catch (e: Exception) {
            android.util.Log.w("SipViewModel", "Failed to nudge device voice volume", e)
        }
    }

    private fun observeCallSession() {
        viewModelScope.launch {
            callSession.collect { session ->
                callTimeoutJob?.cancel()
                callTimeoutJob = null

                if (session != null && session.state != CallState.DISCONNECTED) {
                    registerVoiceVolumeObserver()
                    startVolumePoller()
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
                    stopVolumePoller()
                    unregisterVoiceVolumeObserver()
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

        // Media-never-established watchdog for stuck/silent calls. This is the
        // pjsua2 2.5-only RTP fallback: the binding exposes NO stream packet
        // counters (StreamInfo.rxPt/txPt are payload types, not counts), so packet
        // activity can't be polled. Instead, the negotiated audio codec field is
        // populated only after a media stream goes ACTIVE and getStreamInfo()
        // succeeds — a CONFIRMED call that never negotiates a codec for 15s+15s
        // means no RTP direction ever came up (one-way/silent/stuck call). Fail it
        // explicitly rather than leaving the screen up forever.
        viewModelScope.launch {
            var deadMediaCallId = -1
            while (true) {
                kotlinx.coroutines.delay(15_000)
                val s = callSession.value
                if (s == null || s.callId == -1 || s.state != CallState.CONFIRMED || s.negotiatedCodec != null) {
                    deadMediaCallId = -1
                    continue
                }
                if (deadMediaCallId == s.callId) {
                    android.util.Log.w("SipViewModel", "No media negotiated for CONFIRMED call ${s.callId} after 30s — ending stuck call")
                    hangup()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(getApplication(), "Call failed — no audio path", Toast.LENGTH_SHORT).show()
                    }
                    deadMediaCallId = -1
                } else {
                    deadMediaCallId = s.callId
                }
            }
        }

        // Sync device volume on call start
        viewModelScope.launch {
            callSession.collect { session ->
                if (session != null && session.state != CallState.DISCONNECTED) {
                    try {
                        val am = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        val voiceMax = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                        val voiceIdx = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
                        if (voiceMax > 0) {
                            val factor = (voiceIdx.toFloat() / voiceMax.toFloat() * 6f).coerceIn(0f, 6f)
                            if (Math.abs(_callVolume.value - factor) > 0.01f) {
                                _callVolume.value = factor
                                SipAudioController.setCallVolume(factor)
                                android.util.Log.d("SipViewModel", "Call start: synced device volume $voiceIdx/$voiceMax -> factor $factor")
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("SipViewModel", "Failed to sync volume on call start", e)
                    }
                }
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
        // Use the native call ID rather than the UI state, which can lag briefly
        // during same-app calls and otherwise drops keypad tones in that window.
        if ((callSession.value?.callId ?: -1) >= 0) {
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

         if (callSession.value != null || !_isMakingCall.compareAndSet(false, true)) {
             com.ipdial.util.SipLogger.log("SipViewModel", "makeCall: call already in progress or in-flight, ignoring (state=${callSession.value?.state}, isMakingCall=${_isMakingCall.get()})")
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
                        _isMakingCall.set(false)
                    }
                }
            } else {
                com.ipdial.util.SipLogger.log("SipViewModel", "Calling direct via SipEngine")
                launchDirectCall(account, finalUri)
            }
         } else {
             // A session appeared between the pre-flight check and dispatch (e.g. a
             // concurrent incoming call). Release the in-flight latch so calls aren't
             // permanently blocked.
             _isMakingCall.set(false)
         }
     }

    private fun launchDirectCall(account: SipAccount, uri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val engineStarted = SipEngine.makeCall(account.id, uri)
            _isMakingCall.set(false)
            if (!engineStarted) {
                // makeCall may be elided by the engine guard when Telecom already
                // placed (or is placing) the call. Only warn if no live session exists;
                // else the CallScreen will dismiss on the active session.
                val session = SipEngine.callSession.value
                val callProceeding = session != null && session.state != CallState.DISCONNECTED
                if (!callProceeding) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(getApplication(), "Call not sent", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    com.ipdial.util.SipLogger.log(
                        "SipViewModel",
                        "Direct call elided but session active (callId=${session.callId}, state=${session.state}) — not showing error"
                    )
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
         lastSelfVolumeChangeAt = android.os.SystemClock.elapsedRealtime()
         _callVolume.value = factor
         SipAudioController.setCallVolume(factor)
         mirrorToDeviceVoiceVolume(factor)
     }

    /**
     * Adjusts the in-app call volume (PJSIP RX gain) from the physical volume
     * buttons. Available the moment a call is placed (dialing/ringing/active).
     */
    fun adjustCallVolumeByHardware(up: Boolean) {
        val step = 1f
        val newVol = (if (up) _callVolume.value + step else _callVolume.value - step)
            .coerceIn(0f, 6f)
        android.util.Log.d("SipViewModel", "adjustCallVolumeByHardware: up=$up -> ${_callVolume.value} -> $newVol call=${callSession.value?.state}")
        setCallVolume(newVol)
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
            return
        }
        listOf(
            CallState.CALLING, CallState.EARLY, CallState.INCOMING, CallState.CONNECTING
        ).any { it == session.state } .let { whileDialing ->
            if (whileDialing) {
                // Recording armed during dialing/ringing: start once the call is received.
                startRecordingWhenActive(session)
            } else {
                startRecording(session)
            }
        }
    }

    private fun startRecording(session: CallSession) {
        val baseDir = getApplication<Application>().getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC)
        val folder = java.io.File(baseDir, "IPDialRecordings")
        try {
            if (!folder.exists()) folder.mkdirs()
            val sdf = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US)
            val dateStr = sdf.format(java.util.Date())
            val num = session.remoteUri.replace("<", "").replace(">", "").removePrefix("sip:").substringBefore("@").substringBefore(";")
            val cleanNum = num.filter { it.isLetterOrDigit() || it == '+' }
            val recFile = java.io.File(folder, "IPDial_${cleanNum}_${dateStr}.wav")
            SipAudioController.startRecording(recFile.absolutePath)
        } catch (e: Exception) {
            android.util.Log.e("SipViewModel", "Recording failed", e)
        }
    }

    private fun startRecordingWhenActive(session: CallSession) {
        val baseDir = getApplication<Application>().getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC)
        val folder = java.io.File(baseDir, "IPDialRecordings")
        try {
            if (!folder.exists()) folder.mkdirs()
            val sdf = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US)
            val dateStr = sdf.format(java.util.Date())
            val num = session.remoteUri.replace("<", "").replace(">", "").removePrefix("sip:").substringBefore("@").substringBefore(";")
            val cleanNum = num.filter { it.isLetterOrDigit() || it == '+' }
            val recFile = java.io.File(folder, "IPDial_${cleanNum}_${dateStr}.wav")
            SipAudioController.startRecordingWhenActive(recFile.absolutePath)
        } catch (e: Exception) {
            android.util.Log.e("SipViewModel", "Recording failed", e)
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

    // ── Server Inspector ────────────────────────────────────────────

    private val _inspection = MutableStateFlow(com.ipdial.data.model.ServerInspection())
    val inspection: StateFlow<com.ipdial.data.model.ServerInspection> = _inspection.asStateFlow()

    private val _inspectionRunning = MutableStateFlow(false)
    val inspectionRunning: StateFlow<Boolean> = _inspectionRunning.asStateFlow()

    /**
     * Runs a multi-step server inspection: DNS → Registration check → Call Quality → DTMF.
     *
     * The call-quality and DTMF steps require an account already registered on the
     * target host AND an active call to that host. If no matching account is
     * registered or no call is active, those steps are reported as WARN (not FAIL)
     * with guidance text for the user.
     */
    fun runServerInspection(host: String) {
        if (_inspectionRunning.value) return
        _inspectionRunning.value = true
        val cleanHost = host.trim().removePrefix("sip:").removePrefix("sips://")
        _inspection.value = com.ipdial.data.model.ServerInspection(
            host = cleanHost,
            startedAtMs = System.currentTimeMillis(),
            dns = com.ipdial.data.model.DnsResult(status = com.ipdial.data.model.InspectStatus.RUNNING, host = cleanHost),
        )

        viewModelScope.launch(Dispatchers.IO) {
            // ── Step 1: DNS ─────────────────────────────────────────
            val dnsResult = runDnsProbe(cleanHost)
            _inspection.value = _inspection.value.copy(
                dns = dnsResult,
                register = com.ipdial.data.model.RegisterResult(status = com.ipdial.data.model.InspectStatus.RUNNING),
            )

            // ── Step 2: Registration check ──────────────────────────
            val regResult = runRegisterProbe(cleanHost)
            _inspection.value = _inspection.value.copy(
                register = regResult,
                callQuality = com.ipdial.data.model.CallQualitySnapshot(status = com.ipdial.data.model.InspectStatus.RUNNING),
            )

            // ── Step 3: Call Quality (requires live call) ───────────
            val qualityResult = runCallQualityProbe(cleanHost)
            _inspection.value = _inspection.value.copy(
                callQuality = qualityResult,
                dtmf = com.ipdial.data.model.DtmfResult(status = com.ipdial.data.model.InspectStatus.RUNNING),
            )

            // ── Step 4: DTMF probe (requires live call) ─────────────
            val dtmfResult = runDtmfProbe(cleanHost)
            _inspection.value = _inspection.value.copy(
                dtmf = dtmfResult,
                finishedAtMs = System.currentTimeMillis(),
            )

            _inspectionRunning.value = false
        }
    }

    private fun runDnsProbe(host: String): com.ipdial.data.model.DnsResult {
        return try {
            val hostOnly = host.substringBefore(":").substringBefore(";")
            val start = System.currentTimeMillis()
            val addresses = java.net.InetAddress.getAllByName(hostOnly)
            val elapsed = System.currentTimeMillis() - start
            val resolved = addresses.firstOrNull()?.hostAddress ?: ""
            com.ipdial.data.model.DnsResult(
                status = if (resolved.isNotBlank()) InspectStatus.PASS else InspectStatus.FAIL,
                host = host,
                resolvedIp = resolved,
                latencyMs = elapsed,
            )
        } catch (e: Throwable) {
            com.ipdial.data.model.DnsResult(
                status = InspectStatus.FAIL,
                host = host,
                error = e.message ?: "DNS resolution failed",
            )
        }
    }

    private fun runRegisterProbe(host: String): com.ipdial.data.model.RegisterResult {
        val acct = accounts.value.firstOrNull {
            it.isEnabled && it.domain.lowercase().trim().let { d ->
                d == host.lowercase() || d.startsWith(host.lowercase().substringBefore(":"))
            }
        }
        if (acct == null) {
            return com.ipdial.data.model.RegisterResult(
                status = InspectStatus.WARN,
                statusText = "No account registered on this host. Add an account with domain \"$host\" to test registration.",
            )
        }
        val transport = acct.transport.name
        return when (acct.regStatus) {
            com.ipdial.data.model.RegStatus.REGISTERED -> com.ipdial.data.model.RegisterResult(
                status = InspectStatus.PASS,
                statusCode = 200,
                statusText = "REGISTERED (${acct.displayName})",
                transport = transport,
            )
            com.ipdial.data.model.RegStatus.ERROR -> com.ipdial.data.model.RegisterResult(
                status = InspectStatus.FAIL,
                statusText = "Registration ERROR: ${acct.regStatusText.ifBlank { "auth or network failure" }}",
                transport = transport,
            )
            com.ipdial.data.model.RegStatus.REGISTERING -> com.ipdial.data.model.RegisterResult(
                status = InspectStatus.WARN,
                statusText = "Registration in progress…",
                transport = transport,
            )
            else -> com.ipdial.data.model.RegisterResult(
                status = InspectStatus.WARN,
                statusText = "Account is unregistered (disabled or not yet attempted).",
                transport = transport,
            )
        }
    }

    private fun runCallQualityProbe(host: String): com.ipdial.data.model.CallQualitySnapshot {
        val session = callSession.value
        if (session == null || session.state != com.ipdial.data.model.CallState.CONFIRMED) {
            return com.ipdial.data.model.CallQualitySnapshot(
                status = InspectStatus.WARN,
                qualitySummary = "No active call. Place a call to \"$host\" first, then re-run the inspection to capture live quality metrics.",
            )
        }

        // Verify the call is to the inspected host
        val callDomain = accounts.value.firstOrNull { it.id == session.accountId }?.domain?.lowercase()?.trim() ?: ""
        if (!callDomain.startsWith(host.lowercase().substringBefore(":"))) {
            return com.ipdial.data.model.CallQualitySnapshot(
                status = InspectStatus.WARN,
                qualitySummary = "Active call is on \"$callDomain\", not \"$host\". Place a call to the inspected host for accurate results.",
            )
        }

        val snapshot = SipEngine.snapshotCallQuality()
        if (snapshot == null) {
            return com.ipdial.data.model.CallQualitySnapshot(
                status = InspectStatus.FAIL,
                error = "Failed to read stream info from PJSIP engine.",
            )
        }

        val (codec, clockRate, dump) = snapshot
        val codecClean = codec.trim().uppercase().ifBlank { session.negotiatedCodec ?: "unknown" }
        val quality = when {
            codecClean.contains("OPUS") -> "Excellent (Opus, wideband adaptive)"
            codecClean.contains("G722") -> "Excellent (G.722, wideband 16kHz)"
            codecClean.contains("PCMA") || codecClean.contains("PCMU") -> "Good (G.711, narrowband 8kHz)"
            codecClean.contains("G729") -> "Fair (G.729, low-bandwidth 8kHz)"
            codecClean.contains("GSM") -> "Low (GSM, 13kbps)"
            else -> "Unknown codec: $codecClean"
        }

        return com.ipdial.data.model.CallQualitySnapshot(
            status = InspectStatus.PASS,
            negotiatedCodec = codecClean,
            clockRateHz = clockRate,
            callDurationSec = session.durationSeconds,
            qualitySummary = quality,
        )
    }

    private fun runDtmfProbe(host: String): com.ipdial.data.model.DtmfResult {
        val session = callSession.value
        if (session == null || session.state != com.ipdial.data.model.CallState.CONFIRMED) {
            return com.ipdial.data.model.DtmfResult(
                status = InspectStatus.WARN,
                method = "N/A",
                error = "No active call. Place a call first, then re-run to test DTMF delivery.",
            )
        }

        val (method, accepted) = SipEngine.probeDtmf('1')
        return com.ipdial.data.model.DtmfResult(
            status = if (accepted) InspectStatus.PASS else InspectStatus.FAIL,
            method = method,
            digit = '1',
            accepted = accepted,
            error = if (!accepted) "DTMF digit rejected by engine ($method)" else null,
        )
    }
}
