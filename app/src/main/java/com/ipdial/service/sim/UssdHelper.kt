package com.ipdial.service.sim

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Sends USSD / MMI codes (e.g. *121#, *247#, *#06#, *123*1#) using the same system call path
 * used by Simple Dialer / AOSP Dialer.
 *
 * - Secret codes (`*#*#<code>#*#*`): dispatched via [TelephonyManager.sendDialerSpecialCode].
 * - Device-local info codes (`*#06#`, `*#07#`): handed to the pre-installed system dialer.
 * - Carrier USSD & MMI codes (`*121#`, `*247#`, `*123*1#`, etc.):
 *   When DialDuo is the default dialer, placed directly through [TelecomManager.placeCall] with
 *   `Uri.fromParts("tel", code, null)` and [TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE] (skipping
 *   `TelecomManager.handleMmi`, which only handles PIN/PUK on AOSP and swallows USSD on some OEMs).
 *   When DialDuo is not the default dialer, dispatched via `Intent.ACTION_CALL` to system Telecom
 *   with [TelecomManager.placeCall] and [TelephonyManager.sendUssdRequest] as fallbacks.
 */
object UssdHelper {

    private const val TAG = "UssdHelper"
    private const val SYSTEM_TELECOM_PACKAGE = "com.android.server.telecom"

    /** True for MMI/USSD/special strings such as *121#, *#06#, *123*1#, #31#, *#*#4636#*#*. */
    fun isUssdCode(raw: String): Boolean {
        val code = raw.trim()
        if (extractSecretCode(code) != null) return true
        if (code.length < 3) return false
        if (!code.startsWith("*") && !code.startsWith("#")) return false
        if (!code.endsWith("#")) return false
        return code.all { it.isDigit() || it == '*' || it == '#' || it == '+' }
    }

    /** Extracts the inner code from `*#*#<code>#*#*`, or null if not a secret dialer code. */
    internal fun extractSecretCode(raw: String): String? {
        val code = raw.trim()
        if (!code.startsWith("*#*#") || !code.endsWith("#*#*") || code.length <= 8) return null
        val inner = code.substring(4, code.length - 4)
        return inner.takeIf { it.isNotEmpty() && it.all { ch -> ch.isDigit() || ch == '*' || ch == '#' } }
    }

    /** True for local device information MMI codes (*#06#, *#07#) handled by system dialer. */
    internal fun isLocalDeviceInfoCode(raw: String): Boolean {
        val code = raw.trim()
        return code == "*#06#" || code == "*#07#"
    }

    @SuppressLint("MissingPermission")
    fun send(
        context: Context,
        code: String,
        simLine: SimLine,
        onResult: (success: Boolean, message: String) -> Unit
    ) {
        val trimmed = code.trim()
        if (!isUssdCode(trimmed)) {
            onResult(false, "Invalid USSD code")
            return
        }

        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

        // 1. Secret dialer codes (*#*#4636#*#*) -> TelephonyManager.sendDialerSpecialCode / system dialer
        val secretCode = extractSecretCode(trimmed)
        if (secretCode != null) {
            if (trySendSecretCode(context, telephonyManager, simLine, secretCode)) {
                onResult(true, "")
                return
            }
            if (openInSystemDialer(context, telecomManager, trimmed)) {
                onResult(true, "")
                return
            }
            onResult(false, "Unable to open special dialer code")
            return
        }

        // 2. Local device info codes (*#06#, *#07#) -> pre-installed system dialer, else placeCall
        if (isLocalDeviceInfoCode(trimmed)) {
            if (openInSystemDialer(context, telecomManager, trimmed)) {
                onResult(true, "")
                return
            }
        }

        val hasCallPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCallPermission) {
            if (openInSystemDialer(context, telecomManager, trimmed)) {
                onResult(true, "")
            } else {
                onResult(false, "Phone permission is required to send USSD codes")
            }
            return
        }

        // 3. System call path (matching Simple Dialer):
        //    - Default dialer -> TelecomManager.placeCall directly
        //    - Non-default dialer -> Intent.ACTION_CALL to system Telecom (then placeCall fallback)
        val isDefault = DefaultDialerHelper.isDefaultDialer(context)
        val dispatched = if (isDefault) {
            tryPlaceCallUssd(telecomManager, trimmed, simLine) ||
                trySystemActionCall(context, trimmed, simLine)
        } else {
            trySystemActionCall(context, trimmed, simLine) ||
                tryPlaceCallUssd(telecomManager, trimmed, simLine)
        }

        if (dispatched) {
            Log.i(TAG, "USSD dispatched via system path (defaultDialer=$isDefault) on ${simLine.label}: $trimmed")
            onResult(true, "")
            return
        }

        // 4. Fallback to TelephonyManager.sendUssdRequest if Telecom dispatch failed
        if (telephonyManager != null) {
            sendViaTelephonyManagerApi(context, telephonyManager, trimmed, simLine, onResult)
            return
        }

        // 5. Last resort: open in pre-installed system dialer
        if (openInSystemDialer(context, telecomManager, trimmed)) {
            onResult(true, "")
        } else {
            onResult(false, "Telephony service is not available on this device")
        }
    }

    @SuppressLint("MissingPermission")
    private fun trySendSecretCode(
        context: Context,
        baseTm: TelephonyManager?,
        simLine: SimLine,
        secretCode: String
    ): Boolean {
        if (baseTm == null || !DefaultDialerHelper.isDefaultDialer(context)) return false
        return try {
            val tm = resolveTelephonyForSim(baseTm, simLine)
            tm.sendDialerSpecialCode(secretCode)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "sendDialerSpecialCode permission denied: ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "sendDialerSpecialCode failed: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun tryPlaceCallUssd(
        telecomManager: TelecomManager?,
        code: String,
        simLine: SimLine
    ): Boolean {
        if (telecomManager == null) return false
        val uri = Uri.fromParts("tel", code, null)
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, simLine.handle)
        }
        return try {
            telecomManager.placeCall(uri, extras)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "TelecomManager.placeCall USSD security exception: ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "TelecomManager.placeCall USSD failed: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun trySystemActionCall(
        context: Context,
        code: String,
        simLine: SimLine
    ): Boolean {
        val uri = Uri.fromParts("tel", code, null)
        val candidates = LinkedHashSet<String>().apply {
            add(SYSTEM_TELECOM_PACKAGE)
            addAll(resolveExternalActionPackages(context, Intent.ACTION_CALL, uri))
        }
        for (pkg in candidates) {
            try {
                val intent = Intent(Intent.ACTION_CALL, uri).apply {
                    setPackage(pkg)
                    putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, simLine.handle)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Log.i(TAG, "Dispatched USSD via ACTION_CALL package=$pkg: $code")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "System ACTION_CALL USSD failed for package=$pkg: ${e.message}")
            }
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private fun sendViaTelephonyManagerApi(
        context: Context,
        baseTm: TelephonyManager,
        code: String,
        simLine: SimLine,
        onResult: (success: Boolean, message: String) -> Unit
    ) {
        val tm = resolveTelephonyForSim(baseTm, simLine)
        val handler = Handler(Looper.getMainLooper())
        val callback = object : TelephonyManager.UssdResponseCallback() {
            override fun onReceiveUssdResponse(
                telephonyManager: TelephonyManager?,
                request: String?,
                response: CharSequence?
            ) {
                onResult(true, response?.toString()?.trim().orEmpty())
            }

            override fun onReceiveUssdResponseFailed(
                telephonyManager: TelephonyManager?,
                request: String?,
                failureCode: Int
            ) {
                val message = when (failureCode) {
                    TelephonyManager.USSD_ERROR_SERVICE_UNAVAIL ->
                        "USSD service unavailable. Check network signal and try again."
                    TelephonyManager.USSD_RETURN_FAILURE ->
                        "USSD request failed. Check the code and try again."
                    else -> "USSD request failed (code $failureCode)"
                }
                onResult(false, message)
            }
        }

        try {
            tm.sendUssdRequest(code, callback, handler)
            Log.i(TAG, "USSD sent via TelephonyManager fallback on ${simLine.label}: $code")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException sending USSD: ${e.message}", e)
            onResult(false, "Permission denied while sending USSD")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send USSD: ${e.message}", e)
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (openInSystemDialer(context, telecomManager, code)) {
                onResult(true, "")
            } else {
                onResult(false, e.message ?: "Failed to send USSD code")
            }
        }
    }

    private fun resolveTelephonyForSim(
        baseTm: TelephonyManager,
        simLine: SimLine
    ): TelephonyManager {
        val handleSubId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                baseTm.getSubscriptionId(simLine.handle)
            } catch (_: Throwable) {
                SubscriptionManager.INVALID_SUBSCRIPTION_ID
            }
        } else {
            SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }
        val subId = handleSubId.takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
            ?: simLine.subscriptionId
            ?: SubscriptionManager.getDefaultSubscriptionId()

        return if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            try {
                baseTm.createForSubscriptionId(subId)
            } catch (e: Exception) {
                Log.w(TAG, "createForSubscriptionId($subId) failed: ${e.message}")
                baseTm
            }
        } else {
            baseTm
        }
    }

    private fun openInSystemDialer(
        context: Context,
        telecomManager: TelecomManager?,
        code: String
    ): Boolean {
        val uri = Uri.fromParts("tel", code, null)
        val systemPkg = resolveExternalDialerPackage(context, telecomManager, uri) ?: return false
        return try {
            val intent = Intent(Intent.ACTION_DIAL, uri).apply {
                setPackage(systemPkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "Opened USSD/MMI code in external system dialer ($systemPkg): $code")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open external system dialer ($systemPkg): ${e.message}")
            false
        }
    }

    private fun resolveExternalDialerPackage(
        context: Context,
        telecomManager: TelecomManager?,
        uri: Uri
    ): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val sysPkg = telecomManager?.systemDialerPackage?.takeIf {
                it.isNotBlank() && it != context.packageName
            }
            if (sysPkg != null) return sysPkg
        }
        return resolveExternalActionPackages(context, Intent.ACTION_DIAL, uri).firstOrNull()
    }

    private fun resolveExternalActionPackages(
        context: Context,
        action: String,
        uri: Uri
    ): List<String> {
        val pm = context.packageManager
        val probe = Intent(action, uri)
        val candidates = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(
                    probe,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
            }
        } catch (_: Exception) {
            emptyList()
        }.filter { it.activityInfo?.packageName != null && it.activityInfo.packageName != context.packageName }

        val systemPackages = candidates.filter {
            val flags = it.activityInfo?.applicationInfo?.flags ?: 0
            (flags and ApplicationInfo.FLAG_SYSTEM) != 0
        }.mapNotNull { it.activityInfo?.packageName }

        val otherPackages = candidates.mapNotNull { it.activityInfo?.packageName }
        return (systemPackages + otherPackages).distinct()
    }
}
