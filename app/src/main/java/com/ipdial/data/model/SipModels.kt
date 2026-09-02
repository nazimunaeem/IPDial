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
    val codec: PreferredCodec? = null,                 // Preferred (priority) codec
    val enabledCodecs: Set<PreferredCodec> = DEFAULT_ENABLED_CODECS, // codecs offered in SDP
    val ecEnabled: Boolean = true,   // Echo cancellation
    val nsEnabled: Boolean = true,   // Noise suppression
    val agcEnabled: Boolean = true,  // Auto gain control
    val ringtoneUri: String? = null,
) {
    val displayName: String get() = label.ifBlank { "$username@$domain" }
}

enum class Transport { UDP, TCP, TLS }

enum class PreferredCodec {
    G711U,
    G711A,
    G722,
    G729,
    Opus,
}

val DEFAULT_ENABLED_CODECS: Set<PreferredCodec> = setOf(
    PreferredCodec.G711A,
    PreferredCodec.G711U,
    PreferredCodec.G722,
    PreferredCodec.G729,
    PreferredCodec.Opus,
)

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
    val rxVolume: Float = 2.5f,
    val negotiatedCodec: String? = null,
    val disconnectCode: Int? = null,
    val disconnectReason: String? = null,
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
    val disconnectCode: Int? = null,
    val disconnectReason: String? = null,
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

enum class IncomingCallMode { Slider, Buttons }

enum class ThemeMode { System, Light, Dark, Dynamic, Obsidian, Quartz }

data class CodecInfo(
    val id: String,
    val name: String,
    val priority: Short,
    val isAvailable: Boolean,
    val quality: CodecQuality,
    val clockRate: Int,
    val channelCount: Int,
    val frameLength: Int,
    val bandwidthKbps: Int = 0,
    val estimatedMOS: Float = 0f,
)

enum class CodecQuality(val label: String, val level: Int) {
   Excellent("Excellent", 5),
    Good("Good", 4),
    Fair("Fair", 3),
    Low("Low", 2),
    Minimal("Minimal", 1),
}

enum class AudioDeviceMode { EARPIECE, SPEAKER, BLUETOOTH }
