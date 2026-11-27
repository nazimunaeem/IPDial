package com.ipdial.service.sim

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import android.util.Log
import com.ipdial.AppState
import com.ipdial.data.model.AudioDeviceMode
import com.ipdial.data.model.CallDirection
import com.ipdial.data.model.CallLogEntry
import com.ipdial.data.model.CallSession
import com.ipdial.data.model.CallState
import com.ipdial.data.model.CallTransport
import com.ipdial.data.repository.AccountRepository
import com.ipdial.data.repository.CallLogRepository
import com.ipdial.data.repository.ContactsRepository
import com.ipdial.service.SipRingtonePlayer
import com.ipdial.service.SipWakeLockManager
import com.ipdial.service.activeCallStartTime
import com.ipdial.service.cancelIncomingNotification
import com.ipdial.service.createNotificationChannels
import com.ipdial.service.showCallNotificationStatic
import com.ipdial.service.showMissedCallNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages active cellular SIM calls delivered by Telecom to [SimInCallService],
 * exposing unified [CallSession] & audio state for IPDial's Compose call UI.
 */
object SimCallManager {

    private const val TAG = "SimCallManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val callIdSeq = AtomicInteger(10000)

    @Volatile private var inCallService: InCallService? = null
    @Volatile private var activeCall: Call? = null
    @Volatile private var activeCallback: Call.Callback? = null
    @Volatile private var ringtonePlayer: SipRingtonePlayer? = null
    @Volatile private var wakeLockManager: SipWakeLockManager? = null
    @Volatile private var appContext: Context? = null

    @Volatile private var wasConfirmed: Boolean = false
    @Volatile private var wasHdAudio: Boolean = false
    @Volatile private var confirmedAtMs: Long = 0L
    @Volatile private var loggedCallLogId: String? = null
    @Volatile private var vibratedForCallId: Int = -1
    @Volatile private var supportedRouteMask: Int = CallAudioState.ROUTE_EARPIECE or CallAudioState.ROUTE_SPEAKER

    private val _callSession = MutableStateFlow<CallSession?>(null)
    val callSession: StateFlow<CallSession?> = _callSession.asStateFlow()

    private val _audioDeviceMode = MutableStateFlow(AudioDeviceMode.EARPIECE)
    val audioDeviceMode: StateFlow<AudioDeviceMode> = _audioDeviceMode.asStateFlow()

    private val _hasBluetoothDevice = MutableStateFlow(false)
    val hasBluetoothDevice: StateFlow<Boolean> = _hasBluetoothDevice.asStateFlow()

    fun hasActiveCall(): Boolean {
        val session = _callSession.value
        return activeCall != null && session != null && session.state != CallState.DISCONNECTED
    }

    fun onServiceBound(service: InCallService) {
        inCallService = service
        val ctx = service.applicationContext
        appContext = ctx
        try {
            createNotificationChannels(ctx)
        } catch (_: Throwable) {}
        if (ringtonePlayer == null) {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val repo = AccountRepository(ctx)
            ringtonePlayer = SipRingtonePlayer(ctx, am, repo)
        }
    }

    fun onServiceUnbound(service: InCallService) {
        if (inCallService === service) {
            inCallService = null
        }
    }

