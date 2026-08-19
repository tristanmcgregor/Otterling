#!/usr/bin/env python3
"""Issues the macOS "lock profile" (see macos/FocusLock/GUARDIAN_SETUP.md §6) and ingests tamper
reports from the daemon that watches it.

Important, hard-won limit this file is built around -- read before changing anything here: on
macOS, a configuration profile's `RemovalPasscode` does **not** hold against a local admin account.
Apple's own Profile Manager documentation confirms an admin can remove any profile by holding
Option and clicking Remove in the Profiles pane, authenticating with their own admin password --
the removal passcode is never asked for. `PayloadRemovalDisallowed` only becomes genuinely
un-removable-by-anyone when the profile is delivered by a real MDM server, which this project does
not run. So this service does **not** try to gate the passcode behind a delay (there would be
nothing to protect -- an admin never needs it). The profile is a *tripwire*, not a lock: its real
value is (a) moving DNS onto a profile-managed resolver that can't be hand-edited via
`networksetup`/System Settings without removing the whole profile first, and (b) making that
removal, or the DNS-payload disappearing, into a reported event instead of a silent one.

DNS payload note: `com.apple.dnsSettings.managed` (confirmed manually-installable, unsupervised,
on macOS 11+ via Apple's own device-management spec repo) only supports encrypted DNS protocols
(HTTPS/DoH or TLS/DoT) -- it cannot pin a plain cleartext resolver like this project's own
AdGuard Home cloud filter. This service therefore points it at Cloudflare's public "1.1.1.1 for
Families" DoH endpoint (`family.cloudflare-dns.com`, the DoH form of the 1.1.1.3/1.0.0.3 IPs
`DNSEnforcer.swift` already uses as its own fallback) -- a real, OS-enforced encrypted-DNS floor
that needs no new server infrastructure, layered alongside (not replacing) the daemon's own
DNS/hosts/pf enforcement. Wiring this at the profile's own cloud filter host would need that host
to expose a DoH endpoint of its own -- not set up here, a possible future upgrade.

Notification: every accepted `/alerts/tamper` event is appended to `ALERTS_PATH` regardless, and --
if `NTFY_TOPIC` is set -- also best-effort pushed to ntfy.sh (https://ntfy.sh), a free push
notification service with no signup and no message caps: the accountability partner installs the
ntfy app and subscribes to this one topic, the server does a plain HTTP POST, they get a push
within seconds. `NTFY_TOPIC` should be a long random string (`openssl rand -hex 16` or similar),
not something guessable -- ntfy's public server is topic-name-secret, not authenticated, so anyone
who learns the topic name can read (or spoof) alerts on it. A failed ntfy push never blocks or
fails the request from the daemon; the JSONL log is always the source of truth.

Phone polling: `GET /alerts/poll?since_id=<int>` (see `MacTamperPollWorker.kt` on the Android side)
returns every event with `id > since_id`, so the phone's existing on-device SMS pipeline
(`AlertReporter`/`GuardianSmsSender` -- it already has a SIM, no new SMS provider needed) can alert
on these too, polled on a `WorkManager` cadence rather than pushed. Reuses `LOCKPROFILE_TOKEN` for
auth rather than minting a separate phone-scoped token -- a deliberate simplification for this
single-operator deployment, not an oversight; see the token's own comment in `.env.example`. `id`
is assigned as each event is appended (its 1-based position in `ALERTS_PATH`), under `_alerts_lock`
so concurrent reporters (the daemon and the watchdog can both be POSTing around the same moment)
never race onto the same id.

AI assistant: `POST /ai-assistant/translate` (see `AIAssistantClient.swift`) turns a natural-language
request ("install wget") into candidate shell command(s) via the same host-level reviewer's
`/translate` route -- pure translation, no safety reasoning. Every returned command is then run
through `/sudo-review/check` individually before anything executes, exactly like a manually-typed
command -- this is a convenience layer over the broker, never a way around it.

Sudo-elevation review: `POST /sudo-review/check` (see `SudoBroker.swift` / `sudo_review_server.py`)
is the tier-3 fallback for the Mac's privilege-elevation broker -- a command its own local
denylist/allowlist didn't resolve gets forwarded here, which calls out to a host-level AI reviewer
(NOT reachable from inside this container -- see `sudo_review_server.py`'s own doc comment for why)
and returns allow/deny. Fails closed (deny) on any error, unlike everything else in this file.

Code-tamper check-in: `POST /integrity/checkin` (see `IntegrityReporter.swift`) -- the macOS daemon
reports the git commit and working-tree-dirty state it was built from (`build-info.json`, written by
`Scripts/build_app.sh`) on every start and every 15 minutes after. A dirty tree at build time means
local, uncommitted source changes -- the direct "edit the code and install it locally" bypass -- and
fires a `mac_code_tampered` event through the same alert pipeline as everything else here. Reporting
only, never a gate: nothing waits on this endpoint's response, so an outage here can't take the Mac's
own filtering offline (the project's fail-open rule). See the handler itself for what this does and
does not verify about `git_sha`.

Device log upload: `POST /device-logs/upload` (see `DeviceLogUploader.kt` / the "Send diagnostic
logs" button in the app's filter settings) accepts a device's own recent logcat output plus a
snapshot of its MITM-exemption state, so a Guardian debugging "this app still doesn't work" can see
what actually happened on-device without ADB access to the phone. Written to
`LOGS_DIR/<device_id>/<timestamp>.log`, pruned to the newest `MAX_LOG_FILES_PER_DEVICE` per device.
Reuses `LOCKPROFILE_TOKEN` for auth, same as the tamper-poll endpoints above. `GET
/device-logs/view/list` and `GET /device-logs/view/<device_id>/<filename>` serve them back out --
Caddy puts these behind the `/review` dashboard's Basic Auth and injects the bearer token itself
(see Caddyfile), so a browser never needs to know `LOCKPROFILE_TOKEN` directly.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import plistlib
import re
import secrets
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# Host-level AI reviewer for SudoBroker.swift's tier-3 fallback (see sudo_review_server.py's module
# doc comment for why this has to be a separate host-level process rather than something this
# container does itself). `host.docker.internal` needs docker-compose.yml's `extra_hosts:
# ["host.docker.internal:host-gateway"]` on this service to resolve on Linux.
SUDO_REVIEW_URL = os.environ.get("SUDO_REVIEW_URL", "http://host.docker.internal:9072/review")
SUDO_TRANSLATE_URL = os.environ.get("SUDO_TRANSLATE_URL", "http://host.docker.internal:9072/translate")
SUDO_REVIEW_TIMEOUT = 20
SUDO_TRANSLATE_TIMEOUT = 25

DATA_DIR = os.environ.get("LOCKPROFILE_DATA_DIR", "/data")
STATE_PATH = os.path.join(DATA_DIR, "state.json")
ALERTS_PATH = os.path.join(DATA_DIR, "alerts", "events.jsonl")
LOGS_DIR = os.path.join(DATA_DIR, "logs")

LISTEN_HOST = os.environ.get("LOCKPROFILE_LISTEN_HOST", "0.0.0.0")
LISTEN_PORT = int(os.environ.get("LOCKPROFILE_LISTEN_PORT", "8091"))
TOKEN = os.environ.get("LOCKPROFILE_TOKEN", "")

# Hand-editable master list of every report/alert type (mac/server/android) -- see that file's
# "_readme" for the full contract. Mounted read-only alongside the script (same pattern as
# ai_classifier.py/domain_blocklist.py), not the gitignored DATA_DIR, because this is meant to be
# tracked and hand-edited in the repo, not treated as generated runtime state.
REPORT_TYPES_CONFIG_PATH = os.environ.get(
    "REPORT_TYPES_CONFIG_PATH", os.path.join(os.path.dirname(os.path.abspath(__file__)), "report_types.json")
)

# Separate secret for the Fleet failing-policy webhook. Fleet's webhook can't send an Authorization
# header, so that one route authenticates on a `?token=` query secret instead of the Bearer TOKEN
# every other route requires -- see Handler._handle_fleet_webhook. Empty = the route is disabled
# (returns 403), so an unconfigured deployment can't be poked with unauthenticated Fleet payloads.
FLEET_WEBHOOK_SECRET = os.environ.get("FLEET_WEBHOOK_SECRET", "")

# Device-settings dashboard's OWN login (a custom page, see filter-server/dashboard-login/), not
# Caddy's basic_auth -- that native browser dialog was confusing/unreliable enough in practice
# (stuck re-prompt loops, silent stale-credential caching) that it got replaced outright. Plaintext
# password compared via secrets.compare_digest (not a bcrypt hash like Caddy's basic_auth used --
# Python's stdlib has no bcrypt, and this server already carries plaintext-secret-plus-
# compare_digest as its established pattern for LOCKPROFILE_TOKEN/FLEET_WEBHOOK_SECRET). Session
# tokens are self-verifying (HMAC'd with TOKEN as the key, see _dashboard_session_* below) rather
# than server-stored, so there's no session store to lose on a restart.
DASHBOARD_USER = os.environ.get("DASHBOARD_USER", "")
DASHBOARD_LOGIN_PASSWORD = os.environ.get("DASHBOARD_LOGIN_PASSWORD", "")
DASHBOARD_SESSION_MAX_AGE_SECONDS = 30 * 24 * 60 * 60  # 30 days

NTFY_SERVER = os.environ.get("NTFY_SERVER", "https://ntfy.sh").rstrip("/")
NTFY_TOPIC = os.environ.get("NTFY_TOPIC", "")

# FCM (Firebase Cloud Messaging) push: lets this server wake the phone the instant a tamper event
# lands, so the accountability partner is alerted in seconds instead of on MacTamperPollWorker's
# 15-minute poll floor. The push carries no trusted payload -- it's a "poll now" wake; the phone
# then pulls from /alerts/poll through its existing durable SMS pipeline, so a dropped push loses
# nothing (the periodic poll still delivers). Entirely optional: with no credentials file present,
# FCM is inert and ntfy + polling are unaffected.
#
# FCM_CREDENTIALS_PATH is a Firebase *service-account* JSON (Firebase console -> Project settings ->
# Service accounts -> Generate new private key) -- NOT the app's google-services.json. Drop it into
# the mounted data dir on the server (default /data/fcm-service-account.json). The project id is
# read from that file.
FCM_CREDENTIALS_PATH = os.environ.get(
    "FCM_CREDENTIALS_PATH", os.path.join(DATA_DIR, "fcm-service-account.json")
)
FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"

# (title, priority, tag) per tamper event type -- see TamperReporter.swift / LockProfileGuard.swift
# / FocusLockWatchdog for the `type` values these correspond to. Falls back to a generic
# medium-priority notification for any type not listed here, so a future new event type still
# reaches the partner instead of silently not notifying.
NTFY_EVENT_STYLE = {
    "lock_profile_removed": ("Otterling: lock profile removed", "urgent", "warning"),
    "lock_profile_installed": ("Otterling: lock profile installed", "default", "white_check_mark"),
    # A VPN routes traffic around the DNS floor + hosts + pf, defeating the content filter itself
    # (not just the alerting) -- urgent. `vpn_cleared` is the all-clear once it's down again.
    "vpn_active": ("Otterling: VPN up — content filter bypassed", "urgent", "warning"),
    "vpn_cleared": ("Otterling: VPN down — filter back in effect", "default", "white_check_mark"),
    # A trigger word (the shared trigger_words list) was seen on a page the content filter blocked
    # (server-side, from mitm_nsfw_addon.py) or on-screen on the Mac (FocusLockScanner's
    # accessibility scan). Report-only, like the phone's TRIGGER_WORD alerts -- the content was
    # already blocked/visible; this is the accountability heads-up, not an emergency.
    "trigger_word_detected": ("Otterling: trigger word seen", "high", "eyes"),
    "daemon_unloaded_recovered": ("Otterling: daemon was down, watchdog recovered it", "high", "robot"),
    "watchdog_or_daemon_reregistered": ("Otterling: needed re-registration on GUI launch", "high", "warning"),
    # Mac-side tamper signals from Fleet (see fleet/ + tamper-alerts/). A failing policy means the
    # app was removed or its daemon stopped; "silent" means the Mac stopped checking in entirely,
    # which is the quiet-bypass path (network cut / fleetd killed) the dead-man's switch catches.
    "mac_tamper_policy": ("Otterling Mac: tamper policy failing", "urgent", "warning"),
    "mac_silent": ("Otterling Mac: stopped checking in", "urgent", "warning"),
    "mac_back": ("Otterling Mac: checking in again", "default", "white_check_mark"),
    # The dead-man's switch itself is blind (can't reach Fleet) -- a monitor-health warning, not a
    # tamper, and only after a sustained outage. See deadman.py.
    "monitor_degraded": ("Otterling Mac: tamper monitor is blind", "high", "warning"),
    # See `/integrity/checkin` below -- the daemon reported it was built from an uncommitted,
    # locally-modified source tree. This is the actual "edited the code and installed it locally to
    # dodge review" scenario the rest of this project's self-lockout design exists to catch.
    "mac_code_tampered": ("Otterling Mac: running locally-modified code", "urgent", "warning"),
    # The lock profile's DNS Settings payload can be switched off from System Settings > Network >
    # VPN & Filters without removing the profile at all -- see LockProfileGuard.swift's
    # `dnsFloorFunctionallyActive()` doc comment for how this is detected (the `profiles` CLI itself
    # can't see it).
    "dns_floor_disabled": ("Otterling Mac: DNS floor filter switched off", "urgent", "warning"),
    "dns_floor_reenabled": ("Otterling Mac: DNS floor filter back on", "default", "white_check_mark"),
    # SudoBroker.swift's decision pipeline -- see that file's doc comment. Reported for every
    # decision, approved or denied, specifically so an AI-review approval still reaches the partner.
    "sudo_request_approved": ("Otterling Mac: elevated command APPROVED", "urgent", "warning"),
    "sudo_request_denied": ("Otterling Mac: elevated command denied", "default", "shield"),
    "sudo_request_ai_reviewed": ("Otterling Mac: AI reviewed an elevated command", "high", "robot"),
    # XPCService.killSwitch/.restoreFromKillSwitch -- the emergency stop for the WHOLE app (DNS,
    # proxy, pf, blocked/protected apps, the scanner, the GUI app itself), not just filtering.
    "kill_switch_activated": ("Otterling Mac: KILL SWITCH -- everything disabled", "urgent", "rotating_light"),
    "kill_switch_restored": ("Otterling Mac: kill switch undone, protection back on", "default", "white_check_mark"),
}

PROFILE_IDENTIFIER = "app.otterling.lockprofile"
DNS_PAYLOAD_IDENTIFIER = f"{PROFILE_IDENTIFIER}.dns"
FAMILY_DOH_URL = "https://family.cloudflare-dns.com/dns-query"

MAX_BODY_BYTES = 16 * 1024

# Phone-uploaded diagnostic logs (see DeviceLogUploader.kt / VpnFilterSection's "Send diagnostic
# logs" button) are much bigger than any other request this service handles -- a few thousand
# logcat lines -- so they get their own, much larger cap, plus a per-device file-count limit so a
# device stuck retrying uploads can't fill the disk.
MAX_LOG_BODY_BYTES = 2 * 1024 * 1024
MAX_LOG_FILES_PER_DEVICE = 20
# Device ids come from Settings.Secure.ANDROID_ID on the phone -- always a 16-char lowercase hex
# string in practice, but validated here anyway since it becomes part of a filesystem path below.
DEVICE_ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,128}$")

# Keeps ALERTS_PATH from growing without bound -- see _rotate_alerts_if_needed().
ALERTS_ROTATE_BYTES = 5 * 1024 * 1024
ALERTS_ROTATE_KEEP_LINES = 2000

# Per-device dashboard settings (see /dashboard-api/* below and filter-server/dashboard/) -- kept in
# its own file/lock rather than folded into STATE_PATH's per-device records, since those hold the
# mobileconfig passcode/UUIDs (a different concern with different write patterns) and this project
# already separates concerns that way (e.g. alerts get their own ALERTS_PATH/_alerts_lock too).
SETTINGS_PATH = os.path.join(DATA_DIR, "device_settings.json")

_state_lock = threading.Lock()
_alerts_lock = threading.Lock()
_settings_lock = threading.Lock()


def _load_state() -> dict:
    try:
        with open(STATE_PATH, "r", encoding="utf-8") as fh:
            return json.load(fh)
    except (FileNotFoundError, json.JSONDecodeError):
        return {}


def _save_state(state: dict) -> None:
    os.makedirs(DATA_DIR, exist_ok=True)
    tmp_path = STATE_PATH + ".tmp"
    with open(tmp_path, "w", encoding="utf-8") as fh:
        json.dump(state, fh, indent=2, sort_keys=True)
    os.replace(tmp_path, STATE_PATH)


def _device_record(device_id: str) -> dict:
    """Returns the persisted passcode/UUIDs for device_id, creating them on first call. Idempotent
    on every later call for the same device -- re-provisioning must return the same profile, not
    rotate the passcode out from under an install that already trusts it."""
    with _state_lock:
        state = _load_state()
        record = state.get(device_id)
        if record is None:
            record = {
                "passcode": secrets.token_urlsafe(16),
                "profile_uuid": str(uuid.uuid4()).upper(),
                "dns_payload_uuid": str(uuid.uuid4()).upper(),
                "created_at": time.time(),
            }
            state[device_id] = record
            _save_state(state)
        return record


def build_mobileconfig(device_id: str) -> bytes:
    record = _device_record(device_id)

    dns_payload = {
        "PayloadType": "com.apple.dnsSettings.managed",
        "PayloadIdentifier": DNS_PAYLOAD_IDENTIFIER,
        "PayloadUUID": record["dns_payload_uuid"],
        "PayloadVersion": 1,
        "PayloadDisplayName": "Otterling DNS Floor",
        "DNSSettings": {
            "DNSProtocol": "HTTPS",
            "ServerURL": FAMILY_DOH_URL,
        },
    }

    profile = {
        "PayloadContent": [dns_payload],
        "PayloadDisplayName": "Otterling Lock",
        "PayloadDescription": (
            "Marks this Mac as protected by Otterling and sets an encrypted-DNS floor "
            "(Cloudflare Family) that can't be edited via System Settings without removing this "
            "profile. Removing this profile is reported -- see GUARDIAN_SETUP.md."
        ),
        "PayloadIdentifier": PROFILE_IDENTIFIER,
        "PayloadUUID": record["profile_uuid"],
        "PayloadType": "Configuration",
        "PayloadVersion": 1,
        "PayloadOrganization": "Otterling",
        # Tripwire, not a lock -- see module docstring. Kept anyway: it still stops a Standard
        # (non-admin) account from removing it without any credential at all, which is a real if
        # modest floor, and its presence/absence is exactly what LockProfileGuard watches for.
        "PayloadRemovalDisallowed": True,
        "RemovalPasscode": record["passcode"],
    }
    return plistlib.dumps(profile, fmt=plistlib.FMT_XML)


def _max_existing_alert_id() -> int:
    """O(n) scan of ALERTS_PATH -- only ever called once, the first time `_next_alert_id_locked`
    runs after this service starts, to seed its counter. Every call after that is O(1)."""
    if not os.path.exists(ALERTS_PATH):
        return 0
    max_id = 0
    with open(ALERTS_PATH, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                event = json.loads(line)
            except json.JSONDecodeError:
                continue
            max_id = max(max_id, event.get("id", 0))
    return max_id


def _next_alert_id_locked(state: dict) -> int:
    """Returns the next alert id, persisting the counter in state.json (under `_state_lock`,
    already held by the caller) instead of re-reading and counting every line of ALERTS_PATH on
    every single tamper report -- that approach got slower as the log grew, this one doesn't.
    Seeds itself from the log's existing max id exactly once so ids stay monotonic across the
    upgrade from the old scheme (a phone mid-poll relies on ids never going backwards or repeating)."""
    meta = state.setdefault("_meta", {})
    next_id = meta.get("next_alert_id")
    if next_id is None:
        next_id = _max_existing_alert_id() + 1
    meta["next_alert_id"] = next_id + 1
    return next_id


def _record_ack(since_id: int) -> None:
    """Records that a poller has durably received every event with `id <= since_id` (the phone only
    advances its poll cursor after persisting each event into its local SMS outbox, so a poll at
    `since_id` is a delivery acknowledgement up to that id). `_rotate_alerts_if_needed` uses this
    watermark to guarantee an un-acknowledged tamper report is never rotated away -- so a report
    survives on the server until the partner's phone has actually taken it, no matter how long the
    phone stays offline."""
    if since_id <= 0:
        return
    with _state_lock:
        state = _load_state()
        meta = state.setdefault("_meta", {})
        if since_id > meta.get("acked_alert_id", 0):
            meta["acked_alert_id"] = since_id
            _save_state(state)


def _acked_alert_id() -> int:
    with _state_lock:
        return _load_state().get("_meta", {}).get("acked_alert_id", 0)


def _rotate_alerts_if_needed() -> None:
    """Bounds ALERTS_PATH's size -- called (already under `_alerts_lock`) after every append.
    `os.path.getsize` is O(1), so this is nearly free in the common case; the rewrite only happens
    the rare times the size threshold is crossed.

    Crucially, it NEVER drops an event the phone hasn't acknowledged yet (id > `_acked_alert_id`):
    rotating one away would silently lose a tamper report the partner never received. Unacknowledged
    events are always kept, even if that pushes the file past the keep-lines budget -- durability of
    an undelivered alert wins over the size cap."""
    try:
        size = os.path.getsize(ALERTS_PATH)
    except OSError:
        return
    if size < ALERTS_ROTATE_BYTES:
        return
    with open(ALERTS_PATH, "r", encoding="utf-8") as fh:
        lines = fh.readlines()
    if len(lines) <= ALERTS_ROTATE_KEEP_LINES:
        return

    acked = _acked_alert_id()

    def _line_id(line: str) -> int:
        try:
            return int(json.loads(line).get("id", 0))
        except (json.JSONDecodeError, ValueError, TypeError):
            return 0

    # Lines are in ascending-id append order, so unacked events (id > acked) are the tail. Keep all
    # of them, then backfill the remaining budget with the most recent acknowledged events.
    unacked = [ln for ln in lines if _line_id(ln) > acked]
    acked_lines = [ln for ln in lines if _line_id(ln) <= acked]
    budget = ALERTS_ROTATE_KEEP_LINES - len(unacked)
    kept = (acked_lines[-budget:] + unacked) if budget > 0 else unacked

    tmp_path = ALERTS_PATH + ".tmp"
    with open(tmp_path, "w", encoding="utf-8") as fh:
        fh.writelines(kept)
    os.replace(tmp_path, ALERTS_PATH)


def _load_report_config() -> dict:
    """Re-read on every call (not cached) so hand-editing report_types.json takes effect
    immediately, with no restart -- this file is tiny and events are infrequent enough that the
    disk read is not worth caching. Missing/malformed file -> empty dict, so `_report_type_enabled`
    below falls back to its "unlisted type defaults to enabled" behavior rather than this file's
    absence silently going deaf on every report."""
    try:
        with open(REPORT_TYPES_CONFIG_PATH, "r", encoding="utf-8") as fh:
            return json.load(fh).get("types", {})
    except (OSError, json.JSONDecodeError):
        return {}


def _report_type_enabled(report_type: str) -> bool:
    entry = _load_report_config().get(report_type)
    if entry is None:
        return True
    return entry.get("enabled", True) is not False


# ─── Dashboard session cookie (custom login, replacing Caddy basic_auth) ───────────────────────
# Self-verifying token, `<expiry>.<hmac>` -- no server-side session store to lose on a restart or
# keep in sync across a future second instance. `expiry` is a plain unix timestamp; `hmac` is
# HMAC-SHA256 of that timestamp keyed on TOKEN (LOCKPROFILE_TOKEN), so a token can only have been
# minted by this server (which is the only thing that knows TOKEN) and can't be forged or extended
# by a client tampering with the expiry.
def _dashboard_session_create() -> str:
    expiry = str(int(time.time()) + DASHBOARD_SESSION_MAX_AGE_SECONDS)
    signature = hmac.new(TOKEN.encode("utf-8"), expiry.encode("utf-8"), hashlib.sha256).hexdigest()
    return f"{expiry}.{signature}"


def _dashboard_session_valid(token: str) -> bool:
    if not token or "." not in token:
        return False
    expiry, _, signature = token.partition(".")
    if not expiry.isdigit() or int(expiry) < time.time():
        return False
    expected = hmac.new(TOKEN.encode("utf-8"), expiry.encode("utf-8"), hashlib.sha256).hexdigest()
    return secrets.compare_digest(signature, expected)


def _dashboard_cookie_from_headers(headers) -> str | None:
    raw = headers.get("Cookie", "")
    for part in raw.split(";"):
        name, _, value = part.strip().partition("=")
        if name == "otterling_dashboard_session":
            return value
    return None


def _append_alert(event: dict) -> dict | None:
    """Assigns `id` and appends. Returns the event including that id -- callers that need it (the
    poll endpoint, ntfy) get it without a re-read. Returns None, and doesn't write anything at all,
    if `event["type"]` is disabled in report_types.json -- see that file's "_readme" and
    `_load_report_config` above. Callers must check for None before calling `_push_event`."""
    if not _report_type_enabled(event.get("type", "")):
        return None
    os.makedirs(os.path.dirname(ALERTS_PATH), exist_ok=True)
    with _state_lock:
        state = _load_state()
        event_id = _next_alert_id_locked(state)
        _save_state(state)
    event = {**event, "id": event_id}
    with _alerts_lock:
        with open(ALERTS_PATH, "a", encoding="utf-8") as fh:
            fh.write(json.dumps(event, sort_keys=True) + "\n")
        _rotate_alerts_if_needed()
    return event


def _read_alerts_since(since_id: int) -> list[dict]:
    if not os.path.exists(ALERTS_PATH):
        return []
    events = []
    with open(ALERTS_PATH, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                event = json.loads(line)
            except json.JSONDecodeError:
                continue
            if event.get("id", 0) > since_id:
                events.append(event)
    return events


def _safe_device_id(raw: str) -> str | None:
    return raw if DEVICE_ID_RE.match(raw or "") else None


def _store_device_log(device_id: str, logs: str) -> str:
    """Writes `logs` to a new timestamped file under LOGS_DIR/<device_id>/, then prunes to the
    newest MAX_LOG_FILES_PER_DEVICE files for that device so a device stuck retrying uploads can't
    fill the disk. Returns the filename written."""
    device_dir = os.path.join(LOGS_DIR, device_id)
    os.makedirs(device_dir, exist_ok=True)
    stamp = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    filename = f"{stamp}.log"
    path = os.path.join(device_dir, filename)
    counter = 1
    while os.path.exists(path):
        filename = f"{stamp}-{counter}.log"
        path = os.path.join(device_dir, filename)
        counter += 1
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(logs)
    existing = sorted(os.listdir(device_dir))
    for stale in existing[: max(0, len(existing) - MAX_LOG_FILES_PER_DEVICE)]:
        try:
            os.remove(os.path.join(device_dir, stale))
        except OSError:
            pass
    return filename


def _list_device_logs() -> dict:
    if not os.path.isdir(LOGS_DIR):
        return {}
    result = {}
    for device_id in sorted(os.listdir(LOGS_DIR)):
        device_dir = os.path.join(LOGS_DIR, device_id)
        if not os.path.isdir(device_dir):
            continue
        files = []
        for filename in sorted(os.listdir(device_dir)):
            path = os.path.join(device_dir, filename)
            try:
                stat = os.stat(path)
            except OSError:
                continue
            files.append({"filename": filename, "size": stat.st_size, "mtime": stat.st_mtime})
        result[device_id] = files
    return result


# ─── Dashboard device settings (/dashboard-api/*) ──────────────────────────────────────────────
# Backs the web dashboard at filter-server/dashboard/ (served by Caddy at /dashboard/, see
# Caddyfile) -- a guardian-facing settings console, distinct from the mac/phone-facing routes
# above. Not yet pulled/enforced by the Android app itself (a documented follow-up, see
# filter-server/dashboard/README.md); this is the server-side store + API only.

# (dashboard-path-segment -> (settings list key, item-matching field)). Both the generic
# add/remove handlers and _build_list_item below key off this table.
LIST_ENDPOINTS = {
    "websites": ("blockedWebsites", "domain"),
    "bypass-apps": ("vpnBypassApps", "id"),
    "habits": ("habits", "id"),
    "rules": ("rules", "id"),
    "app-budgets": ("appBudgets", "id"),
}

DASHBOARD_DEVICE_RE = re.compile(r"^/dashboard-api/devices/([A-Za-z0-9_-]{1,128})((?:/.+)?)$")

# PATCH .../settings is allowlisted to these keys -- everything else in _default_device_settings
# (rules, habits, blockedWebsites, vpnBypassApps, appBudgets, guardianPinHash, updatedAt) is either
# managed through its own dedicated endpoint or server-computed, not client-settable via this route.
SETTINGS_PATCH_ALLOWED_KEYS = {"device_name", "protections", "vpnFilter", "frictionDelay", "guardianEmail"}


def _load_settings() -> dict:
    try:
        with open(SETTINGS_PATH, "r", encoding="utf-8") as fh:
            return json.load(fh)
    except (FileNotFoundError, json.JSONDecodeError):
        return {}


def _save_settings(settings: dict) -> None:
    os.makedirs(DATA_DIR, exist_ok=True)
    tmp_path = SETTINGS_PATH + ".tmp"
    with open(tmp_path, "w", encoding="utf-8") as fh:
        json.dump(settings, fh, indent=2, sort_keys=True)
    os.replace(tmp_path, SETTINGS_PATH)


def _default_device_settings() -> dict:
    return {
        "device_name": "",
        "protections": {
            "safeMode": True,
            "factoryReset": True,
            "uninstallBlock": True,
            "guestMode": True,
            "usbDebugging": True,
        },
        "vpnFilter": {"enabled": True},
        "vpnBypassApps": [],
        "blockedWebsites": [],
        "frictionDelay": {"enabled": True, "seconds": 30},
        "habits": [],
        "rules": [],
        "appBudgets": [],
        # Salted hash only (see _handle_dashboard_route's /pin route) -- never returned by GET
        # .../settings. Metadata for a future phone-side sync, not a second web login: Caddy's
        # Basic Auth in front of /dashboard/ is this dashboard's actual login.
        "guardianPinHash": None,
        "guardianEmail": "",
        "updatedAt": None,
    }


def _device_settings(device_id: str, updates: dict | None = None) -> dict:
    """Returns device_id's dashboard settings, creating defaults on first call (mirrors
    _device_record's get-or-create shape above). `updates` merges one level deep -- a dict value
    (e.g. `protections`, `vpnFilter`) is updated key-by-key, anything else is replaced wholesale --
    then persists. Read-only calls (updates=None) on an already-existing record don't rewrite the
    file."""
    with _settings_lock:
        settings = _load_settings()
        record = settings.get(device_id)
        created = record is None
        if created:
            record = _default_device_settings()
        if updates:
            for key, value in updates.items():
                if isinstance(value, dict) and isinstance(record.get(key), dict):
                    record[key].update(value)
                else:
                    record[key] = value
            record["updatedAt"] = time.time()
        if created or updates:
            settings[device_id] = record
            _save_settings(settings)
        return record


def _list_item_add(device_id: str, list_key: str, item: dict) -> dict:
    with _settings_lock:
        settings = _load_settings()
        record = settings.get(device_id) or _default_device_settings()
        record.setdefault(list_key, []).append(item)
        record["updatedAt"] = time.time()
        settings[device_id] = record
        _save_settings(settings)
        return record


def _list_item_remove(device_id: str, list_key: str, match_field: str, match_value: str) -> dict:
    with _settings_lock:
        settings = _load_settings()
        record = settings.get(device_id) or _default_device_settings()
        items = record.get(list_key, [])
        record[list_key] = [i for i in items if str(i.get(match_field)) != str(match_value)]
        record["updatedAt"] = time.time()
        settings[device_id] = record
        _save_settings(settings)
        return record


def _list_item_update(device_id: str, list_key: str, item_id: str, updates: dict) -> dict | None:
    """Returns the updated settings record, or None if item_id isn't found in list_key (caller
    should 404 in that case)."""
    with _settings_lock:
        settings = _load_settings()
        record = settings.get(device_id) or _default_device_settings()
        found = False
        for item in record.get(list_key, []):
            if str(item.get("id")) == str(item_id):
                item.update(updates)
                found = True
                break
        if not found:
            return None
        record["updatedAt"] = time.time()
        settings[device_id] = record
        _save_settings(settings)
        return record


def _build_list_item(kind: str, body: dict) -> dict | None:
    """Validates and shapes a POST body for one of LIST_ENDPOINTS into the stored item shape.
    Returns None on a missing/blank required field -- caller sends 400."""
    if kind == "websites":
        domain = (body.get("domain") or "").strip().lower()
        return {"domain": domain, "addedAt": time.time()} if domain else None
    if kind == "bypass-apps":
        name = (body.get("name") or "").strip()
        return {"id": uuid.uuid4().hex, "name": name} if name else None
    if kind == "habits":
        name = (body.get("name") or "").strip()
        return {"id": uuid.uuid4().hex, "name": name} if name else None
    if kind == "rules":
        app_name = (body.get("appName") or "").strip()
        if not app_name:
            return None
        return {
            "id": uuid.uuid4().hex,
            "appId": body.get("appId", ""),
            "appName": app_name,
            "requiredHabitIds": body.get("requiredHabitIds") or [],
            "schedule": body.get("schedule") or {},
            "dailyBudgetMinutes": body.get("dailyBudgetMinutes"),
            "createdAt": time.time(),
        }
    if kind == "app-budgets":
        app_name = (body.get("appName") or "").strip()
        if not app_name:
            return None
        return {
            "id": uuid.uuid4().hex,
            "appId": body.get("appId", ""),
            "appName": app_name,
            "dailyLimitMinutes": body.get("dailyLimitMinutes"),
        }
    return None


def _list_known_device_ids() -> dict:
    """{device_id: {device_name, updatedAt, alertCount24h}} for every device_id seen either in
    device_settings.json or in alerts.jsonl -- a device can show up in tamper alerts long before a
    guardian ever opens the dashboard for it, or vice versa (settings configured ahead of the phone
    ever checking in), so the device list is the union of both sources rather than a separate
    registry."""
    devices: dict[str, dict] = {}
    for device_id, record in _load_settings().items():
        devices[device_id] = {
            "device_name": record.get("device_name", ""),
            "updatedAt": record.get("updatedAt"),
            "alertCount24h": 0,
        }
    cutoff = time.time() - 86400
    for event in _read_alerts_since(0):
        device_id = event.get("device_id")
        if not device_id:
            continue
        entry = devices.setdefault(device_id, {"device_name": "", "updatedAt": None, "alertCount24h": 0})
        if event.get("received_at", 0) >= cutoff:
            entry["alertCount24h"] += 1
    return devices


def _send_ntfy_notification(event: dict) -> None:
    """Best-effort push via ntfy.sh -- see module docstring. Never raises: a down/unreachable ntfy
    server must not turn an accepted tamper report into a failed request."""
    if not NTFY_TOPIC:
        return
    title, priority, tag = NTFY_EVENT_STYLE.get(
        event["type"], (f"Otterling: {event['type']}", "default", "warning")
    )
    message = f"{event['details']}\n(device {event['device_id']})" if event.get("details") else f"device {event['device_id']}"
    request = urllib.request.Request(
        f"{NTFY_SERVER}/{NTFY_TOPIC}",
        data=message.encode("utf-8"),
        method="POST",
        headers={
            "Title": title,
            "Priority": priority,
            "Tags": tag,
        },
    )
    try:
        urllib.request.urlopen(request, timeout=10).close()
    except (urllib.error.URLError, OSError) as error:
        print(f"[lockprofile] ntfy push failed for {event['type']}: {error}", flush=True)


# --- FCM push (optional) ---------------------------------------------------------------------

# Registered phone tokens live in state.json under this key: {token: {device_model, ...}}.
_FCM_TOKENS_KEY = "fcm_tokens"
# Lazily-built google-auth Credentials + resolved project id, guarded by this lock. `False` means
# "already tried and it's unavailable" (missing file or google-auth not installed) so we don't retry
# the import/load on every event.
_fcm_lock = threading.Lock()
_fcm_creds = None
_fcm_project_id = None
_fcm_unavailable = False


def _register_fcm_token(token: str, device_model: str) -> None:
    """Upsert a phone's FCM token. Idempotent -- the phone re-registers on every launch."""
    with _state_lock:
        state = _load_state()
        tokens = state.get(_FCM_TOKENS_KEY) or {}
        tokens[token] = {
            "device_model": device_model,
            "registered_at": tokens.get(token, {}).get("registered_at", time.time()),
            "last_seen": time.time(),
        }
        state[_FCM_TOKENS_KEY] = tokens
        _save_state(state)


