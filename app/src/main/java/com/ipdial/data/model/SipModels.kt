package com.ipdial.data.model

import java.util.UUID

data class SipAccount(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "",          // Friendly name, e.g. "Work", "Personal"
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    val proxy: String = "",          // Optional outbound proxy
    val port: Int? = null,
    val transport: Transport = Transport.UDP,
    val isEnabled: Boolean = true,
    val isDefault: Boolean = false,
    val regStatus: RegStatus = RegStatus.UNREGISTERED,
    val regStatusText: String = "",
    // Audio quality settings
    val codec: PreferredCodec = PreferredCodec.G711A,
    val ecEnabled: Boolean = true,   // Echo cancellation
    val nsEnabled: Boolean = true,   // Noise suppression
    val agcEnabled: Boolean = true,  // Auto gain control
    val ringtoneUri: String? = null,
) {
    val displayName: String get() = label.ifBlank { "$username@$domain" }
}

enum class Transport { UDP, TCP, TLS }

enum class PreferredCodec(val codecName: String, val priority: Int) {
    AUTO("AUTO", 255),
    G729("G729/8000/1", 250),

    OPUS("opus/48000/2", 240),
    G722("G722/16000/1", 220),

    G711U("PCMU/8000/1", 150),
    G711A("PCMA/8000/1", 140),
}

enum class RegStatus {
    UNREGISTERED,
    REGISTERING,
    REGISTERED,
    ERROR
}

data class CallSession(
    val callId: Int = -1,
    val accountId: String = "",
    val remoteUri: String = "",
    val remoteDisplayName: String = "",
    val direction: CallDirection = CallDirection.OUTGOING,
    val state: CallState = CallState.IDLE,
    val durationSeconds: Long = 0L,
    val isMuted: Boolean = false,
    val isSpeaker: Boolean = false,
    val isOnHold: Boolean = false,
    val isRecording: Boolean = false,
)

enum class CallDirection { INCOMING, OUTGOING }

/**
 * Persisted call-log entry written at the end of every completed/missed call.
 */
data class CallLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val accountId: String = "",
    val remoteUri: String = "",
    val remoteDisplayName: String = "",
    val direction: CallDirection = CallDirection.OUTGOING,
    val missed: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis(),
    val durationSeconds: Long = 0L,
)

enum class CallState {
    IDLE,
    CALLING,
    INCOMING,
    EARLY,
    CONNECTING,
    CONFIRMED,  // Active call
    DISCONNECTED,
}

enum class KeypadDesign { Grid, Rounded }

enum class ThemeMode { System, Light, Dark }

enum class AudioDeviceMode { EARPIECE, SPEAKER, BLUETOOTH }
