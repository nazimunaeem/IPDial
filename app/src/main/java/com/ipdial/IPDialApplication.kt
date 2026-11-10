package com.ipdial

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class IPDialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        android.util.Log.d("IPDialApp", "Application.onCreate")

        // Start SIP foreground service FIRST if mic permission is granted.
        try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                com.ipdial.service.SipService.start(this)
            }
        } catch (e: Throwable) {
            android.util.Log.e("IPDialApp", "SipService early start failed", e)
        }

        // Load PJSIP library on Main thread to ensure proper registration
        try {
            System.loadLibrary("pjsua2")
        } catch (e: Throwable) {
            android.util.Log.e("IPDialApp", "Failed to load pjsua2: ${e.message}", e)
        }

        // Register phone account for Telecom integration
        try {
            com.ipdial.service.TelecomHelper.registerPhoneAccount(this)
        } catch (e: Throwable) {
            android.util.Log.e("IPDialApp", "Failed to register phone account: ${e.message}", e)
        }

        // Emergency check for disabled launcher activity
        try {
            com.ipdial.util.AppIconHelper.forceEnableMainActivity(this)
        } catch (e: Throwable) {
            android.util.Log.e("IPDialApp", "Failed to force enable MainActivity: ${e.message}", e)
        }

        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                com.ipdial.util.RemoteConfigHelper.init(this@IPDialApplication)
            } catch (e: Throwable) {
                android.util.Log.e("IPDialApp", "RemoteConfig init failed", e)
            }

            // Sync icon alias on startup
            try {
                val repo = com.ipdial.data.repository.AccountRepository(this@IPDialApplication)
                val currentAlias = repo.appIconAlias.first()
                if (currentAlias != "Default") {
                    com.ipdial.util.AppIconHelper.setAppIcon(this@IPDialApplication, currentAlias)
                }
            } catch (e: Throwable) {
                android.util.Log.w("IPDialApp", "Failed to sync icon alias: ${e.message}")
            }
        }
    }
}
