# Android emulator (bartholomew)

Headless KVM AVD for Otterling testing over `adb` (Device Owner included).

## Environment

```bash
export ANDROID_HOME=/var/lib/otterling/ci/android-sdk
export PATH="$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
# Gradle downloads (host CBLOCK): use local forward proxy
export HTTP_PROXY=http://127.0.0.1:3128 HTTPS_PROXY=http://127.0.0.1:3128
```

`install-debug.sh` sets `JAVA_TOOL_OPTIONS` proxy props when `HTTP(S)_PROXY` is set. `start-emulator.sh` **unsets** proxy for qemu so guest networking is not blocked by CBLOCK 403.

AVD: `otterling_api34` (API 34 Google APIs x86_64) under `~/.android/avd/`.

You must be in group `kvm` (`groups` should list `kvm`). After `usermod`, use a new login or `newgrp kvm` / `sg kvm`.

## Scripts

```bash
./scripts/emulator/start-emulator.sh   # Xvfb + emulator; waits for boot
./scripts/emulator/install-debug.sh    # ./gradlew :app:installDebug
DEVICE_OWNER=1 ./scripts/emulator/install-debug.sh   # also set Device Owner
./scripts/emulator/stop-emulator.sh
```

Device Owner one-liner (app must be installed; no other accounts/users on the AVD):

```bash
adb shell dpm set-device-owner app.otterling/.admin.DeviceAdminReceiverImpl
```

## Notes

- Logs: `/tmp/otterling-emulator.log`, `/tmp/otterling-xvfb.log`
- For in-app update testing, install a **release** APK (`RELEASE_*` signing); debug has an empty cert pin.
- VPN / MITM against `vpn.bartholomew.help` works if host DNS reaches that name.
- Screenshots later via `adb exec-out screencap` or `scrcpy` if needed — this setup is adb-first.
