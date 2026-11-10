# User Issues Tracking Report

| # | User Report / Issue | Status | Technical Cause & Solution |
|---|---|---|---|
| **1 & 4** | এ্যাপ থেকে অটোমেটিক বের করে দেয় (App crashes / automatically exits on launch) | `[x]` | **Fixed:** Added `RECORD_AUDIO` permission checks in `IPDialApplication` and `SipService.start()` to prevent `SecurityException` and `ForegroundServiceDidNotStartInTimeException` on Android 12+/14+/15+. |
| **2 & 5** | Call যায়, আসে না / স্ক্রিন অফ থাকলে অফলাইন হয়ে যায় (Incoming calls fail when screen is off) | `[x]` | **Fixed:** Configured persistent foreground service keep-alive, wake locks, network reconnection callbacks, and PJSIP keep-alive in `SipService`. |
| **3 & 9** | Call katar button kaaj korche na (End call red button unresponsive or delayed) | `[x]` | **Fixed:** Removed click-lock restriction on `isEndingCall` in `CallScreen.kt` and `SipViewModel.hangup()`, ensuring immediate non-blocking call termination on click. |
| **6** | মিটিং মোডে থাকা অবস্থায় কল আসলে ভাইব্রেশন করে না (No vibration in meeting/silent mode) | `[x]` | **Fixed:** Updated `SipRingtonePlayer.kt` so incoming call vibration triggers even when the phone is in silent or meeting mode (`RINGER_MODE_SILENT` / `RINGER_MODE_VIBRATE`). |
| **7** | Add G729 (Add G.729 codec support) | `[x]` | **Fixed:** Enabled and prioritized G.729 codec in `PreferredCodec` and `SipEngine` SDP codec negotiation. |
| **8** | কল না ধরলে নাম্বার রিসেন্ট এ সেভ থাকে না (Unanswered outgoing call numbers missing from recents) | `[x]` | **Fixed:** Modified `SipViewModel.makeCall()` to log outgoing calls immediately to `CallLogRepository` upon dialing. |
| **9** | স্পিকার দিয়ে ব্যাকগ্রাউন্ডে গেলে কথা শোনা যায় না (Microphone mutes on speakerphone in background) | `[x]` | **Fixed:** Updated `SipService` to set `FOREGROUND_SERVICE_TYPE_PHONE_CALL` during `CallState.CONFIRMED`, preventing Android's background privacy manager from muting mic input on speakerphone. |
