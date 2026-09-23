# IPDial — Open-Source SIP Client for Android

IPDial is a clean, modern, and high-performance SIP (Session Initiation Protocol) softphone for Android. Built with the latest Android technologies, it provides a seamless VoIP experience with a focus on privacy, simplicity, and ease of use.

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

---

## 🤝 Open Source & Support

IPDial is an open-source project. If you find this project useful, consider supporting the developer by starring the repository or sharing it with others.

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

Distributed under the MIT License.
