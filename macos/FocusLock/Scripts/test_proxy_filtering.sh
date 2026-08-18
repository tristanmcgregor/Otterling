#!/bin/bash
# Instruments the mitmproxy system-proxy's effect on this Mac's network -- built to investigate a
# 2026-08-17 report that enabling it caused a severe, escalating "no internet" incident (root
# cause theorized at the time as the proxy living on the same home LAN making every downloaded
# byte cross that link twice). Re-run on 2026-08-18 with real measurement: the theory didn't hold
# up (home server is wired Gigabit, not WiFi, so there's no shared-spectrum hop to double; Mac-NIC
# wire/downloaded ratios stayed ~1.0-1.4x on and off; mitmproxy CPU stayed low; throughput was
# equal-or-better with the proxy on). Proxy enforcement is back under the normal Guardian-controlled
# `state.proxyEnforcementEnabled` path (see EnforcementLoop.swift) as of that investigation.
#
# This script toggles the proxy the same way the GUI/Guardian does -- `focuslockctl enable-proxy` /
# `disable-proxy` -- so it stays useful for future regression checks without any special daemon-side
# hook. (An earlier version of this script used a root-only debug marker file to bypass the then-
# hardcoded kill switch; that marker and the kill switch are both gone now.)
#
# Usage: sudo Scripts/test_proxy_filtering.sh [rounds] [download_seconds] [concurrency]
#   rounds            number of off/on round-trips to run (default 3)
#   download_seconds  duration of each throughput sample (default 20)
#   concurrency       parallel downloads per phase, simulating multiple tabs/streams (default 1)
#
# With concurrency > 1 and/or a longer download_seconds, each phase also samples interface byte
# deltas and a live ping RTT every 5s into a time-series log -- this is what actually lets us see
# whether things degrade the LONGER sustained load runs (matching the original report: "it gets
# worse the longer the computer is on"), not just a single before/after snapshot.
#
# Safety: a trap on EXIT/INT/TERM always calls `disable-proxy` and waits for the daemon to
# reconcile back to proxy-off, then verifies with `networksetup -getwebproxy` and a live ping
# before the script actually exits -- this runs even if a test phase fails or the script is
# Ctrl-C'd mid-run. Total worst-case runtime is bounded (rounds * 2 phases * download_seconds,
# each phase further bounded by its own curl/ping/sampler timeouts), so this can't hang indefinitely.

set -uo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "Must run as root (sudo) -- needs launchctl kickstart on the daemon." >&2
  exit 1
fi

ROUNDS="${1:-3}"
DOWNLOAD_SECONDS="${2:-20}"
CONCURRENCY="${3:-1}"
SAMPLE_INTERVAL=5
DAEMON_LABEL="app.otterling.helperd.direct"
FOCUSLOCKCTL="/usr/local/bin/focuslockctl"
HELPERD_LOG="/var/log/focuslock-helperd.log"
# Cloudflare's own speed-test endpoint (__down?bytes=N) 403s plain curl requests (needs
# browser-specific query params/headers curl doesn't send) -- confirmed by hand on 2026-08-18,
# nothing to do with Otterling. Hetzner's static speed-test file answers plain GETs fine and was
# verified reachable (200, real bytes, ~3.8MB/s) from this Mac before being wired in here.
TEST_FILE_URL="https://ash-speed.hetzner.com/100MB.bin"
PING_TARGET="1.1.1.1"
REPORT_DIR="/var/log/otterling-proxy-test"
TS="$(date -u +%Y%m%dT%H%M%SZ)"
REPORT="${REPORT_DIR}/report-${TS}.log"

mkdir -p "$REPORT_DIR"
: > "$REPORT"

log() {
  local line
  line="[$(date -u +%H:%M:%S)] $*"
  echo "$line"
  echo "$line" >> "$REPORT"
}

# --- primary interface / byte counters ---------------------------------------------------------

primary_interface() {
  route -n get default 2>/dev/null | awk '/interface:/{print $2}'
}

# Maps a device (e.g. en0) to its network-service name (e.g. "Wi-Fi") via
# `networksetup -listnetworkserviceorder`, whose output interleaves "(n) <Service Name>" lines
# with "(Hardware Port: ..., Device: <dev>)" lines. Needed because `-listallnetworkservices`
# alone gives names in an arbitrary order (observed to be "Thunderbolt Bridge" before "Wi-Fi" on
# this Mac) with no interface mapping, which made an earlier version of this script poll proxy
# state on the wrong (inactive) service while real traffic went out over en0/Wi-Fi.
service_for_device() {
  local dev="$1"
  networksetup -listnetworkserviceorder | awk -v dev="$dev" '
    /^\([0-9]+\)/ { name = $0; sub(/^\([0-9]+\) /, "", name) }
    $0 ~ "Device: " dev { print name; exit }
  '
}

# `netstat -I <if> -b -n` prints a header then one totals line; columns: Name Mtu Network Address
# Ipkts Ierrs Ibytes Opkts Oerrs Obytes Coll. We want Ibytes (col 7) and Obytes (col 10).
interface_bytes() {
  local iface="$1"
  netstat -I "$iface" -b -n 2>/dev/null | awk -v iface="$iface" '$1==iface {print $7, $10; exit}'
}

