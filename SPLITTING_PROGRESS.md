# Splitting Progress

Tracked state for the "split big files" effort. Reference plan: `SPLITTING_PLAN.md`.
Build verified green: `./gradlew :app:assembleDebug` (BUILD SUCCESSFUL).

## Status

| Phase | Target | Status | Notes |
|-------|--------|--------|-------|
| A | CommonComponents / Dialpad / CallScreen | **DONE** | Build verified |
| B1 | HomeScreen.kt → `home/` | **DONE** | Build verified |
| B2 | SettingsScreen.kt → `settings/` | pending | |
| C | MainActivity navigation/components | pending | |
| D | SipViewModel delegate extraction | pending | |
| E | SipEngine / SipService extraction | pending | |
| — | Final lint + verification | pending | |

## Phase A — COMPLETED

### CommonComponents.kt (740 lines) → `ui/components/` (package `com.ipdial.ui.components`)
Deleted original file. New files:
- `ContactAvatar.kt` — ContactAvatar, ContactItem
- `AdBanners.kt` — StartIoBanner, CustomAdBanner, CustomAccountPageAd
- `StatusIndicators.kt` — RegStatusIndicator, EmptyState, DotGreen/DotRed/DotAmber/DotGrey, ColorPro
- `AppBars.kt` — IPDialTopBar
- `Dialogs.kt` — NumberPickerDialog, AccountSelectionDialog

Also fixed a latent bug: `EmptyState` was dead code nested inside `StartIoBanner` (missing closing brace); extracted as a proper top-level composable.
Updated imports/FQ references in: MainActivity, ActivityLogScreen, IncomingCallScreen, GetProScreen, PrivacyPolicyScreen, AccountsScreen, AudioCodecScreen, RecordingsScreen, DialpadScreen, AboutScreen, HomeScreen, SettingsScreen, ThemeSettingsScreen, ContactsScreen.

### DialpadScreen.kt (517 lines) → `ui/screens/dialpad/` (package `com.ipdial.ui.screens.dialpad`)
- `DialpadKeypad.kt` — DialpadKeypad (extracted grid), DialKey, DialKeyRounded
- `SuggestionsSection.kt` — SuggestedContactRow, T9_MAP (`internal`)
- `DialpadScreen.kt` rewritten — main composable only (~300 lines); keypad grid replaced by `DialpadKeypad(keys, design, onKeyPress, onZeroLongPress)`.

### CallScreen.kt (490 lines) → `ui/screens/call/` (package `com.ipdial.ui.screens.call`)
- `CallControls.kt` — CallControls, CallControlButton
- `InCallDialpad.kt` — InCallDialpad
- `CallStatus.kt` — PulsingStateLabel, formatDuration
- `CallScreen.kt` rewritten — main composable only (~225 lines).
- HomeScreen.kt: added `import com.ipdial.ui.screens.call.formatDuration`.

## Phase B1 — COMPLETED

HomeScreen.kt (813 lines) → `ui/screens/home/` (package `com.ipdial.ui.screens.home`). Deleted original file. New files:
- `CallLogSection.kt` — LogGroup, CallLogRow, EmptyLogPrompt, formatTime, CallHistoryDetailDialog
- `FavoritesSection.kt` — FavoriteContactsRow, FavoriteContactChip
- `SearchSection.kt` — SearchBarRow
- `HomeScreen.kt` (rewritten) — main composable only (~270 lines)

Key facts confirmed during the split:
- `SearchBarRow` used by `ContactsScreen.kt:140` → added `import com.ipdial.ui.screens.home.SearchBarRow`.
- `CallLogSection.kt` imports `cleanUri`/`cleanDisplayName` (`ui/screens/`) and `formatDuration` (`ui/screens/call/`).
- `MainActivity.kt:119` import updated to `com.ipdial.ui.screens.home.HomeScreen`.
- Dropped dead private `isSameDay` (HomeScreen.kt:661, unused).
- Replaced FQ references (`com.ipdial.ui.components.ContactAvatar`, `com.ipdial.ui.theme.*`, etc.) with imports.

## Conventions used so far
- New sub-packages: `com.ipdial.ui.components`, `com.ipdial.ui.screens.dialpad`, `com.ipdial.ui.screens.call`, `com.ipdial.ui.screens.home`.
- Extracted composables keep identical signatures; only declarations move + imports updated.
- After each phase: `./gradlew :app:compileDebugKotlin` then `./gradlew :app:assembleDebug`.

## Working-tree state
Not committed. `git status` shows:
- Deleted `app/src/main/java/com/ipdial/ui/CommonComponents.kt`
- Deleted `app/src/main/java/com/ipdial/ui/screens/HomeScreen.kt`
- Modified screens (import updates) + MainActivity + ContactsScreen
- New untracked dirs: `ui/components/`, `ui/screens/call/`, `ui/screens/dialpad/`, `ui/screens/home/`
- Pre-existing unrelated: `app/.DS_Store`
