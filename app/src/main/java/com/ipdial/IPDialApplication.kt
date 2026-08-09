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
        } catch (e: Throwable) {
            android.util.Log.e("IPDialApp", "Failed to load pjsua2: ${e.message}", e)
        }

        // Initialize Start.io SDK
        try {
            StartAppSDK.init(this, "205857982", true)
            StartAppSDK.setTestAdsEnabled(false)
            StartAppAd.disableSplash()
        } catch (e: Throwable) {
            android.util.Log.e("IPDialApp", "Failed to init StartApp SDK: ${e.message}", e)
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

        // Sync icon alias on startup
        CoroutineScope(Dispatchers.Main).launch {
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

        // Fetch Remote Config for custom ads
        try {
            com.ipdial.util.RemoteConfigHelper.init(this)
        } catch (e: Throwable) {
            android.util.Log.e("IPDialApp", "RemoteConfig init failed", e)
        }

        // Load ad config from Firestore (admin-managed)
        try {
            com.ipdial.util.FirestoreAdConfig.init()
        } catch (e: Throwable) {
            android.util.Log.e("IPDialApp", "FirestoreAdConfig init failed", e)
        }
    }
}
