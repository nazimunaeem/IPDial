package com.ipdial.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

class SipAudioRouter(
    private val context: Context,
    private val audioManager: AudioManager
) {
    private val TAG = "SipService"

    private var audioFocusRequest: AudioFocusRequest? = null

    /**
     * Returns true if a Bluetooth SCO device (headset/handsfree) is connected.
     * SCO is the only Bluetooth profile that supports full-duplex audio for calls.
     * A2DP is a one-way music profile with no mic path and must NOT be used for VoIP.
     */
    fun isBluetoothConnected(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            return devices.any { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        } else {
            @Suppress("DEPRECATION")
            return audioManager.isBluetoothScoAvailableOffCall
        }
    }

    fun routeAudioToDefault() {
        requestAudioFocus()
        val session = SipEngine.callSession.value ?: return
        if (session.isSpeaker) {
            routeAudioToSpeaker(true)
        } else if (isBluetoothConnected()) {
            routeAudioToBluetooth()
        } else {
            routeAudioToEarpiece()
        }
    }

    fun routeAudioToEarpiece() {
        val session = SipEngine.callSession.value
        if (session != null && session.callId >= 0) {
            val connection = SipConnectionService.getConnection(session.callId)
            @Suppress("DEPRECATION")
            connection?.setAudioRoute(android.telecom.CallAudioState.ROUTE_EARPIECE)
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isMicrophoneMute = false
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            val earpieceDevice = devices.find { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            if (earpieceDevice != null) {
                val res = audioManager.setCommunicationDevice(earpieceDevice)
                Log.d(TAG, "setCommunicationDevice earpiece: $res")
            } else {
                audioManager.clearCommunicationDevice()
                Log.d(TAG, "Earpiece device not found, clearCommunicationDevice")
            }
        }
    }

    fun routeAudioToSpeaker(on: Boolean) {
        val session = SipEngine.callSession.value
        if (session != null && session.callId >= 0) {
            val connection = SipConnectionService.getConnection(session.callId)
            if (connection != null) {
                val route = if (on) android.telecom.CallAudioState.ROUTE_SPEAKER else android.telecom.CallAudioState.ROUTE_EARPIECE
                @Suppress("DEPRECATION")
                connection.setAudioRoute(route)
                Log.d(TAG, "Routed audio via Telecom Connection to speaker=$on")
            }
        }

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isMicrophoneMute = false
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = on

        // Extend EC tail on speakerphone: the acoustic echo path from speaker to mic
        // is much longer on speakerphone (especially on low-end / Chinese OEM devices
        // with large chassis). 600 ms covers even the worst cases.
        // Only apply if hardware AEC is NOT available to avoid double-processing.
        try {
            val hasHwAec = try { android.media.audiofx.AcousticEchoCanceler.isAvailable() } catch(e: Exception) { false }
            val isEmulator = com.ipdial.util.DeviceUtil.isEmulator()
            
            if (!hasHwAec || isEmulator) {
                synchronized(SipEngine.pjsipLock) {
                    val adm = SipEngine.endpoint?.audDevManager()
                    if (adm != null) {
                        // ecOptions 33 = PJMEDIA_ECHO_DEFAULT | PJMEDIA_ECHO_USE_NOISE_SUPPRESSOR
                        adm.setEcOptions(33, if (on) 600 else 500)
                        Log.d(TAG, "EC tail set to ${if (on) 600 else 500} ms (speaker=$on)")
                    }
                }
            } else {
                Log.d(TAG, "Hardware AEC active, skipping software EC adjustment for speaker.")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to adjust EC tail for speaker=$on: ${e.message}")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (on) {
                val devices = audioManager.availableCommunicationDevices
                val speakerDevice = devices.find { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speakerDevice != null) {
                    val res = audioManager.setCommunicationDevice(speakerDevice)
                    Log.d(TAG, "setCommunicationDevice speaker: $res")
                } else {
                    Log.e(TAG, "Built-in speaker device not found")
                }
            } else {
                audioManager.clearCommunicationDevice()
                Log.d(TAG, "clearCommunicationDevice")
            }
        }
    }


    fun routeAudioToBluetooth() {
        val session = SipEngine.callSession.value
        if (session != null && session.callId >= 0) {
            val connection = SipConnectionService.getConnection(session.callId)
            @Suppress("DEPRECATION")
            connection?.setAudioRoute(android.telecom.CallAudioState.ROUTE_BLUETOOTH)
            Log.d(TAG, "Routed audio via Telecom Connection to Bluetooth")
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isMicrophoneMute = false
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            // Only SCO supports full-duplex (microphone + speaker) for VoIP.
            // A2DP is a music-only profile with no mic path; selecting it here
            // would silence the caller's microphone entirely.
            val btDevice = devices.find { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            if (btDevice != null) {
                val res = audioManager.setCommunicationDevice(btDevice)
                Log.d(TAG, "setCommunicationDevice Bluetooth SCO: $res")
            } else {
                Log.e(TAG, "Bluetooth SCO device not found in available devices")
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = true
        }
    }


    fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest == null) {
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    // TRANSIENT_EXCLUSIVE: fully silences competing audio (music, podcasts, etc.)
                    // for the duration of the call. TRANSIENT would only duck them, which
                    // bleeds audible background media into the microphone on low-end devices.
                    .setOnAudioFocusChangeListener { }
                    .build()
            }
            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        }
    }


    fun restoreAudio() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }

        if (audioManager.mode != AudioManager.MODE_NORMAL) {
            audioManager.mode = AudioManager.MODE_NORMAL
        }
        @Suppress("DEPRECATION")
        if (audioManager.isSpeakerphoneOn) {
            audioManager.isSpeakerphoneOn = false
        }
        @Suppress("DEPRECATION")
        if (audioManager.isBluetoothScoOn) {
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                audioManager.clearCommunicationDevice()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear communication device", e)
            }
        }
    }
}
