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
# Phone device ids come from Settings.Secure.ANDROID_ID -- always a 16-char lowercase hex string
# in practice -- but _list_known_device_ids() also surfaces ids from non-phone reporters sharing
# this same alerts/settings storage (e.g. IntegrityReporter.swift's Mac hostnames/IPs), which
# legitimately contain dots. Allowed here so the dashboard can actually fetch settings for every
# id it lists (a dotted id 404ing through DASHBOARD_DEVICE_RE previously left the dashboard stuck
# on "Loading..." with the error silently swallowed). Still excludes "/" and any ".." substring,
# since this becomes part of a filesystem path below (_store_device_log) -- a single "." is inert
# with os.path.join, but ".." would let device_id escape LOGS_DIR.
DEVICE_ID_RE = re.compile(r"^(?!.*\.\.)[A-Za-z0-9_.-]{1,128}$")

# Neither client sends an explicit platform field today, so this is inferred purely from
# device_id shape: the Mac's canonical id (TamperReporter.swift/install_lock_profile.py's
# IOPlatformUUID) is always a dashed UUID; Android's ANDROID_ID (see comment above) is always a
# bare hex token with no dashes. The two never collide for this fleet. Used to decide which
# dashboard-api settings sections actually apply to a device -- most of device_settings.json
# (protections, vpnFilter/vpnBypassApps, blockedWebsites, rules, habits, appBudgets,
# triggerWords, blockedApps) is consumed ONLY by the Android app's DashboardConfigStore
# consumers; nothing on the Mac reads dashboard-api at all today.
_UUID_RE = re.compile(r"^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")


def _detect_platform(device_id: str) -> str:
    return "macos" if _UUID_RE.match(device_id) else "android"

# The Mac has no single canonical identity across every reporter: TamperReporter.swift/
# install_lock_profile.py use IOPlatformUUID (the real, stable identity per-device settings are
# provisioned under), but deadman.py's Fleet lookups key on a configurable hostname and
# block_reporter.py (the mitm proxy addon, which only ever sees network-layer traffic) keys on
# client IP -- so a hostname change or a new DHCP lease used to mint a brand-new "device" in the
# dashboard for a machine that was never actually new. DEVICE_ID_ALIASES lets a deployer declare
# "this hostname/IP is actually this device_id" so every reporting path collapses onto the same
# entry. JSON object in the env var, e.g. {"192.168.0.115": "<canonical-id>", "old-hostname.local":
# "<canonical-id>"} -- unset/invalid means no aliasing (today's behavior).
def _load_device_id_aliases() -> dict:
    raw = os.environ.get("DEVICE_ID_ALIASES", "").strip()
    if not raw:
        return {}
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError:
        return {}
    return {str(k): str(v) for k, v in parsed.items()} if isinstance(parsed, dict) else {}


DEVICE_ID_ALIASES = _load_device_id_aliases()


def _canonicalize_device_id(device_id: str) -> str:
    return DEVICE_ID_ALIASES.get(device_id, device_id)


# Keeps ALERTS_PATH from growing without bound -- see _rotate_alerts_if_needed().
ALERTS_ROTATE_BYTES = 5 * 1024 * 1024
ALERTS_ROTATE_KEEP_LINES = 2000

# Per-device dashboard settings (see /dashboard-api/* below and filter-server/dashboard/) -- kept in
# its own file/lock rather than folded into STATE_PATH's per-device records, since those hold the
# mobileconfig passcode/UUIDs (a different concern with different write patterns) and this project
# already separates concerns that way (e.g. alerts get their own ALERTS_PATH/_alerts_lock too).
SETTINGS_PATH = os.path.join(DATA_DIR, "device_settings.json")

# Guardian PIN is deliberately NOT part of per-device settings: it's one shared secret for a
# guardian's whole fleet (see /dashboard-api/pin below), not something that varies per device.
# Stored as plaintext, not a hash: the server relays the raw PIN so each phone can feed it
# straight into its own existing PinAuthManager.setPin(), which is what actually seals it behind
# Android Keystore-backed encryption on-device. A 4-digit PIN only has 10,000 possible values, so
# a server-side hash would add no real protection over plaintext if this file were ever read --
# the on-device Keystore sealing is the only thing that meaningfully protects this secret either way.
GUARDIAN_PIN_PATH = os.path.join(DATA_DIR, "guardian_pin.json")

