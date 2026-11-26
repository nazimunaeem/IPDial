package com.ipdial.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.Call
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.ipdial.data.model.CallSession
import com.ipdial.data.model.CallTransport

/**
 * Pure & system utilities for detecting and presenting HD Voice (wideband / super-wideband)
 * across both cellular SIM calls (Android Telecom [Call.Details.PROPERTY_HIGH_DEF_AUDIO])
 * and SIP VoIP calls (wideband codecs such as Opus / G.722).
 */
object CallAudioQualityHelper {

    data class SimHdReadiness(
        val hasPhoneStatePermission: Boolean,
        val activeSimCount: Int,
        val hdCapableNetworkCount: Int,
        val networkSummary: String
    )

    fun isTelecomHdAudio(details: Call.Details?): Boolean {
        if (details == null) return false
        return try {
            details.hasProperty(Call.Details.PROPERTY_HIGH_DEF_AUDIO)
        } catch (_: Throwable) {
            isTelecomHdAudioFromProperties(details.callProperties)
        }
    }

    fun isTelecomHdAudioFromProperties(properties: Int): Boolean =
        (properties and Call.Details.PROPERTY_HIGH_DEF_AUDIO) != 0

    /**
     * Returns true when a PJSIP negotiated codec string (e.g. "OPUS/48000/2", "G722/16000/1")
     * represents wideband or super-wideband HD voice (>= 16 kHz).
     */
    fun isSipCodecHd(negotiatedCodec: String?): Boolean {
        val raw = negotiatedCodec?.trim()?.uppercase().orEmpty()
        if (raw.isBlank()) return false

        val parts = raw.split("/")
        val name = parts.getOrNull(0).orEmpty()
        val clockRateHz = parts.getOrNull(1)?.toIntOrNull()

        if (name.contains("PCMA") ||
            name.contains("PCMU") ||
            name.contains("G711") ||
            name.contains("G729") ||
            name.contains("GSM") ||
            name.contains("ILBC")
        ) {
            return false
        }

        if (name.contains("OPUS") ||
            name.contains("G722") ||
            name.contains("AMR-WB") ||
            name.contains("AMRWB") ||
            name.contains("EVS")
        ) {
            return true
        }

        return (clockRateHz ?: 0) >= 16000
    }

    fun shortCodecName(negotiatedCodec: String?): String? {
        val raw = negotiatedCodec?.trim()?.uppercase().orEmpty()
        if (raw.isBlank()) return null
        val base = raw.substringBefore("/")
        return when {
            base.contains("OPUS") -> "Opus"
            base.contains("G722") -> "G.722"
            base.contains("PCMA") -> "G.711A"
            base.contains("PCMU") -> "G.711U"
            base.contains("G729") -> "G.729"
            base.contains("SPEEX") -> "Speex"
            base.contains("ILBC") -> "iLBC"
            base.contains("GSM") -> "GSM"
            else -> base
        }
    }

    fun isSessionHdAudio(session: CallSession): Boolean {
        return when (session.transport) {
            CallTransport.SIM -> session.isHdAudio
            CallTransport.SIP -> session.isHdAudio || isSipCodecHd(session.negotiatedCodec)
        }
    }

    fun hdBadgeText(session: CallSession): String? {
        if (!isSessionHdAudio(session)) return null
        if (session.transport == CallTransport.SIP) {
            val shortName = shortCodecName(session.negotiatedCodec)
            if (!shortName.isNullOrBlank()) return "HD · $shortName"
        }
        return "HD"
    }

    fun isVoiceNetworkHdCapable(networkType: Int): Boolean {
        return when (networkType) {
            TelephonyManager.NETWORK_TYPE_LTE,
            TelephonyManager.NETWORK_TYPE_IWLAN,
            TelephonyManager.NETWORK_TYPE_NR -> true
            else -> false
        }
    }

    @SuppressLint("MissingPermission")
    fun inspectSimHdReadiness(context: Context): SimHdReadiness {
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) {
            return SimHdReadiness(
                hasPhoneStatePermission = false,
                activeSimCount = 0,
                hdCapableNetworkCount = 0,
                networkSummary = "Phone permission needed to check SIM VoLTE / 5G network status"
            )
        }

        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
        val subs = try {
            sm?.activeSubscriptionInfoList.orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }

        if (subs.isEmpty()) {
            val netType = try {
                @Suppress("DEPRECATION")
                tm?.voiceNetworkType ?: TelephonyManager.NETWORK_TYPE_UNKNOWN
            } catch (_: Throwable) {
                TelephonyManager.NETWORK_TYPE_UNKNOWN
            }
            val hdCapable = isVoiceNetworkHdCapable(netType)
            return SimHdReadiness(
                hasPhoneStatePermission = true,
                activeSimCount = 0,
                hdCapableNetworkCount = if (hdCapable) 1 else 0,
                networkSummary = if (hdCapable) {
                    "LTE / 5G / Wi-Fi Calling active — HD Voice supported when carrier & peer support it"
                } else {
                    "No active LTE / 5G VoLTE voice network detected"
                }
            )
        }

        var hdCount = 0
        val labels = mutableListOf<String>()
        subs.forEachIndexed { idx, sub ->
            val subTm = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && tm != null) {
                    tm.createForSubscriptionId(sub.subscriptionId)
                } else {
                    tm
                }
            } catch (_: Throwable) {
                tm
            }
            val netType = try {
                @Suppress("DEPRECATION")
                subTm?.voiceNetworkType ?: TelephonyManager.NETWORK_TYPE_UNKNOWN
            } catch (_: Throwable) {
                TelephonyManager.NETWORK_TYPE_UNKNOWN
            }
            val isHdNet = isVoiceNetworkHdCapable(netType)
            if (isHdNet) hdCount++
            val simName = sub.displayName?.toString()?.trim()?.takeIf { it.isNotBlank() }
                ?: "SIM ${idx + 1}"
            val techLabel = when (netType) {
                TelephonyManager.NETWORK_TYPE_NR -> "5G VoNR"
                TelephonyManager.NETWORK_TYPE_LTE -> "4G VoLTE"
                TelephonyManager.NETWORK_TYPE_IWLAN -> "Wi-Fi Calling"
                TelephonyManager.NETWORK_TYPE_UNKNOWN -> "Carrier managed"
                else -> "2G/3G"
            }
            labels += "$simName: $techLabel"
        }

        return SimHdReadiness(
            hasPhoneStatePermission = true,
            activeSimCount = subs.size,
            hdCapableNetworkCount = hdCount,
            networkSummary = labels.joinToString("  •  ")
        )
    }
}
