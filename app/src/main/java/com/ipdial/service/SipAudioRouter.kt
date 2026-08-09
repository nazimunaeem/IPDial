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

    fun isBluetoothConnected(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            return devices.any {
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            }
        } else {
            @Suppress("DEPRECATION")
            return audioManager.isBluetoothScoAvailableOffCall || audioManager.isBluetoothA2dpOn
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
            audioManager.clearCommunicationDevice()
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
            val btDevice = devices.find {
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            }
            if (btDevice != null) {
                val res = audioManager.setCommunicationDevice(btDevice)
                Log.d(TAG, "setCommunicationDevice Bluetooth: $res")
            } else {
                Log.e(TAG, "Bluetooth device not found in available devices")
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
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { }
                    .build()
            }
            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
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