# Habits are a single shared library across every device, same reasoning as the Guardian PIN
# above -- a habit ("Read 30 min") verified on the phone needs to be referenceable by a rule
# stored under ANY device's record (e.g. a Mac rule gating an app on that habit), so it can't
# live inside one device's own per-device settings the way it used to. See
# /dashboard-api/habits below. HABIT_COMPLETIONS_PATH is the companion "is this habit done
# today" state, reported by whichever device actually verifies the habit (see
# /dashboard-api/habits/<id>/complete) -- kept in its own file/lock since it's written far more
# often (every habit check-in) than the library itself (only edited by the guardian).
HABITS_PATH = os.path.join(DATA_DIR, "habits.json")
HABIT_COMPLETIONS_PATH = os.path.join(DATA_DIR, "habit_completions.json")

_state_lock = threading.Lock()
_alerts_lock = threading.Lock()
_settings_lock = threading.Lock()
_pin_lock = threading.Lock()
_habits_lock = threading.Lock()
_habit_completions_lock = threading.Lock()


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
    # See DEVICE_ID_ALIASES -- collapses hostname/IP-based reporters onto the same device_id a
    # UUID-based reporter (or provisioning) already uses for the same physical machine.
    event = {**event, "device_id": _canonicalize_device_id(event.get("device_id", "")), "id": event_id}
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
    if not DEVICE_ID_RE.match(raw or ""):
        return None
    return _canonicalize_device_id(raw)


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
# above. Pulled and enforced by both clients: the Android app's DashboardConfigStore.kt (see
# filter-server/dashboard/SERVER_DRIVEN_CONFIG_PLAN.md) and the Mac daemon's
# DashboardConfigSync.swift (macos/FocusLock/Sources/FocusLockHelperd/) each poll their own
# device's settings record and reconcile local state against it.

# (dashboard-path-segment -> (settings list key, item-matching field)). Both the generic
# add/remove handlers and _build_list_item below key off this table.
LIST_ENDPOINTS = {
    "websites": ("blockedWebsites", "domain"),
    "bypass-apps": ("vpnBypassApps", "id"),
    # habits is NOT here -- it moved to a global library (see HABITS_PATH /
    # /dashboard-api/habits below), since a rule on ANY device can now reference a habit
    # verified on a different device. `rules` stays per-device: `requiredHabitIds` reference
    # the global habit ids, but WHERE a rule applies (which device, which app) is still
    # per-device.
    "rules": ("rules", "id"),
    "app-budgets": ("appBudgets", "id"),
    "trigger-words": ("triggerWords", "word"),
    "blocked-apps": ("blockedApps", "appId"),
    # macos-only (see DashboardConfigSync.swift's reconcile) -- an app kept alive/undeletable
    # (schg-locked), the inverse of blocked-apps. Meaningless for Android, same as protectedApps
    # not appearing in _default_device_settings() below.
    "protected-apps": ("protectedApps", "executableName"),
}

# Mirrors MitmExemptManager.NEVER_EXEMPT_PACKAGES on the Android side (see
# app/src/main/java/app/otterling/content/MitmExemptManager.kt) -- a general browser can be
# pointed at literally any site, so exempting it from MITM interception would defeat content
# filtering entirely. Enforced here too, in _build_list_item below, not just trusted to the
# Android client's own veto in MitmExemptManager.add() -- a compromised or careless dashboard
# edit must not be able to reach the phone with this package name in vpnBypassApps.
NEVER_EXEMPT_PACKAGES = {
    "com.android.chrome",
    "com.chrome.beta",
    "com.chrome.dev",
    "com.chrome.canary",
}

# Mirrors the self-block guard already in PackageDisableStore.markBlocked/AppSuspensionManager on
# the Android side -- blocking Otterling's own package would brick the parent's ability to manage
# the device. Enforced here too so a careless dashboard edit can't reach the phone with it.
OTTERLING_PACKAGE_NAME = "app.otterling"

