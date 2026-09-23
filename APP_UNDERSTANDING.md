# IPDial — App Understanding Guide (for future AI model edits)

This document is the single source of context for anyone (human or AI) making
changes to this codebase. Read it before editing. All line references are
relative to `app/src/main/java/com/ipdial/` unless stated otherwise.

Last updated: 2026-09-12. Check `git status` / recent commits — this repo is
under active development and has uncommitted in-flight refactors (see §11).

---

## 1. What this app is

IPDial is a **SIP (Session Initiation Protocol) softphone** for Android,
written in Kotlin + Jetpack Compose. It lets users register SIP/VoIP account
and make/receive VoIP calls over data/Wi-Fi using the bundled **PJSIP 2.5 /
pjsua2** native stack.

- Single module: `com.ipdial` (namespace/applicationId), `app/` only.
- `minSdk 26`, `targetSdk 35`, `compileSdk 37`. Version 1.1.6.1 (versionCode 28).
- Not a wrapper of the system dialer — it is a *self-managed Telecom* VoIP app
  (own `PhoneAccount` + `ConnectionService`) and a native SIP stack.

### Big-picture architecture

```
┌───────────────────────── UI ─────────────────────────┐
│ Compose screens  ← StateFlows  →  SipViewModel       │
└──────────────────────────────────────────────────────┘
        ▲ flows (callSession, confirmedAudioRoute, …)
        │ calls (SipAudioController, TelecomHelper, …)
┌───────────────────── SERVICE LAYER ──────────────────┐
│ SipService (foreground)                              │
│   ├─ SipEngine (PJSIP singleton)  ══ calls ══► pjsua2│
│   ├─ SipConnectionService (Telecom ConnectionService)│
│   ├─ SipAudioRouter / SipAudioController / WakeLocks │
│   └─ Ringtone / Notifications / IncomingHandler      │
└──────────────────────────────────────────────────────┘
┌──────────────────── DATA LAYER ──────────────────────┐
│ AccountRepository (DataStore: settings+accounts)     │
│ Room (call logs, contacts) · Firestore (Pro/points)  │
│ Firebase Auth + Credential Manager (Google Sign-In)  │
└──────────────────────────────────────────────────────┘
```

**Key invariant — the PJSIP threading doctrine** (documented at the top of
`service/SipEngine.kt:29-51`): pjsua2 is NOT thread-safe. Native callbacks arrive
on PJSIP's internal worker thread; any concurrent touch from another thread
causes `pj_mutex_unlock()` SIGABRT assertion crashes. The mitigation:

- Every **app-initiated** native call is serialized on a dedicated
  `HandlerThread("PjsipThread")` via `runOnPjsipThread(...)` / `runOnPjsipThreadAndWait(...)`
  (`service/SipEngine.kt:52-78`).
- A global `pjsipLock` monitor serializes *every* native touch, including
  worker-thread callbacks (media-state handling, recorder teardown, codec reads).
- Any non-worker thread that must touch native objects first calls
  `registerCurrentThreadEx()` (`SipEngine.kt:266-286`).
- Net result: only the SIP worker thread and PjsipThread ever touch native
  objects. **Never add a third thread that calls pjsua2 directly.**

---

## 2. Tech stack (app/build.gradle)

| Area | Choice |
|---|---|
| UI | Jetpack Compose (BOM 2026.06.00), Material 3, `material-icons-extended`, Navigation Compose 2.9.8 |
| SIP | `libs/pjsua2-classes.jar` (SWIG bindings) + native `libpjsua2.so` in `app/src/main/jniLibs/{arm64-v8a,armeabi-v7a}` — custom-built pjproject 2.5 |
| Local storage | DataStore Preferences 1.2.1 (settings/accounts) + Room 2.8.4 (call log, contacts) + Gson 2.14.0 |
| Cloud | Firebase BOM 34.17.0: Auth, Firestore, Remote Config, Crashlytics, Analytics. Google Sign-In via Credential Manager 1.3.0 + googleid 1.1.1 |
| Ads | Start.io In-App SDK 5.3.1 (`com.startapp:inapp-sdk`) |
| Images | Coil 2.7.0 |
| Build | AGP + Kotlin Compose plugin + KSP (Room). ABI splits keep APK small; universal APK also built |
| Misc | Coroutines 1.11.0, `-opt-in=ExperimentalComposeUiApi` |

Build/signing notes (`app/build.gradle`):
- Release signing reads **env vars** (`STORE_FILE/STORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD`)
  or `keystore.properties` in the repo root (git-ignored).
- `./gradlew copySignedApk` assembles release and drops a timestamped
  `IPDial-v<version>-signed.apk` into `~/Downloads`.
- `resConfigs "en"` — **only English resources exist**; don't add other locales.
- Release build is minified (R8) + `shrinkResources`.

---

## 3. Directory map

