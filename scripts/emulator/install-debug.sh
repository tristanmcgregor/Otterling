#!/usr/bin/env bash
# Install Otterling debug (or optional APK) on the running emulator.
# Usage:
#   ./install-debug.sh              # ./gradlew :app:installDebug
#   ./install-debug.sh path.apk     # adb install -r
#   DEVICE_OWNER=1 ./install-debug.sh
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-/var/lib/otterling/ci/android-sdk}"
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
export ANDROID_HOME

# Host CBLOCK blocks direct 443; mirror CI proxy wiring for Gradle downloads.
if [[ -n "${HTTPS_PROXY:-${HTTP_PROXY:-${https_proxy:-${http_proxy:-}}}}" ]]; then
  _px="${HTTPS_PROXY:-${HTTP_PROXY:-${https_proxy:-$http_proxy}}}"
  _ph="$(echo "$_px" | sed -E 's|https?://([^:/]+).*|\1|')"
  _pp="$(echo "$_px" | sed -E 's|https?://[^:]+:([0-9]+).*|\1|')"
  export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Dhttp.proxyHost=${_ph} -Dhttp.proxyPort=${_pp} -Dhttps.proxyHost=${_ph} -Dhttps.proxyPort=${_pp} -Dhttp.nonProxyHosts=localhost|127.0.0.1"
fi

adb wait-for-device

if [ "${1:-}" != "" ]; then
  echo "adb install -r $1"
  adb install -r "$1"
else
  echo "gradlew :app:installDebug"
  (cd "$REPO_ROOT" && ./gradlew :app:installDebug)
fi

adb shell pm path app.otterling

if [ "${DEVICE_OWNER:-0}" = "1" ]; then
  echo "preparing Device Owner (no Google accounts / setup wizard)..."
  adb root >/dev/null 2>&1 || true
  adb wait-for-device
  adb shell settings put secure user_setup_complete 1 >/dev/null 2>&1 || true
  adb shell settings put global device_provisioned 1 >/dev/null 2>&1 || true
  for p in com.google.android.setupwizard com.google.android.apps.restore; do
    adb shell pm disable-user --user 0 "$p" >/dev/null 2>&1 || true
  done
  echo "setting Device Owner..."
  # Retry: Google APIs images can briefly report accounts during first boot.
  ok=0
  for i in 1 2 3 4 5 6; do
    if adb shell dpm set-device-owner app.otterling/.admin.DeviceAdminReceiverImpl; then
      ok=1
      break
    fi
    echo "retry $i: waiting for account-free state..."
    sleep 3
  done
  if [ "$ok" != "1" ]; then
    echo "error: could not set Device Owner (remove Google accounts on the AVD / wipe-data)" >&2
    exit 1
  fi
  adb shell dpm list-owners
fi

echo "done"
