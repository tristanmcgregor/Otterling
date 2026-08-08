#!/usr/bin/env bash
# Stop otterling emulator and optional Xvfb started by start-emulator.sh.
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-/var/lib/otterling/ci/android-sdk}"
EMU_PID_FILE="${EMU_PID_FILE:-/tmp/otterling-emulator.pid}"
XVFB_PID_FILE="${XVFB_PID_FILE:-/tmp/otterling-xvfb.pid}"
DISPLAY_NUM="${DISPLAY_NUM:-99}"

export PATH="$ANDROID_HOME/platform-tools:$PATH"

if command -v adb >/dev/null 2>&1; then
  adb emu kill >/dev/null 2>&1 || true
fi

if [ -f "$EMU_PID_FILE" ]; then
  pid="$(cat "$EMU_PID_FILE" || true)"
  if [ -n "${pid:-}" ] && kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null || true
    sleep 1
    kill -9 "$pid" 2>/dev/null || true
  fi
  rm -f "$EMU_PID_FILE"
fi

# Kill leftover qemu/emulator processes for our AVD name if any
pkill -f "qemu-system.*otterling_api34|emulator.*otterling_api34" 2>/dev/null || true

if [ "${STOP_XVFB:-1}" = "1" ]; then
  if [ -f "$XVFB_PID_FILE" ]; then
    xpid="$(cat "$XVFB_PID_FILE" || true)"
    if [ -n "${xpid:-}" ] && kill -0 "$xpid" 2>/dev/null; then
      kill "$xpid" 2>/dev/null || true
    fi
    rm -f "$XVFB_PID_FILE"
  fi
  pkill -f "Xvfb :${DISPLAY_NUM}" 2>/dev/null || true
fi

echo "emulator stopped"
