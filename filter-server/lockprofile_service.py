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

DATA_DIR = os.environ.get("LOCKPROFILE_DATA_DIR", "/data")
STATE_PATH = os.path.join(DATA_DIR, "state.json")
ALERTS_PATH = os.path.join(DATA_DIR, "alerts", "events.jsonl")
LOGS_DIR = os.path.join(DATA_DIR, "logs")

LISTEN_HOST = os.environ.get("LOCKPROFILE_LISTEN_HOST", "0.0.0.0")
LISTEN_PORT = int(os.environ.get("LOCKPROFILE_LISTEN_PORT", "8091"))
TOKEN = os.environ.get("LOCKPROFILE_TOKEN", "")

# Separate secret for the Fleet failing-policy webhook. Fleet's webhook can't send an Authorization
# header, so that one route authenticates on a `?token=` query secret instead of the Bearer TOKEN
# every other route requires -- see Handler._handle_fleet_webhook. Empty = the route is disabled
# (returns 403), so an unconfigured deployment can't be poked with unauthenticated Fleet payloads.
FLEET_WEBHOOK_SECRET = os.environ.get("FLEET_WEBHOOK_SECRET", "")

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

_state_lock = threading.Lock()
_alerts_lock = threading.Lock()


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


def _append_alert(event: dict) -> dict:
    """Assigns `id` and appends. Returns the event including that id -- callers that need it (the
    poll endpoint, ntfy) get it without a re-read."""
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
            _push_event(event)
            count += 1
        return self._send_json(200, {"status": "ok", "events": count})

    def do_POST(self):  # noqa: N802 (http.server API)
        parsed = urllib.parse.urlparse(self.path)
        # This one route authenticates on a query secret (Fleet can't send a Bearer header), so it
        # is dispatched BEFORE the Bearer gate that every other route below still passes through.
        if parsed.path == "/alerts/fleet-webhook":
            return self._handle_fleet_webhook(parsed)

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
            # ntfy and FCM (the phone's instant "poll now" wake).
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

        if self.path == "/device-logs/upload":
            body = self._read_json_body(MAX_LOG_BODY_BYTES)
            device_id = _safe_device_id((body or {}).get("device_id", "").strip())
            logs = (body or {}).get("logs", "") if body else ""
            if not device_id or not logs:
                return self._send_json(400, {"error": "device_id and logs required"})
            filename = _store_device_log(device_id, logs)
            return self._send_json(200, {"status": "ok", "filename": filename})

        return self._send_json(404, {"error": "not found"})

    def do_GET(self):  # noqa: N802
        if not self._authorized():
            return self._send_json(401, {"error": "unauthorized"})

        parsed = urllib.parse.urlparse(self.path)
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
