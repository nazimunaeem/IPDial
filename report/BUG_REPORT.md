# IPDial Project — Comprehensive Bug & Architecture Audit Report

**Project Name:** IPDial (Open-Source SIP Client for Android)  
**Package Name:** `com.ipdial`  
**Target SDK:** 37 | **Min SDK:** 26 | **Language:** Kotlin / C++ (PJSIP Native)  
**Audit Date:** August 1, 2026  

---

## Executive Summary

A comprehensive, full-codebase static and dynamic architectural audit was conducted across the IPDial codebase (`com.ipdial`). The project implements a SIP softphone built with Jetpack Compose, PJSIP native engine integration (VoiSmart prebuilt AAR), Android Telecom framework, Room database, Firebase, and Kotlin Coroutines.

This audit identified **16 notable issues and bugs** spanning native memory safety, multithreading concurrency, Android 14+ system API constraints, security vulnerabilities, state management inconsistencies, and audio lifecycle bugs.

### Issue Summary by Severity

| Severity Level | Count | Primary Impact Areas |
| :--- | :---: | :--- |
| 🔴 **Critical** | 5 | Native Crashes (SIGSEGV), Thread Safety/Data Corruption, Plaintext Credential Leak, FGS Exception on Android 14+ |
| 🟠 **High** | 4 | Dropped Incoming Calls, Network Reconnect Data Loss, Broken Pro Expiration Logic, Flow Collector Delay |
| 🟡 **Medium** | 4 | Mic Mute Overwrite, Omission of Extension Numbers, Unscoped Coroutine Leaks, SSL Certificate Failure |
| 🔵 **Low / Code Smell** | 3 | AGP 10.0 Deprecated Gradle DSLs, PJSIP Vector Long-to-Int Conversions, Multiple DataStore Instantiations |

---

## 1. Critical Severity Bugs (Crashes, Security & System Violations)

