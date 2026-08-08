#!/usr/bin/env bash
# Start headless KVM AVD otterling_api34 under Xvfb; wait until boot completed.
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-/var/lib/otterling/ci/android-sdk}"
AVD_NAME="${AVD_NAME:-otterling_api34}"
DISPLAY_NUM="${DISPLAY_NUM:-99}"
EMU_PID_FILE="${EMU_PID_FILE:-/tmp/otterling-emulator.pid}"
XVFB_PID_FILE="${XVFB_PID_FILE:-/tmp/otterling-xvfb.pid}"
BOOT_TIMEOUT_SEC="${BOOT_TIMEOUT_SEC:-300}"

export ANDROID_HOME
export PATH="$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
export DISPLAY=":${DISPLAY_NUM}"
# Do not route emulator guest networking through the host forward proxy
# (CBLOCK returns 403 to qemu's CONNECT).
unset HTTP_PROXY HTTPS_PROXY http_proxy https_proxy ALL_PROXY all_proxy

if ! groups | grep -qw kvm; then
  echo "warning: current shell is not in group kvm; run: newgrp kvm" >&2
  echo "         (usermod already applied; re-login or newgrp for this session)" >&2
fi

if [ ! -x "$ANDROID_HOME/emulator/emulator" ]; then
  echo "error: emulator not found under $ANDROID_HOME" >&2
  exit 1
fi

if [ -f "$EMU_PID_FILE" ] && kill -0 "$(cat "$EMU_PID_FILE")" 2>/dev/null; then
  echo "emulator already running (pid $(cat "$EMU_PID_FILE"))"
  adb wait-for-device
  exit 0
fi

if ! pgrep -f "Xvfb :${DISPLAY_NUM}" >/dev/null 2>&1; then
  echo "starting Xvfb on :${DISPLAY_NUM}"
  Xvfb ":${DISPLAY_NUM}" -screen 0 1280x720x24 -ac >/tmp/otterling-xvfb.log 2>&1 &
  echo $! >"$XVFB_PID_FILE"
  sleep 1
else
  pgrep -f "Xvfb :${DISPLAY_NUM}" | head -1 >"$XVFB_PID_FILE" || true
fi

echo "starting emulator $AVD_NAME (DISPLAY=$DISPLAY)"
nohup "$ANDROID_HOME/emulator/emulator" \
  -avd "$AVD_NAME" \
  -accel on \
  -gpu swiftshader_indirect \
  -no-audio \
  -no-boot-anim \
  -no-snapshot-save \
  >/tmp/otterling-emulator.log 2>&1 &
echo $! >"$EMU_PID_FILE"

echo "waiting for adb device..."
adb wait-for-device

echo "waiting for boot completed (timeout ${BOOT_TIMEOUT_SEC}s)..."
deadline=$((SECONDS + BOOT_TIMEOUT_SEC))
while true; do
  boot="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  if [ "$boot" = "1" ]; then
    break
  fi
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "error: boot timed out; see /tmp/otterling-emulator.log" >&2
    exit 1
  fi
  sleep 2
done

adb shell input keyevent 82 >/dev/null 2>&1 || true
echo "emulator ready:"
adb devices -l