# --- daemon control -------------------------------------------------------------------------

kick_daemon() {
  launchctl kickstart -k "system/${DAEMON_LABEL}" 2>&1 | tee -a "$REPORT" || true
}

wait_for_proxy_state() {
  # Polls the service that actually maps to the primary/default-route interface (see
  # service_for_device) for up to 30s until Enabled matches $1 (yes|no) -- NOT just the first
  # entry `-listallnetworkservices` happens to list, which may be an inactive interface.
  local want="$1"
  local dev svc
  dev="$(primary_interface)"
  svc="$(service_for_device "$dev")"
  if [ -z "$svc" ]; then
    log "WARNING: could not map primary interface '$dev' to a network service name -- falling back to first listed service"
    svc="$(networksetup -listallnetworkservices | tail -n +2 | grep -v '^\*' | head -1)"
  fi
  for _ in $(seq 1 30); do
    local enabled
    enabled="$(networksetup -getwebproxy "$svc" 2>/dev/null | awk -F': ' '/^Enabled/{print tolower($2)}')"
    if [ "$enabled" = "$want" ]; then
      log "proxy state on '$svc' reached Enabled=$want"
      return 0
    fi
    sleep 1
  done
  log "WARNING: timed out waiting for proxy Enabled=$want on '$svc' (last seen: ${enabled:-unknown})"
  return 1
}

revert_and_verify() {
  log "=== REVERT: disabling proxy enforcement and confirming safe state ==="
  "$FOCUSLOCKCTL" disable-proxy >>"$REPORT" 2>&1 || true
  kick_daemon
  wait_for_proxy_state "no"
  # Belt-and-suspenders: explicitly clear the system proxy too, independent of the daemon's own
  # reconcile-on-next-tick path, in case the daemon is unhealthy for any reason.
  for svc in $(networksetup -listallnetworkservices | tail -n +2 | grep -v '^\*'); do
    networksetup -setwebproxystate "$svc" off >/dev/null 2>&1 || true
    networksetup -setsecurewebproxystate "$svc" off >/dev/null 2>&1 || true
  done
  local ping_result
  ping_result="$(ping -c 3 -t 5 "$PING_TARGET" 2>&1 | tail -1)"
  log "post-revert connectivity check ($PING_TARGET): $ping_result"
  log "Report written to $REPORT"
}
trap revert_and_verify EXIT INT TERM

# --- measurement -----------------------------------------------------------------------------