    fun onCallAdded(service: InCallService, call: Call) {
        onServiceBound(service)
        if (activeCall === call) return
        val ctx = service.applicationContext

        val rawState = getTelecomState(call)
        val number = extractNumber(call)
        if (UssdHelper.isUssdCode(number) ||
            rawState == Call.STATE_DISCONNECTED ||
            rawState == Call.STATE_DISCONNECTING
        ) {
            Log.d(TAG, "Ignoring transient USSD/disconnected Telecom call: number=$number state=$rawState")
            return
        }

        activeCallback?.let { prevCb ->
            try {
                activeCall?.unregisterCallback(prevCb)
            } catch (_: Throwable) {}
        }

        activeCall = call
        wasConfirmed = false
        wasHdAudio = false
        confirmedAtMs = 0L
        loggedCallLogId = null
        vibratedForCallId = -1

        val direction = resolveDirection(call, rawState)
        val callerDisplay = call.details?.callerDisplayName?.takeIf { it.isNotBlank() } ?: number
        val lineLabel = SimPhoneAccountHelper.resolveLabelForHandle(ctx, call.details?.accountHandle)
        val callId = callIdSeq.incrementAndGet()
        val callLogId = UUID.randomUUID().toString()
        val mappedState = mapTelecomState(rawState, direction)

        val isHd = com.ipdial.service.CallAudioQualityHelper.isTelecomHdAudio(call.details)
        if (isHd) wasHdAudio = true

        val initialSession = CallSession(
            callId = callId,
            accountId = call.details?.accountHandle?.id ?: "sim",
            remoteUri = number,
            remoteDisplayName = callerDisplay,
            direction = direction,
            state = mappedState,
            isOnHold = rawState == Call.STATE_HOLDING,
            callLogId = callLogId,
            startedAtMs = System.currentTimeMillis(),
            transport = CallTransport.SIM,
            lineLabel = lineLabel,
            isHdAudio = isHd
        )
        _callSession.value = initialSession

        if (number.isNotBlank()) {
            scope.launch {
                try {
                    val contact = ContactsRepository(ctx).lookupDeviceContactByNumber(number)
                    if (contact != null && activeCall === call) {
                        val cur = _callSession.value
                        if (cur != null && cur.callId == callId) {
                            _callSession.value = cur.copy(remoteDisplayName = contact.name)
                            if (com.ipdial.service.IncomingCallPresentationHelper.shouldPresentIncomingOverLockScreen(AppState.isForeground, ctx) &&
                                cur.state != CallState.DISCONNECTED
                            ) {
                                showCallNotificationStatic(ctx, contact.name, callId)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Contact lookup failed for SIM call: ${e.message}")
                }
            }
        }

        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                if (activeCall !== call) return
                handleStateUpdate(ctx, call, state)
            }

            override fun onDetailsChanged(call: Call, details: Call.Details?) {
                if (activeCall !== call || details == null) return
                val cur = _callSession.value ?: return
                val newNum = details.handle?.schemeSpecificPart?.takeIf { it.isNotBlank() } ?: cur.remoteUri
                if (UssdHelper.isUssdCode(newNum)) {
                    Log.d(TAG, "Ignoring transient USSD Telecom call updated in details: $newNum")
                    activeCallback?.let { cb ->
                        try {
                            call.unregisterCallback(cb)
                        } catch (_: Throwable) {}
                    }
                    activeCallback = null
                    forceClear()
                    return
                }
                val newName = details.callerDisplayName?.takeIf { it.isNotBlank() } ?: cur.remoteDisplayName
                val newLine = SimPhoneAccountHelper.resolveLabelForHandle(ctx, details.accountHandle)
                val isHd = com.ipdial.service.CallAudioQualityHelper.isTelecomHdAudio(details)
                if (isHd) wasHdAudio = true
                _callSession.value = cur.copy(
                    remoteUri = newNum,
                    remoteDisplayName = newName,
                    lineLabel = newLine,
                    isHdAudio = isHd
                )
            }
        }
        activeCallback = callback
        call.registerCallback(callback)

        handleStateUpdate(ctx, call, rawState)
    }

    fun onCallRemoved(service: InCallService, call: Call) {
        if (activeCall !== call) return
        val ctx = service.applicationContext
        activeCallback?.let { cb ->
            try {
                call.unregisterCallback(cb)
            } catch (_: Throwable) {}
        }
        activeCallback = null
        activeCall = null
        ringtonePlayer?.stopRingtone()
        wakeLockManager?.releaseWakeLock()
        cancelIncomingNotification(ctx)

        _callSession.value?.let { session ->
            persistCallLogIfNeeded(ctx, session, call)
        }
        activeCallStartTime = 0L
        _callSession.value = null
        _audioDeviceMode.value = AudioDeviceMode.EARPIECE
    }

    fun onCallAudioStateChanged(audioState: CallAudioState?) {
        if (audioState == null) return
        supportedRouteMask = audioState.supportedRouteMask
        _hasBluetoothDevice.value = (supportedRouteMask and CallAudioState.ROUTE_BLUETOOTH) != 0

        val mode = when {
            (audioState.route and CallAudioState.ROUTE_BLUETOOTH) != 0 -> AudioDeviceMode.BLUETOOTH
            (audioState.route and CallAudioState.ROUTE_SPEAKER) != 0 -> AudioDeviceMode.SPEAKER
            else -> AudioDeviceMode.EARPIECE
        }
        _audioDeviceMode.value = mode

        _callSession.value?.let { cur ->
            val updated = cur.copy(
                isMuted = audioState.isMuted,
                isSpeaker = mode == AudioDeviceMode.SPEAKER
            )
            _callSession.value = updated
            appContext?.let { ctx -> updateWakeLocks(ctx, updated, mode) }
        }
    }

    private fun handleStateUpdate(ctx: Context, call: Call, telecomState: Int) {
        val current = _callSession.value ?: return
        val mappedState = mapTelecomState(telecomState, current.direction)
        val isHold = telecomState == Call.STATE_HOLDING
        val isHd = com.ipdial.service.CallAudioQualityHelper.isTelecomHdAudio(call.details)
        if (isHd) wasHdAudio = true

        val updated = current.copy(
            state = mappedState,
            isOnHold = isHold,
            isHdAudio = isHd
        )
        _callSession.value = updated

        when (telecomState) {
            Call.STATE_RINGING -> {
                getOrCreateWakeLockManager(ctx).acquireWakeLockForIncoming()
                ringtonePlayer?.playRingtone()
                if (com.ipdial.service.IncomingCallPresentationHelper.shouldPresentIncomingOverLockScreen(AppState.isForeground, ctx)) {
                    showCallNotificationStatic(ctx, updated.remoteDisplayName, updated.callId)
                    com.ipdial.service.IncomingCallPresentationHelper.launchIncomingCallUiIfLocked(ctx, updated.callId)
                }
            }
            Call.STATE_DIALING,
            Call.STATE_CONNECTING -> {
                ringtonePlayer?.stopRingtone()
                if (!AppState.isForeground) {
                    showCallNotificationStatic(ctx, updated.remoteDisplayName, updated.callId)
                }
            }
            Call.STATE_ACTIVE -> {
                ringtonePlayer?.stopRingtone()
                wasConfirmed = true
                if (confirmedAtMs == 0L) {
                    confirmedAtMs = System.currentTimeMillis()
                    activeCallStartTime = confirmedAtMs
                }
                if (vibratedForCallId != updated.callId) {
                    vibratedForCallId = updated.callId
                    triggerConnectVibrationIfEnabled(ctx)
                }
                if (!AppState.isForeground) {
                    showCallNotificationStatic(ctx, updated.remoteDisplayName, updated.callId)
                } else {
                    cancelIncomingNotification(ctx)
                }
            }
            Call.STATE_HOLDING -> {
                ringtonePlayer?.stopRingtone()
            }
            Call.STATE_DISCONNECTED,
            Call.STATE_DISCONNECTING -> {
                ringtonePlayer?.stopRingtone()
                wakeLockManager?.releaseWakeLock()
                cancelIncomingNotification(ctx)
                persistCallLogIfNeeded(ctx, updated, call)
                activeCallStartTime = 0L
                val disconnectedCallId = updated.callId
                scope.launch {
                    delay(400)
                    if (_callSession.value?.callId == disconnectedCallId) {
                        _callSession.value = null
                        activeCall = null
                    }
                }
            }
        }
        updateWakeLocks(ctx, updated, _audioDeviceMode.value)
    }

    private fun getOrCreateWakeLockManager(ctx: Context): SipWakeLockManager {
        return wakeLockManager ?: synchronized(this) {
            wakeLockManager ?: SipWakeLockManager(ctx.applicationContext).also { wakeLockManager = it }
        }
    }

    private fun updateWakeLocks(
        ctx: Context,
        session: CallSession?,
        audioMode: AudioDeviceMode = _audioDeviceMode.value
    ) {
        val wl = getOrCreateWakeLockManager(ctx)
        if (session == null || session.state == CallState.DISCONNECTED) {
            wl.releaseWakeLock()
            return
        }
        when (session.state) {
            CallState.INCOMING, CallState.EARLY -> {
                wl.acquireWakeLockForIncoming()
                wl.acquireWakeLock()
                wl.releaseProximityWakeLock()
            }
            CallState.CALLING, CallState.CONNECTING, CallState.CONFIRMED -> {
                wl.acquireWakeLock()
                if (shouldAcquireProximityWakeLock(session, audioMode, AppState.disableProximitySensor)) {
                    wl.acquireProximityWakeLock()
                } else {
                    wl.releaseProximityWakeLock()
                }
            }
            CallState.DISCONNECTED,
            CallState.IDLE -> {
                wl.releaseWakeLock()
            }
        }
    }

    internal fun shouldAcquireProximityWakeLock(
        session: CallSession?,
        audioMode: AudioDeviceMode,
        disableProximitySensor: Boolean
    ): Boolean {
        if (session == null || disableProximitySensor || session.isOnHold) return false
        return audioMode == AudioDeviceMode.EARPIECE && (
            session.state == CallState.CALLING ||
                session.state == CallState.CONNECTING ||
                session.state == CallState.CONFIRMED
            )
    }

    private fun persistCallLogIfNeeded(ctx: Context, session: CallSession, call: Call) {
        if (UssdHelper.isUssdCode(session.remoteUri)) return
        if (loggedCallLogId == session.callLogId) return
        loggedCallLogId = session.callLogId

        val durationSec = if (confirmedAtMs > 0L) {
            ((System.currentTimeMillis() - confirmedAtMs) / 1000L).coerceAtLeast(0L)
        } else {
            0L
        }
        val missed = !wasConfirmed && session.direction == CallDirection.INCOMING
        val cause = call.details?.disconnectCause
        val entry = CallLogEntry(
            id = session.callLogId,
            accountId = session.accountId,
            remoteUri = session.remoteUri,
            remoteDisplayName = session.remoteDisplayName,
            direction = session.direction,
            missed = missed,
            timestampMs = session.startedAtMs,
            durationSeconds = durationSec,
            disconnectCode = cause?.code,
            disconnectReason = cause?.reason?.toString(),
            transport = CallTransport.SIM,
            lineLabel = session.lineLabel.ifBlank { "SIM" },
            isHdAudio = wasHdAudio || session.isHdAudio
        )

        scope.launch {
            try {
                CallLogRepository.getInstance(ctx).insert(entry)
                if (missed) {
                    showMissedCallNotification(ctx, session.remoteDisplayName, session.remoteUri)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist SIM call log: ${e.message}")
            }
        }
    }

    fun answerCall() {
        ringtonePlayer?.stopRingtone()
        try {
            activeCall?.answer(VideoProfile.STATE_AUDIO_ONLY)
        } catch (e: Exception) {
            Log.e(TAG, "answerCall failed: ${e.message}")
        }
    }

    fun hangup() {
        ringtonePlayer?.stopRingtone()
        val call = activeCall ?: return
        val state = getTelecomState(call)
        try {
            if (state == Call.STATE_RINGING) {
                call.reject(false, null)
            } else {
                call.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "hangup failed: ${e.message}")
        }
    }

    fun forceClear() {
        ringtonePlayer?.stopRingtone()
        wakeLockManager?.releaseWakeLock()
        appContext?.let { cancelIncomingNotification(it) }
        activeCallStartTime = 0L
        _callSession.value = null
        activeCall = null
    }

    fun toggleMute() {
        val nextMuted = !(_callSession.value?.isMuted ?: false)
        try {
            @Suppress("DEPRECATION")
            inCallService?.setMuted(nextMuted)
            _callSession.value = _callSession.value?.copy(isMuted = nextMuted)
        } catch (e: Exception) {
            Log.e(TAG, "toggleMute failed: ${e.message}")
        }
    }

    fun toggleHold() {
        val call = activeCall ?: return
        val state = getTelecomState(call)
        try {
            if (state == Call.STATE_HOLDING) {
                call.unhold()
            } else {
                call.hold()
            }
        } catch (e: Exception) {
            Log.e(TAG, "toggleHold failed: ${e.message}")
        }
    }

    fun toggleSpeaker() {
        val currentlySpeaker = _audioDeviceMode.value == AudioDeviceMode.SPEAKER
        val targetRoute = if (currentlySpeaker) CallAudioState.ROUTE_EARPIECE else CallAudioState.ROUTE_SPEAKER
        setTelecomAudioRoute(targetRoute, if (currentlySpeaker) AudioDeviceMode.EARPIECE else AudioDeviceMode.SPEAKER)
    }

    fun cycleAudioDevice() {
        val btSupported = (supportedRouteMask and CallAudioState.ROUTE_BLUETOOTH) != 0
        val current = _audioDeviceMode.value
        val next = when (current) {
            AudioDeviceMode.EARPIECE -> AudioDeviceMode.SPEAKER
            AudioDeviceMode.SPEAKER -> if (btSupported) AudioDeviceMode.BLUETOOTH else AudioDeviceMode.EARPIECE
            AudioDeviceMode.BLUETOOTH -> AudioDeviceMode.EARPIECE
        }
        val targetRoute = when (next) {
            AudioDeviceMode.SPEAKER -> CallAudioState.ROUTE_SPEAKER
            AudioDeviceMode.BLUETOOTH -> CallAudioState.ROUTE_BLUETOOTH
            AudioDeviceMode.EARPIECE -> CallAudioState.ROUTE_EARPIECE
        }
        setTelecomAudioRoute(targetRoute, next)
    }

    private fun setTelecomAudioRoute(route: Int, mode: AudioDeviceMode) {
        try {
            @Suppress("DEPRECATION")
            inCallService?.setAudioRoute(route)
            _audioDeviceMode.value = mode
            val updated = _callSession.value?.copy(isSpeaker = mode == AudioDeviceMode.SPEAKER)
            _callSession.value = updated
            appContext?.let { ctx -> updateWakeLocks(ctx, updated, mode) }
        } catch (e: Exception) {
            Log.e(TAG, "setTelecomAudioRoute($route) failed: ${e.message}")
        }
    }

    fun sendDtmf(digit: Char) {
        val call = activeCall ?: return
        try {
            call.playDtmfTone(digit)
            scope.launch {
                delay(180)
                try {
                    call.stopDtmfTone()
                } catch (_: Throwable) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendDtmf($digit) failed: ${e.message}")
        }
    }

    fun silenceRinger() {
        ringtonePlayer?.stopRingtone()
    }

    private fun triggerConnectVibrationIfEnabled(ctx: Context) {
        scope.launch {
            try {
                val repo = AccountRepository(ctx)
                val vibrate = repo.globalVibrate.first()
                val dnd = repo.dndEnabled.first()
                if (!vibrate || dnd) return@launch

                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    vm.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }
                if (!vibrator.hasVibrator()) return@launch
                val effect = VibrationEffect.createOneShot(120L, VibrationEffect.DEFAULT_AMPLITUDE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    vibrator.vibrate(
                        effect,
                        VibrationAttributes.createForUsage(VibrationAttributes.USAGE_COMMUNICATION_REQUEST)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(effect)
                }
            } catch (_: Throwable) {}
        }
    }

    private fun getTelecomState(call: Call): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            call.details?.state ?: @Suppress("DEPRECATION") call.state
        } else {
            @Suppress("DEPRECATION")
            call.state
        }
    }

    private fun resolveDirection(call: Call, state: Int): CallDirection {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (call.details?.callDirection) {
                Call.Details.DIRECTION_INCOMING -> return CallDirection.INCOMING
                Call.Details.DIRECTION_OUTGOING -> return CallDirection.OUTGOING
            }
        }
        return if (state == Call.STATE_RINGING) CallDirection.INCOMING else CallDirection.OUTGOING
    }

    private fun extractNumber(call: Call): String {
        val handle = call.details?.handle ?: return ""
        return handle.schemeSpecificPart?.takeIf { it.isNotBlank() } ?: handle.toString()
    }

    private fun mapTelecomState(state: Int, direction: CallDirection): CallState {
        return when (state) {
            Call.STATE_RINGING -> CallState.INCOMING
            Call.STATE_DIALING,
            Call.STATE_SELECT_PHONE_ACCOUNT -> CallState.CALLING
            Call.STATE_CONNECTING -> CallState.CONNECTING
            Call.STATE_ACTIVE,
            Call.STATE_HOLDING -> CallState.CONFIRMED
            Call.STATE_DISCONNECTED,
            Call.STATE_DISCONNECTING -> CallState.DISCONNECTED
            else -> if (direction == CallDirection.INCOMING) CallState.INCOMING else CallState.CALLING
        }
    }
}