def _all_fcm_tokens() -> list[str]:
    with _state_lock:
        return list((_load_state().get(_FCM_TOKENS_KEY) or {}).keys())


def _forget_fcm_token(token: str) -> None:
    """Drop a token FCM told us is dead (UNREGISTERED / not found), so the list doesn't rot."""
    with _state_lock:
        state = _load_state()
        tokens = state.get(_FCM_TOKENS_KEY) or {}
        if tokens.pop(token, None) is not None:
            state[_FCM_TOKENS_KEY] = tokens
            _save_state(state)


def _fcm_credentials():
    """Return (credentials, project_id) or (None, None) if FCM isn't configured/available. Loads the
    service-account file once and caches it; the google-auth Credentials object refreshes its own
    access token as needed, so callers just call creds.token after a refresh."""
    global _fcm_creds, _fcm_project_id, _fcm_unavailable
    with _fcm_lock:
        if _fcm_creds is not None:
            return _fcm_creds, _fcm_project_id
        if _fcm_unavailable:
            return None, None
        if not os.path.exists(FCM_CREDENTIALS_PATH):
            _fcm_unavailable = True
            return None, None
        try:
            from google.oauth2 import service_account  # lazy: FCM is optional
            creds = service_account.Credentials.from_service_account_file(
                FCM_CREDENTIALS_PATH, scopes=[FCM_SCOPE]
            )
            with open(FCM_CREDENTIALS_PATH, "r", encoding="utf-8") as fh:
                project_id = json.load(fh).get("project_id")
            if not project_id:
                raise ValueError("service-account file has no project_id")
            _fcm_creds, _fcm_project_id = creds, project_id
            print(f"[lockprofile] FCM push enabled for project {project_id}", flush=True)
            return creds, project_id
        except Exception as error:  # noqa: BLE001 -- any failure just disables push
            print(f"[lockprofile] FCM disabled ({type(error).__name__}: {error})", flush=True)
            _fcm_unavailable = True
            return None, None


