<div align="center">

# 📞 IPDial

**A clean, modern, open-source SIP softphone for Android.**

[![Latest release](https://img.shields.io/github/v/release/nazimunaeem/IPDial?style=flat-square&logo=github&label=latest)](https://github.com/nazimunaeem/IPDial/releases/latest)
[![Total downloads](https://img.shields.io/github/downloads/nazimunaeem/IPDial/total?style=flat-square&logo=github&label=downloads)](https://github.com/nazimunaeem/IPDial/releases)
[![Stars](https://img.shields.io/github/stars/nazimunaeem/IPDial?style=flat-square&logo=github&label=stars)](https://github.com/nazimunaeem/IPDial/stargazers)
[![Forks](https://img.shields.io/github/forks/nazimunaeem/IPDial?style=flat-square&logo=github&label=forks)](https://github.com/nazimunaeem/IPDial/forks)
[![Issues](https://img.shields.io/github/issues/nazimunaeem/IPDial?style=flat-square&logo=github&label=issues)](https://github.com/nazimunaeem/IPDial/issues)
[![Last commit](https://img.shields.io/github/last-commit/nazimunaeem/IPDial?style=flat-square&logo=github)](https://github.com/nazimunaeem/IPDial/commits/main)
[![License](https://img.shields.io/github/license/nazimunaeem/IPDial?style=flat-square&label=license)](LICENSE)

![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.x-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)
![PJSIP](https://img.shields.io/badge/PJSIP-2.5-1E6B3C?style=flat-square)
![Min SDK](https://img.shields.io/badge/minSdk-26-3DDC84?style=flat-square)

[**⬇️ Download the latest APK**](https://github.com/nazimunaeem/IPDial/releases/latest) · [Report a bug](https://github.com/nazimunaeem/IPDial/issues/new) · [Support the developer](https://www.supportkori.com/nazimunaeem)

</div>

---

IPDial is a clean, modern, and high-performance SIP (Session Initiation Protocol) softphone for Android. Built with the latest Android technologies, it provides a seamless VoIP experience with a focus on privacy, simplicity, and ease of use.

## 📥 Download

<a href="https://github.com/nazimunaeem/IPDial/releases/latest">
  <img src="https://img.shields.io/badge/Download_Latest_APK-1E6B3C?style=for-the-badge&logo=android&logoColor=white" alt="Download latest APK">
</a>

- **Latest version:** [![Latest release](https://img.shields.io/github/v/release/nazimunaeem/IPDial?style=flat-square&label=)](https://github.com/nazimunaeem/IPDial/releases/latest) · **latest downloads:** [![Latest downloads](https://img.shields.io/github/downloads/nazimunaeem/IPDial/latest/total?style=flat-square&label=)](https://github.com/nazimunaeem/IPDial/releases/latest)
- **All versions:** [GitHub Releases](https://github.com/nazimunaeem/IPDial/releases)
- **Total downloads:** [![Total downloads](https://img.shields.io/github/downloads/nazimunaeem/IPDial/total?style=flat-square&label=)](https://github.com/nazimunaeem/IPDial/releases)
- **Requirements:** Android 8.0+ (API 26) on a 64-bit ARM (arm64-v8a) device

> Every release is built and signed automatically by GitHub Actions. The APK is safe to sideload; enable **Install unknown apps** for your browser or file manager when prompted.

## 🚀 Key Features

- **Modern Interface**: A clean, responsive design featuring a Forest Green theme that's easy to navigate.
- **High-Quality Audio**: Crystal clear voice calls with support for high-definition audio standards.
- **Advanced Sound Clarity**: Built-in Echo Cancellation and Noise Suppression to ensure you're heard clearly even in noisy environments.
- **Manage Multiple Accounts**: Easily add and switch between multiple SIP/VoIP providers within a single app.
- **Complete Call Control**: Full support for Mute, Speakerphone, Hold, and an in-call Dialpad for navigating automated menus.
- **Smart Call History**: Organized history grouped by date (Today, Yesterday, etc.) with quick filters for missed, dialed, and received calls.
- **Contact Integration**: Works seamlessly with your phone's existing contacts for quick dialing and easy identification.
- **Call Recording**: Record important conversations directly to your device with a built-in recording manager.
- **Stay Updated**: Automatic notifications for new versions to ensure you always have the latest features and security fixes.
- **Reliable & Fast**: Optimized for performance to ensure fast loading and minimal battery impact.

## 🧱 Tech Stack

| Area | Technology |
| --- | --- |
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| SIP / media | PJSIP 2.5 (`pjsua2`) |
| Persistence | Room + DataStore |
| Calls on Android | Telecom `ConnectionService` (self-managed SIP account) |
| CI / releases | GitHub Actions (signed APK + AAB) |

## 🤝 Open Source & Support

IPDial is an open-source project. If you find it useful, please **star the repository** — it helps others discover the project.

- ⭐ [Star this repo](https://github.com/nazimunaeem/IPDial/stargazers)
- 🐛 [Report a bug or request a feature](https://github.com/nazimunaeem/IPDial/issues)
- 🔀 [Fork and contribute](https://github.com/nazimunaeem/IPDial/fork)

<a href="https://www.supportkori.com/nazimunaeem"><img src="https://img.shields.io/badge/Support%20Kori-Support%20the%20Developer-1E6B3C?style=for-the-badge" alt="Support Kori"></a>

<a href="https://star-history.com/#nazimunaeem/IPDial&Date">
  <img src="https://api.star-history.com/svg?repos=nazimunaeem/IPDial&type=Date" alt="Star history" width="600">
</a>

---

## 👨&zwj;💻 Developer Information

**NAZIM U. NAEEM**

[![Facebook](https://img.shields.io/badge/Facebook-1877F2?style=for-the-badge&logo=facebook&logoColor=white)](https://facebook.com/nazimunaeem1)
[![Telegram](https://img.shields.io/badge/Telegram-2CA5E0?style=for-the-badge&logo=telegram&logoColor=white)](https://t.me/IPDial)

### ☕ Support the Development
> "Fuelling innovation one sip at a time. Support the development and help us keep the lines open!"

---

## 🌐 NAT Traversal / TURN Setup

Calls between two ordinary broadband/Wi-Fi connections connect directly (host +
STUN candidates) and never touch a TURN relay. But on **symmetric NAT,
carrier-grade NAT (CGNAT), or UDP-blocking networks** (common on mobile data and
some corporate/campus networks) a direct connection is impossible, and a TURN
relay is required to carry the media — without one, calls fail with one-way or
no audio.

**Default relay: Open Relay Project (metered.ca) — Free plan, 20 GB/month.**

- Sign up at [metered.ca](https://www.metered.ca/) and copy the TURN
  **username** and **password** from the dashboard.
- In IPDial → **SIP Accounts** → open an account → **Advanced — NAT Traversal**:
  - **TURN server** is pre-filled with `staticauth.openrelay.metered.ca:80`.
  - Paste your **TURN username** and **TURN password** from the dashboard.
  - Leave username/password blank to skip TURN entirely (the default for
    accounts that already work fine).
- Only calls that **need** the relay consume quota — calls that connect directly
  use 0 bytes. If the 20 GB/month is exhausted, only the harder-NAT subset of
  calls degrades until the monthly reset; direct calls are unaffected.

**TURN transport (Advanced):** the pre-filled server uses UDP. On networks that
block UDP outright, switch the account's **TURN Transport** to **TCP** (port 80)
or **TLS** (port 443) to force the relay over a TCP connection that passes more
restrictive firewalls.

**Self-hosting later:** if usage outgrows the free tier, a self-hosted
[coturn](https://github.com/coturn/coturn) server is a drop-in replacement — just
point the same **TURN server / username / password** fields at your own host.

> Note: credentials are stored encrypted at rest using the Android Keystore, the
> same way SIP account passwords are handled.

---

## 📄 License

Distributed under the MIT License. See [LICENSE](LICENSE) for details.