# Mirrors AppBlockEnforcer.protectedExecutables on the Mac (macos/FocusLock/Sources/
# FocusLockHelperd/AppBlockEnforcer.swift) -- that file already refuses to kill these regardless
# of what's in blockedApps, so this is defense-in-depth, not the only guard. Keep in sync by hand
# (no shared source between the Python server and the Swift daemon for this list).
MAC_OWN_EXECUTABLE_NAMES = {
    "FocusLockHelperd", "FocusLock", "focuslockctl", "FocusLockWatchdog", "FocusLockScanner",
}

DASHBOARD_DEVICE_RE = re.compile(r"^/dashboard-api/devices/([A-Za-z0-9_.-]{1,128})((?:/.+)?)$")
# Matches /dashboard-api/habits/<id> (DELETE) and /dashboard-api/habits/<id>/complete (POST).
HABIT_ITEM_RE = re.compile(r"^/dashboard-api/habits/([A-Za-z0-9]+)(/complete)?$")

# PATCH .../settings is allowlisted to these keys -- everything else in _default_device_settings
# (rules, blockedWebsites, vpnBypassApps, appBudgets, triggerWords, blockedApps, protectedApps,
# updatedAt) is either managed through its own dedicated endpoint or server-computed, not
# client-settable via this route. The guardian PIN and habit library aren't part of device
# settings at all -- see GUARDIAN_PIN_PATH / the global /dashboard-api/pin route, and
# HABITS_PATH / /dashboard-api/habits, below.
#
# cooldownHours/proxyFilter/cloudFilterHost/cloudFilterEnabled are macos-only (see
# DashboardConfigSync.swift's reconcile) -- meaningless for Android, same as protections is
# meaningless for macOS. Deliberately default to None/absent in _default_device_settings rather
# than a concrete value: DashboardConfigSync only reconciles a field when it's non-null, which
# keeps "the guardian genuinely wants this value" distinct from "this device record's updatedAt
# happened to be set by an unrelated edit" -- see that file's reconcile() doc comment.
SETTINGS_PATCH_ALLOWED_KEYS = {
    "device_name", "protections", "vpnFilter", "frictionDelay", "guardianEmail",
    "cooldownHours", "proxyFilter", "cloudFilterHost", "cloudFilterEnabled",
}


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


def _load_guardian_pin() -> dict:
    try:
        with open(GUARDIAN_PIN_PATH, "r", encoding="utf-8") as fh:
            return json.load(fh)
    except (FileNotFoundError, json.JSONDecodeError):
        return {"pin": None, "updatedAt": None}


def _save_guardian_pin(pin: str) -> dict:
    with _pin_lock:
        record = {"pin": pin, "updatedAt": time.time()}
        os.makedirs(DATA_DIR, exist_ok=True)
        tmp_path = GUARDIAN_PIN_PATH + ".tmp"
        with open(tmp_path, "w", encoding="utf-8") as fh:
            json.dump(record, fh, indent=2, sort_keys=True)
        os.replace(tmp_path, GUARDIAN_PIN_PATH)
        return record


def _load_habits() -> list:
    try:
        with open(HABITS_PATH, "r", encoding="utf-8") as fh:
            data = json.load(fh)
            return data.get("habits", []) if isinstance(data, dict) else []
    except (FileNotFoundError, json.JSONDecodeError):
        return []


def _save_habits(habits: list) -> None:
    os.makedirs(DATA_DIR, exist_ok=True)
    tmp_path = HABITS_PATH + ".tmp"
    with open(tmp_path, "w", encoding="utf-8") as fh:
        json.dump({"habits": habits}, fh, indent=2, sort_keys=True)
    os.replace(tmp_path, HABITS_PATH)


def _load_habit_completions() -> dict:
    try:
        with open(HABIT_COMPLETIONS_PATH, "r", encoding="utf-8") as fh:
            data = json.load(fh)
            return data if isinstance(data, dict) else {}
    except (FileNotFoundError, json.JSONDecodeError):
        return {}