```
app/src/main/java/com/ipdial/
├── IPDialApplication.kt      # Application: loads pjsua2, Start.io, Telecom account,
│                             #   app-icon sync, RemoteConfig, FirestoreAdConfig, starts SipService
├── MainActivity.kt           # Nav graph (NavDest), pager, call overlay, intents/deep links,
│                             #   permissions, battery dialog, update-check dialog
├── data/
│   ├── model/                # SipModels.kt (all domain types), Contact.kt
│   ├── local/                # Room: AppDatabase (+Converters), CallLogDao/Entity, ContactDao/Entity
│   └── repository/           # AccountRepository (DataStore), AuthRepository (Google),
│                             #   CallLogRepository, ContactsRepository, FirestorePointsSync
├── service/                  # See §5 — the SIP engine + foreground service + Telecom
├── ui/
│   ├── SipViewModel.kt       # THE UI state hub (~1850 lines) — settings, calls, ads, Pro
│   ├── components/           # AppBars, AdBanners, AppMenuBottomSheet, FloatingPillNavBar,
│   │                         #   ContactAvatar, Dialogs, StatusIndicators
│   ├── screens/              # One file per screen (see §6) + home/, call/, dialpad/ subfolders
│   └── theme/                # IPDialTheme, IPDialTypography (glass modes, themes, font scaling)
└── util/                     # AppIconHelper, ContactPhotoUtil, DeviceUtil, FirestoreAdConfig,
                              #   RemoteConfigHelper, SipLogger, UpdateChecker
```

Native libs: `app/src/main/jniLibs/<abi>/libpjsua2.so`. Top-level `libs/pjsua2-classes.jar`.

Repo-root docs that matter: `README.md` (marketing), `privacy_policy.md`,
`firestore.rules`, `conference_call_plan.md`, `pjsip_upgradation_plan.md`,
`bug_fix_plan.md`, `device_simplification_plan.md`, `bug_tracking.md`.

---

## 4. Data layer

### 4.1 AccountRepository — DataStore Preferences (`data/repository/AccountRepository.kt`)
Singleton-store keyed `"ipdial_accounts"` (`:29`). Owns **all** app settings, SIP
accounts, points/Pro cache, device id, auth UID. Preferences created at `:44-79`:

| Key | Default | Meaning |
|---|---|---|
| `accounts` | `[]` | Full `List<SipAccount>` as JSON; **passwords AES-encrypted at persist time** (AndroidKeyStore alias `ipdial_sip_crypto_key`, `CryptoHelper :289-360`; `secureAccount/unsecureAccount :388-389`) |
| `global_ringtone` | built-in mp3 | Ringtone URI (global) |
| `dnd_enabled` | false | Do-not-disturb |
| `global_vibrate` | true | Vibrate on incoming |
| `theme_mode` | System | ThemeMode enum |
| `font_size_multiplier` | 1.0f | UI font scale |
| `app_icon_alias` | "Default" | Icon variant (Default/Green/Blue/Red) |
| `keypad_design` | Rounded | KeypadDesign (Grid/Rounded/Ring) |
| `incoming_call_mode` | Slider | IncomingCallMode (Slider/Buttons) |
| `default_domain` | "103.129.202.202" | Prefilled domain in account form |
| `last_dialed` | — | Last dialed number |
| `ads_enabled` | true | Master ad switch |
| `device_id` | — | Anonymous random UUID (lazy) |
| `pro_points` / `pro_expiration` | 0 / 0 | Points + Pro expiry cache (Firestore-backed) |
| `recording_counter` | 0 | Free-recording ad-gate counter (gate every 5) |
| `battery_notice_shown` | false | Battery-optimization nag flag |
| `auto_record_enabled` | false | Auto-record calls |
| `global_noise_cancellation` | true | Device NS toggle |
| `global_ec/n_s/_agc_enabled` | all true | Global PJSIP-side audio processing |
| `sip_ec_enabled` / `sip_ns_enabled` | false/false | **Dead code** — repo-only, never surfaced in VM |
| `full_screen_contact_photo` | false | Full-screen photo on incoming |
| `google_sign_in_banner_dismissed` | false | Superseded sign-in banner flag |
| `saved_labels` / `saved_hosts` | `[]` | Account-form autocomplete history |
| `firebase_user_id` | — | UID of last signed-in user (device→UID migration trigger) |
| `user_code` | — | 6-char referral/short code |
| `pro_device_authorized(For)` | — | Cached device-slot Pro grant for fast relaunch |

Notes:
- Account CRUD: `saveAccount/deleteAccount/setDefault/updateRegStatus` + `exportAccountsJson/importAccountsJson` (`:405-456`). ⚠ `exportAccountsJson` serializes **decrypted** accounts → plaintext passwords in the exported JSON.
- Enums persist as names (Room) — no converter needed. Lists-as-JSON use Gson (`data/local/Converters.kt`).
- `resetSettings` at `:276-287`.

### 4.2 Room (`data/local/`)
`AppDatabase` v3, DB `"ipdial_database"`, `fallbackToDestructiveMigration(dropAllTables=true)` (`:26`) — **schema bumps wipe local data; no migrations are maintained.**
- `call_logs`: id(UUID), accountId, remoteUri, remoteDisplayName, direction, missed, timestampMs, durationSeconds, disconnectCode, disconnectReason. DAO auto-trims to **200** via `trim(200)`.
- `contacts`: id (device CONTACT_ID), name, numbers(List<String> as JSON), photoUri, isFavorite. Favorites owned locally (Room is source of truth).

