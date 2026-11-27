package com.ipdial.service.sim

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat

object SimPhoneAccountHelper {

    private const val TAG = "SimPhoneAccountHelper"

    /**
     * Normalizes user-entered or SIP-formatted strings into a dialable cellular tel number,
     * preserving a leading '+', digits, '*', and '#'.
     */
    fun cleanTelNumber(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("sip:", ignoreCase = true)) {
            s = s.substring(4).substringBefore('@')
        } else if (s.startsWith("tel:", ignoreCase = true)) {
            s = s.substring(4)
        }
        s = s.substringBefore(';')
        val sb = StringBuilder()
        for (ch in s) {
            when {
                ch == '+' && sb.isEmpty() -> sb.append(ch)
                ch.isDigit() || ch == '*' || ch == '#' -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    @SuppressLint("MissingPermission")
    fun getAvailableSimLines(context: Context): List<SimLine> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }

        val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return emptyList()
        val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

        val handles = try {
            tm.callCapablePhoneAccounts
        } catch (e: SecurityException) {
            Log.w(TAG, "Unable to read callCapablePhoneAccounts: ${e.message}")
            return emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Error querying callCapablePhoneAccounts: ${e.message}")
            return emptyList()
        }

        val simHandles = handles.filter { handle ->
            if (handle.componentName.packageName == context.packageName) return@filter false
            val pa = try {
                tm.getPhoneAccount(handle)
            } catch (_: Exception) {
                null
            }
            if (pa != null) {
                val isSelfManaged = (pa.capabilities and PhoneAccount.CAPABILITY_SELF_MANAGED) != 0
                val isSimCapable = (pa.capabilities and PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION) != 0 ||
                    (pa.capabilities and PhoneAccount.CAPABILITY_CALL_PROVIDER) != 0
                !isSelfManaged && isSimCapable
            } else {
                true
            }
        }

        if (simHandles.isEmpty()) return emptyList()

        val activeSubs: List<SubscriptionInfo> = try {
            sm?.activeSubscriptionInfoList ?: emptyList()
        } catch (e: SecurityException) {
            Log.w(TAG, "Unable to read activeSubscriptionInfoList: ${e.message}")
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        return simHandles.mapIndexed { idx, handle ->
            val phoneAccount = try {
                tm.getPhoneAccount(handle)
            } catch (_: Exception) {
                null
            }
            val matchedSub = matchSubscription(telephonyManager, handle, activeSubs, idx, simHandles.size)
            val slotIdx = matchedSub?.simSlotIndex?.takeIf { it >= 0 }
            // Use the name the user gave the SIM in device settings when available.
            val renamed = matchedSub?.displayName?.toString()?.trim()?.takeIf { it.isNotBlank() }
            val label = when {
                renamed != null -> renamed
                slotIdx != null -> "SIM ${slotIdx + 1}"
                simHandles.size > 1 -> "SIM ${idx + 1}"
                else -> "SIM 1"
            }
            val carrierName = matchedSub?.carrierName?.toString()?.trim()
                ?.takeIf { it.isNotBlank() && !it.equals(label, ignoreCase = true) }
                ?: matchedSub?.displayName?.toString()?.trim()
                    ?.takeIf { it.isNotBlank() && !it.equals(label, ignoreCase = true) }
                ?: phoneAccount?.label?.toString()?.trim()
                    ?.takeIf { it.isNotBlank() && !it.equals(label, ignoreCase = true) }
                ?: ""

            val simNumber = readSubscriptionNumber(sm, matchedSub)
            val subtitle = listOfNotNull(
                carrierName.takeIf { it.isNotBlank() },
                simNumber?.takeIf { it.isNotBlank() }
            ).joinToString(" • ")

            SimLine(
                id = "sim_${handle.id}_${slotIdx ?: idx}",
                label = label,
                subtitle = subtitle,
                slotIndex = slotIdx,
                handle = handle,
                subscriptionId = matchedSub?.subscriptionId
                    ?.takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
            )
        }.sortedWith(compareBy({ it.slotIndex ?: Int.MAX_VALUE }, { it.label }))
    }

    private fun matchSubscription(
        telephonyManager: TelephonyManager?,
        handle: PhoneAccountHandle,
        subs: List<SubscriptionInfo>,
        index: Int,
        totalHandles: Int
    ): SubscriptionInfo? {
        if (subs.isEmpty()) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && telephonyManager != null) {
            val resolvedSubId = try {
                telephonyManager.getSubscriptionId(handle)
            } catch (_: Throwable) {
                SubscriptionManager.INVALID_SUBSCRIPTION_ID
            }
            if (resolvedSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                subs.firstOrNull { it.subscriptionId == resolvedSubId }?.let { return it }
            }
        }
        subs.firstOrNull { sub ->
            handle.id == sub.subscriptionId.toString() ||
                (!sub.iccId.isNullOrBlank() && handle.id.startsWith(sub.iccId, ignoreCase = true))
        }?.let { return it }

        if (subs.size == totalHandles && index in subs.indices) {
            return subs.sortedBy { it.simSlotIndex }.getOrNull(index)
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun readSubscriptionNumber(sm: SubscriptionManager?, sub: SubscriptionInfo?): String? {
        if (sub == null) return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                sm?.getPhoneNumber(sub.subscriptionId)?.takeIf { it.isNotBlank() }
            } else {
                @Suppress("DEPRECATION")
                sub.number?.takeIf { it.isNotBlank() }
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun resolveLabelForHandle(context: Context, handle: PhoneAccountHandle?): String {
        if (handle == null) return "SIM"
        getAvailableSimLines(context).firstOrNull { it.handle == handle }?.let { return it.label }
        val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        val paLabel = try {
            tm?.getPhoneAccount(handle)?.label?.toString()?.trim()
        } catch (_: Exception) {
            null
        }
        return paLabel?.takeIf { it.isNotBlank() } ?: "SIM"
    }

    @SuppressLint("MissingPermission")
    fun placeSimCall(context: Context, rawNumber: String, simLine: SimLine): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "CALL_PHONE permission missing")
            return false
        }

        val cleanNumber = cleanTelNumber(rawNumber)
        if (cleanNumber.isBlank()) {
            Log.w(TAG, "Cannot place SIM call: empty number from '$rawNumber'")
            return false
        }

        val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return false
        val uri = Uri.fromParts("tel", cleanNumber, null)
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, simLine.handle)
        }
        return try {
            tm.placeCall(uri, extras)
            Log.i(TAG, "Placed SIM call to $cleanNumber via ${simLine.label}")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException placing SIM call: ${e.message}", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to place SIM call: ${e.message}", e)
            false
        }
    }
}
