# Family Device Guard

Samsung Knox parental-control app for Android 9+ (`minSdk 28`).

## Phase 1 setup

1. Install Android Studio with Android SDK 37.
2. Generate a Knox development key for
   `au.com.tbmcgregor.bwparker.familyguard`.
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
   adb shell dpm set-device-owner au.com.tbmcgregor.bwparker.familyguard/.admin.DeviceAdminReceiverImpl
   ```
3. Reopen the app and tap **Refresh status** — it should report
   `Device owner: true`.
4. If you need to uninstall/rebuild, first remove the admin or the app can't
   be uninstalled normally:
   ```
   adb shell dpm remove-active-admin au.com.tbmcgregor.bwparker.familyguard/.admin.DeviceAdminReceiverImpl
   ```

## Phase 3 — Tamper resistance

Applied automatically once Device Owner is active (see Phase 2). The "Phase 3 —
Tamper resistance" section has per-restriction toggles and an
"Enable all recommended protections" button for re-applying everything at once.
All of it is stock Android (`UserManager`/`DevicePolicyManager`), no Knox
license required:

- Block Safe Mode boot, factory reset, USB debugging, guest mode/additional
  users (`UserManager` restrictions).
- Block app uninstall for Family Device Guard itself (`setUninstallBlocked`).
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
For best reliability, also exclude Family Device Guard from Samsung's
battery optimization: Settings → Apps → Family Device Guard → Battery →
**Unrestricted**, and remove it from any "Sleeping apps"/"Deep sleeping
apps" list under Settings → Battery and device care.

**Physical Samsung test results**:

- Safe Mode hardware-key boot: **blocked successfully** while Family Device
  Guard was Device Owner.
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

## Secret handling

`local.properties` and `app/libs/*.jar` are ignored. Never commit Knox license
keys.