### 4.3 ContactsRepository (`data/repository/ContactsRepository.kt`)
- `buildNumberIndex()` (`:42-62`) builds an in-memory digit-only map keyed with suffix lengths 10/11/12/13 so local `0172-…` and `+880-…` forms match.
- `findContactByNumber()` (`:68-88`) is O(1) name-resolution for incoming calls.
- `syncContacts()` (`:94-176`) reads `ContactsContract.CommonDataKinds.Phone`, preserves orphaned favorites.
- `getContacts(query)` always reads Room, with a cold-cache fallback sync.

### 4.4 AuthRepository (`data/repository/AuthRepository.kt`)
Google Sign-In via **Credential Manager** using `GetSignInWithGoogleOption` (not the deprecated `GetGoogleIdOption` which broke on Android 15+). `signIn()` handles the OnePlus/GMS transient "code 16" with an 800ms retry (`:63-79`). Exposes `currentUser: StateFlow<FirebaseUser?>`, referral code = `uid.take(6)` (`:37`). Sign-out also calls `credentialManager.clearCredentialState`.

### 4.5 FirestorePointsSync (`data/repository/FirestorePointsSync.kt`)
Cloud sync of Pro points/expiration + logged-in-device list for one signed-in Firebase user. Document: **`users/{userId}`** where userId = Firebase **Auth UID** (no deviceId keying anymore; the old path survives only as `migrateFromDeviceId :350-386`).

- Sync: `startListening()` (`:52-166`) — one-shot `docRef.get()` + snapshot listener, both gated by a **last-write-wins watermark** (`lastLocalPointsWriteAt`).
- Points: `incrementPoints(amount)` (`:173-254`) uses a transaction (NOT `FieldValue.increment()` — Firestore rules reject increment transforms). `redeemPoints(cost, newExpiration)` (`:650-700`), `updateExpiration(...)` (`:706-722`).
- Referrals: `claimReferral(refCode, cb)` (`:398-509`) gives +50 both sides; anti-abuse = no self-referral, no double-claim (`referredBy`), no mutual referral, cap `MAX_REFERRALS_PER_USER = 50`.
- Devices (new simplified model per `device_simplification_plan.md`): `addLoggedInDevice/removeLoggedInDevice/getLoggedInDevices/addDeviceAndCheckPro` (`:531-648`). `allowedDevices` default 2. **Pro follows the account/expiration, NOT devices** (device slots are informational).
- ⚠ There is still schema debt: the `onDeviceSlotsChanged` callback reads **old** field names `authorizedDevices`/`pendingDevices` (`:74-75, :130-131`) while writes use `loggedInDevices` — so the VM's device list is fed empty data until fixed (see §11).

### 4.6 Call log
`SipViewModel.logCall` typically caps history at **50** (`SipViewModel.kt:1728-1738`) independently of the repo's 200-trim — redundant cutoff, keep in mind when changing limits.

---

## 5. Service layer (`service/`)

### 5.1 SipEngine (`service/SipEngine.kt`, 1458 lines) — the PJSIP singleton
`object SipEngine` owns the entire pjsua2 lifecycle and is the only entrance to
native code (besides `SipAudioController` which funnels through it).

Key state: `endpoint` (volatile), `accountMap` (accountId→SipAccountDelegate),
`accountConfigs`, `callMap` (callId→SipCallDelegate), `localHangupCauses`,
`SipTransportManager`, `audioManager`/`audioRouter` (injected by SipService),
`pendingDisconnectInfo` (final SIP code/reason side-channel — StateFlow conflates
intermediate values), `recorder` (single AudioMediaRecorder at a time).

Exposed flows: **`callSession: StateFlow<CallSession?>`** (`:170`),
**`confirmedAudioRoute: StateFlow<AudioDeviceMode?>`** (`:177`),
`registrationEvents: SharedFlow<Triple<accountId, RegStatus, statusCode>>` (`:184`).
Lambdas `onIncomingCall` / `onCallDisconnected` wired by `SipService.onCreate`
(`service/SipService.kt:115-130`).

Lifecycle: `init(context)` (re-entrant, posts to PjsipThread) → `libCreate/libInit/libStart`
(`:292-455`); `destroy()` (`:1402-1441`) deletes calls→accounts→recorder→transports→endpoint.

Notable EpConfig tuning (each backed by a real-device bug, `:369-422`):
`clockRate=16000`, `sndClockRate=48000` (BlueStacks silence fix), software EC off
(`ecOptions=0, ecTailLen=0` — rely on device AEC; double-EC mutes Samsung mics),
`noVad=true` (VAD silences low-gain mics), `quality=7`, `channelCount=1`,
`audioFramePtime=20`, jitter buffer `jbInit=60, jbMinPre=60, jbMax=400`,
STUN `stun.l.google.com:19302`, ICE disabled, UA "IPDial/1.1", `maxCalls=4`.

Key functions:
- Accounts: `addAccount` `:457`, `removeAccount` `:625`, `reconnectAccount` `:639`,
  `forceReconnectAll` `:650`, `handleIpChange` `:733`, `reconnectOnNetworkChange`
  `:674` (deletes + rebinds process + recreates transports + re-adds).
  Registration: `regConfig.timeoutSec=180`, retry 30s, first retry 15s, refresh 90s.
