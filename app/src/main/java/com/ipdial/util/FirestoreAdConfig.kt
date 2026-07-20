package com.ipdial.util

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reads ad configuration from Firestore (config/ads document).
 * This allows the admin app to manage ad settings in real-time.
 * Falls back to Remote Config values if Firestore fetch fails.
 */
object FirestoreAdConfig {

    private const val TAG = "FirestoreAdConfig"
    private val firestore = FirebaseFirestore.getInstance()
    private var registration: ListenerRegistration? = null

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    // custom_ad (global banner)
    private val _customAdEnabled = MutableStateFlow(false)
    val customAdEnabled: StateFlow<Boolean> = _customAdEnabled.asStateFlow()

    private val _customAdImageUrl = MutableStateFlow("")
    val customAdImageUrl: StateFlow<String> = _customAdImageUrl.asStateFlow()

    private val _customAdLink = MutableStateFlow("")
    val customAdLink: StateFlow<String> = _customAdLink.asStateFlow()

    private val _customAdShowTo = MutableStateFlow("non_pro")
    val customAdShowTo: StateFlow<String> = _customAdShowTo.asStateFlow()

    private val _customAdBg = MutableStateFlow("#FFFFFF")
    val customAdBg: StateFlow<String> = _customAdBg.asStateFlow()

    // custom_account_page_ad
    private val _accountPageAdEnabled = MutableStateFlow(false)
    val accountPageAdEnabled: StateFlow<Boolean> = _accountPageAdEnabled.asStateFlow()

    private val _accountPageAdTitle = MutableStateFlow("")
    val accountPageAdTitle: StateFlow<String> = _accountPageAdTitle.asStateFlow()

    private val _accountPageAdSubtitle = MutableStateFlow("")
    val accountPageAdSubtitle: StateFlow<String> = _accountPageAdSubtitle.asStateFlow()

    private val _accountPageAdImage = MutableStateFlow("")
    val accountPageAdImage: StateFlow<String> = _accountPageAdImage.asStateFlow()

    private val _accountPageAdBg = MutableStateFlow("#1E6B3C")
    val accountPageAdBg: StateFlow<String> = _accountPageAdBg.asStateFlow()

    private val _accountPageAdCta = MutableStateFlow("")
    val accountPageAdCta: StateFlow<String> = _accountPageAdCta.asStateFlow()

    private val _accountPageAdShowTo = MutableStateFlow("all")
    val accountPageAdShowTo: StateFlow<String> = _accountPageAdShowTo.asStateFlow()

    fun init() {
        registration?.remove()
        registration = firestore.collection("config").document("ads")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Firestore listen failed, using Remote Config defaults", error)
                    _isLoaded.value = true
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    _customAdEnabled.value = snapshot.getBoolean("custom_ad_enabled")
                        ?: RemoteConfigHelper.isCustomAdEnabled()
                    _customAdImageUrl.value = snapshot.getString("custom_ad_image_url")
                        ?: RemoteConfigHelper.customAdImageUrl()
                    _customAdLink.value = snapshot.getString("custom_ad_link")
                        ?: RemoteConfigHelper.customAdLink()
                    _customAdShowTo.value = snapshot.getString("custom_ad_show_to")
                        ?: RemoteConfigHelper.customAdShowTo()
                    _customAdBg.value = snapshot.getString("custom_ad_bg")
                        ?: RemoteConfigHelper.customAdBg()
                    _accountPageAdEnabled.value = snapshot.getBoolean("custom_account_page_ad_enabled")
                        ?: RemoteConfigHelper.isAccountPageAdEnabled()
                    _accountPageAdTitle.value = snapshot.getString("custom_account_page_ad_title")
                        ?: RemoteConfigHelper.accountPageAdTitle()
                    _accountPageAdSubtitle.value = snapshot.getString("custom_account_page_ad_subtitle")
                        ?: RemoteConfigHelper.accountPageAdSubtitle()
                    _accountPageAdImage.value = snapshot.getString("custom_account_page_ad_image")
                        ?: RemoteConfigHelper.accountPageAdImage()
                    _accountPageAdBg.value = snapshot.getString("custom_account_page_ad_bg")
                        ?: RemoteConfigHelper.accountPageAdBg()
                    _accountPageAdCta.value = snapshot.getString("custom_account_page_ad_cta")
                        ?: RemoteConfigHelper.accountPageAdCta()
                    _accountPageAdShowTo.value = snapshot.getString("custom_account_page_ad_show_to")
                        ?: RemoteConfigHelper.accountPageAdShowTo()
                    Log.d(TAG, "Ad config loaded from Firestore")
                } else {
                    Log.d(TAG, "No Firestore ad config, using Remote Config defaults")
                    _customAdEnabled.value = RemoteConfigHelper.isCustomAdEnabled()
                    _customAdImageUrl.value = RemoteConfigHelper.customAdImageUrl()
                    _customAdLink.value = RemoteConfigHelper.customAdLink()
                    _customAdShowTo.value = RemoteConfigHelper.customAdShowTo()
                    _customAdBg.value = RemoteConfigHelper.customAdBg()
                    _accountPageAdEnabled.value = RemoteConfigHelper.isAccountPageAdEnabled()
                    _accountPageAdTitle.value = RemoteConfigHelper.accountPageAdTitle()
                    _accountPageAdSubtitle.value = RemoteConfigHelper.accountPageAdSubtitle()
                    _accountPageAdImage.value = RemoteConfigHelper.accountPageAdImage()
                    _accountPageAdBg.value = RemoteConfigHelper.accountPageAdBg()
                    _accountPageAdCta.value = RemoteConfigHelper.accountPageAdCta()
                    _accountPageAdShowTo.value = RemoteConfigHelper.accountPageAdShowTo()
                }
                _isLoaded.value = true
            }
    }

    fun cleanup() {
        registration?.remove()
        registration = null
    }
}