def _save_habit_completions(completions: dict) -> None:
    os.makedirs(DATA_DIR, exist_ok=True)
    tmp_path = HABIT_COMPLETIONS_PATH + ".tmp"
    with open(tmp_path, "w", encoding="utf-8") as fh:
        json.dump(completions, fh, indent=2, sort_keys=True)
    os.replace(tmp_path, HABIT_COMPLETIONS_PATH)


def _today_str() -> str:
    """Naive server-local calendar date (YYYY-MM-DD) -- this deployment is effectively
    single-timezone (one household), so no per-user timezone conversion is attempted. A habit's
    `date` (set by whichever device reports its completion, see
    /dashboard-api/habits/<id>/complete) is compared against this exact string."""
    return time.strftime("%Y-%m-%d")


def _habits_with_completion_status() -> list:
    """{id, name, doneToday, verifiedAt} for every habit in the global library -- doneToday is
    computed by comparing the stored completion's `date` against today's server-local date, not
    stored as its own persisted boolean (so it naturally resets at local midnight without a
    separate daily-reset job)."""
    habits = _load_habits()
    completions = _load_habit_completions()
    today = _today_str()
    result = []
    for habit in habits:
        completion = completions.get(habit.get("id", ""))
        done_today = bool(completion) and completion.get("date") == today
        result.append({
            "id": habit.get("id"),
            "name": habit.get("name"),
            "doneToday": done_today,
            "verifiedAt": completion.get("verifiedAt") if completion else None,
        })
    return result


