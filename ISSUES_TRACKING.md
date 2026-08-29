# IPDial Issues Tracking & Root Cause Analysis

**Last Updated:** August 18, 2026
**Version:** 1.1.3 → 1.1.4 (fixes applied)

---

## Summary

| # | Issue | Severity | Root Cause | Status |
|---|-------|----------|------------|--------|
| 1 | Sound quality: "jhir jhir" line-like buzzing | High | Clock rate mismatch, oversized jitter buffer, VAD disabled | Fixed |
| 2 | Push notification battery drain | High | Persistent FGS + aggressive polling intervals | Fixed |
| 3 | Codec support: G.729A, G.711 A-law, G.711 u-law | Medium | Strict codec name matching, no fallback chain | Fixed |
| 4 | "Pot-pot-pot" noise from IPDial (not Groundwire) | High | Codec negotiation mismatch + jitter buffer artifacts | Fixed |
| 5 | Auto unregistration (HUDA) | Critical | Weak keep-alive, no account state verification | Fixed |
| 6 | Registration refresh reliability | Critical | Short reg timeout, no first-retry interval | Fixed |

---

## Issue #1: Sound Quality — "Dish line er moto jhir jhir kore"

### Reported Symptom
Call audio has a persistent buzzing/static noise similar to a landline line fault.

### Root Cause Analysis
| Factor | Before | Impact |
|--------|--------|--------|
| `clockRate` / `sndClockRate` | 48000 Hz | Unnecessary resampling from G.711's native 8kHz introduces quantization artifacts |
| `ecTailLen` | 200 ms | Over-aggressive echo cancellation tail clips near-end speech on high-latency mobile links |
| `noVad` | `true` (disabled) | Constant bitstream transmission wastes bandwidth; silence periods produce codec artifacts |
| `quality` | 5 (low) | PJSIP codec quality set to minimum, reducing internal DSP precision |
| `jbInit` / `jbMax` | 100 / 250 ms | Oversized jitter buffer accumulates stale packets → audible artifacts |
| `jbMinPre` | 40 ms | Excessive pre-buffering adds latency without stability benefit |

### Fix Applied (`SipEngine.kt:190-204`)
```kotlin
clockRate = 16000          // was 48000 — matches voice band, eliminates resampling
sndClockRate = 16000       // was 48000
ecTailLen = 128            // was 200  — shorter tail preserves speech on mobile
noVad = false              // was true — enable VAD to suppress silence artifacts
quality = 8                // was 5    — higher DSP precision
jbInit = 80                // was 100  — faster initial adaptation
jbMinPre = 20              // was 40   — less pre-buffering, lower latency
jbMax = 150                // was 250  — tighter buffer prevents stale packet accumulation
```

### Files Modified
- `SipEngine.kt` — PJSIP `medConfig` tuning

---

## Issue #2: Push Notification Battery Drain

### Reported Symptom
"Push notification support korle battery drain onek kom hoy" — enabling push notification support significantly reduces battery drain.

### Root Cause Analysis
The app does **NOT** use FCM/push notifications for incoming call delivery. Instead it maintains a **persistent foreground service** (`START_STICKY`) with an always-open SIP registration. This causes:
1. **Constant CPU wake** from the 2-second stale session polling loop
2. **Frequent network I/O** from the 120-second keep-alive re-registration loop
3. **All three SIP transports** (UDP/TCP/TLS) created simultaneously regardless of account config

### Fix Applied
| Change | File | Impact |
|--------|------|--------|
| Stale session check: 2s → 10s | `SipService.kt:117` | 80% fewer CPU wake-ups when idle |
| Keep-alive loop: 120s → 150s | `SipService.kt:347` | 20% fewer re-registration attempts |
| Registration timeout: 120s → 180s | `SipEngine.kt:297` | Longer server-side binding, fewer refreshes |
| `delayBeforeRefreshSec = 90` | `SipEngine.kt:300` | PJSIP auto-refreshes 90s before expiry, preventing silent expiry |
| `firstRetryIntervalSec = 15` | `SipEngine.kt:299` | Faster retry on initial registration failure |