def _send_fcm_wake(event: dict) -> None:
    """Best-effort FCM 'poll now' wake to every registered phone. Never raises. The data payload is
    advisory only -- the phone re-pulls from /alerts/poll, which is the source of truth."""
    creds, project_id = _fcm_credentials()
    if creds is None:
        return
    tokens = _all_fcm_tokens()
    if not tokens:
        return
    try:
        from google.auth.transport.requests import Request as GoogleAuthRequest
        if not creds.valid:
            creds.refresh(GoogleAuthRequest())
        access_token = creds.token
    except Exception as error:  # noqa: BLE001
        print(f"[lockprofile] FCM token refresh failed: {error}", flush=True)
        return

    url = f"https://fcm.googleapis.com/v1/projects/{project_id}/messages:send"
    for token in tokens:
        message = {
            "message": {
                "token": token,
                # Data-only (no "notification" block) so the app's onMessageReceived always runs and
                # can wake the poller, rather than the system silently tray-ing a notification.
                "data": {"type": str(event.get("type", "")), "reason": "tamper"},
                "android": {"priority": "high"},
            }
        }
        request = urllib.request.Request(
            url,
            data=json.dumps(message).encode("utf-8"),
            method="POST",
            headers={
                "Authorization": f"Bearer {access_token}",
                "Content-Type": "application/json",
            },
        )
        try:
            urllib.request.urlopen(request, timeout=10).close()
        except urllib.error.HTTPError as error:
            # 404 (or UNREGISTERED) means the token is dead -- prune it so we stop trying.
            if error.code in (404, 400):
                _forget_fcm_token(token)
            print(f"[lockprofile] FCM send failed for {event.get('type')}: HTTP {error.code}", flush=True)
        except (urllib.error.URLError, OSError) as error:
            print(f"[lockprofile] FCM send failed for {event.get('type')}: {error}", flush=True)


