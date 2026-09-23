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
    // EC and NS are OFF by default: on some devices these SIP-side effects keep
    // the microphone muted/silent. The device's own (automatic) audio processing
    // still applies. AGC stays ON (gain is safe and usually needed).
    val ecEnabled: Boolean = false,  // Echo cancellation
    val nsEnabled: Boolean = false,  // Noise suppression
    val agcEnabled: Boolean = true,  // Auto gain control
    val ringtoneUri: String? = null,
) {
    val displayName: String get() = label.ifBlank { "$username@$domain" }
}

enum class Transport { UDP, TCP, TLS }

/**
 * TURN relay transport selection. Used by the global NAT traversal settings
 * (Settings → Network) — TURN credentials are user-supplied per device and
 * applied to ALL accounts, not baked into the app.
 */
enum class TurnTransport { UDP, TCP, TLS }

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
    val isRecordingPending: Boolean = false,
    val rxVolume: Float = com.ipdial.service.SipAudioController.DEFAULT_RX_VOLUME,
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

enum class KeypadDesign { Grid, Rounded, Ring }

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

// ── Server Inspector Models ─────────────────────────────────────────

/** Overall result state for each inspection step. */
enum class InspectStatus { IDLE, RUNNING, PASS, WARN, FAIL }

/** DNS resolution result for a SIP host. */
data class DnsResult(
    val status: InspectStatus = InspectStatus.IDLE,
    val host: String = "",
    val resolvedIp: String? = null,
    val latencyMs: Long = 0,
    val error: String? = null,
)

/** SIP REGISTER probe result. */
data class RegisterResult(
    val status: InspectStatus = InspectStatus.IDLE,
    val statusCode: Int = 0,
    val statusText: String = "",
    val latencyMs: Long = 0,
    val transport: String = "",
    val error: String? = null,
)

/** Call quality snapshot sampled during a live test call. */
data class CallQualitySnapshot(
    val status: InspectStatus = InspectStatus.IDLE,
    val negotiatedCodec: String = "",
    val clockRateHz: Int = 0,
    val callDurationSec: Long = 0,
    /** Human-readable quality summary derived from codec + call behaviour. */
    val qualitySummary: String = "",
    val error: String? = null,
)

/** DTMF delivery probe result. */
data class DtmfResult(
    val status: InspectStatus = InspectStatus.IDLE,
    /** The method used to deliver the digit (RFC2833, SIP-INFO, or BUFFERED). */
    val method: String = "",
    /** The digit that was sent. */
    val digit: Char = ' ',
    /** true when PJSIP accepted the send without throwing. */
    val accepted: Boolean = false,
    val error: String? = null,
)

/** Aggregate output of a full server inspection run. */
data class ServerInspection(
    val host: String = "",
    val dns: DnsResult = DnsResult(),
    val register: RegisterResult = RegisterResult(),
    val callQuality: CallQualitySnapshot = CallQualitySnapshot(),
    val dtmf: DtmfResult = DtmfResult(),
    val startedAtMs: Long = 0L,
    val finishedAtMs: Long = 0L,
)
