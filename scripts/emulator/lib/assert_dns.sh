#!/usr/bin/env bash
# DNS probe via DEBUG_PROBE_DNS.
# shellcheck shell=bash

assert_dns_probe() {
  local host="$1"
  local expect_blocked="$2" # true|false
  clear_otterling_logs
  adb_broadcast DEBUG_PROBE_DNS --es host "$host" >/dev/null
  sleep 1
  local line
  line="$(adb logcat -d -s DebugUnsuspend:I 2>/dev/null | grep "PROBE_DNS host=${host} " | tail -1 || true)"
  if [[ -z "$line" ]]; then
    echo "FAIL dns: no PROBE_DNS log for $host"
    return 1
  fi
  if [[ "$expect_blocked" == "true" ]]; then
    if echo "$line" | grep -q 'blocked=true'; then
      echo "PASS dns block $host ($line)"
      return 0
    fi
    echo "FAIL dns: expected blocked=true for $host got: $line"
    return 1
  else
    if echo "$line" | grep -q 'blocked=false'; then
      echo "PASS dns allow $host ($line)"
      return 0
    fi
    echo "FAIL dns: expected blocked=false for $host got: $line"
    return 1
  fi
}
