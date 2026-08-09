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
"""

from __future__ import annotations

import json
import os
import plistlib
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

LISTEN_HOST = os.environ.get("LOCKPROFILE_LISTEN_HOST", "0.0.0.0")
LISTEN_PORT = int(os.environ.get("LOCKPROFILE_LISTEN_PORT", "8091"))
TOKEN = os.environ.get("LOCKPROFILE_TOKEN", "")

NTFY_SERVER = os.environ.get("NTFY_SERVER", "https://ntfy.sh").rstrip("/")
NTFY_TOPIC = os.environ.get("NTFY_TOPIC", "")

# (title, priority, tag) per tamper event type -- see TamperReporter.swift / LockProfileGuard.swift
# / FocusLockWatchdog for the `type` values these correspond to. Falls back to a generic
# medium-priority notification for any type not listed here, so a future new event type still
# reaches the partner instead of silently not notifying.
NTFY_EVENT_STYLE = {
    "lock_profile_removed": ("Otterling: lock profile removed", "urgent", "warning"),
    "lock_profile_installed": ("Otterling: lock profile installed", "default", "white_check_mark"),
    "daemon_unloaded_recovered": ("Otterling: daemon was down, watchdog recovered it", "high", "robot"),
    "watchdog_or_daemon_reregistered": ("Otterling: needed re-registration on GUI launch", "high", "warning"),
}

PROFILE_IDENTIFIER = "au.com.tbmcgregor.bwparker.focuslock.lockprofile"
DNS_PAYLOAD_IDENTIFIER = f"{PROFILE_IDENTIFIER}.dns"
FAMILY_DOH_URL = "https://family.cloudflare-dns.com/dns-query"

MAX_BODY_BYTES = 16 * 1024

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


def _append_alert(event: dict) -> dict:
    """Assigns `id` (1-based, this event's position in the file) and appends. Returns the event
    including that id -- callers that need it (the poll endpoint, ntfy) get it without a re-read."""
    os.makedirs(os.path.dirname(ALERTS_PATH), exist_ok=True)
    with _alerts_lock:
        existing_count = 0
        if os.path.exists(ALERTS_PATH):
            with open(ALERTS_PATH, "r", encoding="utf-8") as fh:
                existing_count = sum(1 for _ in fh)
        event = {**event, "id": existing_count + 1}
        with open(ALERTS_PATH, "a", encoding="utf-8") as fh:
            fh.write(json.dumps(event, sort_keys=True) + "\n")
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

    def _read_json_body(self) -> dict | None:
        length = int(self.headers.get("Content-Length", "0") or "0")
        if length <= 0 or length > MAX_BODY_BYTES:
            return None
        try:
            return json.loads(self.rfile.read(length).decode("utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError):
            return None

    def do_POST(self):  # noqa: N802 (http.server API)
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
                "type": body["type"],
                "details": body.get("details", ""),
                "reported_at": body.get("ts", time.time()),
                "received_at": time.time(),
            }
            event = _append_alert(event)
            # Backgrounded so a slow/unreachable ntfy.sh can't delay this response -- the daemon's
            # own TamperReporter has just a 10s timeout and one retry, and the JSONL append above
            # (the actual source of truth) is already durable before this fires.
            threading.Thread(target=_send_ntfy_notification, args=(event,), daemon=True).start()
            return self._send_json(200, {"status": "ok"})

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
            events = _read_alerts_since(since_id)
            max_id = events[-1]["id"] if events else since_id
            return self._send_json(200, {"events": events, "max_id": max_id})

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