def _check_sudo_command(command: str, reason: str) -> tuple[str, str]:
    """Calls out to the host-level `sudo_review_server.py` (see its own doc comment for why this
    can't happen inside this container). ANY failure -- unreachable, timeout, malformed response --
    is "deny", never "allow": this is the one place in the whole system that fails closed on purpose,
    since an admin command denied on a hiccup is safe and recoverable, unlike DNS/proxy enforcement
    failing open elsewhere in this project."""
    payload = json.dumps({"command": command, "reason": reason}).encode("utf-8")
    request = urllib.request.Request(
        SUDO_REVIEW_URL, data=payload, method="POST", headers={"Content-Type": "application/json"}
    )
    try:
        with urllib.request.urlopen(request, timeout=SUDO_REVIEW_TIMEOUT) as response:
            parsed = json.loads(response.read().decode("utf-8"))
    except (urllib.error.URLError, OSError, json.JSONDecodeError, UnicodeDecodeError) as error:
        return "deny", f"Could not reach the AI reviewer ({error}) -- denying on failure."

    verdict = str(parsed.get("verdict", "")).lower()
    explanation = str(parsed.get("explanation", "(no explanation)"))
    if verdict not in ("allow", "deny"):
        return "deny", f"Reviewer returned an unrecognized verdict -- denying on ambiguity. {explanation}"
    return verdict, explanation