- Calls: `makeCall(accountId, destination)` `:888`, `answerCall(callId)` `:974`,
  `hangupCall(callId=-1)` `:988` (hangs up ALL calls if id missing — "H2 ghost fix" `:1031`).
- `configureCodecs(account)` `:1063` sets priority scheme (Opus 260 → preferred 250 → G.722 220 → G.711A 180 → G.711U 170 → G.729 160 → GSM 140 → else disabled).
- `getAvailableCodecs()` `:1272` builds UI `CodecInfo` (derives quality/MOS/bandwidth from codec id because pjsua2-2.5 has no CodecParam). ⚠ one of the few inline native reads — callers must register thread + take `pjsipLock`.
- Media: `forceAudioDevicesForCall()` `:1153`, `forceEcForCallAudio()` `:1187`,
  `reconnectAudioPathForCall(callId)` `:1207` (re-bridges device↔call, re-applies gains + recorder). `checkRtpActivity(callId)` `:1358`.
- Recording: `startRecording` `:770`, `startRecordingWhenActive` `:801` (arms a pending flag so `onCallMediaStateLocked` bridges when media starts), `stopRecording` `:827`.
- ℹ️ **`releaseSoundDevice()` is intentionally a no-op** (`:1345`) — `setNullDev` would break all subsequent calls' audio.

### 5.2 SipService (`service/SipService.kt`, 767 lines) — foreground service
`START_STICKY` started service (`:350`). `SipService.start(context, delayStartForeground)`
(`:30-56`) prefers `startForegroundService`. `onCreate` calls `startForeground()`
immediately (`:90`) and stops itself without mic permission (`:92-98`).

Owns a `CoroutineScope(Dispatchers.IO + SupervisorJob())` (`:59`) running:
- Engine init + `registerAccountsFromDataStore()` + default network callback (`:132-146`).
- `observeCallState()` (`:487-641`) — the reactive core. On **session==null** it logs the call via `consumeDisconnectInfo()`, shows miss toasts/notifications, stops ringtone, `audioRouter.restoreAudio()`, releases wake locks, `SipConnectionService.disconnectCall`. On **INCOMING/EARLY** it plays ringtone, wake lock, FGS type PHONE_CALL, incoming notification (re-pushed every 4s while backgrounded). On **CONFIRMED** it sets `MODE_IN_COMMUNICATION`, routes audio (+ delayed `reconnectAudioPathForCall` on speaker toggle), proximity lock, auto-record check, active-call notification.
- 10s `nullSessionIfStale` watchdog loop (`:150-157`).
- 30s registration keep-alive loop re-registering stragglers (`:452-484`).

`onStartCommand` (`:228-351`) dispatches notification/service actions:
`ACTION_ANSWER`, `ACTION_DECLINE`, `ACTION_STOP_BANNER`, `ACTION_SET_AUDIO_DEVICE`,
`ACTION_HANGUP`, `ACTION_STOP`, `ACTION_TEST_CALL` (debug).

Network: default-network callback (`:160-226`) → active call: `handleIpChange()`
(preserve media); idle: `reconnectOnNetworkChange` (captive-WiFi clears
`authFailedAccounts` `:175`); on network lost marks all accounts ERROR.

FGS: `startServiceForeground()` (`:683`) — type `PHONE_CALL` during calls, else
`DATA_SYNC` (API<34) or `SPECIAL_USE` (API≥34).

### 5.3 SipConnectionService (`service/SipConnectionService.kt`, 327 lines) — Telecom
Self-managed `ConnectionService` + inner class `SipConnection : Connection` (`:218`).
Static registry `ConcurrentHashMap<Int, SipConnection>` with `disconnectCall(callId, cause)`
(`:34-65`, idempotent CAS-guarded teardown, destroy posted to Main), `destroyAll()` (`:67`).
- `onCreateOutgoingConnection` (`:95-151`): capabilities MUTE + SUPPORT_HOLD, `PROPERTY_SELF_MANAGED`, `setAudioModeIsVoip`.
- `onCreateIncomingConnection` (`:153-215`): ghost/late-incoming guard, reuses existing connections.
- Connection actions (`:230-308`): onAnswer/onDisconnect/onAbort/onHold/onUnhold/onReject, each resolved via `CallHangupResolver`.
- `onCallAudioStateChanged()` (`:310-326`): forwards Telecom's actual route (BT SCO link established) → `SipEngine.setConfirmedAudioRoute`.

### 5.4 SipCallDelegate (`service/SipCallDelegate.kt`, 547 lines) — per-call callbacks
- `onCallState(prm)` (`:113-397`, worker thread): maps `pjsip_inv_state` → `CallState`; on disconnect stashes `pendingDisconnectInfo`, fires `onCallDisconnected`, stamps session DISCONNECTED, `scheduleSessionNull` (busy 486/487 gets 2s grace, else 500ms — lets the busy tone play), resets audio, deletes recorder under `pjsipLock`, resolves Telecom cause, and schedules a **delayed native `delete()` 2s later** so the 200 OK for a remote BYE flushes over UDP (`:215-239`).
- Samsung audio watchdog (`:48-89`): every 700ms forces `MODE_IN_COMMUNICATION` + clears `isMicrophoneMute` to stop OneUI flipping into cellular "SIM CALL" mode.
- `onCallMediaStateLocked` (`:451-546`, holds `pjsipLock`): requests focus, enforces `MODE_IN_COMMUNICATION`, applies `adjustTxLevel` (mic) + `adjustRxLevel` (rx volume, `DEFAULT_RX_VOLUME=6f`), establishes **bidirectional** bridges capture↔call↔playback (+ recorder), detects negotiated codec, re-routes on Main after PJSIP opens AudioRecord.

