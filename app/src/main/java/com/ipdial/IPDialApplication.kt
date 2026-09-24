package com.ipdial

import android.app.Application
import com.startapp.sdk.adsbase.StartAppAd
import com.startapp.sdk.adsbase.StartAppSDK
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class IPDialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        android.util.Log.d("IPDialApp", "Application.onCreate")

        // Suppress known StartApp SDK background crashes before any SDK thread starts.
        installSdkCrashGuard()

        // Start SIP foreground service FIRST so startForeground() lands within
        // Android's ~5s ForegroundServiceDidNotStartInTimeException window. Putting
        // this at the very top of onCreate() gives the service maximum headroom
        // while the main thread continues with the initialization below.  If
        // microphone permission is missing the service stops itself; it will be
        // restarted once the user grants the permission via MainActivity.
        try {
            com.ipdial.service.SipService.start(this)
        } catch (e: Throwable) {
            android.util.Log.e("IPDialApp", "SipService early start failed", e)
        }

        // Load PJSIP library on Main thread to ensure proper registration
        try {
            System.loadLibrary("pjsua2")
        } catch (e: Throwable) {
            android.util.Log.e("IPDialApp", "Failed to load pjsua2: ${e.message}", e)
        }

        // Initialize Start.io SDK
        try {
            @Suppress("DEPRECATION")
            StartAppSDK.init(this, "205857982", true)
            // Test ads only for local debug builds; production serves real ads.
            if (com.ipdial.BuildConfig.DEBUG) {
                StartAppSDK.setTestAdsEnabled(true)
            }
            @Suppress("DEPRECATION")
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

        // Network-dependent init (Remote Config / Firestore ad config / icon alias)
        // runs on a background thread so it can never stall the main thread while
        // the SIP service is being promoted to foreground. These waits had to be
        // started afterwards anyway.
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                com.ipdial.util.RemoteConfigHelper.init(this@IPDialApplication)
            } catch (e: Throwable) {
                android.util.Log.e("IPDialApp", "RemoteConfig init failed", e)
            }

            try {
                com.ipdial.util.FirestoreAdConfig.init()
            } catch (e: Throwable) {
                android.util.Log.e("IPDialApp", "FirestoreAdConfig init failed", e)
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

    // The StartApp SDK spawns ad-loading workers that can crash on devices with a
    // broken/corrupted WebView provider (e.g. a null ApplicationInfo.metaData),
    // surfacing as an uncaught ExceptionInInitializerError/native crash that kills
    // the whole process even though the app itself was unaffected.  Suppress those
    // known third-party background crashes and forward everything else to the
    // previous handler (Crashlytics, already installed by the time onCreate runs).
    private fun installSdkCrashGuard() {
        try {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                if (isSuppressibleSdkBackgroundCrash(thread, throwable)) {
                    android.util.Log.w(
                        "IPDialApp",
                        "Suppressing known ad-SDK background crash on ${thread.name}",
                        throwable
                    )
                } else {
                    previous?.uncaughtException(thread, throwable)
                        ?: android.util.Log.e("IPDialApp", "Uncaught exception", throwable)
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun isSuppressibleSdkBackgroundCrash(thread: Thread, throwable: Throwable): Boolean {
        val threadName = thread.name ?: ""
        if (threadName.startsWith("startapp")) return true

        var deepest = throwable
        while (deepest.cause != null) deepest = deepest.cause!!
        // Signature of the WebView-provider metaData bug that crashes StartApp's
        // obfuscated worker classes (WV.*) during WebView/Ad initialization.
        return deepest is NullPointerException &&
            (deepest.message?.contains("PackageItemInfo.metaData") == true)
    }
}