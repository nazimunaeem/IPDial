package com.ipdial

import android.app.Application
import com.startapp.sdk.adsbase.StartAppAd
import com.startapp.sdk.adsbase.StartAppSDK
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class IPDialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        android.util.Log.d("IPDialApp", "Application.onCreate")
        
        // Load PJSIP library on Main thread to ensure proper registration
        try {
            System.loadLibrary("pjsua2")
        } catch (e: Exception) {
            android.util.Log.e("IPDialApp", "Failed to load pjsua2", e)
        }

        // Initialize Start.io SDK
        StartAppSDK.init(this, "205857982", true)
        // Enable test ads to verify integration
        StartAppSDK.setTestAdsEnabled(false)
        // Disable splash screen ads if desired
        StartAppAd.disableSplash()

        // Register phone account for Telecom integration
        com.ipdial.service.TelecomHelper.registerPhoneAccount(this)

        // Emergency check for disabled launcher activity
        com.ipdial.util.AppIconHelper.forceEnableMainActivity(this)

        // Sync icon alias on startup
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val repo = com.ipdial.data.repository.AccountRepository(this@IPDialApplication)
                val currentAlias = repo.appIconAlias.first()
                if (currentAlias != "Default") {
                    com.ipdial.util.AppIconHelper.setAppIcon(this@IPDialApplication, currentAlias)
                }
            } catch (_: Exception) {}
        }

        // Fetch Remote Config for custom ads
        com.ipdial.util.RemoteConfigHelper.init(this)

        // Load ad config from Firestore (admin-managed)
        com.ipdial.util.FirestoreAdConfig.init()
    }
}