def _translate_request(request_text: str) -> tuple[list[str], str]:
    """Calls out to the host-level reviewer's `/translate` route -- pure natural-language-to-shell
    translation, no safety reasoning (see that route's own doc comment for why). Every command it
    returns still goes through `_check_sudo_command` individually before anything executes."""
    payload = json.dumps({"request": request_text}).encode("utf-8")
    request = urllib.request.Request(
        SUDO_TRANSLATE_URL, data=payload, method="POST", headers={"Content-Type": "application/json"}
    )
    try:
        with urllib.request.urlopen(request, timeout=SUDO_TRANSLATE_TIMEOUT) as response:
            parsed = json.loads(response.read().decode("utf-8"))
    except (urllib.error.URLError, OSError, json.JSONDecodeError, UnicodeDecodeError) as error:
        return [], f"Could not reach the AI assistant ({error})."
    commands = parsed.get("commands", [])
    if not isinstance(commands, list):
        return [], "Assistant returned a malformed response."
    return [str(c) for c in commands], str(parsed.get("explanation", ""))


def _push_event(event: dict) -> None:
    """Fan an accepted event out to both push channels, each best-effort and non-blocking."""
    threading.Thread(target=_send_ntfy_notification, args=(event,), daemon=True).start()
    threading.Thread(target=_send_fcm_wake, args=(event,), daemon=True).start()


