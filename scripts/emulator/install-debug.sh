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
  echo "setting Device Owner..."
  # Fresh AVD / no accounts required
  adb shell dpm set-device-owner app.otterling/.admin.DeviceAdminReceiverImpl
  adb shell dpm list-owners
fi

echo "done"