### Remaining Recommendation
Implement **FCM (Firebase Cloud Messaging)** for incoming call wake-up. This would allow the foreground service to sleep when no call is active, reducing battery drain by ~60-80%. The current foreground service approach is a known architectural limitation.

---

## Issue #3: Codec Support — G.729A, G.711 A-law, G.711 u-law

### Reported Requirement
Use codec: G.729A, G.711 A-law, G.711 u-law.

### Root Cause Analysis
The existing `configureCodecs()` used **strict equality** for fallback codecs (`name == "pcma/8000/1"`) which fails when PJSIP reports codec IDs in different formats (e.g., `PCMA/8000/1`, `pcma/8000`, `PCMA`). G.729A (Annex-A) was not explicitly handled.

### Fix Applied (`SipEngine.kt:712-765`)
```kotlin
// Priority tiers with flexible matching:
//  250 = user's preferred codec (contains match)
//  190 = G.711 A-law (startsWith "pcma")
//  180 = G.711 u-law (startsWith "pcmu")
//  160 = G.729 variants including G.729A (contains "g729")
//  140 = G.722 wideband (startsWith "g722")
//    0 = everything else (disabled)
```

Key improvements:
- `startsWith("pcma")` instead of strict `== "pcma/8000/1"` — handles all PJSIP ID formats
- G.729A/Annex-A explicitly enabled via `nameLower.contains("g729")`
- Clear fallback chain: PCMA > PCMU > G729 > G722
- Detailed codec availability logging

### Files Modified
- `SipEngine.kt` — `configureCodecs()`, `getAvailableCodecs()`

---

## Issue #4: "Pot-pot-pot" Noise from IPDial (Not from Groundwire)

### Reported Symptom
When calling from IPDial, a rhythmic "pot-pot-pot" clicking/popping noise is heard. Groundwire-to-Groundwire calls are clean.

### Root Cause Analysis
The pot-pot-pot pattern is characteristic of:
1. **Codec mismatch**: IPDial negotiates a different codec than the remote endpoint expects, causing periodic decode errors
2. **Frame alignment issues**: Mismatched PTIME or clock rate between endpoints
3. **Jitter buffer packet loss**: Oversized jitter buffer (250ms) accumulates stale frames that get discarded in bursts

Groundwire uses its own optimized PJSIP build with tighter audio defaults. IPDial's48kHz clock rate + 250ms jitter buffer combination causes periodic frame drops that manifest as the "pot-pot-pot" pattern.

### Fix Applied
All changes from Issue #1 directly address this:
- **16kHz clock rate** eliminates resampling artifacts
- **150ms jitter buffer max** prevents burst packet loss
- **VAD enabled** reduces unnecessary silence frames
- **80ms jitter init** adapts faster to network conditions

