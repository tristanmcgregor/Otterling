#!/usr/bin/env bash
# Shared adb helpers for the blocker harness.
# shellcheck shell=bash

: "${ANDROID_HOME:=/var/lib/otterling/ci/android-sdk}"
export PATH="${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/emulator:${PATH}"

# Prefer a ready emulator; fall back to any device.
adb_device() {
  adb devices 2>/dev/null | awk '/^emulator-.*[[:space:]]+device$/{print $1; exit}'
}

require_device() {
  local serial
  # Brief retry — adb can flap for a second after boot.
  local i
  for i in 1 2 3 4 5 6 7 8 9 10; do
    serial="$(adb_device)"
    if [[ -n "$serial" ]]; then
      export ANDROID_SERIAL="$serial"
      echo "using device $ANDROID_SERIAL"
      return 0
    fi
    sleep 1
  done
  echo "error: no emulator device online" >&2
  adb devices -l >&2
  return 1
}

adb_sh() {
  adb shell "$@"
}

adb_broadcast() {
  local action="$1"
  shift
  adb shell am broadcast -a "app.otterling.${action}" \
    -n app.otterling/.monitoring.DebugUnsuspendReceiver "$@"
}

# Wait until logcat (tag DebugUnsuspend) matches regex. Usage: wait_log REGEX [timeout_sec]
wait_log() {
  local pattern="$1"
  local timeout_sec="${2:-60}"
  local deadline=$((SECONDS + timeout_sec))
  while (( SECONDS < deadline )); do
    if adb logcat -d -s DebugUnsuspend:I VpnFilterService:I UrlPathBlockEnforcer:I FocusGuardA11y:I 2>/dev/null \
      | grep -E "$pattern" >/dev/null; then
      return 0
    fi
    sleep 1
  done
  echo "error: timed out waiting for log matching: $pattern" >&2
  return 1
}

clear_otterling_logs() {
  adb logcat -c >/dev/null 2>&1 || true
}
