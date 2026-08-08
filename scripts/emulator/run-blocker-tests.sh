#!/usr/bin/env bash
# HARD GUARD: Android emulator on Bartholomew saturates CPU/RAM and makes the
# LAN NIC stop answering (SSH/HTTP/ping die; Cloudflare tunnel still works).
# Never run here unless explicitly forced.
if [[ "$(hostname -s)" == "bartholomew" && "${FORCE_EMULATOR_ON_PROD:-}" != "1" ]]; then
  echo "REFUSING to start Android emulator on bartholomew (kills LAN)." >&2
  echo "Run on a dedicated machine, or FORCE_EMULATOR_ON_PROD=1 if you really mean it." >&2
  exit 99
fi

# End-to-end content-blocker harness on otterling_api34.
# Usage:
#   ./scripts/emulator/run-blocker-tests.sh
#   KEEP_EMU=1 SKIP_BOOT=1 ./scripts/emulator/run-blocker-tests.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
EMU_DIR="$ROOT/scripts/emulator"
# shellcheck source=lib/adb.sh
source "$EMU_DIR/lib/adb.sh"
# shellcheck source=lib/assert_dns.sh
source "$EMU_DIR/lib/assert_dns.sh"
# shellcheck source=lib/assert_http_chrome.sh
source "$EMU_DIR/lib/assert_http_chrome.sh"

export ANDROID_HOME="${ANDROID_HOME:-/var/lib/otterling/ci/android-sdk}"
export PATH="$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
export HTTP_PROXY="${HTTP_PROXY:-http://127.0.0.1:3128}"
export HTTPS_PROXY="${HTTPS_PROXY:-$HTTP_PROXY}"
export http_proxy="$HTTP_PROXY" https_proxy="$HTTPS_PROXY"

CASES_JSON="$EMU_DIR/testdata/cases.json"
PASS=0
FAIL=0
SKIP=0
RESULTS=()

log() { printf '%s\n' "$*"; }
record() {
  local status="$1" msg="$2"
  RESULTS+=("$status $msg")
  case "$status" in
    PASS) PASS=$((PASS + 1)) ;;
    FAIL) FAIL=$((FAIL + 1)) ;;
    SKIP) SKIP=$((SKIP + 1)) ;;
  esac
}

require_proxy_password() {
  if [[ -n "${PROXY_PASSWORD:-}" ]]; then
    return 0
  fi
  local env_file="$ROOT/filter-server/.env"
  if [[ -f "$env_file" ]]; then
    # shellcheck disable=SC1090
    set -a
    # Only pull PROXY_* without dumping the whole file into the shell history unnecessarily
    PROXY_PASSWORD="$(grep -E '^PROXY_PASSWORD=' "$env_file" | head -1 | cut -d= -f2-)"
    PROXY_USER="$(grep -E '^PROXY_USER=' "$env_file" | head -1 | cut -d= -f2- || true)"
    set +a
  fi
  if [[ -z "${PROXY_PASSWORD:-}" ]]; then
    echo "error: set PROXY_PASSWORD or put it in filter-server/.env" >&2
    exit 2
  fi
  export PROXY_PASSWORD
  export PROXY_USER="${PROXY_USER:-otterling}"
}

smoke_host_proxy() {
  log "== host CONNECT smoke (8080 mux) =="
  python3 - <<'PY'
import base64, os, socket, sys
user = os.environ.get("PROXY_USER", "otterling")
pw = os.environ["PROXY_PASSWORD"]
cred = base64.b64encode(f"{user}:{pw}".encode()).decode()
ok = False
for port in (8080, 8090):
    try:
        s = socket.create_connection(("127.0.0.1", port), 3)
        req = (
            "CONNECT example.com:443 HTTP/1.1\r\n"
            f"Host: example.com:443\r\n"
            f"Proxy-Authorization: Basic {cred}\r\n\r\n"
        )
        s.sendall(req.encode())
        s.settimeout(5)
        line = s.recv(200).split(b"\r\n")[0]
        print(f"port {port}: {line!r}")
        if line.startswith(b"HTTP/1.1 200"):
            ok = True
        s.close()
    except Exception as e:
        print(f"port {port}: {e}")
if not ok:
    sys.exit(1)
PY
}