def _default_device_settings(device_id: str = "") -> dict:
    # Default name by detected platform rather than leaving it blank -- a guardian can still
    # rename via the dashboard's Device Name field at any time, this is just so a never-configured
    # device doesn't show its raw device_id in the sidebar/header.
    default_name = "Macbook" if _detect_platform(device_id) == "macos" else "Phone"
    return {
        "device_name": default_name,
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
        # habits deliberately NOT here -- moved to the global library, see LIST_ENDPOINTS' comment.
        "rules": [],
        "appBudgets": [],
        "triggerWords": [],
        "blockedApps": [],
        "protectedApps": [],
        "guardianEmail": "",
        # macos-only, deliberately None ("no opinion") rather than a concrete value that would
        # only coincidentally match a fresh Mac install's own defaults -- see
        # SETTINGS_PATCH_ALLOWED_KEYS's comment for why. A guardian must explicitly interact with
        # the dashboard's Proxy/Cloud Filter Host/Cooldown controls at least once before
        # DashboardConfigSync reconciles any of these against the Mac.
        "cooldownHours": None,
        "proxyFilter": None,
        "cloudFilterHost": None,
        "cloudFilterEnabled": None,
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
            record = _default_device_settings(device_id)
        else:
            # Backfill keys added to the schema after this record was first created (e.g. a
            # macos-only field shipped later) -- without this, an already-provisioned device's
            # GET/PATCH response would silently omit new fields forever, since only a brand-new
            # record goes through _default_device_settings() above. Only fills in what's
            # missing; never overwrites an existing value. A bare GET with no `updates` still
            # doesn't rewrite the file below, matching the existing read-only contract -- the
            # backfilled keys just aren't persisted until something else triggers a save.
            for key, value in _default_device_settings(device_id).items():
                if key not in record:
                    record[key] = value
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


def _delete_device(device_id: str) -> bool:
    """Removes device_id's record from device_settings.json entirely (not just resets it) --
    used to clean up test/ghost entries (e.g. from curl testing) without a data-file edit, which
    the app's own permission model requires go through a real endpoint rather than raw file
    mutation. Does NOT touch alerts.jsonl -- historical tamper events stay for audit purposes, and
    since device_id is canonicalized before storage/lookup (see DEVICE_ID_ALIASES), a deleted
    settings record won't resurrect itself from old alerts unless that device reports again.
    Returns False if device_id had no settings record (caller can still 200 -- DELETE is
    idempotent)."""
    with _settings_lock:
        settings = _load_settings()
        if device_id not in settings:
            return False
        del settings[device_id]
        _save_settings(settings)
        return True


def _list_item_add(device_id: str, list_key: str, item: dict) -> dict:
    with _settings_lock:
        settings = _load_settings()
        record = settings.get(device_id) or _default_device_settings(device_id)
        record.setdefault(list_key, []).append(item)
        record["updatedAt"] = time.time()
        settings[device_id] = record
        _save_settings(settings)
        return record


def _list_item_remove(device_id: str, list_key: str, match_field: str, match_value: str) -> dict:
    with _settings_lock:
        settings = _load_settings()
        record = settings.get(device_id) or _default_device_settings(device_id)
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
        record = settings.get(device_id) or _default_device_settings(device_id)
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
        if not name or name.lower() in NEVER_EXEMPT_PACKAGES:
            return None
        return {"id": uuid.uuid4().hex, "name": name}
    if kind == "trigger-words":
        word = (body.get("word") or "").strip().lower()
        return {"word": word, "addedAt": time.time()} if word else None
    if kind == "blocked-apps":
        app_id = (body.get("appId") or "").strip()
        if not app_id or app_id == OTTERLING_PACKAGE_NAME or app_id in MAC_OWN_EXECUTABLE_NAMES:
            return None
        return {"appId": app_id, "addedAt": time.time()}
    if kind == "protected-apps":
        # macos-only -- see LIST_ENDPOINTS. No self-block guard needed here (protecting, not
        # blocking, this app's own executables would just be inert/harmless, unlike blocked-apps).
        display_name = (body.get("displayName") or "").strip()
        executable_name = (body.get("executableName") or "").strip()
        bundle_path = (body.get("bundlePath") or "").strip()
        if not executable_name or not bundle_path:
            return None
        return {
            "displayName": display_name or executable_name,
            "executableName": executable_name,
            "bundlePath": bundle_path,
            "addedAt": time.time(),
        }
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
    registry.

    Also canonicalizes through DEVICE_ID_ALIASES so historical records written under an alias
    (before the alias was declared, or from a reporter DEVICE_ID_ALIASES doesn't cover) still
    merge into the one entry going forward, without needing a data migration -- alertCount24h sums
    across every alias, and device_name/updatedAt keep whichever alias's value is more informative."""
    devices: dict[str, dict] = {}
    for device_id, record in _load_settings().items():
        device_id = _canonicalize_device_id(device_id)
        entry = devices.setdefault(
            device_id,
            {"device_name": "", "updatedAt": None, "alertCount24h": 0, "platform": _detect_platform(device_id)},
        )
        if record.get("device_name"):
            entry["device_name"] = record["device_name"]
        record_updated = record.get("updatedAt")
        if record_updated and (entry["updatedAt"] is None or record_updated > entry["updatedAt"]):
            entry["updatedAt"] = record_updated
    cutoff = time.time() - 86400
    for event in _read_alerts_since(0):
        device_id = _canonicalize_device_id(event.get("device_id") or "")
        if not device_id:
            continue
        entry = devices.setdefault(
            device_id,
            {"device_name": "", "updatedAt": None, "alertCount24h": 0, "platform": _detect_platform(device_id)},
        )
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


def _send_fcm_wake(event: dict) -> int:
    """Best-effort FCM 'poll now' wake to every registered phone. Never raises. The data payload is
    advisory only -- the phone re-pulls from /alerts/poll (and, since DashboardConfigStore piggybacks
    on the same MacTamperPollWorker cycle this wakes, the phone's own dashboard/PIN/habits sync too --
    see MacTamperMessagingService.kt's doc comment: any push at all is treated as "go poll now",
    regardless of this payload's content). Returns how many tokens were actually sent to, so callers
    like the dashboard's "Poll Now" button can tell the guardian whether this reached a real device."""
    creds, project_id = _fcm_credentials()
    if creds is None:
        return 0
    tokens = _all_fcm_tokens()
    if not tokens:
        return 0
    try:
        from google.auth.transport.requests import Request as GoogleAuthRequest
        if not creds.valid:
            creds.refresh(GoogleAuthRequest())
        access_token = creds.token
    except Exception as error:  # noqa: BLE001
        print(f"[lockprofile] FCM token refresh failed: {error}", flush=True)
        return 0

    sent = 0
    url = f"https://fcm.googleapis.com/v1/projects/{project_id}/messages:send"
    for token in tokens:
        message = {
            "message": {
                "token": token,
                # Data-only (no "notification" block) so the app's onMessageReceived always runs and
                # can wake the poller, rather than the system silently tray-ing a notification.
                # This "type" value is advisory/for-logging only -- MacTamperMessagingService.kt
                # deliberately ignores message.data entirely and treats ANY push as "poll now".
                "data": {"type": str(event.get("type", ""))},
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
            sent += 1
        except urllib.error.HTTPError as error:
            # 404 (or UNREGISTERED) means the token is dead -- prune it so we stop trying.
            if error.code in (404, 400):
                _forget_fcm_token(token)
            print(f"[lockprofile] FCM send failed for {event.get('type')}: HTTP {error.code}", flush=True)
        except (urllib.error.URLError, OSError) as error:
            print(f"[lockprofile] FCM send failed for {event.get('type')}: {error}", flush=True)
    return sent


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

    def _send_redirect(self, location: str) -> None:
        self.send_response(302)
        self.send_header("Location", location)
        self.send_header("Content-Length", "0")
        self.end_headers()

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
        anything sensitive riding along.

        Deliberately no `Max-Age` on login -- that makes this a browser *session* cookie, cleared
        automatically when the browser itself closes, rather than a persistent one that would keep
        a guardian logged in for weeks after they'd left the site. DASHBOARD_SESSION_MAX_AGE_SECONDS
        still bounds the underlying token server-side (checked in _dashboard_session_valid) as a
        backstop, in case a browser's "restore previous session" setting resurrects the cookie
        anyway."""
        if token is None:
            value = "deleted; Max-Age=0"
        else:
            value = token
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
        one, and the cookie itself is what proves the login happened.

        On failure, forward_auth relays this response verbatim as the final response to whatever
        request triggered it. For an actual page load (/dashboard, /dashboard/*) that should send
        the browser straight to the login page rather than showing this route's raw JSON body.
        /dashboard-api/* calls (the SPA's own fetch()s, which forward_auth gates too) still need a
        plain 401 though -- api.ts already turns that into an in-app error screen, and redirecting
        those would hand the login page's HTML to a caller expecting JSON. forward_auth sets
        X-Forwarded-Uri to the original request's path, which is how these two cases are told apart
        here."""
        cookie = _dashboard_cookie_from_headers(self.headers)
        if cookie and _dashboard_session_valid(cookie):
            return self._send_json(200, {"status": "ok"})
        forwarded_uri = self.headers.get("X-Forwarded-Uri", "")
        if forwarded_uri.startswith("/dashboard-api/"):
            return self._send_json(401, {"error": "not logged in"})
        return self._send_redirect("/dashboard-login/")

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

        # Guardian PIN: one shared secret for the whole fleet, not per-device -- see
        # GUARDIAN_PIN_PATH's comment for why this is plaintext rather than a hash, and why it
        # lives outside device_settings.json entirely. GET is used both by the dashboard (to show
        # whether a PIN is currently set) and by each phone's periodic sync (to pull the raw PIN
        # and feed it into its own local PinAuthManager.setPin()).
        if path == "/dashboard-api/pin" and method == "GET":
            self._send_json(200, _load_guardian_pin())
            return True

        if path == "/dashboard-api/pin" and method == "POST":
            pin = (body or {}).get("pin", "").strip()
            if not re.fullmatch(r"\d{4}", pin):
                self._send_json(400, {"error": "pin must be exactly 4 digits"})
                return True
            record = _save_guardian_pin(pin)
            self._send_json(200, record)
            return True

        # Global habit library: one shared list for the whole fleet, not per-device -- see
        # HABITS_PATH's comment. GET includes each habit's `doneToday`/`verifiedAt`, computed
        # from HABIT_COMPLETIONS_PATH, so both the dashboard AND every device's own
        # DashboardConfigSync/DashboardConfigStore can see live completion state from the same
        # response a per-device settings fetch would otherwise need a second round-trip for.
        if path == "/dashboard-api/habits" and method == "GET":
            self._send_json(200, {"habits": _habits_with_completion_status()})
            return True

        if path == "/dashboard-api/habits" and method == "POST":
            name = ((body or {}).get("name") or "").strip()
            if not name:
                self._send_json(400, {"error": "name required"})
                return True
            with _habits_lock:
                habits = _load_habits()
                habits.append({"id": uuid.uuid4().hex, "name": name})
                _save_habits(habits)
            self._send_json(200, {"habits": _habits_with_completion_status()})
            return True

        habit_match = HABIT_ITEM_RE.match(path)
        if habit_match:
            habit_id, is_complete = habit_match.group(1), habit_match.group(2)

            if is_complete and method == "POST":
                # Reported by whichever device just verified the habit (see
                # HabitCompletionReporter.kt / the Mac equivalent once built) -- bearer-token
                # authenticated like every other phone/mac -> server call, not gated further.
                # `date` is the REPORTING DEVICE's own local calendar date, trusted as-is -- see
                # _today_str's comment on why this server does no timezone conversion.
                date = ((body or {}).get("date") or "").strip()
                if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", date):
                    self._send_json(400, {"error": "date must be YYYY-MM-DD"})
                    return True
                reporting_device_id = _safe_device_id(((body or {}).get("device_id") or "").strip()) or ""
                with _habits_lock:
                    if not any(h.get("id") == habit_id for h in _load_habits()):
                        self._send_json(404, {"error": "no such habit"})
                        return True
                with _habit_completions_lock:
                    completions = _load_habit_completions()
                    completions[habit_id] = {
                        "date": date,
                        "verifiedAt": time.time(),
                        "device_id": reporting_device_id,
                    }
                    _save_habit_completions(completions)
                self._send_json(200, {"status": "ok"})
                return True

            if not is_complete and method == "DELETE":
                with _habits_lock:
                    habits = [h for h in _load_habits() if h.get("id") != habit_id]
                    _save_habits(habits)
                with _habit_completions_lock:
                    completions = _load_habit_completions()
                    completions.pop(habit_id, None)
                    _save_habit_completions(completions)
                self._send_json(200, {"habits": _habits_with_completion_status()})
                return True

        # "Poll Now" button: wakes every registered phone via FCM instead of waiting out
        # MacTamperPollWorker's 15-minute floor -- same push channel _push_event already uses for
        # tamper alerts (_send_fcm_wake), just guardian-triggered instead of event-triggered. Run
        # synchronously (unlike _push_event's fire-and-forget thread) so the response can tell the
        # guardian whether this actually reached a device, not just "requested" -- there's realistically
        # at most a couple of tokens to notify, so blocking briefly here is fine for a button click.
        if path == "/dashboard-api/poll-now" and method == "POST":
            notified = _send_fcm_wake({"type": "dashboard_poll_requested"})
            _, project_id = _fcm_credentials()
            self._send_json(200, {
                "status": "ok",
                "notified": notified,
                "fcmConfigured": project_id is not None,
            })
            return True

        match = DASHBOARD_DEVICE_RE.match(path)
        if not match:
            return False
        device_id = _safe_device_id(match.group(1))
        if device_id is None:
            self._send_json(400, {"error": "invalid device_id"})
            return True
        parts = [p for p in (match.group(2) or "").split("/") if p]

        if not parts and method == "DELETE":
            _delete_device(device_id)
            self._send_json(200, {"status": "ok"})
            return True

        if parts == ["settings"] and method in ("GET", "PATCH"):
            updates = None
            if method == "PATCH":
                if body is None:
                    self._send_json(400, {"error": "bad json"})
                    return True
                # Allowlisted, not a raw pass-through: everything else (rules, habits,
                # blockedWebsites, vpnBypassApps, appBudgets) has its own dedicated endpoint above.
                updates = {k: v for k, v in body.items() if k in SETTINGS_PATCH_ALLOWED_KEYS}
            record = _device_settings(device_id, updates)
            # platform is computed, not stored -- see _detect_platform's doc comment. Lets the
            # dashboard show/hide Android-only sections (most of this record) per selected device.
            self._send_json(200, {**record, "platform": _detect_platform(device_id)})
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
