package com.ipdial.service

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
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
            putBoolean(PhoneAccount.EXTRA_LOG_SELF_MANAGED_CALLS, false)
            putBoolean("android.telecom.extra.SKIP_CALL_LOGGING", true)
        }

        val phoneAccount = PhoneAccount.builder(
            handle,
            context.getString(R.string.app_name)
        )
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .setShortDescription("SIP Calls via IPDial")
            .addSupportedUriScheme("ipdial")
            .setExtras(extras)
            .build()

        telecomManager.registerPhoneAccount(phoneAccount)
    }

    fun reportIncomingCall(context: Context, number: String, name: String) {
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val handle = getPhoneAccountHandle(context)

        val cleanNumber = number.removePrefix("sip:")

        val extras = Bundle().apply {
            putParcelable(
                TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                Uri.fromParts("ipdial", cleanNumber, null)
            )
            putString(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS, name)
        }

        try {
            telecomManager.addNewIncomingCall(handle, extras)
        } catch (e: Exception) {
            android.util.Log.e("TelecomHelper", "Error reporting incoming call", e)
        }
    }

    // Vivo Android 8.1 test patch
    fun placeOutgoingCall(
        context: Context,
        number: String,
        accountId: String
    ): Boolean {

        android.util.Log.w(
            "TelecomHelper",
            "Telecom bypass enabled - forcing direct SIP call"
        )

        return false
    }
}