ensure_emulator() {
  if [[ "${SKIP_BOOT:-0}" == "1" ]]; then
    if [[ "$(adb get-state 2>/dev/null || true)" == "device" ]]; then
      log "== reusing running emulator =="
      require_device
      return 0
    fi
    log "SKIP_BOOT=1 but adb not ready; booting anyway"
  fi
  log "== starting emulator =="
  # Avoid -no-window on this host (adb stays offline).
  sg kvm -c "$EMU_DIR/start-emulator.sh" || "$EMU_DIR/start-emulator.sh"
  require_device
}

install_apps() {
  local otter_apk victim_apk
  otter_apk="$(ls -1 "$ROOT"/app/build/outputs/apk/debug/*.apk 2>/dev/null | head -1)"
  victim_apk="$(ls -1 "$ROOT"/scripts/emulator/victim-app/build/outputs/apk/debug/*.apk 2>/dev/null | head -1)"
  if [[ -z "$otter_apk" || -z "$victim_apk" ]]; then
    echo "error: missing built APKs otter='$otter_apk' victim='$victim_apk' (pre-build step failed?)" >&2
    exit 5
  fi

  log "== install Otterling + Device Owner =="
  require_device
  local i
  for i in 1 2 3 4 5 6 7 8; do
    if DEVICE_OWNER=1 "$EMU_DIR/install-debug.sh" "$otter_apk"; then
      break
    fi
    echo "otterling install/DO retry $i..."
    sleep 3
    adb wait-for-device || true
    require_device || true
    if (( i == 8 )); then
      echo "error: otterling install / Device Owner failed" >&2
      exit 5
    fi
  done

  log "== adb install victim =="
  for i in 1 2 3 4 5; do
    if adb install -r -t "$victim_apk"; then
      break
    fi
    sleep 2
    adb wait-for-device || true
    if (( i == 5 )); then
      echo "error: victim adb install failed" >&2
      exit 5
    fi
  done
  adb shell pm path test.blocker.victim
}

enable_filter() {
  log "== DEBUG_ENABLE_FILTER (lockdown=false) =="
  clear_otterling_logs
  adb_broadcast DEBUG_ENABLE_FILTER \
    --es host "${FILTER_HOST:-vpn.bartholomew.help}" \
    --ez proxy_enabled true \
    --ez lockdown false \
    --ez always_on false \
    --es proxy_password "$PROXY_PASSWORD" >/dev/null || true
  sleep 3
  if ! wait_log 'DEBUG_ENABLE_FILTER vpnOk=' 90; then
    adb logcat -d -s DebugUnsuspend:I | tail -20 >&2 || true
    echo "error: DEBUG_ENABLE_FILTER did not complete" >&2
    exit 3
  fi
  adb logcat -d -s DebugUnsuspend:I | grep DEBUG_ENABLE_FILTER | tail -1 || true
  local vpn_app
  vpn_app="$(adb shell settings get global always_on_vpn_app 2>/dev/null | tr -d '\r')"
  log "always_on_vpn_app=$vpn_app"
}

refresh_and_seed() {
  log "== refresh blocklist =="
  clear_otterling_logs
  adb_broadcast DEBUG_REFRESH_BLOCKLIST >/dev/null
  # Network download can be slow; allow empty fail if sources blocked — custom seed still works.
  wait_log 'DEBUG_REFRESH_BLOCKLIST' 120 || true
  adb logcat -d -s DebugUnsuspend:I | grep DEBUG_REFRESH_BLOCKLIST | tail -1 || true

  log "== seed custom domain/path =="
  clear_otterling_logs
  adb_broadcast DEBUG_SEED_CUSTOM_BLOCK --es rule 'blockme.otterling.test' >/dev/null
  sleep 1
  adb_broadcast DEBUG_SEED_CUSTOM_BLOCK --es rule 'youtube.com/shorts' >/dev/null
  sleep 1
  wait_log 'DEBUG_SEED_CUSTOM_BLOCK ok=true' 30
}

