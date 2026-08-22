#!/usr/bin/env python3
"""Dead-man's switch for the Otterling Mac tamper-detection.

The Fleet failing-policy webhook only fires if, at the moment of tampering, the Mac is online AND
fleetd is still alive to report. Cutting the network first, or killing fleetd, defeats it silently.
This poller inverts that: it asks Fleet how long since the Mac last checked in, and when that gap
crosses a threshold it raises a `mac_silent` tamper event through the SAME pipeline everything else
uses -- lockprofile_service's /alerts/tamper -> JSONL + ntfy + the phone's /alerts/poll -> SMS. It
also clears with a `mac_back` event when the Mac reappears, and dedupes via a state file so an
ordinary offline weekend is one "went quiet" + one "came back", not a page every cycle.

Runs on the server (a container in fleet/docker-compose.yml), NOT on the Mac -- so killing the Mac
can't kill the thing that notices the Mac went quiet. No third-party deps; stdlib only.

Env:
  FLEET_URL                e.g. http://fleet:8080 (internal, TLS off)
  FLEET_API_EMAIL          a read-only Fleet API-only user
  FLEET_API_PASSWORD
  FLEET_HOST_IDENTIFIER    the Mac's hostname/uuid/serial as Fleet knows it (used ONLY to query
                            Fleet's API for seen_time -- NOT reused as the alert device_id, since
                            Fleet's identifier can change on rename/re-enrollment while the alert
                            device_id must stay the one stable id every other reporter uses for
                            this same Mac)
  DEVICE_ID                canonical device_id to report alerts under. Defaults to
                            FLEET_HOST_IDENTIFIER if unset (old behavior), but should be set
                            explicitly to the same IOPlatformUUID-derived id
                            TamperReporter.swift/install_lock_profile.py use for this Mac, so this
                            doesn't mint its own fragment in the dashboard's device list (see
                            lockprofile_service.py's DEVICE_ID_ALIASES doc)
  ALERTS_URL               lockprofile /alerts/tamper, e.g. http://host.docker.internal:8091/alerts/tamper
  LOCKPROFILE_TOKEN        Bearer token /alerts/tamper requires
  DEADMAN_THRESHOLD_MINUTES  silence longer than this = alert (default 2880 = 48h)
  POLL_INTERVAL_SECONDS      how often to check (default 900 = 15m)
  STATE_FILE               dedupe state (default /state/deadman.json)
"""
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

FLEET_URL = os.environ["FLEET_URL"].rstrip("/")
FLEET_API_EMAIL = os.environ["FLEET_API_EMAIL"]
FLEET_API_PASSWORD = os.environ["FLEET_API_PASSWORD"]
HOST_ID = os.environ["FLEET_HOST_IDENTIFIER"]
DEVICE_ID = os.environ.get("DEVICE_ID") or HOST_ID
ALERTS_URL = os.environ["ALERTS_URL"]
LOCKPROFILE_TOKEN = os.environ["LOCKPROFILE_TOKEN"]
THRESHOLD_MIN = float(os.environ.get("DEADMAN_THRESHOLD_MINUTES", "2880"))
POLL_SECONDS = int(os.environ.get("POLL_INTERVAL_SECONDS", "900"))
STATE_FILE = os.environ.get("STATE_FILE", "/state/deadman.json")
# How many consecutive failed Fleet checks before we alert that the MONITOR is blind. A single
# transient error (login throttle, network blip, Fleet restart) must never fire an alert -- it is
# NOT evidence the Mac is silent, and conflating the two spams the partner with false tampers.
FLEET_FAIL_ALERT_AFTER = int(os.environ.get("FLEET_FAIL_ALERT_AFTER", "4"))

# Never proxy internal calls -- this host routes HTTP through Squid, which can't reach these hosts.
_opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def _post(url, data, headers):
    req = urllib.request.Request(url, data=json.dumps(data).encode(), headers=headers, method="POST")
    return _opener.open(req, timeout=20)


def _get(url, headers):
    req = urllib.request.Request(url, headers=headers, method="GET")
    return _opener.open(req, timeout=20)


def fleet_login():
    r = _post(f"{FLEET_URL}/api/latest/fleet/login",
              {"email": FLEET_API_EMAIL, "password": FLEET_API_PASSWORD},
              {"Content-Type": "application/json"})
    return json.load(r)["token"]


def host_seen_time(token):
    r = _get(f"{FLEET_URL}/api/latest/fleet/hosts/identifier/{urllib.parse.quote(HOST_ID)}",
             {"Authorization": f"Bearer {token}"})
    seen = json.load(r)["host"]["seen_time"]
    # Fleet emits RFC3339 UTC, e.g. 2026-08-15T08:44:52Z
    return datetime.fromisoformat(seen.replace("Z", "+00:00"))