# Runs $CONCURRENCY concurrent curl downloads (simulating multiple tabs/streams pulling at once,
# not just one) for $DOWNLOAD_SECONDS, while a background sampler logs interface byte deltas and a
# fresh ping RTT every $SAMPLE_INTERVAL seconds to its own time-series file -- so a slow-onset
# problem (matches the original report: "it gets worse the longer the computer is on") shows up as
# a trend across samples, not just a single before/after snapshot that could miss it. Reports
# aggregate downloaded bytes, wire bytes (Ibytes delta), the resulting multiplier, and overall ping
# stats for the window, same as before, plus the path to the time-series log.
measure_phase() {
  local label="$1"
  local iface
  iface="$(primary_interface)"
  if [ -z "$iface" ]; then
    log "$label: could not determine primary interface -- skipping measurement"
    return
  fi

  local start_in start_out end_in end_out
  read -r start_in start_out < <(interface_bytes "$iface")

  local dl_log_prefix="${REPORT_DIR}/dl-${TS}-${label// /_}"
  local ping_log="${REPORT_DIR}/ping-${TS}-${label// /_}.log"
  local series_log="${REPORT_DIR}/series-${TS}-${label// /_}.log"
  : > "$series_log"

  log "$label: starting ${DOWNLOAD_SECONDS}s x ${CONCURRENCY} concurrent download(s) from $TEST_FILE_URL (iface=$iface)"

  local curl_pids=()
  for n in $(seq 1 "$CONCURRENCY"); do
    curl -s -o /dev/null -w '%{size_download} %{speed_download} %{time_total}\n' \
      --max-time "$DOWNLOAD_SECONDS" "$TEST_FILE_URL" > "${dl_log_prefix}_stream${n}.log" 2>&1 &
    curl_pids+=($!)
  done

  ping -i 1 -c "$DOWNLOAD_SECONDS" "$PING_TARGET" > "$ping_log" 2>&1 &
  local ping_pid=$!

  # Time-series sampler: every SAMPLE_INTERVAL seconds, log elapsed time, interface byte deltas
  # since the LAST sample (not since phase start -- so this is an instantaneous rate, and a trend
  # across rows shows degradation over the run rather than an average that could hide it), plus a
  # single fresh ping RTT taken right then.
  (
    local prev_in="$start_in" prev_out="$start_out" elapsed=0
    while [ "$elapsed" -lt "$DOWNLOAD_SECONDS" ]; do
      sleep "$SAMPLE_INTERVAL"
      elapsed=$((elapsed + SAMPLE_INTERVAL))
      local now_in now_out
      read -r now_in now_out < <(interface_bytes "$iface")
      local d_in=$(( ${now_in:-0} - ${prev_in:-0} ))
      local d_out=$(( ${now_out:-0} - ${prev_out:-0} ))
      local rate_mbps
      rate_mbps="$(awk -v b="$d_in" -v s="$SAMPLE_INTERVAL" 'BEGIN{printf "%.2f", (b*8)/(s*1000000)}')"
      local rtt
      rtt="$(ping -c 1 -t 2 "$PING_TARGET" 2>/dev/null | awk -F'time=' '/time=/{print $2}' | awk '{print $1}')"
      echo "t=+${elapsed}s wire_rx_delta=${d_in}B wire_tx_delta=${d_out}B rate=${rate_mbps}Mbps rtt=${rtt:-timeout}ms" >> "$series_log"
      prev_in="$now_in"; prev_out="$now_out"
    done
  ) &
  local sampler_pid=$!

  for pid in "${curl_pids[@]}"; do wait "$pid" 2>/dev/null; done
  wait "$ping_pid" 2>/dev/null
  wait "$sampler_pid" 2>/dev/null

  read -r end_in end_out < <(interface_bytes "$iface")

  local downloaded_bytes=0 speed_sum=0
  for n in $(seq 1 "$CONCURRENCY"); do
    local f="${dl_log_prefix}_stream${n}.log"
    local sz sp
    sz="$(awk '{print $1}' "$f" 2>/dev/null)"; sz="${sz:-0}"
    sp="$(awk '{print $2}' "$f" 2>/dev/null)"; sp="${sp:-0}"
    downloaded_bytes=$((downloaded_bytes + sz))
    speed_sum="$(awk -v a="$speed_sum" -v b="$sp" 'BEGIN{printf "%.0f", a+b}')"
  done

  local wire_in_delta wire_out_delta
  wire_in_delta=$(( ${end_in:-0} - ${start_in:-0} ))
  wire_out_delta=$(( ${end_out:-0} - ${start_out:-0} ))

  local multiplier="n/a"
  if [ "${downloaded_bytes:-0}" -gt 0 ] 2>/dev/null; then
    multiplier="$(awk -v w="$wire_in_delta" -v d="$downloaded_bytes" 'BEGIN{ if (d>0) printf "%.2f", w/d; else print "n/a" }')"
  fi

  local ping_stats
  ping_stats="$(tail -1 "$ping_log" 2>/dev/null)"

  log "$label: downloaded=${downloaded_bytes}B combined_avg_speed=${speed_sum}B/s wire_rx_delta=${wire_in_delta}B wire_tx_delta=${wire_out_delta}B wire_rx/downloaded=${multiplier}x"
  log "$label: ping ($PING_TARGET, $DOWNLOAD_SECONDS samples): $ping_stats"
  log "$label: time series ($SAMPLE_INTERVAL s samples): $series_log"
  cat "$series_log" >> "$REPORT"
  log "$label: raw logs: ${dl_log_prefix}_stream*.log $ping_log"
}

# --- run -----------------------------------------------------------------------------------

log "=== test_proxy_filtering.sh starting: rounds=$ROUNDS download_seconds=$DOWNLOAD_SECONDS concurrency=$CONCURRENCY ==="
log "helperd log tail is being captured separately -- see $HELPERD_LOG"

helperd_marker_line() {
  wc -l < "$HELPERD_LOG" 2>/dev/null || echo 0
}

for round in $(seq 1 "$ROUNDS"); do
  log "--- round $round/$ROUNDS: PROXY OFF (baseline) ---"
  "$FOCUSLOCKCTL" disable-proxy >>"$REPORT" 2>&1 || true
  kick_daemon
  wait_for_proxy_state "no"
  before_lines=$(helperd_marker_line)
  measure_phase "round${round}_off"

  log "--- round $round/$ROUNDS: PROXY ON ---"
  "$FOCUSLOCKCTL" enable-proxy >>"$REPORT" 2>&1 || true
  kick_daemon
  if wait_for_proxy_state "yes"; then
    measure_phase "round${round}_on"
  else
    log "round${round}: proxy never came up -- capturing helperd log tail for diagnosis and skipping this round's ON measurement"
  fi
  after_lines=$(helperd_marker_line)
  log "--- round $round/$ROUNDS: helperd log lines $before_lines..$after_lines (proxy/dns/pf lines only) ---"
  sed -n "$((before_lines+1)),${after_lines}p" "$HELPERD_LOG" 2>/dev/null | grep -E '\[proxy|\[dns|\[pf' >> "$REPORT" || true

  # Always return to OFF between rounds so a failed ON phase doesn't run any longer than one
  # measurement window.
  "$FOCUSLOCKCTL" disable-proxy >>"$REPORT" 2>&1 || true
  kick_daemon
  wait_for_proxy_state "no"
done

log "=== all rounds complete ==="
log "Summary (grep '\''wire_rx/downloaded'\'' \"$REPORT\" to compare OFF vs ON multipliers directly):"
grep 'wire_rx/downloaded' "$REPORT" | tee -a "$REPORT"

# trap fires revert_and_verify on exit from here
