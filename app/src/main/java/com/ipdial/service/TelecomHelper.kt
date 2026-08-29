package com.ipdial.service

import android.content.ComponentName
import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import com.ipdial.R

object TelecomHelper {

    private const val ACCOUNT_ID = "IPDIAL_SIP_ACCOUNT"

    fun getPhoneAccountHandle(context: Context): PhoneAccountHandle {
        val componentName = ComponentName(context, SipConnectionService::class.java)
        return PhoneAccountHandle(componentName, ACCOUNT_ID)
    }

    fun registerPhoneAccount(context: Context) {
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val handle = getPhoneAccountHandle(context)
        
        try {
            telecomManager.unregisterPhoneAccount(handle)
        } catch (e: Exception) {
            android.util.Log.e("TelecomHelper", "Error unregistering phone account", e)
        }
        
        val extras = Bundle().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                putBoolean(PhoneAccount.EXTRA_LOG_SELF_MANAGED_CALLS, false)
            }
            // Use string literal if constant is missing in current API level context
            putBoolean("android.telecom.extra.SKIP_CALL_LOGGING", true)
        }
        @Suppress("DEPRECATION")
        val phoneAccountBuilder = PhoneAccount.builder(handle, context.getString(R.string.app_name))
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .setShortDescription("SIP Calls via IPDial")
            .addSupportedUriScheme("ipdial")
            .setExtras(extras)
            
        try {
            telecomManager.registerPhoneAccount(phoneAccountBuilder.build())
        } catch (e: Exception) {
            android.util.Log.e("TelecomHelper", "Failed to register phone account (may not have permission on this user profile)", e)
        }
    }

    fun reportIncomingCall(context: Context, number: String, name: String, callId: Int = -1) {
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val handle = getPhoneAccountHandle(context)
        
        val cleanNumber = number.removePrefix("sip:")
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, Uri.fromParts("ipdial", cleanNumber, null))
            putBoolean("android.telecom.extra.SKIP_CALL_LOGGING", true)
            val incomingExtras = Bundle().apply {
                putString("com.ipdial.EXTRA_CALLER_NAME", name)
                putInt("com.ipdial.EXTRA_CALL_ID", callId)
                putBoolean("android.telecom.extra.SKIP_CALL_LOGGING", true)
            }
            putBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS, incomingExtras)
        }
        
        try {
            telecomManager.addNewIncomingCall(handle, extras)
        } catch (e: Exception) {
            android.util.Log.e("TelecomHelper", "Error reporting incoming call", e)
        }
    }

    fun placeOutgoingCall(context: Context, number: String, accountId: String): Boolean {
        // Self-managed ConnectionService is often buggy on Android 8.0/8.1 (API 26/27)
        // on certain devices (like Vivo, Oppo). Bypassing to direct SipEngine for better reliability.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            android.util.Log.i("TelecomHelper", "Android version < Pie detected, bypassing TelecomManager for outgoing call")
            return false
        }

        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val handle = getPhoneAccountHandle(context)
        
        val cleanNumber = number.removePrefix("sip:")
        val uri = Uri.fromParts("ipdial", cleanNumber, null)
        
        // Put accountId in both the root extras and the outgoing call extras bundle for compatibility
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
            putBoolean("android.telecom.extra.SKIP_CALL_LOGGING", true)
            putString("com.ipdial.EXTRA_ACCOUNT_ID", accountId)
            val subExtras = Bundle().apply {
                putString("com.ipdial.EXTRA_ACCOUNT_ID", accountId)
                putBoolean("android.telecom.extra.SKIP_CALL_LOGGING", true)
            }
            putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, subExtras)
        }
        
        return try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                telecomManager.placeCall(uri, extras)
                true
            } else {
                android.util.Log.e("TelecomHelper", "CALL_PHONE permission not granted")
                false
            }
        } catch (e: Exception) {
            android.util.Log.e("TelecomHelper", "Error placing call via Telecom", e)
            false
        }
    }
}
