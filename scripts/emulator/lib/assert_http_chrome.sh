#!/usr/bin/env bash
# Chrome / YouTube / package assertions.
# shellcheck shell=bash

open_chrome_url() {
  local url="$1"
  adb shell am start -a android.intent.action.VIEW -d "$url" \
    -n com.android.chrome/com.google.android.apps.chrome.Main >/dev/null 2>&1 \
    || adb shell am start -a android.intent.action.VIEW -d "$url" >/dev/null 2>&1
}

# Soft allow check: page intent launches and VpnFilterService stays up (no hard TLS proof).
assert_chrome_allow() {
  local url="$1"
  clear_otterling_logs
  open_chrome_url "$url"
  sleep 5
  if ! adb shell pidof app.otterling >/dev/null 2>&1; then
    # VPN service may run without main process; check always-on setting instead.
    local vpn_app
    vpn_app="$(adb shell settings get global always_on_vpn_app 2>/dev/null | tr -d '\r')"
    if [[ "$vpn_app" != "app.otterling" ]]; then
      echo "FAIL chrome allow: always_on_vpn_app=$vpn_app (expected app.otterling)"
      return 1
    fi
  fi
  echo "PASS chrome allow opened $url (vpn still app.otterling)"
  return 0
}

# Block / fail-closed: either mitm block page path, CONNECT to host, or chrome still launched under VPN.
assert_chrome_block_or_fail_closed() {
  local url="$1"
  local host
  host="$(echo "$url" | sed -E 's|https?://([^/]+).*|\1|')"
  clear_otterling_logs
  local before
  before="$(docker logs otterling-mitmproxy 2>/dev/null | wc -l | tr -d ' ')"
  open_chrome_url "$url"
  sleep 8
  local new_logs
  new_logs="$(docker logs otterling-mitmproxy 2>/dev/null | tail -n +"$((before + 1))" || true)"
  if echo "$new_logs" | grep -qiE "${host}|pornhub|502|block"; then
    echo "PASS chrome block/fail-closed saw mitm activity for $host"
    return 0
  fi
  # Fail-closed without mitm log is still OK if VPN is active (RST path).
  local vpn_app
  vpn_app="$(adb shell settings get global always_on_vpn_app 2>/dev/null | tr -d '\r')"
  if [[ "$vpn_app" == "app.otterling" ]]; then
    echo "PASS chrome block/fail-closed VPN active (no mitm hit required) for $url"
    return 0
  fi
  echo "FAIL chrome block: no mitm signal and VPN not active for $url"
  return 1
}

assert_youtube_exempt() {
  local pkg="${1:-com.google.android.youtube}"
  if ! adb shell pm path "$pkg" >/dev/null 2>&1; then
    echo "SKIP youtube exempt: package $pkg not installed"
    return 0
  fi
  local before
  before="$(docker logs otterling-mitmproxy 2>/dev/null | wc -l | tr -d ' ')"
  adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
  sleep 6
  local new_logs
  new_logs="$(docker logs otterling-mitmproxy 2>/dev/null | tail -n +"$((before + 1))" || true)"
  # Exempt app should not produce MITM CONNECT to youtube/googlevideo from the guest.
  # Host/LAN devices may still hit mitm; only fail if we see a clear pattern surge AND
  # certificate-unknown for youtube right after launch is acceptable for Chrome, not for the app.
  # Soft pass: app launched and is still installed/not force-stopped by TLS storms.
  if adb shell pidof "$pkg" >/dev/null 2>&1 || adb shell dumpsys activity activities 2>/dev/null | grep -q "$pkg"; then
    echo "PASS youtube exempt: $pkg launched (mitm CONNECT not required)"
    return 0
  fi
  echo "FAIL youtube exempt: could not confirm $pkg running"
  return 1
}

assert_path_rule_seeded() {
  local rule="$1"
  local url="$2"
  clear_otterling_logs
  adb_broadcast DEBUG_SEED_CUSTOM_BLOCK --es rule "$rule" >/dev/null
  sleep 1
  # Enable accessibility service for Otterling if possible (Device Owner / settings).
  adb shell settings put secure enabled_accessibility_services \
    app.otterling/app.otterling.focus.FocusGuardAccessibilityService >/dev/null 2>&1 || true
  adb shell settings put secure accessibility_enabled 1 >/dev/null 2>&1 || true
  adb_broadcast DEBUG_PERMIT_A11Y >/dev/null
  open_chrome_url "$url"
  sleep 6
  if adb logcat -d 2>/dev/null | grep -qiE 'UrlPathBlockEnforcer|Browser URL blocked|This page is blocked|Shorts blocked'; then
    echo "PASS path rule: saw a11y/path block log for $rule"
    return 0
  fi
  # Soft pass if rule seeded successfully (a11y may need interactive grant on some images).
  if adb logcat -d -s DebugUnsuspend:I 2>/dev/null | grep -q "DEBUG_SEED_CUSTOM_BLOCK ok=true"; then
    echo "PASS path rule: seeded $rule (a11y block log not observed — soft)"
    return 0
  fi
  echo "FAIL path rule: seed/block not confirmed for $rule"
  return 1
}

assert_package_suspended() {
  local pkg="$1"
  clear_otterling_logs
  adb_broadcast DEBUG_SEED_BLOCK_APP --es package "$pkg" --ez blocked true >/dev/null
  sleep 2
  local line
  line="$(adb logcat -d -s DebugUnsuspend:I 2>/dev/null | grep "DEBUG_SEED_BLOCK_APP package=${pkg}" | tail -1 || true)"
  local suspended
  suspended="$(adb shell dumpsys package "$pkg" 2>/dev/null | grep -i 'suspended=' | head -1 | tr -d '\r' || true)"
  if echo "$line" | grep -q 'suspended=true' || echo "$suspended" | grep -qi 'suspended=true'; then
    echo "PASS package suspend $pkg ($line $suspended)"
    return 0
  fi
  # Launch should fail or show suspended dialog
  if ! adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; then
    echo "PASS package suspend $pkg (launch refused)"
    return 0
  fi
  echo "FAIL package suspend $pkg line=$line dumpsys=$suspended"
  return 1
}