class Handler(BaseHTTPRequestHandler):
    server_version = "OtterlingLockProfile/1.0"

    def _send_json(self, code: int, body: dict) -> None:
        payload = json.dumps(body).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def _authorized(self) -> bool:
        if not TOKEN:
            return False
        header = self.headers.get("Authorization", "")
        prefix = "Bearer "
        if not header.startswith(prefix):
            return False
        return secrets.compare_digest(header[len(prefix):], TOKEN)

    def _read_json_body(self, max_bytes: int = MAX_BODY_BYTES) -> dict | None:
        try:
            length = int(self.headers.get("Content-Length", "0") or "0")
        except ValueError:
            # A non-numeric Content-Length used to raise here uncaught, killing this connection's
            # handler thread instead of returning the 400 every other malformed-request path gets.
            return None
        if length <= 0 or length > max_bytes:
            return None
        try:
            return json.loads(self.rfile.read(length).decode("utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError):
            return None

    def _handle_fleet_webhook(self, parsed) -> None:
        """Fleet's failing-policy webhook -> the existing tamper-alert pipeline (JSONL + ntfy +
        the phone's /alerts/poll -> SMS relay). Authenticated by a `?token=` query secret rather
        than the Bearer TOKEN, because Fleet's webhook sends no auth header. Fleet's payload is
        `{"policy": {"name": ...}, "hosts": [{"display_name"/"hostname"/"url"/"id": ...}]}`; each
        host becomes one `mac_tamper_policy` event so the partner is told which machine and why."""
        query = urllib.parse.parse_qs(parsed.query)
        provided = query.get("token", [""])[0]
        if not FLEET_WEBHOOK_SECRET or not secrets.compare_digest(provided, FLEET_WEBHOOK_SECRET):
            return self._send_json(403, {"error": "forbidden"})
        body = self._read_json_body()
        if body is None:
            return self._send_json(400, {"error": "bad json"})

        policy_name = (body.get("policy") or {}).get("name") or "an Otterling policy"
        hosts = body.get("hosts") or [{}]  # still emit one event if Fleet omits host detail
        count = 0
        for host in hosts:
            name = host.get("display_name") or host.get("hostname") or (
                f"host {host.get('id')}" if host.get("id") else "unknown-mac"
            )
            url = host.get("url")
            details = f"'{policy_name}' is failing on {name}." + (f" {url}" if url else "")
            event = _append_alert({
                "device_id": name,
                "type": "mac_tamper_policy",
                "details": details,
                "reported_at": time.time(),
                "received_at": time.time(),
            })
            if event:
                _push_event(event)
                count += 1
        return self._send_json(200, {"status": "ok", "events": count})

    def _set_dashboard_session_cookie(self, token: str | None) -> None:
        """`token=None` clears the cookie (logout) by writing one that's already expired.
        `HttpOnly` so the dashboard's own JS can never read it (nothing to steal via XSS);
        `SameSite=Strict` since this cookie should never be sent cross-site; `Secure` because this
        server is only ever reached over HTTPS (Caddy) or the LAN vhost, never plain HTTP with
        anything sensitive riding along."""
        if token is None:
            value = "deleted; Max-Age=0"
        else:
            value = f"{token}; Max-Age={DASHBOARD_SESSION_MAX_AGE_SECONDS}"
        self.send_header(
            "Set-Cookie",
            f"otterling_dashboard_session={value}; Path=/; HttpOnly; Secure; SameSite=Strict",
        )

    def _handle_dashboard_login(self) -> None:
        if not DASHBOARD_USER or not DASHBOARD_LOGIN_PASSWORD:
            return self._send_json(503, {"error": "dashboard login not configured -- set DASHBOARD_USER/DASHBOARD_LOGIN_PASSWORD"})
        body = self._read_json_body()
        username = (body or {}).get("username", "")
        password = (body or {}).get("password", "")
        if not (secrets.compare_digest(username, DASHBOARD_USER) and secrets.compare_digest(password, DASHBOARD_LOGIN_PASSWORD)):
            return self._send_json(401, {"error": "invalid username or password"})
        token = _dashboard_session_create()
        payload = json.dumps({"status": "ok"}).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self._set_dashboard_session_cookie(token)
        self.end_headers()
        self.wfile.write(payload)

    def _handle_dashboard_logout(self) -> None:
        payload = json.dumps({"status": "ok"}).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self._set_dashboard_session_cookie(None)
        self.end_headers()
        self.wfile.write(payload)

    def _handle_dashboard_verify(self) -> None:
        """Backs Caddy's `forward_auth` in front of `/dashboard/*` and `/dashboard-api/*` -- Caddy
        calls this on every request to those paths and only lets the real request through on 2xx.
        Deliberately checks nothing but the cookie (no Bearer token): a browser has no way to send
        one, and the cookie itself is what proves the login happened."""
        cookie = _dashboard_cookie_from_headers(self.headers)
        if cookie and _dashboard_session_valid(cookie):
            return self._send_json(200, {"status": "ok"})
        return self._send_json(401, {"error": "not logged in"})

    def do_POST(self):  # noqa: N802 (http.server API)
        parsed = urllib.parse.urlparse(self.path)
        # This one route authenticates on a query secret (Fleet can't send a Bearer header), so it
        # is dispatched BEFORE the Bearer gate that every other route below still passes through.
        if parsed.path == "/alerts/fleet-webhook":
            return self._handle_fleet_webhook(parsed)
        # Dashboard login/logout authenticate on the dashboard's own username/password or session
        # cookie, not the Bearer TOKEN -- a browser has neither, so these too must run before the
        # Bearer gate below.
        if parsed.path == "/dashboard-auth/login":
            return self._handle_dashboard_login()
        if parsed.path == "/dashboard-auth/logout":
            return self._handle_dashboard_logout()

        if not self._authorized():
            return self._send_json(401, {"error": "unauthorized"})

        if self.path == "/lockprofile/provision":
            body = self._read_json_body()
            device_id = (body or {}).get("device_id", "").strip()
            if not device_id:
                return self._send_json(400, {"error": "device_id required"})
            profile_bytes = build_mobileconfig(device_id)
            self.send_response(200)
            self.send_header("Content-Type", "application/x-apple-aspen-config")
            self.send_header("Content-Length", str(len(profile_bytes)))
            self.end_headers()
            self.wfile.write(profile_bytes)
            return

        if self.path == "/alerts/tamper":
            body = self._read_json_body()
            if not body or not body.get("device_id") or not body.get("type"):
                return self._send_json(400, {"error": "device_id and type required"})
            event = {
                "device_id": body["device_id"],
                # Optional human-readable computer name (e.g. "Tristan's MacBook Pro") --
                # device_id itself stays a stable opaque key (IOPlatformUUID for the Mac daemon,
                # a client IP for the mitm proxy's block_reporter.py) so per-device server records
                # keep working; this is purely for the phone's SMS text (see AlertReporter
                # .formatBody), which falls back to its own device name when this is absent.
                "device_name": body.get("device_name", ""),
                "type": body["type"],
                "details": body.get("details", ""),
                "reported_at": body.get("ts", time.time()),
                "received_at": time.time(),
            }
            event = _append_alert(event)
            # Backgrounded so a slow/unreachable push channel can't delay this response -- the
            # daemon's own TamperReporter has just a 10s timeout and one retry, and the JSONL append
            # above (the actual source of truth) is already durable before this fires. Pushes to both
            # ntfy and FCM (the phone's instant "poll now" wake). `event` is None if this type is
            # disabled in report_types.json -- still a 200 "ok" either way (the Mac shouldn't treat
            # a muted report type as a failed request).
            if event:
                _push_event(event)
            return self._send_json(200, {"status": "ok"})

        if self.path == "/alerts/register-token":
            # The phone hands us its FCM token so _send_fcm_wake can reach it (see FcmTokenRegistrar
            # / MacTamperMessagingService on the Android side). Bearer-gated like every route here.
            body = self._read_json_body()
            token = (body or {}).get("token", "").strip()
            if not token:
                return self._send_json(400, {"error": "token required"})
            _register_fcm_token(token, (body or {}).get("device_model", "").strip() or "unknown")
            return self._send_json(200, {"status": "ok"})

        if self.path == "/integrity/checkin":
            body = self._read_json_body()
            if not body or not body.get("device_id") or "dirty" not in body:
                return self._send_json(400, {"error": "device_id and dirty required"})
            git_sha = str(body.get("git_sha", "unknown"))[:64]
            if body.get("dirty"):
                event = _append_alert({
                    "device_id": body["device_id"],
                    "device_name": body.get("device_name", ""),
                    "type": "mac_code_tampered",
                    "details": f"built from an uncommitted, locally-modified source tree (git {git_sha[:12]})",
                    "reported_at": body.get("ts", time.time()),
                    "received_at": time.time(),
                })
                if event:
                    _push_event(event)
            # NOTE on what this does NOT verify: it does not confirm `git_sha` was ever pushed to, or
            # is reachable from, this repo's main branch -- that would need this server to hold live
            # GitHub credentials and keep a checkout in sync, which isn't set up (see SELF_LOCKOUT.md
            # for why the CI checkout in particular is handled carefully). So a locally *committed*
            # change that's simply never pushed passes this check silently. What this DOES reliably
            # catch is the direct case: edit a file and rebuild without committing at all -- `dirty`
            # is computed by `git status` at build time and can't be faked without also faking a
            # clean tree, i.e. actually committing the change somewhere findable.
            return self._send_json(200, {"status": "ok"})

        if self.path == "/sudo-review/check":
            body = self._read_json_body()
            command = (body or {}).get("command", "").strip()
            if not command:
                return self._send_json(400, {"error": "command required"})
            reason = (body or {}).get("reason", "")
            device_id = (body or {}).get("device_id", "unknown")
            device_name = (body or {}).get("device_name", "")
            verdict, explanation = _check_sudo_command(command, reason)
            # Reported here too (not just by the Mac's own TamperReporter call) so a request is
            # visible even if the daemon crashes/is killed before it can report its own result --
            # the whole point of this system is that a decision can never go unreported.
            event = _append_alert({
                "device_id": device_id,
                "device_name": device_name,
                "type": "sudo_request_ai_reviewed",
                "details": f"[{verdict.upper()}] \"{command}\" (reason: \"{reason}\") -- {explanation}",
                "reported_at": time.time(),
                "received_at": time.time(),
            })
            # Verdict/explanation still return to the Mac regardless of whether this report type is
            # muted -- disabling a report must never change the actual sudo decision.
            if event:
                _push_event(event)
            return self._send_json(200, {"verdict": verdict, "explanation": explanation})

        if self.path == "/ai-assistant/translate":
            body = self._read_json_body()
            request_text = (body or {}).get("request", "").strip()
            if not request_text:
                return self._send_json(400, {"error": "request required"})
            commands, explanation = _translate_request(request_text)
            return self._send_json(200, {"commands": commands, "explanation": explanation})

        if self.path == "/device-logs/upload":
            body = self._read_json_body(MAX_LOG_BODY_BYTES)
            device_id = _safe_device_id((body or {}).get("device_id", "").strip())
            logs = (body or {}).get("logs", "") if body else ""
            if not device_id or not logs:
                return self._send_json(400, {"error": "device_id and logs required"})
            filename = _store_device_log(device_id, logs)
            return self._send_json(200, {"status": "ok", "filename": filename})

        if self.path.startswith("/dashboard-api/"):
            body = self._read_json_body()
            if self._handle_dashboard_route("POST", self.path, urllib.parse.urlparse(self.path), body):
                return

        return self._send_json(404, {"error": "not found"})

    def do_PATCH(self):  # noqa: N802
        if not self._authorized():
            return self._send_json(401, {"error": "unauthorized"})
        if self.path.startswith("/dashboard-api/"):
            body = self._read_json_body()
            if self._handle_dashboard_route("PATCH", self.path, urllib.parse.urlparse(self.path), body):
                return
        return self._send_json(404, {"error": "not found"})

    def do_DELETE(self):  # noqa: N802
        if not self._authorized():
            return self._send_json(401, {"error": "unauthorized"})
        if self.path.startswith("/dashboard-api/"):
            if self._handle_dashboard_route("DELETE", self.path, urllib.parse.urlparse(self.path), None):
                return
        return self._send_json(404, {"error": "not found"})

    # ─── Dashboard device settings (/dashboard-api/*) ──────────────────────────────────────────
    # Shared by do_GET/do_POST/do_PATCH/do_DELETE above -- see the module-level "Dashboard device
    # settings" section (LIST_ENDPOINTS, _device_settings, _list_item_*, _build_list_item,
    # _list_known_device_ids) for the storage this dispatches to. Returns True once it has sent a
    # response (any response, including an error), False only for "not a dashboard-api path I
    # recognize" so the caller's own 404 fallback fires -- callers must not send anything themselves
    # after a True return.
    def _handle_dashboard_route(self, method: str, path: str, parsed, body: dict | None) -> bool:
        if path == "/dashboard-api/devices" and method == "GET":
            devices = [{"device_id": k, **v} for k, v in _list_known_device_ids().items()]
            self._send_json(200, {"devices": devices})
            return True

        match = DASHBOARD_DEVICE_RE.match(path)
        if not match:
            return False
        device_id = _safe_device_id(match.group(1))
        if device_id is None:
            self._send_json(400, {"error": "invalid device_id"})
            return True
        parts = [p for p in (match.group(2) or "").split("/") if p]

        if parts == ["settings"] and method in ("GET", "PATCH"):
            updates = None
            if method == "PATCH":
                if body is None:
                    self._send_json(400, {"error": "bad json"})
                    return True
                # Allowlisted, not a raw pass-through: everything else (rules, habits,
                # blockedWebsites, vpnBypassApps, appBudgets) has its own dedicated endpoint above,
                # and guardianPinHash must only ever be set via the salted-hash /pin route below --
                # letting an arbitrary PATCH body reach _device_settings unfiltered would let a
                # caller overwrite guardianPinHash directly, bypassing that hashing entirely.
                updates = {k: v for k, v in body.items() if k in SETTINGS_PATCH_ALLOWED_KEYS}
            record = _device_settings(device_id, updates)
            record = {k: v for k, v in record.items() if k != "guardianPinHash"}
            self._send_json(200, record)
            return True

        if parts == ["activity"] and method == "GET":
            query = urllib.parse.parse_qs(parsed.query)
            try:
                since_id = int(query.get("since_id", ["0"])[0])
            except ValueError:
                self._send_json(400, {"error": "since_id must be an integer"})
                return True
            events = [e for e in _read_alerts_since(since_id) if e.get("device_id") == device_id]
            max_id = events[-1]["id"] if events else since_id
            self._send_json(200, {"events": events, "max_id": max_id})
            return True

        if parts == ["pin"] and method == "POST":
            pin = (body or {}).get("pin", "").strip()
            if not pin:
                self._send_json(400, {"error": "pin required"})
                return True
            salt = secrets.token_hex(16)
            digest = hashlib.sha256((salt + pin).encode("utf-8")).hexdigest()
            _device_settings(device_id, {"guardianPinHash": f"{salt}${digest}"})
            self._send_json(200, {"status": "ok"})
            return True

        if len(parts) == 1 and parts[0] in LIST_ENDPOINTS and method in ("GET", "POST"):
            list_key, _ = LIST_ENDPOINTS[parts[0]]
            if method == "GET":
                record = _device_settings(device_id)
                self._send_json(200, {list_key: record.get(list_key, [])})
                return True
            if body is None:
                self._send_json(400, {"error": "bad json"})
                return True
            item = _build_list_item(parts[0], body)
            if item is None:
                self._send_json(400, {"error": "invalid payload"})
                return True
            record = _list_item_add(device_id, list_key, item)
            self._send_json(200, {list_key: record.get(list_key, [])})
            return True

        if len(parts) == 2 and parts[0] in LIST_ENDPOINTS and method in ("DELETE", "PATCH"):
            list_key, id_field = LIST_ENDPOINTS[parts[0]]
            item_id = urllib.parse.unquote(parts[1])
            if method == "DELETE":
                record = _list_item_remove(device_id, list_key, id_field, item_id)
                self._send_json(200, {list_key: record.get(list_key, [])})
                return True
            # PATCH (in-place edit): only rules/app-budgets support this -- websites/bypass-apps/
            # habits are add-only-with-guardian-remove per the design doc, no edit-in-place case.
            if parts[0] not in ("rules", "app-budgets"):
                self._send_json(405, {"error": "method not allowed"})
                return True
            if body is None:
                self._send_json(400, {"error": "bad json"})
                return True
            record = _list_item_update(device_id, list_key, item_id, body)
            if record is None:
                self._send_json(404, {"error": "not found"})
                return True
            self._send_json(200, {list_key: record.get(list_key, [])})
            return True

        self._send_json(404, {"error": "not found"})
        return True

    def do_GET(self):  # noqa: N802
        parsed = urllib.parse.urlparse(self.path)
        # Caddy's forward_auth calls this on every /dashboard*/ request; a browser has no Bearer
        # token to send, so this has to run before the Bearer gate below (same reasoning as the
        # login/logout routes in do_POST).
        if parsed.path == "/dashboard-auth/verify":
            return self._handle_dashboard_verify()

        if not self._authorized():
            return self._send_json(401, {"error": "unauthorized"})

        if parsed.path == "/report-config":
            # Lets the phone's own AlertReporter (Android-origin types never touch this server --
            # see report_types.json's "_readme") honor the same enabled/disabled list this file
            # already enforces for mac/server-origin types. `{type: enabled}` only -- source/
            # description are for humans editing the file, not needed on the wire.
            config = _load_report_config()
            return self._send_json(200, {"types": {k: v.get("enabled", True) is not False for k, v in config.items()}})

        if parsed.path == "/alerts/poll":
            query = urllib.parse.parse_qs(parsed.query)
            try:
                since_id = int(query.get("since_id", ["0"])[0])
            except ValueError:
                return self._send_json(400, {"error": "since_id must be an integer"})
            # Polling at since_id acknowledges durable receipt of everything up to it, so the
            # rotation never drops those -- and, more importantly, never drops anything ABOVE it.
            _record_ack(since_id)
            events = _read_alerts_since(since_id)
            max_id = events[-1]["id"] if events else since_id
            return self._send_json(200, {"events": events, "max_id": max_id})

        if parsed.path == "/device-logs/view/list":
            return self._send_json(200, {"devices": _list_device_logs()})

        if parsed.path.startswith("/device-logs/view/"):
            remainder = parsed.path[len("/device-logs/view/"):]
            parts = remainder.split("/", 1)
            if len(parts) != 2:
                return self._send_json(404, {"error": "not found"})
            device_id, filename = parts
            if not DEVICE_ID_RE.match(device_id) or "/" in filename or ".." in filename:
                return self._send_json(400, {"error": "invalid path"})
            path = os.path.join(LOGS_DIR, device_id, filename)
            if not os.path.isfile(path):
                return self._send_json(404, {"error": "not found"})
            with open(path, "rb") as fh:
                content = fh.read()
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(content)))
            self.end_headers()
            self.wfile.write(content)
            return

        if parsed.path.startswith("/dashboard-api/"):
            if self._handle_dashboard_route("GET", parsed.path, parsed, None):
                return

        return self._send_json(404, {"error": "not found"})

    def log_message(self, fmt, *args):  # quiet -- Caddy/docker already logs the request line
        return


def main() -> None:
    if not TOKEN:
        raise SystemExit("LOCKPROFILE_TOKEN must be set -- refusing to start unauthenticated")
    server = ThreadingHTTPServer((LISTEN_HOST, LISTEN_PORT), Handler)
    print(f"[lockprofile] listening on {LISTEN_HOST}:{LISTEN_PORT}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
