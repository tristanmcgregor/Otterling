# Otterling

Samsung Knox parental-control app for Android 9+ (`minSdk 28`).

## Package rename migration (`au.com…familyguard` → `app.otterling`)

This is a **new install line**, not an in-place upgrade. The old `applicationId`
cannot receive signed updates from the new id, and Device Owner cannot be
transferred between packages.

1. On any phone still enrolled as Device Owner under
   `au.com.tbmcgregor.bwparker.familyguard`, clear uninstall blocks / remove
   device owner (debug build hook or `dpm remove-active-admin` on that old
   component), then uninstall:
   ```
   adb uninstall au.com.tbmcgregor.bwparker.familyguard
   ```
2. Install a **release**-signed APK whose `applicationId` is `app.otterling`
   (from the update host after a PASS, or a matching local release build).
3. Re-provision Device Owner from scratch (factory-reset / no accounts if the
   device requires it):
   ```
   adb shell dpm set-device-owner app.otterling/.admin.DeviceAdminReceiverImpl
   ```
4. Re-enable Content Filter VPN and confirm updates resolve to
   `https://vpn.bartholomew.help/updates/` for package `app.otterling`.

Kotlin source packages stay under the historical namespace; only the install id
changed. Do not ship further renames without repeating this full re-provision.

## Phase 1 setup

1. Install Android Studio with Android SDK 37.
2. Generate a Knox development key for `app.otterling`.
3. Download the current `knoxsdk.jar` from the Knox Developer Portal and place
   it at `app/libs/knoxsdk.jar`.
4. Copy `local.properties.example` to `local.properties`, set `sdk.dir`, and
   add `KNOX_LICENSE_KEY`. Alternatively, export `KNOX_LICENSE_KEY`.
5. Run `./gradlew assembleDebug`, then install on a Knox-capable Samsung test
   device.
6. Open the app and verify these Logcat tags:
   - `KnoxLicenseManager` confirms the activation request.
   - `KnoxLicenseReceiver` reports error code `0` for success.

The SDK classes are discovered reflectively during this bootstrap phase so the
project remains buildable before Samsung's proprietary JAR is downloaded. The
JAR is still declared as a compile-only dependency for direct API integration
in later phases.

## Phase 2 — Device Owner provisioning

Requires a factory-reset or never-configured test device (no Google account
added yet).

1. Install the debug APK: `adb install app/build/outputs/apk/debug/app-debug.apk`
2. Open the app once so the package is known to the system, then run the
   command shown in the "Device Admin / Device Owner" section (also shown in
   Logcat-free form here):
   ```
   adb shell dpm set-device-owner app.otterling/.admin.DeviceAdminReceiverImpl
   ```
3. Reopen the app and tap **Refresh status** — it should report
   `Device owner: true`.
4. If you need to uninstall/rebuild, first remove the admin or the app can't
   be uninstalled normally:
   ```
   adb shell dpm remove-active-admin app.otterling/.admin.DeviceAdminReceiverImpl
   ```

## Phase 3 — Tamper resistance

Applied automatically once Device Owner is active (see Phase 2). The "Phase 3 —
Tamper resistance" section has per-restriction toggles and an
"Enable all recommended protections" button for re-applying everything at once.
All of it is stock Android (`UserManager`/`DevicePolicyManager`), no Knox
license required:

- Block Safe Mode boot, factory reset, USB debugging, guest mode/additional
  users (`UserManager` restrictions).
- Block app uninstall for Otterling itself (`setUninstallBlocked`).
- **Protect apps from uninstall**: enter any other app's package name (e.g.
  `com.facebook.katana`) and tap **Protect** to block it too, via the same
  `setUninstallBlocked` call — it works for any package when called by the
  Device Owner, not just this app. Tap **Remove** to unprotect. The list is
  persisted in Room and re-applied by `ProtectionEnforcementService` after a
  reboot or if anything clears it.
- Record Device Admin disable attempts and periodic restriction drift in Room.
  Recent tamper events appear in Settings.

