package com.ipdial.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ipdial.data.scheduler.CallSchedulerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in listOf(
                Intent.ACTION_BOOT_COMPLETED,
                "android.intent.action.QUICKBOOT_POWERON"
            )
        ) {
            try {
                // Pass delayStartForeground=true because FGS with phoneCall type is not
                // allowed from BOOT_COMPLETED on Android 12+ (throws
                // BackgroundServiceStartNotAllowedException / IllegalStateException).
                SipService.start(context, delayStartForeground = true)
            } catch (e: Exception) {
                Log.e("BootReceiver", "Failed to start SipService after boot", e)
            }

            val pendingResult = goAsync()
            val appContext = context.applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    CallSchedulerManager.rescheduleAllPending(appContext)
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Failed to reschedule scheduled calls after boot", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