run_cases() {
  log "== running cases from $CASES_JSON =="
  # Prefer python for JSON; fall back to hard-coded matrix if missing.
  if [[ ! -f "$CASES_JSON" ]]; then
    echo "error: missing $CASES_JSON" >&2
    exit 4
  fi
  while IFS=$'\t' read -r id type rest; do
    [[ -z "$id" ]] && continue
    log "-- case $id ($type) --"
    case "$type" in
      dns)
        local host expect
        host="$(cut -f1 <<<"$rest")"
        expect="$(cut -f2 <<<"$rest")"
        if assert_dns_probe "$host" "$expect"; then
          record PASS "$id"
        else
          record FAIL "$id"
        fi
        ;;
      chrome)
        local url expect
        url="$(cut -f1 <<<"$rest")"
        expect="$(cut -f2 <<<"$rest")"
        if [[ "$expect" == "allow" ]]; then
          if assert_chrome_allow "$url"; then record PASS "$id"; else record FAIL "$id"; fi
        else
          if assert_chrome_block_or_fail_closed "$url"; then record PASS "$id"; else record FAIL "$id"; fi
        fi
        ;;
      youtube_exempt)
        local pkg
        pkg="$(cut -f1 <<<"$rest")"
        out="$(assert_youtube_exempt "$pkg" || true)"
        echo "$out"
        if echo "$out" | grep -q '^SKIP'; then record SKIP "$id"
        elif echo "$out" | grep -q '^PASS'; then record PASS "$id"
        else record FAIL "$id"
        fi
        ;;
      path_rule)
        local rule url
        rule="$(cut -f1 <<<"$rest")"
        url="$(cut -f2 <<<"$rest")"
        if assert_path_rule_seeded "$rule" "$url"; then record PASS "$id"; else record FAIL "$id"; fi
        ;;
      package_suspend)
        local pkg
        pkg="$(cut -f1 <<<"$rest")"
        if assert_package_suspended "$pkg"; then record PASS "$id"; else record FAIL "$id"; fi
        ;;
      *)
        record SKIP "$id (unknown type $type)"
        ;;
    esac
  done < <(python3 - "$CASES_JSON" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))
for c in data["cases"]:
    t = c["type"]
    if t == "dns":
        print(f'{c["id"]}\tdns\t{c["host"]}\t{str(c["expect_blocked"]).lower()}')
    elif t == "chrome":
        print(f'{c["id"]}\tchrome\t{c["url"]}\t{c["expect"]}')
    elif t == "youtube_exempt":
        print(f'{c["id"]}\tyoutube_exempt\t{c["package"]}')
    elif t == "path_rule":
        print(f'{c["id"]}\tpath_rule\t{c["rule"]}\t{c["url"]}')
    elif t == "package_suspend":
        print(f'{c["id"]}\tpackage_suspend\t{c["package"]}')
PY
)
}

print_summary() {
  log ""
  log "======== blocker harness summary ========"
  for r in "${RESULTS[@]}"; do
    log "$r"
  done
  log "PASS=$PASS FAIL=$FAIL SKIP=$SKIP"
  if (( FAIL > 0 )); then
    return 1
  fi
  return 0
}

main() {
  require_proxy_password
  smoke_host_proxy
  # Build APKs before booting the AVD — concurrent Gradle + qemu OOMs this host.
  log "== pre-build APKs (emulator down) =="
  (
    cd "$ROOT"
    export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=3128 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=3128 -Dhttp.nonProxyHosts=localhost|127.0.0.1"
    ./gradlew :app:assembleDebug :emulator-victim:assembleDebug --no-daemon
  )
  ensure_emulator
  install_apps
  enable_filter
  refresh_and_seed
  run_cases
  print_summary
  local rc=$?
  if [[ "${KEEP_EMU:-1}" != "1" ]]; then
    "$EMU_DIR/stop-emulator.sh" || true
  else
    log "leaving emulator running (KEEP_EMU=1)"
  fi
  exit "$rc"
}

main "$@"