**Restrictions resetting on their own**: Android can momentarily clear Device
Owner restrictions (e.g. right after an app update); `ProtectionEnforcementService`
normally catches and reapplies this within seconds while the app is running.
But Samsung's battery management can kill/freeze that foreground service
while the app isn't open, so a restriction cleared in the background could
stay cleared until you next open the app. `RestrictionEnforcementWorker` is a
`WorkManager` job (every 15 minutes, independent of the app process) that
re-checks and reapplies restrictions/blocked-apps/protected-apps as a backup.
For best reliability, also exclude Otterling from Samsung's
battery optimization: Settings → Apps → Otterling → Battery →
**Unrestricted**, and remove it from any "Sleeping apps"/"Deep sleeping
apps" list under Settings → Battery and device care.

**Physical Samsung test results**:

- Safe Mode hardware-key boot: **blocked successfully** while Otterling
  was Device Owner.
- Bootloader recovery-menu factory reset (power + volume-up): **not yet
  tested** because this path is destructive. Some MDM vendors report that this
  Samsung-specific path may need Knox `RestrictionPolicy`; test it on a
  disposable/factory-reset-ready device before relying on stock
  `DISALLOW_FACTORY_RESET` alone.

## Phase 4 — Content filtering

Also stock Android, no Knox required. In the "Phase 4 — Content filtering"
section:

- **DNS content filter**: forces CleanBrowsing's free family-filter
  DNS-over-TLS resolver via `setGlobalPrivateDnsModeSpecifiedHost`. Blocks
  adult/proxy/VPN domains and forces Safe Search on Google/Bing/YouTube in one
  call. Requires Android 10+ (API 29); shows a status message on API 28.
- **Blocked apps**: enter a package name (e.g. `com.facebook.katana`) and tap
  **Block** to suspend it via `setPackagesSuspended`. Toggle or remove
  entries from the list below. The list is persisted in a local Room database
  so it can be re-applied after a reboot.

Note: Scheduled access windows (time-based allow/block rules) were removed —
not needed for this app.

Note: Phase 5 (usage logging & reporting — usage stats collection, the
"Today's usage" list, and the daily summary notification) was removed —
not needed for this app. `ProtectionEnforcementService` (formerly
`UsageTrackingService`) still runs as a foreground service to reapply the
Phase 3 restrictions and Phase 4 blocked-app list if anything clears them;
it just no longer collects usage stats. Started on app open, Device Owner
enable, and `BOOT_COMPLETED` (via `BootCompletedReceiver`).

## Phase 6 — PIN lock & consolidated settings

The home screen is now a minimal status view (Device Owner state + an
**Open Settings** button). Tapping it always requires a PIN:

- **First time**: you're walked through creating a PIN (enter + confirm, 4-8
  digits).
- **After that**: entering the correct PIN unlocks the Settings screen, which
  hosts every remaining control (Device Owner, tamper resistance, content
  filtering, Knox setup). Leaving Settings via **Back** re-locks it — the
  PIN is required again next time.
- **Change PIN**: at the bottom of Settings; clears the current PIN and
  immediately prompts you to set a new one.

The PIN itself is never stored — only a salted PBKDF2 (120k iterations,
SHA-256) hash, protected by `EncryptedSharedPreferences`.

## Phase 7 — NSFW cloud filter VPN (Canopy-style)

`VpnFilterService` is registered as the device's mandatory always-on VPN with
lockdown enabled (`DevicePolicyManager.setAlwaysOnVpnPackage(..., lockdownEnabled = true)`),
via `VpnFilterManager`. With lockdown on, Android refuses all network access to
anything other than this VPN, so a second VPN app installed on the device
cannot get network access to establish its own tunnel — there is no bypass
path around the filter via another VPN.

DNS is filtered in two layers:

