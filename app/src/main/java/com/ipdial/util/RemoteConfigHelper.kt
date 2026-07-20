package com.ipdial.util

import android.app.Application
import android.util.Log
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Wraps Firebase Remote Config for ad banner control.
 *
 * Firebase console keys:
 *   custom_ad_enabled              (Boolean)  – replace Start.io with custom banner
 *   custom_ad_image_url            (String)   – image to show
 *   custom_ad_link                 (String)   – CTA URL
 *   custom_ad_show_to              (String)   – "all" | "pro" | "non_pro"
 *   custom_ad_bg                   (String)   – hex color e.g. "#1E6B3C"
 *
 *   custom_account_page_ad_enabled (Boolean)  – show banner on Accounts page
 *   custom_account_page_ad_title   (String)
 *   custom_account_page_ad_subtitle(String)
 *   custom_account_page_ad_image   (String)
 *   custom_account_page_ad_bg      (String)   – hex color e.g. "#1E6B3C"
 *   custom_account_page_ad_cta     (String)   – CTA URL
 *   custom_account_page_ad_show_to (String)   – "all" | "pro" | "non_pro"
 */
object RemoteConfigHelper {

    private const val TAG = "RemoteConfigHelper"
    private lateinit var remoteConfig: FirebaseRemoteConfig

    private val _isFetched = MutableStateFlow(false)
    val isFetched: StateFlow<Boolean> = _isFetched.asStateFlow()

    fun init(application: Application) {
        remoteConfig = FirebaseRemoteConfig.getInstance()

        val configSettings = FirebaseRemoteConfigSettings.Builder()
            .setMinimumFetchIntervalInSeconds(0L)
            .build()
        remoteConfig.setConfigSettingsAsync(configSettings)

        remoteConfig.setDefaultsAsync(
            mapOf(
                "custom_ad_enabled" to false,
                "custom_ad_image_url" to "",
                "custom_ad_link" to "",
                "custom_ad_show_to" to "non_pro",
                "custom_ad_bg" to "#FFFFFF",
                "custom_account_page_ad_enabled" to false,
                "custom_account_page_ad_title" to "",
                "custom_account_page_ad_subtitle" to "",
                "custom_account_page_ad_image" to "",
                "custom_account_page_ad_bg" to "#1E6B3C",
                "custom_account_page_ad_cta" to "",
                "custom_account_page_ad_show_to" to "all"
            )
        )

        remoteConfig.fetchAndActivate().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                Log.d(TAG, "Remote Config fetched and activated")
            } else {
                Log.w(TAG, "Remote Config fetch failed, using defaults")
            }
            _isFetched.value = true
        }
    }

    // ── custom_ad (global banner replacement) ────────────────────────────────

    fun isCustomAdEnabled(): Boolean = remoteConfig.getBoolean("custom_ad_enabled")

    fun customAdImageUrl(): String = remoteConfig.getString("custom_ad_image_url")

    fun customAdLink(): String = remoteConfig.getString("custom_ad_link")

    fun customAdShowTo(): String = remoteConfig.getString("custom_ad_show_to")

    fun customAdBg(): String = remoteConfig.getString("custom_ad_bg")

    // ── custom_account_page_ad ───────────────────────────────────────────────

    fun isAccountPageAdEnabled(): Boolean = remoteConfig.getBoolean("custom_account_page_ad_enabled")

    fun accountPageAdTitle(): String = remoteConfig.getString("custom_account_page_ad_title")

    fun accountPageAdSubtitle(): String = remoteConfig.getString("custom_account_page_ad_subtitle")

    fun accountPageAdImage(): String = remoteConfig.getString("custom_account_page_ad_image")

    fun accountPageAdBg(): String = remoteConfig.getString("custom_account_page_ad_bg")

    fun accountPageAdCta(): String = remoteConfig.getString("custom_account_page_ad_cta")

    fun accountPageAdShowTo(): String = remoteConfig.getString("custom_account_page_ad_show_to")
}
