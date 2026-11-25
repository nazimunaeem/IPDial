package com.ipdial.service.sim

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Sends USSD / MMI codes (e.g. *121#, *#06#, *123*1#) through the telephony stack.
 *
 * Dialing these as normal calls (TelecomManager.placeCall) makes the modem reject them
 * with the generic "connection problem or invalid MMI code" error. They must go through
 * [TelephonyManager.sendUssdRequest] on the selected SIM subscription instead.
 */
object UssdHelper {

    private const val TAG = "UssdHelper"

    /** True for MMI/USSD strings such as *121#, *#06#, *123*1#, #31#. */
    fun isUssdCode(raw: String): Boolean {
        val code = raw.trim()
        if (code.length < 3) return false
        if (!code.startsWith("*") && !code.startsWith("#")) return false
        if (!code.endsWith("#")) return false
        return code.all { it.isDigit() || it == '*' || it == '#' || it == '+' }
    }

    @SuppressLint("MissingPermission")
    fun send(
        context: Context,
        code: String,
        simLine: SimLine,
        onResult: (success: Boolean, message: String) -> Unit
    ) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            onResult(false, "Phone permission is required to send USSD codes")
            return
        }

        val baseTm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (baseTm == null) {
            onResult(false, "Telephony is not available on this device")
            return
        }

        val subId = simLine.subscriptionId
            ?: SubscriptionManager.getDefaultSubscriptionId()
        val tm = if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            try {
                baseTm.createForSubscriptionId(subId)
            } catch (e: Exception) {
                Log.w(TAG, "createForSubscriptionId($subId) failed: ${e.message}")
                baseTm
            }
        } else {
            baseTm
        }

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
            Log.i(TAG, "USSD sent on ${simLine.label}: $code")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException sending USSD: ${e.message}", e)
            onResult(false, "Permission denied while sending USSD")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send USSD: ${e.message}", e)
            onResult(false, e.message ?: "Failed to send USSD code")
        }
    }
}
