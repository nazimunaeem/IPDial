package com.ipdial.service

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.ipdial.MainActivity

/**
 * Coordinates standard Android calling-app behavior when an incoming call arrives
 * while the device is locked or the screen is off.
 *
 * Standard Android dialer behavior:
 * 1. Show the incoming call screen directly OVER the lock screen (showWhenLocked + turnScreenOn)
 *    without calling requestDismissKeyguard() so PIN/Pattern/Biometric prompts do not block
 *    the user from seeing or answering/declining the call.
 * 2. Post a high-priority CallStyle notification with a fullScreenIntent (and
 *    FLAG_ACTIVITY_NO_USER_ACTION) whenever the app is backgrounded OR the device is locked/off,
 *    and never suppress it solely because DND silences audio/vibration.
 * 3. Only attempt a direct startActivity() when the device is actually locked or screen-off;
 *    when unlocked in another app, rely on the heads-up notification banner.
 */
object IncomingCallPresentationHelper {

    private const val TAG = "IncomingCallPresent"

    const val ACTION_INCOMING_CALL = "com.ipdial.ACTION_INCOMING_CALL"
    const val ACTION_SHOW_CALL = "com.ipdial.ACTION_SHOW_CALL"

    fun isCallLaunchAction(action: String?): Boolean =
        action == ACTION_INCOMING_CALL || action == ACTION_SHOW_CALL

    fun shouldPresentIncomingOverLockScreen(
        isAppForeground: Boolean,
        isKeyguardLocked: Boolean,
        isScreenInteractive: Boolean
    ): Boolean = !isAppForeground || isKeyguardLocked || !isScreenInteractive

    fun shouldDirectlyStartIncomingActivity(
        isKeyguardLocked: Boolean,
        isScreenInteractive: Boolean
    ): Boolean = isKeyguardLocked || !isScreenInteractive

    fun isDeviceLockedOrScreenOff(context: Context): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isKeyguardLocked = try {
            km?.isKeyguardLocked == true
        } catch (_: Throwable) {
            false
        }
        val isInteractive = try {
            pm?.isInteractive ?: true
        } catch (_: Throwable) {
            true
        }
        return shouldDirectlyStartIncomingActivity(isKeyguardLocked, isInteractive)
    }

    fun shouldPresentIncomingOverLockScreen(isAppForeground: Boolean, context: Context): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isKeyguardLocked = try {
            km?.isKeyguardLocked == true
        } catch (_: Throwable) {
            false
        }
        val isInteractive = try {
            pm?.isInteractive ?: true
        } catch (_: Throwable) {
            true
        }
        return shouldPresentIncomingOverLockScreen(isAppForeground, isKeyguardLocked, isInteractive)
    }

    fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return true
        return try {
            nm.canUseFullScreenIntent()
        } catch (_: Throwable) {
            true
        }
    }

    fun createManageFullScreenIntentSettingsIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
    }

    fun buildIncomingCallActivityIntent(
        context: Context,
        isIncomingRinging: Boolean,
        callId: Int
    ): Intent {
        return Intent(context, MainActivity::class.java).apply {
            action = if (isIncomingRinging) ACTION_INCOMING_CALL else ACTION_SHOW_CALL
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_NO_USER_ACTION
            putExtra("callId", callId)
        }
    }

    fun launchIncomingCallUiIfLocked(
        context: Context,
        callId: Int,
        isIncomingRinging: Boolean = true
    ): Boolean {
        if (!isDeviceLockedOrScreenOff(context)) return false
        val intent = buildIncomingCallActivityIntent(context, isIncomingRinging, callId)
        return try {
            context.startActivity(intent)
            Log.d(TAG, "Started MainActivity directly for locked-screen callId=$callId")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Direct startActivity blocked/failed (fullScreenIntent notification will handle): ${e.message}")
            false
        }
    }
}
