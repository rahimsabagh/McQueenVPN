# McQueen VPN

**Version:** `1.0.0-beta.4` (`versionCode` 764)  
**Package ID:** `com.aistudio.mcqueenvpn.rmqvpn`

McQueen VPN is a minimalist, user-friendly Android VPN built with Kotlin and Jetpack Compose. It is designed around a one-tap workflow—open the app, pick a country, and tap Connect—while automatically managing server probing, dual-engine routing (Xray-core and Aether), first-run WARP identity registration, and same-country failover behind the scenes.

---

## Implemented Features

- **Country-Based One-Tap Connection:** Browse available countries synced from a remote HTTPS manifest (`servers.json`) and connect without managing raw links, UUIDs, subscriptions, or protocol internals.
- **Dual-Engine Tunnel Routing:**
  - **Xray-core** for VLESS, VMess, Trojan, Shadowsocks, Hysteria2, and WireGuard configurations.
  - **Aether** (`libaether.so`) for Cloudflare WARP / WireGuard, MASQUE, Psiphon-chained, and Tor-chained profiles.
- **Automatic Aether WARP Identity Provisioning:** On first use of an Aether profile, `AetherIdentityProvisioner` checks existing identities, registers missing WireGuard / MASQUE credentials in app-private storage (`filesDir/aether`), verifies readability, and reuses existing valid identities across reconnects.
- **Automatic Same-Country Failover & Tunnel Verification:** Probes candidate servers concurrently, prefers lowest-latency routes, verifies live tunnel connectivity, and falls back to alternative endpoints within the selected country if a connection attempt fails.
- **Remote Server Directory Sync:** Fetches `servers.json` over HTTPS on app launch and schedules a unique periodic WorkManager refresh every 30 minutes (best-effort, respecting Android battery optimizations and Doze). Supports custom HTTPS manifest URLs and an optional user-supplied Bearer token stored locally for private directories.
- **Live Telemetry & System Integration:** Displays live download/upload speeds and session duration, supports Dark / Light / System themes, Bypass LAN, Connect on Boot, Quick Settings Tile, and a Home Screen toggle widget.

---

## Requirements & Targets

- **Minimum Android Version:** Android 10 (API 29)
- **Target / Compile SDK:** API 36 (Android 16, 16 KB page-size aligned)
- **Production Native ABIs:** `arm64-v8a` (64-bit ARM, 16 KB aligned), `armeabi-v7a` (32-bit ARM) *(optional `x86_64` emulator development mode via `-PincludeX86_64=true`)*
- **Build Toolchain:** Java 17, Kotlin, Gradle Kotlin DSL, Jetpack Compose (Material 3)

---

## Build & Installation

### Build from Source

```bash
# Run JVM unit tests
./gradlew :app:testDebugUnitTest

# Build debug ABI split & universal APKs (signed with repository debug keystore)
./gradlew :app:assembleDebug

# Run Android Lint
./gradlew :app:lintDebug
```

> **Note for CI / Container Environments:** If the Gradle wrapper (`./gradlew`) is unavailable or restricted in your build environment, invoke the pre-installed `gradle` binary directly (e.g., `gradle :app:assembleDebug`).

### Install Pre-Built Beta APK (`v1.0.0-beta.4`)

1. Download the matching APK for your device from the release assets:
   - `McQueenVPN-v1.0.0-beta.4-arm64-v8a-release.apk` (recommended for 64-bit ARM phones/tablets)
   - `McQueenVPN-v1.0.0-beta.4-armeabi-v7a-release.apk` (for 32-bit ARM Android 10+ devices)
   - `McQueenVPN-v1.0.0-beta.4-universal-release.apk` (universal ARM fallback)
2. Verify the SHA-256 checksum against `SHA256SUMS.txt`.
3. Install on an Android 10+ device (enable installation from your browser or file manager if prompted, or install via `adb install McQueenVPN-v1.0.0-beta.4-arm64-v8a-release.apk`).

---

## Remote Manifest (`servers.json`) & Security

- **Public Default Branch:** `https://raw.githubusercontent.com/rahimsabagh/McQueenVPN/my-releases/servers.json`
- **No Embedded Credentials:** Real private VPN credentials and GitHub Personal Access Tokens are never embedded in the APK or committed to the public repository.
- **Private Manifest Support:** Users or operators can configure a custom HTTPS manifest URL and an optional Bearer token inside **Preferences → Server Directory Source**. Tokens are saved strictly in local device storage (MMKV) and transmitted only over HTTPS (loopback HTTP is permitted solely for automated local testing).
- **Cleartext Disabled:** `android:usesCleartextTraffic="false"` is enforced in `AndroidManifest.xml`.

---

## Known Limitations (`1.0.0-beta.4`)

- **Pre-release Beta Status:** This is an active beta release (`v1.0.0-beta.4`). Broad physical-device compatibility across OEMs, carriers, and restrictive network environments is still being validated.
- **Release Signing Keystore:** Release signing credentials are kept outside the public repository in GitHub Actions secrets (`RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`); `:app:assembleRelease` and `:app:bundleRelease` fail fast if signing credentials are not provided.
- **Background Manifest Sync Timing:** Periodic WorkManager synchronization is configured for 30-minute intervals, but exact execution timing is governed by Android battery optimizations and Doze mode.

---

## License

McQueen VPN is free software licensed under the **GNU General Public License v3.0 (GPL-3.0)**. See [`LICENSE`](LICENSE) for the full license text.

---

## Attribution & Open-Source Credits

McQueen VPN builds upon the work of these open-source projects:

- **[PattNG](https://github.com/patterniha/PattNG)** by `@patterniha` (GPL-3.0) — upstream Android client fork introducing Aether integration and reliability improvements
- **[v2rayNG](https://github.com/2dust/v2rayNG)** by `2dust` (GPL-3.0) — foundational Android V2Ray/Xray client architecture
- **[Xray-core](https://github.com/XTLS/Xray-core)** (MPL-2.0) — multi-protocol proxy and routing core
- **[Aether](https://github.com/CluvexStudio/aether)** by `CluvexStudio` (AGPL-3.0) — Cloudflare WARP / MASQUE / Psiphon / Tor tunnel client
- **Additional Libraries:** Psiphon, Tor / lyrebird, `hev-socks5-tunnel`, Tencent MMKV, OkHttp, Gson, Coil, and AndroidX / Jetpack Compose