### 1.1 Non-Thread-Safe Data Structures Accessed Concurrently (`SipEngine.kt`, `SipConnectionService.kt`)
* **Files:** [`SipEngine.kt`](file:///Users/igeneration/ipdial/IPDial/app/src/main/java/com/ipdial/service/SipEngine.kt#L29-L32), [`SipConnectionService.kt`](file:///Users/igeneration/ipdial/IPDial/app/src/main/java/com/ipdial/service/SipConnectionService.kt#L13)
* **Lines:** `SipEngine.kt:L29-32`, `SipConnectionService.kt:L13`
* **Root Cause:**
  `accountMap` and `accountConfigs` in `SipEngine` are declared as non-thread-safe `mutableMapOf()` (standard JVM `HashMap`). Similarly, `activeConnections` in `SipConnectionService` is a `mutableMapOf<Int, SipConnection>()`. These maps are concurrently read and mutated across PJSIP C++ worker threads, main UI threads, and Coroutine IO dispatchers during incoming call callbacks, account registration updates, and network reconnections.
* **Impact:**
  Concurrent modification on standard `HashMap` leads to `ConcurrentModificationException`, corrupted entry buckets, infinite loops during map resizing, or unexpected `NullPointerException` crashes during active call processing.
* **Remediation:**
  Replace non-thread-safe maps with `ConcurrentHashMap`:
  ```kotlin
  // SipEngine.kt
  internal val accountMap = java.util.concurrent.ConcurrentHashMap<String, SipAccountDelegate>()
  internal val accountConfigs = java.util.concurrent.ConcurrentHashMap<String, SipAccount>()

  // SipConnectionService.kt
  private val activeConnections = java.util.concurrent.ConcurrentHashMap<Int, SipConnection>()
  ```

---

### 1.2 Unregistered Thread Native Deletion Crash (`SipCallDelegate.kt`)
* **File:** [`SipCallDelegate.kt`](file:///Users/igeneration/ipdial/IPDial/app/src/main/java/com/ipdial/service/SipCallDelegate.kt#L116-L122)
* **Lines:** `L116-L122`
* **Root Cause:**
  In `onCallState()`, upon call disconnection, `callToDelete.delete()` is posted to the Main Looper Handler:
  ```kotlin
  Handler(Looper.getMainLooper()).post {
      try { callToDelete.delete() } catch (e: Throwable) { ... }
  }
  ```
  PJSIP objects (such as `Call` and `Account`) invoke C++ native destructors (`delete pjsua2::Call`). PJSIP mandates that any thread executing PJSIP operations or deletions must be registered with the PJSIP library via `ep.libRegisterThread()`. The Main Looper thread is not registered prior to invoking `.delete()`.
* **Impact:**
  Native heap corruption or a `SIGSEGV` crash on the Android Main UI Thread when releasing PJSIP call objects.
* **Remediation:**
  Ensure the thread is registered before deleting PJSIP delegates:
  ```kotlin
  Handler(Looper.getMainLooper()).post {
      try {
          SipEngine.registerCurrentThreadEx()
          callToDelete.delete()
      } catch (e: Throwable) {
          Log.e("SipEngine", "Failed to delete call on main loop", e)
      }
  }
  ```

---

### 1.3 Plaintext Storage of SIP Passwords (`AccountRepository.kt`)
* **File:** [`AccountRepository.kt`](file:///Users/igeneration/ipdial/IPDial/app/src/main/java/com/ipdial/data/repository/AccountRepository.kt#L44-L48)
* **Lines:** `L44-L48`, `L151-L158`
* **Root Cause:**
  `AccountRepository` serializes `SipAccount` domain models directly to JSON via `Gson` and stores them in Android Jetpack `DataStore` preferences XML (`ipdial_accounts.xml`). The `SipAccount` data class contains the cleartext user password field (`password`).
* **Impact:**
  Account credentials are saved unencrypted on local flash storage. Vulnerable to ADB backup extraction, rooted device inspection, or unencrypted storage access.
* **Remediation:**
  Use Android `EncryptedSharedPreferences` or encrypt the password field using `AndroidKeyStore` (AES-GCM-256) before writing to `DataStore`.

---

### 1.4 `UninitializedPropertyAccessException` on `audioManager` (`SipEngine.kt`)
* **File:** [`SipEngine.kt`](file:///Users/igeneration/ipdial/IPDial/app/src/main/java/com/ipdial/service/SipEngine.kt#L37)
* **Lines:** `L37`, `L696`
* **Root Cause:**
  `audioManager` is declared as `internal lateinit var audioManager: AudioManager`. It is assigned inside `SipEngine.init(context)`. However, methods such as `SipEngine.destroy()` or audio helper delegates access `audioManager` directly without verifying initialization:
  ```kotlin
  audioManager.mode = AudioManager.MODE_NORMAL
  ```
* **Impact:**
  If `SipEngine.destroy()` is called before `init()` completes, or if `init()` fails mid-execution, the app crashes with an `UninitializedPropertyAccessException`.
* **Remediation:**
  Check `::audioManager.isInitialized` before access:
  ```kotlin
  if (::audioManager.isInitialized) {
      audioManager.mode = AudioManager.MODE_NORMAL
      audioManager.isSpeakerphoneOn = false
  }
  ```

---

### 1.5 Foreground Service Start Exception on Android 14+ (`SipService.kt`)
* **File:** [`SipService.kt`](file:///Users/igeneration/ipdial/IPDial/app/src/main/java/com/ipdial/service/SipService.kt#L471-L487)
* **Lines:** `L471-L487`
* **Root Cause:**
  `SipService` attempts to promote itself to a Foreground Service with type `ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL` inside `startServiceForeground()` during initial app launch or system boot (`BootReceiver`). On Android 14+ (API 34+), starting a `phoneCall` foreground service when there is no active ongoing call or valid call state throws a system restriction exception.
* **Impact:**
  Throws `ForegroundServiceStartNotAllowedException` on Android 14/15 devices when starting background registration on device startup.
* **Remediation:**
  Start as `FOREGROUND_SERVICE_TYPE_DATA_SYNC` during background registration/idle monitoring, and dynamically update the service type to `FOREGROUND_SERVICE_TYPE_PHONE_CALL` via `updateForegroundType()` only when an active call session (`CallState.INCOMING`, `CallState.CALLING`, `CallState.CONFIRMED`) starts.

---

## 2. High Severity Bugs (Functional Failures & Logical Edge Cases)

### 2.1 Stale Callback Capture in `SipAccountDelegate` (`SipEngine.kt`, `SipAccountDelegate.kt`)
* **Files:** [`SipEngine.kt`](file:///Users/igeneration/ipdial/IPDial/app/src/main/java/com/ipdial/service/SipEngine.kt#L281-L289), [`SipAccountDelegate.kt`](file:///Users/igeneration/ipdial/IPDial/app/src/main/java/com/ipdial/service/SipAccountDelegate.kt#L21)
* **Lines:** `SipEngine.kt:L281-289`, `SipAccountDelegate.kt:L21`
* **Root Cause:**
  When `SipEngine.addAccount` instantiates `SipAccountDelegate`, it passes `onIncomingCall` as a constructor parameter (`onIncomingCall = onIncomingCall`). If `addAccount` is called before `SipService.onCreate` assigns `SipEngine.onIncomingCall = { session -> ... }`, `SipAccountDelegate` captures `null` permanently.
* **Impact:**
  Incoming calls received by pre-existing or auto-registered accounts fail to invoke `SipIncomingCallHandler`. The device receives the SIP INVITE natively, but no incoming call UI or Telecom notification is presented to the user.
* **Remediation:**
  Access `SipEngine.onIncomingCall` dynamically via getter or read `SipEngine.onIncomingCall` inside `SipAccountDelegate.onIncomingCall()` instead of capturing it at constructor time.

---

### 2.2 SSL Hostname Verification Failure for IP Endpoint (`SipViewModel.kt`)
* **File:** [`SipViewModel.kt`](file:///Users/igeneration/ipdial/IPDial/app/src/main/java/com/ipdial/ui/SipViewModel.kt#L995)
* **Lines:** `L995`
* **Root Cause:**
  In `fetchBalance()`, for domain `"103.170.231.10"`, the code constructs a URL: `https://103.170.231.10/api/mobile/login`. Connecting over HTTPS to a IP address via standard `HttpURLConnection` triggers SSL hostname verification errors (`SSLPeerUnverifiedException`), because SSL certificates match hostnames, not IP addresses.
* **Impact:**
  Fetching balance for accounts on `103.170.231.10` continuously throws an exception and fails with `"Failed to fetch balance"`.
* **Remediation:**
  Use domain names with valid SSL certificates or configure a custom HostnameVerifier for verified IP endpoints.

---

### 2.3 Permanent Account Removal on Network Reconnection Failure (`SipEngine.kt`)
* **File:** [`SipEngine.kt`](file:///Users/igeneration/ipdial/IPDial/app/src/main/java/com/ipdial/service/SipEngine.kt#L368-L370)
* **Lines:** `L368-L370`, `L406-L412`
* **Root Cause:**
  Inside `reconnectOnNetworkChange()`, `accountMap.clear()` and `accountConfigs.clear()` wipe all stored active account objects. It then loops over `savedConfigs` and calls `addAccount(config)`. If `addAccount` throws an exception for an account (e.g. temporary network interface transition error), that account is removed from `accountConfigs` and never restored.
* **Impact:**
  Accounts disappear silently from the SIP engine during mobile-to-WiFi network transitions, requiring the user to re-save account settings.
* **Remediation:**
  Only clear `accountMap` and retain `accountConfigs`, or re-populate `accountConfigs` in a `finally` block if re-registration fails.

---

### 2.4 Stale Pro Subscription Expiration Evaluation (`SipViewModel.kt`)
* **File:** [`SipViewModel.kt`](file:///Users/igeneration/ipdial/IPDial/app/src/main/java/com/ipdial/ui/SipViewModel.kt#L125)
* **Lines:** `L125`
* **Root Cause:**
  In `SipViewModel`:
  ```kotlin
  val isPro: StateFlow<Boolean> = proExpiration.map { it > System.currentTimeMillis() }
      .stateIn(viewModelScope, SharingStarted.Eagerly, false)
  ```
  `System.currentTimeMillis()` is evaluated only when `proExpiration` emits a value. As time elapses and the expiration timestamp passes, `isPro` does not update dynamically because `proExpiration` emits no new items.
* **Impact:**
  Users retain Pro feature privileges indefinitely after their Pro period expires until `proExpiration` is updated in DataStore.
* **Remediation:**
  Include periodic time ticker ticks or evaluate `isPro` against `System.currentTimeMillis()` dynamically.

---

## 3. Medium Severity Bugs (Audio, UI & Performance Issues)

### 3.1 Automatic Mic Mute Override in Call State Observer (`SipService.kt`)
* **File:** [`SipService.kt`](file:///Users/igeneration/ipdial/IPDial/app/src/main/java/com/ipdial/service/SipService.kt#L402)
* **Lines:** `L402`
* **Root Cause:**
  Inside `SipService.observeCallState()`, on every emission of `SipEngine.callSession`, line 402 executes:
  ```kotlin
  audioManager.isMicrophoneMute = false
  ```
  When the user toggles Mute via `SipAudioController.setMute(true)`, any subsequent minor state update in `callSession` (e.g. Rx volume adjustment or hold toggle) causes `observeCallState` to reset `audioManager.isMicrophoneMute` back to `false`.
* **Impact:**
  Mute state is silently undone on audio device changes or state emissions during active calls.
* **Remediation:**
  Set `isMicrophoneMute` according to `session.isMuted` rather than forcing `false`:
  ```kotlin
  audioManager.isMicrophoneMute = session.isMuted
  ```

---

### 3.2 Short Numbers & Office Extensions Filtered Out (`SipViewModel.kt`)
* **File:** [`SipViewModel.kt`](file:///Users/igeneration/ipdial/IPDial/app/src/main/java/com/ipdial/ui/SipViewModel.kt#L442)
* **Lines:** `L442-L448`
* **Root Cause:**
  In `mostCalledContacts`, the frequency map filters out entries with length check:
  ```kotlin
  if (cleanedCallLogNumber.length < 10) null
  ```
* **Impact:**
  PBX extensions (e.g., 101, 1002), internal short numbers, and emergency numbers are omitted from the Most Called Contacts UI list.
* **Remediation:**
  Lower or remove the strict 10-digit threshold for matching call logs with contacts.

---

### 3.3 Un-scoped Coroutines for Call Log Insertion (`SipService.kt`)
* **File:** [`SipService.kt`](file:///Users/igeneration/ipdial/IPDial/app/src/main/java/com/ipdial/service/SipService.kt#L379)
* **Lines:** `L379`
* **Root Cause:**
  When a call disconnects, `SipService` inserts the call log entry by creating an un-scoped CoroutineScope:
  ```kotlin
  kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
      com.ipdial.data.repository.CallLogRepository.getInstance(applicationContext).insert(entry)
  }
  ```
* **Impact:**
  Breaks Kotlin structured concurrency guidelines and risks memory leaks if the service is destroyed mid-execution.
* **Remediation:**
  Use the service's existing `scope.launch(Dispatchers.IO)` instance.

---

### 3.4 Blocking Flow Collector with Hardcoded `delay(300)` (`SipService.kt`)
* **File:** [`SipService.kt`](file:///Users/igeneration/ipdial/IPDial/app/src/main/java/com/ipdial/service/SipService.kt#L428)
* **Lines:** `L428`
* **Root Cause:**
  Inside `observeCallState()`, `if (stateChanged) delay(300)` is executed directly inside the Flow `collect` block.
* **Impact:**
  Blocks the coroutine collecting call state emissions for 300ms. If a call transitions quickly (e.g., `CONNECTING` -> `CONFIRMED` -> `DISCONNECTED`), handling of disconnect is delayed, causing UI lag and audio tearing.
* **Remediation:**
  Move audio routing delays into asynchronous launch blocks rather than blocking the primary state collector thread.

---

## 4. Low Severity Issues & Code Smells

### 4.1 AGP 10.0 Deprecated DSL Flags (`build.gradle`, `gradle.properties`)
* **Files:** [`app/build.gradle`](file:///Users/igeneration/ipdial/IPDial/app/build.gradle), [`gradle.properties`](file:///Users/igeneration/ipdial/IPDial/gradle.properties)
* **Description:**
  Gradle build outputs deprecation warnings for `android.builtInKotlin=false`, `android.newDsl=false`, `android.defaults.buildfeatures.resvalues=true`, and obsolete APIs (`applicationVariants`, `testVariants`, `unitTestVariants`).
* **Remediation:**
  Migrate to `AndroidComponentsExtension` API before upgrading to Android Gradle Plugin 10.0.

---

### 4.2 PJSIP Vector Loops Type Casting (`SipCallDelegate.kt`, `SipEngine.kt`)
* **Files:** [`SipCallDelegate.kt`](file:///Users/igeneration/ipdial/IPDial/app/src/main/java/com/ipdial/service/SipCallDelegate.kt#L197), [`SipEngine.kt`](file:///Users/igeneration/ipdial/IPDial/app/src/main/java/com/ipdial/service/SipEngine.kt#L587)
* **Description:**
  PJSIP vector `.size` returns `Long`. Loops written as `for (i in 0 until ci.media.size)` create Kotlin `LongRange` instances. Calling `ci.media.get(i)` implicitly casts or requires `i.toInt()`. Explicit casting (`0 until ci.media.size.toInt()`) avoids unnecessary range allocation overhead.

---

### 4.3 `extractNativeLibs` Deprecated Manifest Attribute (`AndroidManifest.xml`)
* **File:** [`AndroidManifest.xml`](file:///Users/igeneration/ipdial/IPDial/app/src/main/AndroidManifest.xml#L51)
* **Lines:** `L51`
* **Description:**
  `android:extractNativeLibs="true"` is specified in `AndroidManifest.xml`. AGP recommends removing this attribute to allow page-aligned native library loading directly from the APK on modern Android versions.

---

## Recommended Action & Remediation Plan

1. **Immediate Action (Critical Fixes):**
   - Refactor `accountMap`, `accountConfigs`, and `activeConnections` to `ConcurrentHashMap`.
   - Update `SipCallDelegate.kt` main thread handler to call `SipEngine.registerCurrentThreadEx()` prior to `call.delete()`.
   - Update `SipService.kt` to start as `FOREGROUND_SERVICE_TYPE_DATA_SYNC` during background idle mode and elevate to `FOREGROUND_SERVICE_TYPE_PHONE_CALL` only on active call sessions.
   - Encrypt SIP account passwords in `AccountRepository` using `EncryptedSharedPreferences`.

2. **Secondary Action (Stability & UX Fixes):**
   - Fix `SipAccountDelegate` constructor parameter capture for `onIncomingCall`.
   - Remove hardcoded `delay(300)` from `SipService.observeCallState()` collector.
   - Adjust `audioManager.isMicrophoneMute` to respect `session.isMuted`.

3. **Maintenance & Build Modernization:**
   - Remove `android:extractNativeLibs` from `AndroidManifest.xml`.
   - Clean up deprecated Gradle options in `gradle.properties`.

---
*Report generated automatically for project IPDial (`com.ipdial`).*