def get_seen_time(cached_token):
    """Returns (seen_time, token_to_cache). Reuses a cached token so we don't hit Fleet's /login on
    every cycle -- login is rate-limited, and a burst of logins (e.g. rapid restarts) returns 401.
    Only re-authenticates when there's no token yet or the cached one is rejected (401/403)."""
    if cached_token:
        try:
            return host_seen_time(cached_token), cached_token
        except urllib.error.HTTPError as e:
            if e.code not in (401, 403):
                raise  # a real error (e.g. host not found) -- don't paper over it with a re-login
    token = fleet_login()
    return host_seen_time(token), token


def raise_alert(kind, details):
    _post(ALERTS_URL,
          {"device_id": DEVICE_ID, "type": kind, "details": details, "ts": time.time()},
          {"Content-Type": "application/json", "Authorization": f"Bearer {LOCKPROFILE_TOKEN}"}).close()
    print(f"[deadman] alert sent: {kind} -- {details}", flush=True)


def load_state():
    try:
        with open(STATE_FILE) as fh:
            return json.load(fh)
    except (FileNotFoundError, json.JSONDecodeError):
        return {}


def save_state(state):
    os.makedirs(os.path.dirname(STATE_FILE), exist_ok=True)
    tmp = STATE_FILE + ".tmp"
    with open(tmp, "w") as fh:
        json.dump(state, fh)
    os.replace(tmp, STATE_FILE)


def human(minutes):
    if minutes < 60:
        return f"{round(minutes)}m"
    if minutes < 1440:
        return f"{minutes / 60:.1f}h"
    return f"{minutes / 1440:.1f}d"


def check_once():
    state = load_state()
    try:
        seen, token = get_seen_time(state.get("fleet_token"))
        state["fleet_token"] = token  # persist for reuse next cycle
    except (urllib.error.URLError, urllib.error.HTTPError, KeyError, OSError, ValueError) as e:
        # We could NOT talk to Fleet. This says nothing about whether the Mac is silent, so it must
        # never raise a tamper alert. Track a failure streak; only warn (once) that the monitor is
        # blind after a SUSTAINED outage, so a transient 401/throttle/blip is absorbed silently.
        fails = int(state.get("fleet_fail_streak", 0)) + 1
        state["fleet_fail_streak"] = fails
        save_state(state)
        body = ""
        if isinstance(e, urllib.error.HTTPError):
            try:
                body = " | " + e.read().decode()[:200]
            except Exception:  # noqa: BLE001
                pass
        print(f"[deadman] fleet check failed ({fails}x): {e}{body}", flush=True)
        if fails == FLEET_FAIL_ALERT_AFTER:
            mins = fails * POLL_SECONDS // 60
            try:
                raise_alert("monitor_degraded",
                            f"The Otterling tamper monitor has been unable to reach Fleet for "
                            f"{fails} checks (~{mins}m). It is blind to the Mac's status until this clears.")
            except Exception as post_err:  # noqa: BLE001 -- never let alerting crash the loop
                print(f"[deadman] could not post monitor_degraded alert: {post_err}", flush=True)
        return

    # Fleet reached: persist the (possibly refreshed) token for reuse and clear any failure streak
    # quietly -- a recovered monitor isn't news.
    state["fleet_fail_streak"] = 0
    save_state(state)

    gap_min = (datetime.now(timezone.utc) - seen).total_seconds() / 60.0
    alerted = state.get("silent_alerted", False)

    if gap_min > THRESHOLD_MIN and not alerted:
        raise_alert("mac_silent",
                    f"The Mac has not checked in with Fleet for {human(gap_min)} "
                    f"(threshold {human(THRESHOLD_MIN)}). It may be off, offline, or fleetd was stopped.")
        state["silent_alerted"] = True
        save_state(state)
    elif gap_min <= THRESHOLD_MIN and alerted:
        raise_alert("mac_back", f"The Mac is checking in again (last seen {seen.isoformat()}).")
        state["silent_alerted"] = False
        save_state(state)
    else:
        print(f"[deadman] ok: last check-in {round(gap_min)}m ago (threshold {human(THRESHOLD_MIN)})", flush=True)


def main():
    print(f"[deadman] starting; host={HOST_ID} threshold={human(THRESHOLD_MIN)} poll={POLL_SECONDS}s", flush=True)
    while True:
        try:
            check_once()
        except Exception as e:  # noqa: BLE001 -- the loop must outlive any single bad cycle
            print(f"[deadman] cycle error: {e}", flush=True)
        time.sleep(POLL_SECONDS)


if __name__ == "__main__":
    main()
