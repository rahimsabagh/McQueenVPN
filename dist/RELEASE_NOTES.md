# McQueen VPN v1.0.0-beta.1

**Release Tag:** `v1.0.0-beta.1`  
**Version:** `1.0.0-beta.1` (`versionCode` `761`)  
**Application ID:** `com.aistudio.mcqueenvpn.rmqvpn`  
**License:** GNU General Public License v3.0 (`GPL-3.0`)

---

## Overview

`v1.0.0-beta.1` is the first independently branded beta release of **McQueen VPN**, preserving the working dual-engine VPN core, automatic Aether WARP identity provisioning, country-based one-tap selection, and remote manifest synchronization while updating all user-facing launcher, in-app, Quick Settings, and notification tray branding.

---

## Highlights & Changes

### Branding, Launcher & Notification Tray Icons
- **Custom McQueen Launcher & Brand Artwork:** Applied the minimalist twin-peak `M` ridge and cyan-to-cobalt speed swoosh on dark obsidian navy (`#0A0E17`) across adaptive icons (`ic_launcher`, `ic_launcher_round`, Android 13+ `<monochrome>` themed icon), legacy density PNGs (`mdpi`–`xxxhdpi`), store/web icons, and the in-app header emblem (`ic_mcqueen_mark`).
- **Complete Notification & Quick Settings Icon Replacement:**
  - Created a dedicated pure-white, transparent-background monochrome `VectorDrawable` (`res/drawable/ic_stat_mcqueen.xml`, `24dp x 24dp`).
  - Updated `NotificationManager` (both initial foreground notification and live speed updates), `NotificationHelper`, `QSTileService`, and `AndroidManifest.xml` to use `ic_stat_mcqueen`.
  - Removed all obsolete PattNG `ic_stat_name`, `ic_stat_name_black`, `ic_stat_direct`, and `ic_stat_proxy` PNG assets so no legacy `"P"` artwork appears in the status bar, notification drawer, or Quick Settings tray.
- **Branding Cleanup:** Cleaned user-facing PattNG/v2rayNG copy, `AppConfig.kt` repository/issue/privacy URLs (`rahimsabagh/McQueenVPN`), `fastlane` store metadata, and `README.md` while keeping GPL-3.0 licensing and upstream attribution intact.

### Preserved Core VPN Functionality
- **Dual-Engine Routing:** Xray-core (`libgojni.so` from pinned `AndroidLibXrayLite` `v26.11.20`) and Aether (`libaether.so`, `libpsiphon-tunnel-core.so`, `liblyrebird.so`, `libhev-socks5-tunnel.so`, `libhevsockstun.so`).
- **Aether WARP Identity Provisioning:** Automatic first-run WireGuard / MASQUE / secondary MASQUE registration, verification, and persistent reuse in `filesDir/aether`.
- **Remote Server Directory Sync:** Startup sync and unique 30-minute WorkManager periodic sync against `servers.json` (with custom HTTPS endpoint and local Bearer token support).

---

## Build & Verification Summary

| Check | Result | Details |
| --- | --- | --- |
| JVM Unit Tests (`:app:testDebugUnitTest`) | Passed | `34` tests across `7` suites (`0` failures, `0` errors, `0` skipped) |
| Android Lint (`:app:lintDebug`) | Passed | `0` errors (`722` pre-existing warnings) |
| Debug APK (`:app:assembleDebug`) | Built & Verified | `dist/McQueenVPN-v1.0.0-beta.1-debug.apk` (`195 MiB`), APK Signature Scheme v2 verified (`debug.keystore`) |
| Release APK (`:app:assembleRelease`) | Built (Unsigned) | `app/build/outputs/apk/release/app-release-unsigned.apk` (`166 MiB`) — release keystore not stored in repository |
| Emulator / Physical Device / ADB | Not Run | No emulator or ADB device is available in this build container |

---

## Artifact Checksums (SHA-256)

- `McQueenVPN-v1.0.0-beta.1-debug.apk`  
  `917609a37ba6fca57412124b5166b22bb78b78ec219ca9eade2395d505493b3d`
- `app-release-unsigned.apk` *(unsigned release build)*  
  `8c477d5494929ac406375e36c81f6277fdcc508703c1f688b9d14666d73fc8e0`

---

## Known Beta Limitations

- **Debug-Signed Distribution:** Pre-built beta APK in `dist/` is signed with the repository debug key (`CN=Android Debug, O=Android, C=US`). Release signing requires supplying your production keystore during release CI or local `apksigner` signing.
- **On-Device Notification & Tunnel Check:** Automated JVM tests, Lint, `aapt2`, and `apksigner` checks ran cleanly in the container; live status-bar icon rendering and tunnel connections on physical Android hardware should be smoke-tested on device prior to public rollout.

---

## Attribution & License

McQueen VPN is licensed under the **GNU General Public License v3.0 (GPL-3.0)** (`LICENSE`) and builds upon **[PattNG](https://github.com/patterniha/PattNG)**, **[v2rayNG](https://github.com/2dust/v2rayNG)**, **[Xray-core](https://github.com/XTLS/Xray-core)**, and **[Aether](https://github.com/CluvexStudio/aether)**.
