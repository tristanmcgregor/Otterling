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

## Blocker test harness

End-to-end content-blocker checks on `otterling_api34` (DNS allow/block, MITM allow/block, YouTube exemption, Shorts path seed, package suspend). Uses debug broadcasts on **debuggable** builds only (`FLAG_DEBUGGABLE` gate). Filter enable always registers **always-on lockdown VPN** via `VpnFilterManager.enable()` — there is no harness switch to weaken lockdown (checklist §2). If USB adb drops after enable, switch to `adb connect <emu-ip>:5555` (the harness runs `adb tcpip 5555` before enabling).

Prerequisites: KVM AVD scripts above, healthy mitmproxy (`otterling-mitmproxy` on `:8090` + mux on `:8080`), `PROXY_PASSWORD` in `filter-server/.env`.

```bash
cd /home/admin/Otterling
KEEP_EMU=1 ./scripts/emulator/run-blocker-tests.sh
# Reuse an already-booted emulator:
SKIP_BOOT=1 KEEP_EMU=1 ./scripts/emulator/run-blocker-tests.sh
```

Fixture matrix: [`testdata/cases.json`](testdata/cases.json). Victim stub package: `test.blocker.victim` (`:emulator-victim` Gradle module).

The harness **pre-builds APKs before booting qemu** (Gradle + emulator together OOMs this host). Google APIs images must be account-free for Device Owner — `install-debug.sh` disables the setup wizard and retries `dpm set-device-owner`.

Not wired into release CI (needs KVM + live filter-server on bartholomew).

## Notes

- Logs: `/tmp/otterling-emulator.log`, `/tmp/otterling-xvfb.log`
- For in-app update testing, install a **release** APK (`RELEASE_*` signing); debug has an empty cert pin.
- VPN / MITM against `vpn.bartholomew.help` works if host DNS reaches that name.
- Screenshots later via `adb exec-out screencap` or `scrcpy` if needed — this setup is adb-first.
