# DialDuo Privacy Policy

**Last updated: November 2026**

DialDuo is a privacy-first SIP and SIM phone dialer for Android. The app is designed to run locally on your device without user accounts, cloud tracking, analytics SDKs, or third-party advertisements.

## 1. Information We Do Not Collect

DialDuo does **not** operate developer-hosted servers to collect, store, or sell your personal data. We do not collect analytics, crash telemetry, advertising identifiers, or call content.

## 2. Data Stored Locally on Your Device

All app data stays in app-private storage on your device:

- **SIP & TURN Account Credentials:** Server addresses, usernames, and passwords you configure are stored locally. Sensitive passwords (SIP and TURN passwords) are encrypted at rest using AES-256-GCM keys managed by the Android Keystore.
- **Call History & Statistics:** Unified SIP and device call logs and the statistics/charts computed from them are processed and stored locally on your device. You can export or import call history as a JSON file at your discretion.
- **Call Recordings:** When you record a call or enable auto-recording, audio files are saved only in app-private storage on your device until you share or delete them.
- **Preferences & Diagnostics:** App themes, dialpad styles, codec preferences, and local activity logs are stored on-device.

## 3. Permissions We Use

DialDuo requests Android permissions strictly for core calling features:

- **Microphone (`RECORD_AUDIO`):** Required to transmit your voice during VoIP calls and capture audio if you record a call.
- **Contacts (`READ_CONTACTS`, `WRITE_CONTACTS`):** Used to display your phonebook, match caller names and photos, and edit or delete contacts when you initiate those actions.
- **Phone State, Calling & Call Log (`READ_PHONE_STATE`, `CALL_PHONE`, `MANAGE_OWN_CALLS`, `READ_CALL_LOG`, `WRITE_CALL_LOG`):** Used to manage SIM and SIP calls through Android Telecom, display unified call history, and act as your Default Phone App when selected.
- **Notifications & Full-Screen Alerts (`POST_NOTIFICATIONS`, `USE_FULL_SCREEN_INTENT`):** Used to show incoming call screens, ongoing call controls, and persistent SIP registration status.
- **Run at Startup (`RECEIVE_BOOT_COMPLETED`):** Restarts background SIP registration after your device reboots so you can continue receiving calls.
- **Install Updates (`REQUEST_INSTALL_PACKAGES`):** Used only when you choose to download and install a signed update APK from GitHub Releases.

## 4. Network Connections & Third-Party Services

DialDuo only initiates network connections directly required by features you use:

- **Your SIP, STUN, and TURN Providers:** Voice media, SIP registration, and optional account balance queries are sent directly to the providers you configure, under their respective privacy policies.
- **GitHub Releases (`github.com` / `api.github.com`):** Used only to check for newer public DialDuo releases and download signed APK updates when initiated.
- **Other Calling & Messaging Apps:** Contact Details may detect compatible apps installed on your device (such as SMS, WhatsApp, Telegram, Signal, Google Meet, Viber, or Messenger) and open them only when you tap an action.

## 5. Your Choices & Control

- You can revoke any permission at any time in Android **Settings → Apps → DialDuo → Permissions**.
- You can remove SIP accounts, clear call history, delete call recordings, or reset app preferences directly inside DialDuo.
- Uninstalling DialDuo removes all locally stored app-private data.

## 6. Changes to This Policy

We may update this Privacy Policy as DialDuo evolves. The **Last updated** date at the top indicates the latest revision.