1. **Local, always-on**: every query is checked against a downloaded
   adult-domain blocklist (`DomainBlocklistManager`, defaulting to StevenBlack's
   porn-only list + The Blocklist Project's porn list) — this applies
   regardless of network conditions.
2. **Cloud, primary**: anything not already locally blocked is forwarded to a
   configurable cloud filter server (`CloudFilterSettings`, set from Settings →
   Content Filter VPN → "Cloud filter server") — see `filter-server/` for the
   AdGuard Home Docker stack to deploy. If the cloud filter is unset or
   unreachable, queries fall back to a hardcoded public resolver instead of
   failing outright; the local list from step 1 still applies either way.

Known public DoH/DoT resolver IPs (Cloudflare, Google, Quad9, OpenDNS) are
refused outright so apps can't dodge filtering by hardcoding their own
encrypted resolver.

**Verification checklist** (needs a physical Knox/Device-Owner test device —
not yet run against real hardware):

1. With Device Owner active, enable the Content Filter VPN in Settings — the
   app's "Lockdown" status row should read **Active**.
2. Install a second VPN app and try to connect — it should fail to establish
   any tunnel while lockdown holds.
3. With a cloud filter server configured and reachable: adult domains resolve
   to NXDOMAIN; ordinary sites resolve normally.
4. Stop the cloud filter server briefly: known adult domains from the local
   list still get NXDOMAIN; other domains keep resolving via the fallback
   resolver.
5. Apps added to the VPN bypass list (e.g. Android Auto) still work and are
   not filtered.

## Phase 8 — Gated app updates

Once the VPN lockdown + filter proxy (Phase 7) are in place, the app itself becomes the weakest
link: `adb install -r` (or any sideload) of a self-built APK with the lockdown/proxy-fail-closed/CA
-install/blocklist code quietly stripped out would install and run exactly like the real thing,
undoing everything above. Phase 8 closes that: **the app can only update itself via a build that
was AI-reviewed against a deny checklist on the update host (not via git), then signed with a
release key that never touches the daily account.**

- **Server release gate** (GitHub webhook → root `release.sh`) -- push to `main` hits
  `https://vpn.bartholomew.help/hooks/github`, which pulls that commit, reviews against the pinned
  `/var/lib/otterling/ci/checklist.md`, requires AI `VERDICT: PASS`, signs the APK, publishes under
  `/var/lib/otterling/updates/`, and posts a GitHub commit status (`otterling/release`).
  See [`filter-server/SELF_LOCKOUT.md`](filter-server/SELF_LOCKOUT.md).
- [`.github/workflows/update-review.yml`](.github/workflows/update-review.yml) is advisory only —
  it must not sign or publish.
- [`ApprovedUpdateManager`](app/src/main/java/app/otterling/updates/ApprovedUpdateManager.kt)
  is the *only* code path on the phone that installs anything. Settings → **App updates** → "Check
  for update" fetches `manifest.json`, downloads the referenced APK, and verifies (a) its SHA-256
  matches the manifest and (b) its signing certificate fingerprint matches
  `BuildConfig.RELEASE_CERT_SHA256` (baked in at build time from release secrets) before
  installing via a self-delegated `PackageInstaller` session -- no "install unknown apps" prompt,
  and no fallback path that installs an unverified file. A build with no pinned certificate
  refuses to install any update at all. "Request update" just opens a browser to file a GitHub
  issue and can SMS-alert a configured contact -- it cannot install anything by itself.

**Setup vs. production install paths**: `./gradlew :app:installDebug` (or any direct `adb install`)
is fine for initial device setup/provisioning and local development, but is **not** the production
update path once a device is actually deployed -- enable the existing "Block USB debugging"
restriction (Phase 3) on a production device so sideloading itself requires disabling a
restriction first, not just a phone plugged into a laptop. The signed, gated pipeline above is
what a deployed device should actually update through.

**Required secrets** live on the host under `/var/lib/otterling/ci/secrets.env` (and the
keystore under `/var/lib/otterling/ci/secrets/`), not in GitHub Actions and not in
`local.properties` on a daily account. See [`filter-server/SELF_LOCKOUT.md`](filter-server/SELF_LOCKOUT.md).

## Secret handling

`local.properties` and `app/libs/*.jar` are ignored. Never commit Knox license
keys.
fahhhhhhhhh