### 5.5 SipAccountDelegate (`service/SipAccountDelegate.kt`, 173 lines) — registration & incoming
- `onRegState` (`:27-68`): maps status → REGISTERED for 2xx/active, REGISTERING for 1xx + transient failures (408/423/5xx/480), ERROR for 401/403 + ≥300, else UNREGISTERED.
- `onIncomingCall` (`:70-172`): **must capture `prm.callId` synchronously** (PJSIP reuses the param after return). Posts to PJSIP thread: 603 Decline if account disabled, 486 Busy if another call active, else answers 180 Ringing, builds `CallSession(INCOMING)`, fires `onIncomingCall`.

### 5.6 Audio control & routing
- `SipAudioController` (`object`, `:13`): mute=reduce mic TX gain to 0 (`:36-65`), speaker just flips the session bool (`:67-70`; actual routing = SipAudioRouter), `setCallVolume` → `adjustRxLevel` (`:72`), DTMF via `call.dialDtmf` (`:117`), `holdCall` (`:130` — setHold; on un-hold does reinvite + force devices + reconnect audio because pjsip doesn't re-fire onCallMediaState). Mic gains: `MIC_GAIN_REAL=1.2f`, emulator `2.5f`.
- `SipAudioRouter` (`SipAudioRouter.kt:10`): earpiece/speaker/BT-SCO routing + audio focus. Uses only `TYPE_BLUETOOTH_SCO` devices (A2DP has no mic) `:153`. Sets EC tail (600ms speaker/500ms earpiece) via engine `setEcOptions(0,0)` only when hardware AEC absent or emulator (`:90-116`). Focus = `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`. `restoreAudio()` (`:193`) resets everything.
- `SipWakeLockManager`: CPU (`PARTIAL_WAKE_LOCK` 1h) + incoming (20s full/screen wake) + proximity (screen-off during call).
- `SipTransportManager`: creates/closes/recreates transports; TLS skips cert verification for IP providers.

### 5.7 Incoming-call orchestration + notifications
- `SipIncomingCallHandler` (`SipIncomingCallHandler.kt:21-93`): ghost guard, wake lock, DND check, contact-name lookup via `ContactsRepository.findContactByNumber`, `SipEngine.updateCallSessionName`, then on Main: `TelecomHelper.reportIncomingCall`, notification banner (re-pushed every 4s while backgrounded), starts MainActivity if backgrounded.
- `SipServiceNotification.kt`: channels `sip_service_v1` / `incoming_call_v4` / `missed_calls_v1`, ids 1001/1002/1003. `showCallNotificationStatic` (`:135-240`) uses `NotificationCompat.CallStyle` (`forIncomingCall` with answer/decline, `forOngoingCall` with hangup), chronometer for CONFIRMED, full-screen intent for ringing/active.
- `TelecomHelper` (`TelecomHelper.kt`): registers self-managed PhoneAccount (`:25-55`), `reportIncomingCall` (`:57`), `placeOutgoingCall` (`:80-118`) — **bypasses Telecom below Android 9** (buggy CTS on Vivo/Oppo; returns false and the VM falls back to `SipEngine.makeCall`).
- `BootReceiver`: restarts service on boot with `delayStartForeground=true` (FGS `phoneCall` forbidden from BOOT_COMPLETED on 12+).
- `CallHangupResolver` (`CallHangupResolver.kt:40-94`): incoming-ringing→REJECTED, outgoing pre-answer→CANCELED, confirmed→LOCAL.
- `RecordingManager`: WAV into `getExternalFilesDir(Music)/IPDialRecordings/`, filename `IPDial_<millis>_<uuid>.wav` (never the phone number — privacy).
- `SipLogger`: 500-entry in-memory ring buffer feeding the Activity Log screen; PJSIP `LogWriter` forwards native logs into it (`SipEngine.kt:357-366`).

---

## 6. UI layer

### 6.1 Navigation (`MainActivity.kt`)
Routes are a sealed class `NavDest` (`:419-434`). Three pager tabs
(Home / Keypad / Contacts) all render `MainPagerScreen`; everything else is a
detail screen pushed on the back stack. **When a call is active, the entire nav
host is replaced by `CallOverlay`** (`:670-712`) — `IncomingCallScreen` for
incoming ringing, `CallScreen` otherwise. `isCallScreenVisible()` at `:670` is the
single source of truth for call-overlay, volume-stream, and keep-screen-on logic.

- Pager ↔ nav sync is one-way (`LaunchedEffect` scrolls pager on route change,
  `:447-460`); swipes do NOT update the NavController (avoids multi-instantiating
  the pager → lag). Bottom bar highlights from `pagerState`.
- Intents handled in `MainActivity.handleIntent` (`:333-369`): `tel:` DIAL/VIEW/CALL,
  `ACTION_PROCESS_TEXT`, plus app-internal `com.ipdial.ACTION_INCOMING_CALL`/`SHOW_CALL`/`TEST_CALL`.
- Volume keys pass through to the system during calls (`:198-210`) so the system
  voice-call slider appears; `SipViewModel` mirrors stream changes via observer/poller.
- `AppState.isForeground` object (`:139`) — used to decide notifications vs in-app UI.

### 6.2 Screens
- `screens/home/` — HomeScreen (favorites row, grouped call log, search, ad banner), FavoritesSection, CallLogSection, SearchSection.
- `screens/dialpad/` — DialpadKeypad (3×4, haptic, styles Grid/Rounded/Ring), SuggestionsSection (T9 map in `SuggestionsSection.kt:22-31`).
- `screens/DialpadScreen.kt` — keypad tab page.
- `screens/CallScreen.kt` + `screens/call/{CallControls,CallStatus,InCallDialpad}.kt` — active call UI (audio route cycle, mute/speaker/hold/record, pulsing status, DTMF, wide/landscape layout).
- `screens/IncomingCallScreen.kt` — draggable Slider or Buttons accept/reject.
- `screens/SettingsScreen.kt` — settings list; routes to Logs, Codecs, Theme, IncomingCallStyle, Privacy, About.
- `screens/AccountsScreen.kt` — SIP account CRUD (ModalBottomSheet form, per-account toggle).
- `screens/AudioCodecScreen.kt`, `ThemeSettingsScreen.kt`, `DialpadStyleScreen.kt`,
  `IncomingCallSettingsScreen.kt` — preference screens.
- `screens/GetProScreen.kt` — Pro upsell: points, expiration, referral, device list, Google sign-in.
- `screens/ActivityLogScreen.kt`, `RecordingsScreen.kt`, `AboutScreen.kt`,
  `PrivacyPolicyScreen.kt` — misc.
- `screens/Utils.kt` — `cleanUri`/`cleanDisplayName`, `clickableWithRipple/NoRipple`, `formatDisplayNumber` (`01728-867896` style), `PhoneNumberTransformation`, TelegramSupportCard.

### 6.3 Components & menu
- `components/FloatingPillNavBar.kt` — floating bottom nav: Contacts, Dialpad, Home, More (menu). Hidden during calls.
- `components/AppMenuBottomSheet.kt` — full-screen menu sheet listing Accounts, Theme, Codecs, Recordings, Activity Log, Get Pro, About, Privacy, Incoming Call Style; includes **app-exit** (calls `SipConnectionService.destroyAll()` + `SipService.stop`), account balance chip, ad.
- `components/AdBanners.kt` — `StartIoBanner` (respects `adsEnabled`; Firestore custom ad if configured else Start.io banner for non-Pro), `CustomAdBanner` (720×90), `CustomAccountPageAd`.
- `components/AppBars.kt` (IPDialTopBar), `ContactAvatar.kt`, `Dialogs.kt`, `StatusIndicators.kt`.

### 6.4 Theme (`ui/theme/`)
- `enum GlassMode { None, Obsidian, Quartz }` (`IPDialTheme.kt:25`) + `LocalGlassMode`/`LocalGlassAlpha`.
- Palettes: `ForestGreen 0xFF1E6B3C` primary, `SageBackground`, `MintSurface`, `EndRed 0xFFD32F2F`, etc.
- `ThemeMode` (System/Light/Dark/Dynamic/Obsidian/Quartz) maps to schemes + glass mode. Dynamic/System use system dynamic color on API≥31.
- `Modifier.glass(...)` helper (`:223-239`): clip + translucent fill + 1dp border.
- Font scaling via `scaleTextStyle` (clamped to 1.25).
- ⚠ `themePreviewForMode` Light preview colors (`:124-131`) don't exactly match `LightColors` — cosmetic.

### 6.5 App icons (`util/AppIconHelper.kt`)
Aliases `MainActivity{Default,Green,Blue,Red}`; `setAppIcon` enables target + force-enables stable `MainActivity`; `forceEnableMainActivity` is an emergency recovery called from Application. Persisted via `repo.appIconAlias`.

---

## 7. SipViewModel (`ui/SipViewModel.kt`, ~1850 lines) — the UI state hub

`class SipViewModel(app) : AndroidViewModel(app)` (`:60`). Public `repo = AccountRepository(app)`.
The bridge between UI Compose and the service/data layers.

### State exposed (StateFlows)
- Settings mirrors from repo (`:96-141`), auth/identity (`:145-161` incl. `userDisplayId`),
  Pro/points (`:163-221` — **`isPro` = `proExpiration > now` via 30s ticker** `:200-215`;
  device slots informational only), audio processing prefs (`:223-238`).
- **`callSession` = `SipEngine.callSession`** (`:705`). Contacts + A-Z grouping (`:714-727`),
  most-called top-5 (`:777-802`), `activeAccount` selection (`:804-807`), dial string, volume, showFullIncomingScreen, isConnected, balances, ad state.

### init (`:811-989`)
Observes call session, disables NS if unsupported, mirrors Telecom route, connectivity callback,
fresh-install Pro welcome + deviceId, seeds volume, auth flows, FirestorePointsSync wiring
(`:921-940`), cached Pro relaunch (`:944-951`), device→UID migration (`:954-963`), auto re-add device.

### Call lifecycle (UI → engine)
`makeCall(overrideNumber)` (`:1344-1500`) — in-flight `AtomicBoolean` guard (double-tap),
builds `sip:num@host;transport=...` URI, routes through `TelecomHelper.placeOutgoingCall`
with 3s fallback to `SipEngine.makeCall` (emulator + OEM bypass), accounts dialog for multi-account.
`answerCall`/`hangup` (`:1518-1556`), mute/speaker/hold/volume/DTMF (`:1557-1607`),
`cycleAudioDevice`/`setAudioDevice` (`:1584-1627`), volume bridging subsystem
(observer + 200ms poller for OEM quirks, `:1000-1145`), call timeout/zombie/RTP-inactivity
watchdogs (`:1147-1259`).

### Monetization / ads
`triggerAd` (`:1749`; now shows a 10s banner — interstitials replaced), `watchRewardedAd`
(`:527-575`, signed-in only, +1 point, fallback interstitial), `showAdGate`/`triggerAdGate`
(queue one use), `showProBlockPopup`, recording gate `incrementRecordingAction` (every 5th →
ad), `fetchBalance` (`:1767-1842`) — ⚠ raw `HttpURLConnection` POST to 3 hardcoded billing
endpoints (`billing.webvoice.net`, `sip.amarip.net`) for account balance.

### Google / Pro
`signIn` (`:316-383`), `signOut` (`:385-395` → `removeLoggedInDevice` then `forceSignOutLocally`),
`deleteAccount` (`:415-431`), `claimReferral` (`:433`), `redeemPoints` (`:441-465`, ad-gated cost map),
`removeDevice`/`clearAllDevices` (`:475-501`).

---

## 8. Key end-to-end flows

### Startup
1. `IPDialApplication.onCreate` (`IPDialApplication.kt:12-89`): load `pjsua2` → Start.io init → `TelecomHelper.registerPhoneAccount` → `AppIconHelper.forceEnableMainActivity` → RemoteConfig + FirestoreAdConfig init → **early `SipService.start`** (inside the 5s FGS timeout).
2. `SipService.onCreate`: FGS → mic gate → helpers → engine wiring → `SipEngine.init` → register accounts → network callback → watchers → `observeCallState`.

### Outgoing call
`SipViewModel.makeCall` → Telecom `placeOutgoingCall` (API 28+) → `SipConnectionService.onCreateOutgoingConnection` → `SipEngine.makeCall` → PJSIP `onCallState` states (CALLING→EARLY→CONNECTING→CONFIRMED) pushed to `callSession` + `SipConnection` → `SipService.observeCallState` drives audio/wake/FGS/notification → `onCallMediaStateLocked` bridges devices + recorder. Hangup: resolver → BYE/CANCEL/603 → `onCallState` DISCONNECTED → disconnectInfo side-channel → Telecom cause → `disconnectCall` → delayed `scheduleSessionNull` (busy tone) → delayed native delete.

### Incoming call
`SipAccountDelegate.onIncomingCall` (worker thread, capture callId synchronously) → 180 Ringing → `CallSession(INCOMING)` → `onIncomingCall` → `SipIncomingCallHandler` → wake lock, contact lookup, `TelecomHelper.reportIncomingCall` → Telecom `onCreateIncomingConnection` (ghost guard) → ringtone/notification → answer → CONFIRMED flow.

### Network change
`SipService` default-network callback → active call: `handleIpChange` (re-register + re-establish media); idle: `reconnectOnNetworkChange` (rebind + recreate transports + re-add accounts).

### Call end
Session null → `SipService.observeCallState` logs call (Room, trimmed to 50 via VM), miss toast/notification, restore audio, release locks, Telecom `disconnectCall`.

---

## 9. Build, test, run

```bash
# Build debug
./gradlew :app:assembleDebug

# Sign + copy release APK to ~/Downloads
./gradlew copySignedApk   # needs signing env or keystore.properties

# Unit tests
./gradlew :app:testDebugUnitTest
```

- Only test today: `app/src/test/java/com/ipdial/service/CallHangupResolverTest.kt`.
- Lint is configured non-fatal (`abortOnError false`).
- Firebase: `app/google-services.json`, `firestore.rules`, `firebase.json` (hosting under `hosting/`), `.firebaserc`.
- Debug builds enable Start.io **test ads** and `BuildConfig.DEBUG` media dumps
  (gated by `-Dipdial.mediaDump=1`).

### App icon quirks when launching from Android Studio
The launch alias must be one of `MainActivity{Default,Green,Blue,Red}`. `forceEnableMainActivity` keeps the stable activity enabled so Studio always has a launch entry.

---

## 10. Gotchas / invariants checklist (read before editing)

1. **Never touch pjsua2 outside PjsipThread + `pjsipLock`** (`SipEngine.kt:29-51`). Worker-thread callbacks must hold `pjsipLock`; other threads must go through `runOnPjsipThread`/`registerCurrentThreadEx`.
2. **Capture `prm.callId` synchronously** in `SipAccountDelegate.onIncomingCall` (`:75`) — PJSIP mutates the param after the callback returns.
3. **`releaseSoundDevice()` must stay a no-op** (`SipEngine.kt:1345`).
4. **Two disconnect paths** (`onCallState` + `onCallTsxState`) and the **2s delayed native delete** are deliberate — the C++ call object must flush the 200 OK before `delete()`.
5. Audio tuning constants (`sndClockRate=48000`, `ecOptions=0`, `noVad`, jitter buffer) each fix a real device bug — do not "clean up" without the hardware to test.
6. `StateFlow` conflates values; the **`pendingDisconnectInfo` side-channel** exists so the final DISCONNECTED code/reason reaches slow collectors (`SipEngine.kt:242`).
7. **Room migrations are destructive** — schema bumps wipe all local data (`AppDatabase.kt:26`).
8. Firestore points use **transactions, never `FieldValue.increment()`** — rules reject it (`FirestorePointsSync.kt:202-207`).
9. `exportAccountsJson` emits **plaintext passwords** (`AccountRepository.kt:439`); syncing accounts JSON would leak credentials.
10. Incoming calls are answered 180 **before** the user sees the UI — logic that must block shouldn't be added in `SipAccountDelegate`.
11. Only `en` resources exist (`resConfigs "en"`); don't add locale resource folders.
12. `isPro` is **expiration-based**; `currentDeviceHasPro`/device slots are informational and must never revoke paid Pro.
13. Ad-gating is applied to `setThemeMode`, `setFontSize`, `setAppIcon`, `setKeypadDesign`, `setIncomingCallMode`, `setAutoRecord`-adjacent actions, codec changes, recording every-5th — keep the `!isPro && !adsEnabled` gating consistent if you touch these.

---

## 11. Current state: in-flight work & known debt (2026-09-12)

> ⚠ **The working tree has uncommitted changes — check `git status`/`git diff` before editing.** Multiple files are mid-refactor.

### Active refactor: device-slot simplification (`device_simplification_plan.md`)
Goal: remove "authorized/unauthorized" — all logged-in devices tracked, Pro = first N in `loggedInDevices`, remove device = sign out.

**Current compile-blocking issue:** `SipViewModel.clearAllDevices()` (`SipViewModel.kt:485-494`)
calls `firestoreSync?.clearAllDevices(uid)` but **`clearAllDevices` no longer exists**
in `FirestorePointsSync.kt` (only `addLoggedInDevice`, `removeLoggedInDevice`,
`getLoggedInDevices`, `addDeviceAndCheckPro`, `migrateFromDeviceId`). **The app will not compile until this is fixed.**

**Schema debt:** `onDeviceSlotsChanged` in `FirestorePointsSync.kt` still reads
`authorizedDevices`/`pendingDevices` (`:74-75, :130-131`) while writes use
`loggedInDevices` — the VM's device list renders empty. The callback signature
naming (`authorized/pending`) is stale; update to match the list model.

### Other known debt (from §4-6)
- `sipEcEnabled`/`sipNsEnabled` are dead repo flows never surfaced in the VM.
- `DEVICE_SLOT_COST = 100` and `SlotPurchaseResult` unused (`FirestorePointsSync.kt:513, 730`).
- `ui/screens/AlphabetIndexer.kt` is dead code (ContactsScreen has its own inline indexer).
- Duplicated comment block in `MainActivity.kt:184-193`.
- `NavDest.DialpadStyle` registered in nav but no entry from Settings.
- `ReadCoroutineScope`-style call: `SipViewModel.logCall` caps at 50 vs repo 200-trim.
- `onCodecAction` / `onAudioAction` are stubs in the VM.
- `fetchBalance` does HTTP in the ViewModel with hardcoded domain list (`SipViewModel.kt:62, 1767-1842`).
- `UpdateChecker` only strips a leading "v" from release tags.

### Bug tracker (`bug_tracking.md`)
1. Volume-slider consistency with default phone call volume.
2. IPDial→IPDial hangup propagation.
3. Device-management UI should show Brand/Model names.

### Roadmap-adjacent plans (repo root)
`conference_call_plan.md` (conference calls), `pjsip_upgradation_plan.md` (pjsua2 upgrade), `bug_fix_plan.md`, `device_ui_plan.md`.

---

## 12. Quick mental map for common tasks

| Task | Where to look |
|---|---|
| Change UI setting → persisted | Add repo key (`AccountRepository.kt:44-79`) + VM flow + setter (+ ad gate), UI collects in screen |
| Change call behavior | `service/SipEngine.kt` (native), `SipCallDelegate` (state/media), `SipService.observeCallState` (audio/notif) |
| New notification | `SipServiceNotification.kt` constants/functions |
| New screen | `NavDest` + `AppNavHost` composable in `MainActivity.kt` + file under `ui/screens/` |
| Change call log limits | `CallLogRepository.insert` (200) vs `SipViewModel.logCall` (50) |
| New Firestore data | `FirestorePointsSync.kt` (+ keep `firestore.rules` in sync) |
| Pro/points gating | `SipViewModel.isPro`, `triggerAd`/`triggerAdGate`/`showProBlockPopup` |
| Theme/glass changes | `ui/theme/IPDialTheme.kt` |