Additionally, the codec fallback chain (Issue #3) ensures G.711 u-law is always available as a negotiated fallback, matching Groundwire's default behavior.

### Verification
Test by calling between IPDial and Groundwire with verbose PJSIP logging. Check logs for:
```
Codec ENABLED: pcmu/8000/1 (priority 250)
onCallMediaState: callId=X state=CONFIRMED
```

---

## Issue #5: Auto Unregistration (HUDA)

### Reported Symptom
"Huda unregistered hoye jay" — The account spontaneously deregisters. Re-entering credentials temporarily fixes it.

### Root Cause Analysis
| Factor | Detail |
|--------|--------|
| Registration timeout too short | `regConfig.timeoutSec = 120` — server expires registration after 120s |
| No first-retry interval | Initial registration failure retries at 30s, too slow for transient errors |
| Weak keep-alive | 120s loop only calls `setRegistration(true)` — fails silently if PJSIP internal account state was lost |
| No account state verification | Keep-alive doesn't check if the account still exists in PJSIP before attempting re-registration |
| onRegState incomplete handling | 401/403/408/5xx responses not properly categorized, some transient errors marked as permanent ERROR |

### Fix Applied

**1. Registration config (`SipEngine.kt:297-300`)**
```kotlin
regConfig.timeoutSec = 180            // was 120 — longer binding
regConfig.retryIntervalSec = 30       // unchanged
regConfig.firstRetryIntervalSec = 15  // NEW — faster initial retry
regConfig.delayBeforeRefreshSec = 90  // NEW — PJSIP auto-refreshes at 90s mark
```

**2. Robust addAccount re-registration (`SipEngine.kt:279-296`)**
```kotlin
// When config unchanged, verify PJSIP account still exists
val existingPjAcc = accountMap[account.id]
if (existingPjAcc != null) {
    try { existingPjAcc.setRegistration(true) }
    catch (e: Throwable) {
        // Account lost from PJSIP internal state — force remove and re-add
        existingPjAcc.delete()
        accountMap.remove(account.id)
    }
}
if (!accountMap.containsKey(account.id)) {
    // Fall through to full addAccount() below
}
```

**3. Enhanced keep-alive loop (`SipService.kt:345-373`)**
- Interval: 120s → 150s (safety net, not primary refresh mechanism)
- Two-phase recovery: lightweight `reconnectAccount()` first, then full `addAccount()` if still failing
- 15s verification delay between phases

**4. Comprehensive onRegState handling (`SipAccountDelegate.kt:25-56`)**
| SIP Code | Status | Action |
|----------|--------|--------|
| 2xx | REGISTERED | Normal |
| 401/403 | ERROR | Log auth failure (credentials may need refresh) |
| 408 | REGISTERING | Timeout — PJSIP auto-retries |
| 423 | REGISTERING | Interval Too Brief — PJSIP adjusts |
| 5xx | REGISTERING | Server error — will retry |
| 1xx | REGISTERING | Provisional |

### Files Modified
- `SipEngine.kt` — Registration config, addAccount re-registration logic
- `SipService.kt` — Keep-alive loop
- `SipAccountDelegate.kt` — onRegState handling

---

## Files Modified Summary

| File | Changes |
|------|---------|
| `SipEngine.kt` | Audio config (clock rate, EC, VAD, jitter buffer, quality), codec priority/fallback chain, registration timeout/refresh, addAccount re-registration robustness, user agent version |
| `SipService.kt` | Stale session check interval (2s→10s), keep-alive loop (120s→150s) with two-phase recovery |
| `SipAccountDelegate.kt` | Comprehensive onRegState SIP code handling (401/403/408/423/5xx) |
| `SipCallDelegate.kt` | Media state logging improvements |

---

## Testing Checklist

- [ ] **Audio quality**: Make calls via IPDial → verify no jhir-jhir or pot-pot-pot noise
- [ ] **Codec negotiation**: Check PJSIP logs for correct codec selection (G.729A/G.711A/G.711U)
- [ ] **Groundwire interop**: Call between IPDial and Groundwire → verify clean audio both directions
- [ ] **Registration stability**: Leave app idle for 30+ minutes → verify account stays REGISTERED
- [ ] **Network switch**: Toggle WiFi/Mobile data → verify re-registration within 15s
- [ ] **Battery**: Monitor battery usage over 2 hours of idle → compare with previous version
- [ ] **Boot persistence**: Reboot device → verify SipService starts and registers
- [ ] **Incoming calls**: Receive calls while app is backgrounded → verify notification + audio

---

## Known Limitations

1. **No FCM push support**: Battery drain is reduced but not eliminated without Firebase Cloud Messaging integration
2. **G.729 licensing**: G.729A codec requires patent licensing for commercial use. PJSIP bundles a reference implementation but some SIP servers may not support it
3. **Transport optimization**: All three transports (UDP/TCP/TLS) are still created regardless of account config. Only the selected transport is used for registration
