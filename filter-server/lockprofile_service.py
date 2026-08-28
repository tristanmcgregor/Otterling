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
command -- this is a convenience layer over the broker, never a way around it. The macOS client
(`XPCService.runAssistantAgentLoop`) now calls this route in a loop -- one request's worth of
commands run through the broker, then their real stdout/stderr/exit codes get folded into the next
call's `request` text so the assistant can adapt, up to a hard round/step cap on the client side.
This endpoint itself is unchanged and stateless per call: it has no idea it's mid-loop, it just
translates whatever text it's given this time, same as always.

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
Caddy serves these under the (unauthenticated, see Caddyfile) `/review` dashboard and injects the
bearer token itself, so a browser never needs to know `LOCKPROFILE_TOKEN` directly.
"""

from __future__ import annotations

import base64
import binascii
import collections
import hashlib
import hmac
import json
import os
import plistlib
import re
import secrets
import shutil
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import nsfw_image_classifier
import onnx_nsfw_pipeline
import route_policy
import session_token

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
# Flagged-only screenshot evidence for POST /screenshot-classify -- see that route's comment and
# _store_screenshot below. Under DATA_DIR (not a new top-level path) so it inherits
# deploy_filter_server.sh's existing `--exclude 'lockprofile-data/'` rsync protection for free.
SCREENSHOTS_DIR = os.path.join(DATA_DIR, "screenshots")

LISTEN_HOST = os.environ.get("LOCKPROFILE_LISTEN_HOST", "0.0.0.0")
LISTEN_PORT = int(os.environ.get("LOCKPROFILE_LISTEN_PORT", "8091"))
TOKEN = os.environ.get("LOCKPROFILE_TOKEN", "")

# In-memory ring buffer of this process's own diagnostic output (the _log() calls below), exposed
# read-only via GET /debug/server-log for remote debugging. There is otherwise no way to see this
# process's own diagnostics short of `docker logs`/journald on the host, which this container has
# no access to (see SUDO_REVIEW_URL's comment on why host-level things stay out of this container).
_LOG_BUFFER_MAX_LINES = 2000
_log_buffer: collections.deque[str] = collections.deque(maxlen=_LOG_BUFFER_MAX_LINES)
_log_buffer_lock = threading.Lock()


def _log(message: str) -> None:
    """Drop-in replacement for the old bare `print(..., flush=True)` calls this file used
    everywhere: still writes to stdout (so `docker logs`/Caddy's log driver keep working exactly
    as before), but also appends a timestamped copy to _log_buffer so GET /debug/server-log can
    show recent diagnostics without shelling out to the host."""
    line = f"{datetime.now(timezone.utc).isoformat(timespec='seconds')} {message}"
    print(line, flush=True)
    with _log_buffer_lock:
        _log_buffer.append(line)


# Hand-editable master list of every report/alert type (mac/server/android) -- see that file's
# "_readme" for the full contract. Mounted read-only alongside the script (same pattern as
# ai_classifier.py/domain_blocklist.py), not the gitignored DATA_DIR, because this is meant to be
# tracked and hand-edited in the repo, not treated as generated runtime state. Ships each type's
# *default* enabled/customMessage/suspicion/description/source -- never written to at runtime (see
# REPORT_TYPES_OVERRIDES_PATH below for where a guardian's dashboard edit actually lands); a
# release deploy rsyncs the git checkout straight over this file, so anything written here would
# be silently wiped out on the next release.
REPORT_TYPES_CONFIG_PATH = os.environ.get(
    "REPORT_TYPES_CONFIG_PATH", os.path.join(os.path.dirname(os.path.abspath(__file__)), "report_types.json")
)
# Guardian PATCHes (POST /dashboard-api/report-types/<type>) land HERE instead, under the
# gitignored DATA_DIR (bind-mounted from lockprofile-data/, which deploy_filter_server.sh's rsync
# already excludes -- see SCREENSHOTS_DIR's comment above for the same protection) so a release
# deploy can never revert a live edit the way overwriting REPORT_TYPES_CONFIG_PATH itself used to.
# Sparse: only holds the keys (enabled/customMessage/suspicion) a guardian actually changed for a
# given type -- see _merged_report_types().
REPORT_TYPES_OVERRIDES_PATH = os.path.join(DATA_DIR, "report_types_overrides.json")

# Guardian-editable override for the one-time welcome SMS (see AlertReporter.kt's
# sendWelcomeMessage, sent the first time a phone number is added as an accountability partner).
# Same gitignored-DATA_DIR-file pattern as REPORT_TYPES_OVERRIDES_PATH, for the same reason: a
# release deploy must never revert a live edit. Missing file/key means "use
# DEFAULT_WELCOME_MESSAGE" -- see _effective_welcome_message().
WELCOME_MESSAGE_OVERRIDE_PATH = os.path.join(DATA_DIR, "welcome_message_override.json")

# Fallback wording, kept in sync with AlertReporter.kt's own DEFAULT_WELCOME_MESSAGE constant
# (that copy is what actually ships in the APK and is used if the phone has never fetched
# /report-config yet -- this one is only what the dashboard shows/sends as the starting point for
# a guardian who hasn't customized it).
DEFAULT_WELCOME_MESSAGE = (
    "Otterling: you've been added as an accountability partner. From now on you may get SMS "
    "alerts here when something on the monitored device needs attention. Each one is tagged with "
    "how concerning it is:\n\n"
    "[HIGH SUSPICION] — there's a high likelihood of an attempt to bypass Otterling. Please check "
    "in.\n\n"
    "[MEDIUM SUSPICION] — could be a false positive, but check in to be safe.\n\n"
    "[LOW SUSPICION] — most likely nothing, but still worth checking in.\n\n"
    "Thanks for helping with accountability."
)

# Separate secret for the Fleet failing-policy webhook. Fleet's webhook can't send an Authorization
# header, so that one route authenticates on a `?token=` query secret instead of the Bearer TOKEN
# every other route requires -- see Handler._handle_fleet_webhook. Empty = the route is disabled
# (returns 403), so an unconfigured deployment can't be poked with unauthenticated Fleet payloads.
FLEET_WEBHOOK_SECRET = os.environ.get("FLEET_WEBHOOK_SECRET", "")

# Guardian secret -- the Guardian PIN (see GUARDIAN_PIN_PATH below) -- for the device-settings
# dashboard (filter-server/dashboard-login/), in addition to gating Settings on the phone. This
# used to be a separate 8+ character password (DASHBOARD_LOGIN_PASSWORD, unified into
# GUARDIAN_LOGIN_PASSWORD on 2026-08-19); the guardian explicitly asked to collapse that into the
# same PIN already used on-device instead, rather than juggle two secrets. See GUARDIAN_PIN_PATH's
# comment for the PIN's own storage/exposure rules. /review used to check this same PIN via its
# own session cookie too, until it was made unauthenticated on 2026-08-26 (see Caddyfile).
# Re-exported from session_token, which is now the authority (see that module). Kept as a name
# here because the cookie-setting docstring below refers to it.
DASHBOARD_SESSION_MAX_AGE_SECONDS = session_token.MAX_AGE_SECONDS  # 30 days

# One-time handoff link: lets whoever currently holds a guardian dashboard session (see
# POST /dashboard-api/handoff-link below) generate a single-use, expiring, unguessable (256-bit)
# token that lets someone WITHOUT the current PIN set a brand-new one at
# GET /handoff/?token=... -> POST /handoff-auth/set-pin -- for the one-time "I'm done setting this
# up, here's a link to claim the account" handoff moment, not an ongoing PIN-reset mechanism.
# Deliberately does NOT require knowing the current PIN (that's the whole point -- the person
# using the link is meant to be someone who doesn't have it), unlike the regular
# POST /dashboard-api/pin change flow (which is guardian-session-gated instead). Setting a new PIN
# this way has the exact same effect as that route (_save_guardian_pin), including invalidating
# every existing dashboard session, same as any other PIN change. This does NOT protect against
# someone who retains actual server/filesystem access after generating a link (the PIN is still stored in
# plaintext, same as every other secret this file manages) -- it's a clean handoff ceremony, not a
# technical guarantee against a host operator who keeps root.
HANDOFF_TOKEN_PATH = os.path.join(DATA_DIR, "password_handoff_token.json")
HANDOFF_TOKEN_TTL_SECONDS = 48 * 60 * 60  # 48 hours


def _guardian_pin_value() -> str:
    """The Guardian PIN as a string, or "" if none is set yet -- see GUARDIAN_PIN_PATH's comment.
    Single read path shared by device Settings-unlock verification AND the dashboard login
    below, so there is exactly one secret to keep in sync."""
    return _load_guardian_pin().get("pin") or ""


def _load_handoff_token() -> dict | None:
    """None if no pending token, or the stored one has expired (expiry is checked here, not just
    at consume-time, so GET /dashboard-api/handoff-link -- used to show "link pending" status --
    doesn't report a stale expired token as still active)."""
    try:
        with open(HANDOFF_TOKEN_PATH, "r", encoding="utf-8") as fh:
            data = json.load(fh)
    except (FileNotFoundError, json.JSONDecodeError):
        return None
    if not isinstance(data, dict) or data.get("expiresAt", 0) < time.time():
        return None
    return data


def _save_handoff_token() -> dict:
    """Generates a fresh 256-bit token, overwriting any previous pending one (only one valid
    handoff link at a time -- generating a new one implicitly invalidates whatever was sent out
    before, so an old leaked/forgotten link can't still be used)."""
    with _handoff_token_lock:
        data = {"token": secrets.token_urlsafe(32), "expiresAt": time.time() + HANDOFF_TOKEN_TTL_SECONDS}
        os.makedirs(DATA_DIR, exist_ok=True)
        tmp_path = HANDOFF_TOKEN_PATH + ".tmp"
        with open(tmp_path, "w", encoding="utf-8") as fh:
            json.dump(data, fh)
        os.replace(tmp_path, HANDOFF_TOKEN_PATH)
        return data


def _clear_handoff_token() -> None:
    with _handoff_token_lock:
        try:
            os.remove(HANDOFF_TOKEN_PATH)
        except FileNotFoundError:
            pass


def _consume_handoff_token(token: str) -> bool:
    """Single-use: validates `token` against the pending one (constant-time compare) and, if it
    matches and hasn't expired, clears it so it can never be used again -- even a second attempt
    with the exact same token fails once this returns True once. Returns False for "no such
    token" and "expired" alike, same as an incorrect token -- no need to distinguish those for
    the caller, which just shows one generic error either way."""
    with _handoff_token_lock:
        data = _load_handoff_token()
        if data is None or not secrets.compare_digest(token, data["token"]):
            return False
        try:
            os.remove(HANDOFF_TOKEN_PATH)
        except FileNotFoundError:
            pass
        return True

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
CHROME_DNS_PAYLOAD_IDENTIFIER = f"{PROFILE_IDENTIFIER}.chrome-dns"
FAMILY_DOH_URL = "https://family.cloudflare-dns.com/dns-query"

MAX_BODY_BYTES = 16 * 1024

# Phone-uploaded diagnostic logs (see DeviceLogUploader.kt / VpnFilterSection's "Send diagnostic
# logs" button) are much bigger than any other request this service handles -- a few thousand
# logcat lines -- so they get their own, much larger cap, plus a per-device file-count limit so a
# device stuck retrying uploads can't fill the disk.
MAX_LOG_BODY_BYTES = 2 * 1024 * 1024
MAX_LOG_FILES_PER_DEVICE = 20
# A downscaled (720px max dimension), JPEG-compressed phone screenshot (see
# FocusGuardAccessibilityService.kt's capture path) should be well under 1-2MB base64-encoded;
# this caps the whole JSON request body, same pattern as MAX_LOG_BODY_BYTES above.
MAX_SCREENSHOT_BODY_BYTES = 6 * 1024 * 1024
# Only NSFW-classified screenshots are ever stored (see _store_screenshot) -- this is a "recent
# incidents" cap, not a full history, so a much lower number than MAX_LOG_FILES_PER_DEVICE is fine.
MAX_SCREENSHOT_FILES_PER_DEVICE = 50
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

# Fleet-wide baseline for protections/vpnFilter/frictionDelay a brand-new device_id gets on its
# very first _default_device_settings() call -- guardian-editable via GET/PATCH
# /dashboard-api/default-settings (DEFAULT_TEMPLATE_ALLOWED_KEYS), instead of the hardcoded values
# that used to be the only option. Deliberately does NOT touch any device that already has a
# settings record: editing this template is a one-time "what should new devices start as," not a
# live push to the whole fleet -- a guardian who wants to change an existing device's protections
# still does that on the device's own Settings screen, same as always.
DEFAULT_TEMPLATE_PATH = os.path.join(DATA_DIR, "default_device_settings.json")
DEFAULT_TEMPLATE_ALLOWED_KEYS = {"protections", "vpnFilter", "frictionDelay"}

# Guardian PIN is deliberately NOT part of per-device settings: it's one shared secret for a
# guardian's whole fleet (see /dashboard-api/pin below), not something that varies per device.
# Stored as plaintext on disk (a 4-digit PIN can't meaningfully be protected by hashing it at
# rest anyway -- 10,000 combinations is nothing to brute force once a hash is in hand). What
# matters is who can ask the server for it over the network: GET /dashboard-api/pin (which
# returns the raw value) is guardian-browser-session-only, never reachable via the device
# LOCKPROFILE_TOKEN bearer -- that token ships inside the APK and is trivially extractable by the
# same person the PIN is meant to gate, so a device-reachable plaintext read would hand them the
# real PIN directly. Devices instead call POST /dashboard-api/pin/verify (see below), which
# checks a guess against this file server-side and returns only correct/incorrect, under
# escalating lockout (_PIN_VERIFY_LOCKOUT) -- the PIN itself never crosses that boundary.
#
# This same value also gates the dashboard website login now (_guardian_pin_value(),
# _handle_dashboard_login) -- the guardian asked to drop the separate login password and just
# reuse the PIN everywhere. (/review used to check this same PIN too, until it was made
# unauthenticated on 2026-08-26 -- see Caddyfile.) That folds two different threat models into one
# secret: the PIN was originally sized (4 digits, escalating lockout) for a guardian to
# fat-finger-enter on their own kid's phone, not to resist unlimited remote guessing against a
# website login -- the escalating lockout on the login routes (_guardian_login_record_result) is
# what keeps that acceptable, same mechanism as the on-device /pin/verify lockout above.
GUARDIAN_PIN_PATH = os.path.join(DATA_DIR, "guardian_pin.json")

# Bootstrap-only PIN for a brand-new deployment with no guardian_pin.json yet: since
# POST /dashboard-api/pin (the normal way to set/change the PIN) is guardian-session-gated, and
# dashboard login now requires a PIN to exist at all, a fresh install with nothing in
# GUARDIAN_PIN's env var and no session yet would otherwise have no way to log in for the very
# first time. Same role DASHBOARD_LOGIN_PASSWORD used to play for the old password -- an env-var
# seed that only matters until the guardian sets a real PIN from Global Settings, at which point
# GUARDIAN_PIN_PATH exists and always wins (see _load_guardian_pin below). Ignored if it isn't
# exactly 4 digits, same format rule POST /dashboard-api/pin enforces.
GUARDIAN_PIN_ENV_BOOTSTRAP = os.environ.get("GUARDIAN_PIN", "")

# HabitShare (the third-party habit-tracking app/service HabitShareApiClient.kt polls directly)
# account credentials -- one shared login for the whole fleet, same "global, not per-device"
# reasoning as the Guardian PIN, but a very different risk profile: unlike the PIN or a habit
# completion, knowing this credential doesn't unlock anything Otterling itself protects (it's an
# unrelated third-party account, not a gate). So GET is device-bearer-reachable (see Caddyfile) --
# the phone needs the actual username/password to log into HabitShare's own servers, not just a
# yes/no -- while POST/DELETE (setting or clearing it) stay guardian-browser-session-only, same as
# every other guardian-authored config.
HABITSHARE_ACCOUNT_PATH = os.path.join(DATA_DIR, "habitshare_account.json")

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

# Habit rules used to live inside each device's own record in device_settings.json (WHERE a rule
# applies -- which device, which app/website -- was baked into it being stored there at all). That
# meant a rule could only ever target the one device its record lived under, and could only ever
# target an app OR a website (targetType), never both. Rules are now a single global library, same
# "one shared list, not per-device" reasoning as HABITS_PATH above: each rule carries its own
# targetApps/targetWebsites (both may be non-empty -- a rule can gate an app AND a website at once)
# and deviceIds (an explicit device_id list, or the sentinel ["all"]) saying which device(s)
# enforce it. See _rules_for_device/_build_rule below and /dashboard-api/rules. Existing per-device
# rules are migrated in-place on first startup after this change -- see _migrate_legacy_device_rules.
RULES_PATH = os.path.join(DATA_DIR, "rules.json")

# Tracked browsing time for website rules that set dailyBudgetMinutes (see _build_rule) --
# {"date": "YYYY-MM-DD", "usage": {device_id: {domain: seconds}}}. Populated by
# mitm_nsfw_addon.py's POST /internal/website-usage-tick (that file's _UsageTracker estimates
# active browsing time from HTTP request gaps, since a domain-level budget has no on-device
# equivalent to AppTimeBudgetManager's per-app foreground ticks -- only the proxy that actually
# sees ongoing requests can measure it). Resets itself the same way habit_completions.json's
# doneToday does: the stored `date` simply stops matching _today_str(), so stale data is never
# read back, no separate midnight-reset job needed. Consulted by
# _currently_blocked_website_domains to fold "over budget for today" into the same blocked-domain
# set schedule/habit-gated rules already produce.
WEBSITE_USAGE_PATH = os.path.join(DATA_DIR, "website_usage.json")

# A completion report otherwise carries NO proof at all -- POST .../complete is device-bearer
# authenticated the same as every other low-stakes phone->server call (see Caddyfile), but that
# bearer is embedded in the shipped APK and extractable by the same person a habit-gated app
# block is meant to restrain. Unlike the Guardian PIN (a secret only the guardian knows, so the
# server can just compare), "I did this habit" isn't something the server can verify from a
# claim alone. For habits the guardian has flagged `requiresProof` (mirrors HabitProofManager.kt's
# existing on-device photo-matching, which already gates *local* rule satisfaction the same way --
# this extends that same guardian-configured bar to the cross-device-visible server state), the
# completion POST must include the same photo HabitProofManager already matched against its
# reference before calling HabitCompletionReporter -- see that class's doc comment. This doesn't
# independently re-verify the image server-side (no image-matching pipeline here); what it does is
# turn an invisible, instant, zero-evidence bypass into one that requires producing an actual photo
# and leaves an audit trail the guardian can review or revoke (GET/DELETE .../proof,
# DELETE .../complete -- all guardian-browser-session only, never device-bearer, see Caddyfile).
HABIT_PROOFS_DIR = os.path.join(DATA_DIR, "habit_proofs")
MAX_HABIT_PROOF_BYTES = 1_500_000

# Tombstone of device_ids the guardian has explicitly removed via DELETE /dashboard-api/devices/<id>
# (see _delete_device/_mark_device_removed) -- kept separate from device_settings.json (which no
# longer has a record for a removed device at all) because a removed device still needs to be
# distinguishable from one that's simply never checked in before, the next time it polls GET
# .../settings: a never-seen device gets fresh defaults (unremarkable, first run); a removed one
# gets {"removed": true} instead, which DashboardConfigStore.kt on the phone reads as "disable
# everything and offer to uninstall" (see DeviceRemovalHandler.kt) rather than "here are your
# settings". Deliberately one-way once set -- there is no dashboard "undo" for this, matching how
# every other guardian-side protection-reducing action in this project requires deliberate,
# hard-to-reverse action rather than something that can silently un-happen.
REMOVED_DEVICES_PATH = os.path.join(DATA_DIR, "removed_devices.json")

_state_lock = threading.Lock()
_alerts_lock = threading.Lock()
_settings_lock = threading.Lock()
_pin_lock = threading.Lock()
_habits_lock = threading.Lock()
_habit_completions_lock = threading.Lock()
_rules_lock = threading.Lock()
_removed_devices_lock = threading.Lock()
_website_usage_lock = threading.Lock()
_habitshare_account_lock = threading.Lock()
_report_types_lock = threading.Lock()
_handoff_token_lock = threading.Lock()

# Rate limit for POST /dashboard-api/pin/verify. Global (not per-device/IP): every device shares
# the one LOCKPROFILE_TOKEN, so a guesser can't be told apart from a legitimate device by identity
# alone anyway -- same reasoning PinAuthManager's old *local* lockout used, just moved server-side
# now that verification itself moved server-side. Same escalating shape as that local lockout
# (PinAuthManager.kt) had: 5 free wrong guesses, then a doubling backoff up to 5 minutes. In-memory
# only (resets on a service restart) -- acceptable since only the operator can restart this, and a
# perfect-forever counter isn't the point; raising the cost of guessing from "instant, unlimited"
# to "a few guesses per lockout window" is.
_pin_verify_lock = threading.Lock()
_pin_verify_failed_attempts = 0
_pin_verify_lockout_until = 0.0
_PIN_VERIFY_LOCKOUT_THRESHOLD = 5
_PIN_VERIFY_BASE_LOCKOUT_SECONDS = 5.0
_PIN_VERIFY_MAX_LOCKOUT_SECONDS = 5 * 60.0

# Same escalating-lockout shape as the PIN verify above, guarding the dashboard login instead
# (_handle_dashboard_login) -- previously unlimited, instant password guesses since
# secrets.compare_digest is only timing-safe, not rate-limited, and now doubly important since
# login checks the same 4-digit Guardian PIN as /pin/verify (see _guardian_pin_value's comment).
# (/review used to share this same counter via its own login route, until it was made
# unauthenticated on 2026-08-26 -- see Caddyfile.) Deliberately still a separate counter from
# _pin_verify_lock above rather than merged with it -- device /pin/verify is Bearer-token
# authenticated (only a device that already has LOCKPROFILE_TOKEN can call it), while this login
# route is reachable by anyone unauthenticated, a meaningfully different attack surface even
# though the secret being guessed is now identical.
_guardian_login_lock = threading.Lock()
_guardian_login_failed_attempts = 0
_guardian_login_lockout_until = 0.0
_GUARDIAN_LOGIN_LOCKOUT_THRESHOLD = 5
_GUARDIAN_LOGIN_BASE_LOCKOUT_SECONDS = 5.0
_GUARDIAN_LOGIN_MAX_LOCKOUT_SECONDS = 5 * 60.0

# Shared by both _pin_verify_record_result and _guardian_login_record_result: past this many
# consecutive wrong guesses (well above anything a guardian fat-fingering their own PIN/password
# would plausibly hit), report it as a suspected brute-force attempt via the normal alert
# pipeline (_append_alert/_push_event -- same ntfy push + accountability-partner SMS channel,
# and same report_types.json enable/disable + customMessage controls, as every other report type).
_BRUTEFORCE_ALERT_THRESHOLD = 10


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
                "chrome_dns_payload_uuid": str(uuid.uuid4()).upper(),
                "created_at": time.time(),
            }
            state[device_id] = record
            _save_state(state)
        elif "chrome_dns_payload_uuid" not in record:
            # Backfill for a record provisioned before the Chrome DoH-bypass payload existed --
            # see build_mobileconfig's chrome_dns_payload comment for why this got added.
            record["chrome_dns_payload_uuid"] = str(uuid.uuid4()).upper()
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

    # Chrome (and other Chromium browsers) auto-upgrade to their own DNS-over-HTTPS the moment the
    # system resolver looks like a known DoH-capable provider -- which the DNS floor above (and
    # DNSEnforcer.swift's own Cloudflare Family fallback) both deliberately are, by design. Once
    # Chrome does that, every lookup it makes goes straight to Cloudflare over HTTPS: never touches
    # dns_classify_mux.py, so neither a habit-gated website rule nor the adult-domain blocklist ever
    # sees it -- confirmed live via mitmproxy's own connection log showing Chrome's DoH traffic to
    # family.cloudflare-dns.com sailing straight through while a habit rule was blocking youtube.com
    # for every other path. PFBlocker.swift's pf rules can't close this without also breaking the
    # DNS floor's own legitimate use of the same host/port, so this is closed here instead, the same
    # way an enterprise MDM would: an explicit Chrome policy turning its built-in secure DNS off,
    # forcing it back onto plain system DNS (which DNSEnforcer/PFBlocker already handle correctly).
    chrome_dns_payload = {
        "PayloadType": "com.google.Chrome",
        "PayloadIdentifier": CHROME_DNS_PAYLOAD_IDENTIFIER,
        "PayloadUUID": record["chrome_dns_payload_uuid"],
        "PayloadVersion": 1,
        "PayloadDisplayName": "Otterling Chrome DNS Policy",
        "DnsOverHttpsMode": "off",
    }

    profile = {
        "PayloadContent": [dns_payload, chrome_dns_payload],
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


def _load_report_types_base() -> dict:
    """The raw, git-tracked report_types.json, unmerged with any guardian override -- re-read on
    every call (not cached) so hand-editing the file in git takes effect immediately, with no
    restart. Missing/malformed file -> the empty shape; callers merge this with
    _load_report_types_overrides() rather than reading it directly (see _merged_report_types)."""
    try:
        with open(REPORT_TYPES_CONFIG_PATH, "r", encoding="utf-8") as fh:
            data = json.load(fh)
            return data if isinstance(data, dict) else {"_readme": [], "types": {}}
    except (OSError, json.JSONDecodeError):
        return {"_readme": [], "types": {}}


def _load_report_types_overrides() -> dict:
    """{type: {enabled?, customMessage?, suspicion?}} -- see REPORT_TYPES_OVERRIDES_PATH's own
    comment for why this lives separately from the base file. Missing/malformed -> no overrides,
    i.e. every type falls back to the base file's shipped defaults."""
    try:
        with open(REPORT_TYPES_OVERRIDES_PATH, "r", encoding="utf-8") as fh:
            data = json.load(fh)
            return data if isinstance(data, dict) else {}
    except (OSError, json.JSONDecodeError):
        return {}


def _save_report_types_overrides(data: dict) -> None:
    os.makedirs(os.path.dirname(REPORT_TYPES_OVERRIDES_PATH), exist_ok=True)
    tmp_path = REPORT_TYPES_OVERRIDES_PATH + ".tmp"
    with open(tmp_path, "w", encoding="utf-8") as fh:
        json.dump(data, fh, indent=2, sort_keys=True)
    os.replace(tmp_path, REPORT_TYPES_OVERRIDES_PATH)


def _merged_report_types() -> dict:
    """The base file's `types`, each entry overlaid with any guardian edit from the overrides file
    -- the one view every reader (GET /dashboard-api/report-types, GET /report-config,
    _report_type_enabled, _send_ntfy_notification's customMessage lookup) should use, instead of
    ever reading REPORT_TYPES_CONFIG_PATH directly. An override for a type no longer present in
    the base file (removed from report_types.json since the override was saved) is simply inert."""
    base = _load_report_types_base()
    types = {k: dict(v) for k, v in (base.get("types") or {}).items()}
    for report_type, override in _load_report_types_overrides().items():
        if report_type in types:
            types[report_type].update(override)
    return {"_readme": base.get("_readme", []), "types": types}


def _load_report_config() -> dict:
    """`types` only, merged -- what _report_type_enabled and _send_ntfy_notification's
    customMessage lookup actually need."""
    return _merged_report_types().get("types", {})


def _report_type_enabled(report_type: str) -> bool:
    entry = _load_report_config().get(report_type)
    if entry is None:
        return True
    return entry.get("enabled", True) is not False


def _load_report_types_file() -> dict:
    """Merged view including `_readme` -- used by the dashboard's guardian-only GET below (the
    plain `/report-config` route -- device-bearer-reachable, see Caddyfile -- deliberately only
    ever returns the bare `{type: {enabled, customMessage, suspicion}}` map, never `_readme` or a
    write capability; see the route handler below for why those two must never be merged)."""
    return _merged_report_types()


REPORT_SUSPICION_LEVELS = ("high", "medium", "low")


def _update_report_type(report_type: str, updates: dict) -> dict | None:
    """Updates an EXISTING type's `enabled` flag, `customMessage`, and/or `suspicion` -- never
    adds/removes/renames a type via this path, keeping the API narrowly "edit something that's
    already defined" rather than a general file editor. `updates` may contain any of `enabled`
    (bool) / `customMessage` (str, "" clears back to the built-in default wording -- see
    `customMessage`'s own comment in report_types.json's schema for where it's consulted) /
    `suspicion` (one of REPORT_SUSPICION_LEVELS -- see that key's own comment in
    report_types.json's schema; caller has already validated it's one of those three values).
    Writes only to REPORT_TYPES_OVERRIDES_PATH (see its comment for why) -- never to the git-
    tracked base file, which a release deploy would just overwrite again. Returns the merged file
    (for the response), or None if report_type isn't a known key in the base file (caller sends
    404) -- an override can only edit a type that actually exists, same as before."""
    with _report_types_lock:
        merged = _merged_report_types()
        if report_type not in merged["types"]:
            return None
        overrides = _load_report_types_overrides()
        entry = overrides.setdefault(report_type, {})
        if "enabled" in updates:
            entry["enabled"] = updates["enabled"]
        if "customMessage" in updates:
            entry["customMessage"] = updates["customMessage"]
        if "suspicion" in updates:
            entry["suspicion"] = updates["suspicion"]
        _save_report_types_overrides(overrides)
        return _merged_report_types()


def _load_welcome_message_override() -> str:
    """"" (empty) means "no override -- use DEFAULT_WELCOME_MESSAGE", same convention as
    ReportType.customMessage. Missing/malformed file -> no override."""
    try:
        with open(WELCOME_MESSAGE_OVERRIDE_PATH, "r", encoding="utf-8") as fh:
            data = json.load(fh)
            message = data.get("message", "") if isinstance(data, dict) else ""
            return message if isinstance(message, str) else ""
    except (OSError, json.JSONDecodeError):
        return ""


def _save_welcome_message_override(message: str) -> None:
    os.makedirs(os.path.dirname(WELCOME_MESSAGE_OVERRIDE_PATH), exist_ok=True)
    tmp_path = WELCOME_MESSAGE_OVERRIDE_PATH + ".tmp"
    with open(tmp_path, "w", encoding="utf-8") as fh:
        json.dump({"message": message}, fh, indent=2)
    os.replace(tmp_path, WELCOME_MESSAGE_OVERRIDE_PATH)


def _effective_welcome_message() -> str:
    """What actually gets sent -- the guardian's override if they've set one, else the built-in
    default. Consulted by both GET /dashboard-api/welcome-message (dashboard display) and
    GET /report-config (device fetch, see AlertReporter.kt's sendWelcomeMessage)."""
    override = _load_welcome_message_override()
    return override if override.strip() else DEFAULT_WELCOME_MESSAGE


# ─── Dashboard session cookie (custom login, replacing Caddy basic_auth) ───────────────────────
# Self-verifying token, `<expiry>.<hmac>` -- no server-side session store to lose on a restart or
# keep in sync across a future second instance. `expiry` is a plain unix timestamp; `hmac` is
# HMAC-SHA256 keyed on TOKEN (LOCKPROFILE_TOKEN), so a token can only have been minted by this
# server and can't be forged or extended by a client tampering with the expiry.
#
# THE SIGNED PAYLOAD INCLUDES A DIGEST OF THE CURRENT PIN, and that is not incidental. Two comments
# in this file (see HANDOFF_TOKEN_PATH and _handle_handoff_set_pin) claimed that changing the PIN
# "invalidates every existing dashboard session". It did not: the signature was over the expiry
# alone, keyed on a token no PIN change touches, so every previously-issued cookie stayed valid for
# the full 30 days. That broke the one-time handoff flow at its whole purpose -- "I have finished
# setting this up, you take over" left the previous holder logged in -- and made rotating a leaked
# PIN useless. Binding the signature to the PIN means a change to the PIN changes what verifies,
# with no session store and no new state file to migrate.
def _session_pin_binding() -> str:
    """Kept as a thin alias so existing call sites read unchanged -- the logic lives in
    session_token.py so it can be unit-tested outside this container. See that module."""
    return session_token.pin_binding(_guardian_pin_value())


def _dashboard_session_create() -> str:
    return session_token.create(TOKEN, _guardian_pin_value())


def _dashboard_session_valid(token: str) -> bool:
    return session_token.valid(token, TOKEN, _guardian_pin_value())


def _dashboard_cookie_from_headers(headers) -> str | None:
    return session_token.cookie_from_header(headers.get("Cookie", ""))


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


# A phone can report ~a few hundred entries (every installed package); a Mac's /Applications is
# usually much smaller. Cap well above either so a legitimate device is never truncated, but not
# unbounded -- a malformed or hostile report shouldn't be able to grow device_settings.json
# without limit.
MAX_INSTALLED_APPS = 2000
MAX_INSTALLED_APP_FIELD_LENGTH = 200


def _sanitize_installed_apps(raw: list) -> list:
    """Validates and caps a device's self-reported installed-apps list -- see the POST
    .../installed-apps route. Silently drops malformed entries rather than rejecting the whole
    report, since one bad entry in a list of hundreds shouldn't lose every good one."""
    result = []
    seen_ids = set()
    for entry in raw:
        if len(result) >= MAX_INSTALLED_APPS:
            break
        if not isinstance(entry, dict):
            continue
        app_id = str(entry.get("id", "")).strip()[:MAX_INSTALLED_APP_FIELD_LENGTH]
        name = str(entry.get("name", "")).strip()[:MAX_INSTALLED_APP_FIELD_LENGTH]
        if not app_id or not name or app_id in seen_ids:
            continue
        seen_ids.add(app_id)
        result.append({"id": app_id, "name": name})
    return result


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


def _store_screenshot(device_id: str, package_name: str, image_bytes: bytes) -> str:
    """Writes an NSFW-flagged screenshot to SCREENSHOTS_DIR/<device_id>/, then prunes to the
    newest MAX_SCREENSHOT_FILES_PER_DEVICE for that device -- see POST /screenshot-classify.
    Only ever called for a positive (NSFW) classification result; a safe/error result is never
    written to disk at all. `package_name` is sanitized the same way _sanitize_installed_apps
    caps a field, since it becomes part of a filename below."""
    safe_package = "".join(c for c in package_name if c.isalnum() or c in "._-")[:200] or "unknown"
    device_dir = os.path.join(SCREENSHOTS_DIR, device_id)
    os.makedirs(device_dir, exist_ok=True)
    stamp = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    filename = f"{stamp}-{safe_package}.jpg"
    path = os.path.join(device_dir, filename)
    counter = 1
    while os.path.exists(path):
        filename = f"{stamp}-{safe_package}-{counter}.jpg"
        path = os.path.join(device_dir, filename)
        counter += 1
    with open(path, "wb") as fh:
        fh.write(image_bytes)
    existing = sorted(os.listdir(device_dir))
    for stale in existing[: max(0, len(existing) - MAX_SCREENSHOT_FILES_PER_DEVICE)]:
        try:
            os.remove(os.path.join(device_dir, stale))
        except OSError:
            pass
    return filename


def _list_screenshots() -> dict:
    if not os.path.isdir(SCREENSHOTS_DIR):
        return {}
    result = {}
    for device_id in sorted(os.listdir(SCREENSHOTS_DIR)):
        device_dir = os.path.join(SCREENSHOTS_DIR, device_id)
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
    # habits and rules are NOT here -- both moved to global libraries (see HABITS_PATH /
    # /dashboard-api/habits and RULES_PATH / /dashboard-api/rules below), since a rule on ANY
    # device can now reference a habit verified on a different device, AND a single rule can now
    # apply to multiple devices at once (its own deviceIds field) instead of living inside one
    # device's record.
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
    "FocusLockHelperd", "FocusLock", "otterlingctl", "FocusLockWatchdog", "FocusLockScanner",
}

DASHBOARD_DEVICE_RE = re.compile(r"^/dashboard-api/devices/([A-Za-z0-9_.-]{1,128})((?:/.+)?)$")
# Matches /dashboard-api/habits/<id> (PATCH requiresProof, DELETE the whole habit),
# /dashboard-api/habits/<id>/complete (POST to report done, DELETE to revoke just today's report),
# and /dashboard-api/habits/<id>/proof (GET today's stored proof photo). The bare (no suffix) and
# /complete forms are the only two Caddy ever lets a device bearer reach (POST /complete only) --
# /proof and every other verb here are guardian-browser-session only, see Caddyfile.
HABIT_ITEM_RE = re.compile(r"^/dashboard-api/habits/([A-Za-z0-9]+)(/complete|/proof)?$")

# Matches /dashboard-api/rules/<id> (PATCH in-place edit, DELETE). Guardian-browser-session only,
# same as the bare (no suffix) /dashboard-api/rules -- see RULES_PATH's comment for why rules are
# a global library now, not nested under DASHBOARD_DEVICE_RE.
RULE_ITEM_RE = re.compile(r"^/dashboard-api/rules/([A-Za-z0-9]+)$")

# Matches /dashboard-api/report-types/<type> (PATCH enabled) -- report type keys are always
# lower/upper snake_case (see report_types.json, e.g. "lock_profile_removed", "VPN_BLOCK"), never
# containing anything else, so this is deliberately narrower than HABIT_ITEM_RE's [A-Za-z0-9]+
# (no hex-id shape to allow for).
REPORT_TYPE_ITEM_RE = re.compile(r"^/dashboard-api/report-types/([A-Za-z0-9_]+)$")

# PATCH .../settings is allowlisted to these keys -- everything else in _default_device_settings
# (rules, blockedWebsites, vpnBypassApps, appBudgets, triggerWords, blockedApps, protectedApps,
# updatedAt) is either managed through its own dedicated endpoint or server-computed, not
# client-settable via this route. The guardian PIN and habit library aren't part of device
# settings at all -- see GUARDIAN_PIN_PATH / the global /dashboard-api/pin route, and
# HABITS_PATH / /dashboard-api/habits, below.
#
# proxyFilter/cloudFilterHost/cloudFilterEnabled are macos-only (see DashboardConfigSync.swift's
# reconcile) -- meaningless for Android, same as protections is meaningless for macOS. Deliberately
# default to None/absent in _default_device_settings rather than a concrete value:
# DashboardConfigSync only reconciles a field when it's non-null, which keeps "the guardian
# genuinely wants this value" distinct from "this device record's updatedAt happened to be set by
# an unrelated edit" -- see that file's reconcile() doc comment.
SETTINGS_PATCH_ALLOWED_KEYS = {
    "device_name", "protections", "vpnFilter", "frictionDelay",
    "proxyFilter", "cloudFilterHost", "cloudFilterEnabled",
    "visualFilterEnabled", "visualFilterIntervalSeconds",
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


def _load_default_template() -> dict:
    """Guardian-edited overrides for a brand-new device's protections/vpnFilter/frictionDelay --
    see DEFAULT_TEMPLATE_PATH's comment. Empty (not the hardcoded defaults) when never edited, so
    _default_device_settings below can tell "guardian set this" apart from "nothing to override"
    with a plain dict-merge rather than needing a sentinel."""
    try:
        with open(DEFAULT_TEMPLATE_PATH, "r", encoding="utf-8") as fh:
            data = json.load(fh)
            return data if isinstance(data, dict) else {}
    except (FileNotFoundError, json.JSONDecodeError):
        return {}


def _save_default_template(updates: dict) -> dict:
    """Merges `updates` (already filtered to DEFAULT_TEMPLATE_ALLOWED_KEYS by the caller) one
    level deep, same shape as _device_settings' own merge -- a partial `protections` update only
    overrides the keys present, not the whole sub-dict."""
    with _settings_lock:
        template = _load_default_template()
        for key, value in updates.items():
            if isinstance(value, dict) and isinstance(template.get(key), dict):
                template[key].update(value)
            else:
                template[key] = value
        os.makedirs(DATA_DIR, exist_ok=True)
        tmp_path = DEFAULT_TEMPLATE_PATH + ".tmp"
        with open(tmp_path, "w", encoding="utf-8") as fh:
            json.dump(template, fh, indent=2, sort_keys=True)
        os.replace(tmp_path, DEFAULT_TEMPLATE_PATH)
        return template


def _load_guardian_pin() -> dict:
    try:
        with open(GUARDIAN_PIN_PATH, "r", encoding="utf-8") as fh:
            return json.load(fh)
    except (FileNotFoundError, json.JSONDecodeError):
        if re.fullmatch(r"\d{4}", GUARDIAN_PIN_ENV_BOOTSTRAP):
            return {"pin": GUARDIAN_PIN_ENV_BOOTSTRAP, "updatedAt": None}
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


def _load_habitshare_account() -> dict:
    try:
        with open(HABITSHARE_ACCOUNT_PATH, "r", encoding="utf-8") as fh:
            return json.load(fh)
    except (FileNotFoundError, json.JSONDecodeError):
        return {"username": None, "password": None, "updatedAt": None}


def _save_habitshare_account(username: str, password: str) -> dict:
    with _habitshare_account_lock:
        record = {"username": username, "password": password, "updatedAt": time.time()}
        os.makedirs(DATA_DIR, exist_ok=True)
        tmp_path = HABITSHARE_ACCOUNT_PATH + ".tmp"
        with open(tmp_path, "w", encoding="utf-8") as fh:
            json.dump(record, fh, indent=2, sort_keys=True)
        os.replace(tmp_path, HABITSHARE_ACCOUNT_PATH)
        return record


def _clear_habitshare_account() -> dict:
    with _habitshare_account_lock:
        record = {"username": None, "password": None, "updatedAt": time.time()}
        os.makedirs(DATA_DIR, exist_ok=True)
        tmp_path = HABITSHARE_ACCOUNT_PATH + ".tmp"
        with open(tmp_path, "w", encoding="utf-8") as fh:
            json.dump(record, fh, indent=2, sort_keys=True)
        os.replace(tmp_path, HABITSHARE_ACCOUNT_PATH)
        return record


HABITSHARE_LOGIN_URL = "https://habitshare.herokuapp.com/rest-auth/login/"
HABITSHARE_HABITS_URL = "https://habitshare.herokuapp.com/habits"


def _fetch_habitshare_habit_names(username: str, password: str) -> list:
    """Logs into HabitShare with the stored third-party account credentials and returns the
    account's habit names, for the dashboard's "Import from HabitShare" button (see
    /dashboard-api/habits/import-from-habitshare below). Mirrors HabitShareApiClient.kt's own
    login-then-fetch flow and its defensive parsing (title -> name -> habitName field priority;
    response may be a bare array or wrapped in results/habits/data) -- server-side so this doesn't
    need the phone to be reachable, since the credentials are already held here (see
    HABITSHARE_ACCOUNT_PATH's comment: this is an unrelated third-party account, not a guardian
    secret, so the server already has full custody of it for the phone's own use).

    Raises on any failure (bad credentials, network error, unexpected response shape) -- the
    caller turns that into a clean error response, not a crash.
    """
    login_body = json.dumps({"username": username, "password": password}).encode("utf-8")
    login_req = urllib.request.Request(
        HABITSHARE_LOGIN_URL,
        data=login_body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(login_req, timeout=15) as resp:
        login_data = json.loads(resp.read().decode("utf-8"))
    token = login_data.get("key") if isinstance(login_data, dict) else None
    if not token:
        raise ValueError("HabitShare login did not return a token")

    habits_req = urllib.request.Request(
        HABITSHARE_HABITS_URL,
        headers={"Authorization": f"Token {token}"},
    )
    with urllib.request.urlopen(habits_req, timeout=15) as resp:
        habits_data = json.loads(resp.read().decode("utf-8"))

    if isinstance(habits_data, list):
        items = habits_data
    elif isinstance(habits_data, dict):
        items = habits_data.get("results") or habits_data.get("habits") or habits_data.get("data") or []
    else:
        items = []

    names = []
    for item in items:
        if not isinstance(item, dict):
            continue
        name = item.get("title") or item.get("name") or item.get("habitName")
        if isinstance(name, str) and name.strip():
            names.append(name.strip())
    return names


def _pin_verify_lockout_remaining_seconds() -> float:
    with _pin_verify_lock:
        return max(0.0, _pin_verify_lockout_until - time.time())


def _pin_verify_record_result(correct: bool) -> None:
    """Escalating lockout, mirrors the shape PinAuthManager's old local-only lockout used (see
    GUARDIAN_PIN_PATH's comment) -- 5 free wrong guesses, then a doubling backoff up to 5 minutes.
    A correct guess resets the counter."""
    global _pin_verify_failed_attempts, _pin_verify_lockout_until
    with _pin_verify_lock:
        if correct:
            _pin_verify_failed_attempts = 0
            _pin_verify_lockout_until = 0.0
            return
        _pin_verify_failed_attempts += 1
        if _pin_verify_failed_attempts >= _PIN_VERIFY_LOCKOUT_THRESHOLD:
            doublings = _pin_verify_failed_attempts - _PIN_VERIFY_LOCKOUT_THRESHOLD
            lockout_seconds = min(
                _PIN_VERIFY_BASE_LOCKOUT_SECONDS * (2 ** doublings),
                _PIN_VERIFY_MAX_LOCKOUT_SECONDS,
            )
            _pin_verify_lockout_until = time.time() + lockout_seconds
        # Fired exactly once per failure streak (== not >=), the moment it crosses 10, not on
        # every subsequent attempt after -- a sustained guessing effort (as opposed to a guardian
        # fat-fingering their own PIN a few times) is what this is meant to catch.
        if _pin_verify_failed_attempts == _BRUTEFORCE_ALERT_THRESHOLD:
            event = _append_alert({
                "device_id": "",
                "type": "pin_bruteforce_suspected",
                "details": f"{_BRUTEFORCE_ALERT_THRESHOLD} wrong Guardian PIN guesses in a row -- possible brute-force attempt.",
                "reported_at": time.time(),
                "received_at": time.time(),
            })
            if event:
                _push_event(event)


def _guardian_login_lockout_remaining_seconds() -> float:
    with _guardian_login_lock:
        return max(0.0, _guardian_login_lockout_until - time.time())


def _guardian_login_record_result(correct: bool) -> None:
    """Escalating lockout for the dashboard login password -- see the counter's own comment.
    Same shape as _pin_verify_record_result: 5 free wrong guesses, then a doubling backoff up to
    5 minutes. A correct guess resets the counter."""
    global _guardian_login_failed_attempts, _guardian_login_lockout_until
    with _guardian_login_lock:
        if correct:
            _guardian_login_failed_attempts = 0
            _guardian_login_lockout_until = 0.0
            return
        _guardian_login_failed_attempts += 1
        if _guardian_login_failed_attempts >= _GUARDIAN_LOGIN_LOCKOUT_THRESHOLD:
            doublings = _guardian_login_failed_attempts - _GUARDIAN_LOGIN_LOCKOUT_THRESHOLD
            lockout_seconds = min(
                _GUARDIAN_LOGIN_BASE_LOCKOUT_SECONDS * (2 ** doublings),
                _GUARDIAN_LOGIN_MAX_LOCKOUT_SECONDS,
            )
            _guardian_login_lockout_until = time.time() + lockout_seconds
        # Same "fire once, at the threshold" stance as _pin_verify_record_result's own alert.
        if _guardian_login_failed_attempts == _BRUTEFORCE_ALERT_THRESHOLD:
            event = _append_alert({
                "device_id": "",
                "type": "guardian_login_bruteforce_suspected",
                "details": f"{_BRUTEFORCE_ALERT_THRESHOLD} wrong dashboard login attempts in a row -- possible brute-force attempt.",
                "reported_at": time.time(),
                "received_at": time.time(),
            })
            if event:
                _push_event(event)


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


def _load_rules() -> list:
    try:
        with open(RULES_PATH, "r", encoding="utf-8") as fh:
            data = json.load(fh)
            return data.get("rules", []) if isinstance(data, dict) else []
    except (FileNotFoundError, json.JSONDecodeError):
        return []


def _save_rules(rules: list) -> None:
    os.makedirs(DATA_DIR, exist_ok=True)
    tmp_path = RULES_PATH + ".tmp"
    with open(tmp_path, "w", encoding="utf-8") as fh:
        json.dump({"rules": rules}, fh, indent=2, sort_keys=True)
    os.replace(tmp_path, RULES_PATH)


def _load_website_usage() -> dict:
    """{"date": "YYYY-MM-DD", "usage": {device_id: {domain: seconds}}} -- see WEBSITE_USAGE_PATH's
    comment for the reset-by-ignoring-a-stale-date approach. Callers that only read (e.g.
    _currently_blocked_website_domains) don't need _website_usage_lock; _add_website_usage_seconds
    below takes it for its own read-modify-write."""
    try:
        with open(WEBSITE_USAGE_PATH, "r", encoding="utf-8") as fh:
            data = json.load(fh)
    except (FileNotFoundError, json.JSONDecodeError):
        data = {}
    if not isinstance(data, dict) or data.get("date") != _today_str():
        return {"date": _today_str(), "usage": {}}
    usage = data.get("usage")
    return {"date": data["date"], "usage": usage if isinstance(usage, dict) else {}}


def _save_website_usage(data: dict) -> None:
    os.makedirs(DATA_DIR, exist_ok=True)
    tmp_path = WEBSITE_USAGE_PATH + ".tmp"
    with open(tmp_path, "w", encoding="utf-8") as fh:
        json.dump(data, fh, indent=2, sort_keys=True)
    os.replace(tmp_path, WEBSITE_USAGE_PATH)


def _add_website_usage_seconds(device_id: str, domain: str, seconds: float) -> None:
    """Accumulates today's tracked browsing time for one device+domain -- called from
    POST /internal/website-usage-tick (see that route's comment for who calls it and why)."""
    if seconds <= 0:
        return
    with _website_usage_lock:
        data = _load_website_usage()
        bucket = data["usage"].setdefault(device_id, {})
        bucket[domain] = bucket.get(domain, 0) + seconds
        _save_website_usage(data)


def _rule_applies_to_device(rule: dict, device_id: str) -> bool:
    """`deviceIds: ["all"]` (the dashboard's "All devices" shortcut) applies everywhere;
    otherwise the device must be explicitly listed. device_id is already canonicalized by every
    caller (see _safe_device_id), same as the ids _build_rule stores in deviceIds."""
    device_ids = rule.get("deviceIds") or []
    return "all" in device_ids or device_id in device_ids


def _rules_for_device(device_id: str) -> list:
    """The subset (and shape) of the global rule library GET .../settings embeds for one device --
    same "rules" key devices have always read, just sourced from RULES_PATH now instead of the
    device's own settings record. See _currently_blocked_website_domains for the equivalent used
    by the DNS-blocks endpoint."""
    return [rule for rule in _load_rules() if _rule_applies_to_device(rule, device_id)]


def _build_rule(body: dict) -> dict | None:
    """Validates and shapes a POST body for the global rule library (/dashboard-api/rules) into
    the stored item shape. A rule may target apps and/or websites at once (the dashboard's
    HabitRuleWizard lets a guardian add any mix of both to one rule) -- at least one of
    targetApps/targetWebsites must end up non-empty, and deviceIds must name at least one device
    (or the ["all"] sentinel). Returns None on an invalid/empty payload, caller sends 400."""
    target_apps = []
    for entry in body.get("targetApps") or []:
        if not isinstance(entry, dict):
            continue
        app_id = (entry.get("appId") or "").strip()
        app_name = (entry.get("appName") or "").strip()
        if app_id and app_name:
            target_apps.append({"appId": app_id, "appName": app_name})
    target_websites = []
    for entry in body.get("targetWebsites") or []:
        if not isinstance(entry, dict):
            continue
        domain = (entry.get("domain") or "").strip().lower()
        if domain:
            target_websites.append({"domain": domain})
    if not target_apps and not target_websites:
        return None
    raw_device_ids = body.get("deviceIds")
    if not isinstance(raw_device_ids, list) or not raw_device_ids:
        return None
    if "all" in raw_device_ids:
        device_ids = ["all"]
    else:
        seen: list[str] = []
        for raw_id in raw_device_ids:
            safe_id = _safe_device_id(str(raw_id))
            if safe_id and safe_id not in seen:
                seen.append(safe_id)
        device_ids = seen
    if not device_ids:
        return None
    return {
        "id": uuid.uuid4().hex,
        "targetApps": target_apps,
        "targetWebsites": target_websites,
        "deviceIds": device_ids,
        "requiredHabitIds": body.get("requiredHabitIds") or [],
        "schedule": body.get("schedule") or {},
        "dailyBudgetMinutes": body.get("dailyBudgetMinutes"),
        "createdAt": time.time(),
    }


def _migrate_legacy_device_rules() -> None:
    """One-time upgrade from the old per-device `rules` list (inside each device's own
    device_settings.json record) to the new global RULES_PATH library -- see RULES_PATH's doc
    comment for why. Runs once at process startup (see bottom of file); a no-op after the first
    successful run, guarded by RULES_PATH already existing (an empty library still writes the
    file, via _save_rules, so this never re-runs and never re-migrates a guardian's from-scratch
    deletion of every rule as if it were "not migrated yet"). Preserves each legacy rule's
    original device scope exactly (deviceIds = [that one device_id]) rather than broadening it to
    "all" -- a migration must not silently change what's currently blocked for anyone."""
    if os.path.exists(RULES_PATH):
        return
    with _settings_lock, _rules_lock:
        settings = _load_settings()
        migrated: list[dict] = []
        changed = False
        for device_id, record in settings.items():
            legacy_rules = record.pop("rules", None)
            if not legacy_rules:
                continue
            changed = True
            for legacy in legacy_rules:
                target_type = legacy.get("targetType") if legacy.get("targetType") in ("app", "website") else "app"
                if target_type == "website":
                    target_apps: list = []
                    website_domain = (legacy.get("websiteDomain") or "").strip().lower()
                    target_websites = [{"domain": website_domain}] if website_domain else []
                else:
                    target_websites = []
                    app_id = (legacy.get("appId") or "").strip()
                    app_name = (legacy.get("appName") or "").strip() or app_id
                    target_apps = [{"appId": app_id, "appName": app_name}] if app_name else []
                if not target_apps and not target_websites:
                    continue
                migrated.append({
                    "id": legacy.get("id") or uuid.uuid4().hex,
                    "targetApps": target_apps,
                    "targetWebsites": target_websites,
                    "deviceIds": [device_id],
                    "requiredHabitIds": legacy.get("requiredHabitIds") or [],
                    "schedule": legacy.get("schedule") or {},
                    "dailyBudgetMinutes": legacy.get("dailyBudgetMinutes"),
                    "createdAt": legacy.get("createdAt", time.time()),
                })
        _save_rules(migrated)
        if changed:
            _save_settings(settings)
        if migrated:
            _log(f"[lockprofile] migrated {len(migrated)} legacy per-device rule(s) into {RULES_PATH}")


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


def _habit_proof_path(habit_id: str, date: str) -> str:
    return os.path.join(HABIT_PROOFS_DIR, habit_id, f"{date}.jpg")


def _habits_with_completion_status() -> list:
    """{id, name, requiresProof, doneToday, hasProof, verifiedAt} for every habit in the global
    library -- doneToday is computed by comparing the stored completion's `date` against today's
    server-local date, not stored as its own persisted boolean (so it naturally resets at local
    midnight without a separate daily-reset job). requiresProof defaults to False for habits
    created before this field existed (see HABIT_PROOFS_DIR's comment)."""
    habits = _load_habits()
    completions = _load_habit_completions()
    today = _today_str()
    result = []
    for habit in habits:
        habit_id = habit.get("id", "")
        completion = completions.get(habit_id)
        done_today = bool(completion) and completion.get("date") == today
        result.append({
            "id": habit_id,
            "name": habit.get("name"),
            "requiresProof": bool(habit.get("requiresProof", False)),
            "doneToday": done_today,
            "hasProof": bool(completion.get("hasProof")) if completion else False,
            "verifiedAt": completion.get("verifiedAt") if completion else None,
        })
    return result


def _parse_time_to_minute_of_day(text: str) -> int | None:
    """"HH:MM" -> minutes since midnight, or None if malformed -- mirrors
    HabitRuleManager.kt's own parseTimeToMinuteOfDay exactly."""
    try:
        hour_str, minute_str = (text or "").split(":", 1)
        return int(hour_str) * 60 + int(minute_str)
    except (ValueError, AttributeError):
        return None


def _is_within_window(minute_of_day: int, start: int, end: int) -> bool:
    """Mirrors HabitRuleManager.kt's isWithinWindow exactly, including the overnight
    (e.g. 21:00-06:00) wraparound case."""
    return (start <= minute_of_day < end) if start <= end else (minute_of_day >= start or minute_of_day < end)


def _website_budget_minutes_by_device() -> dict:
    """device_id -> {domain: dailyBudgetMinutes}, one entry per website rule that sets a budget
    (see _build_rule) -- independent of whether that budget is currently exceeded (that's
    _currently_blocked_website_domains' job, just below, which calls this). The smallest
    configured budget wins if more than one rule targets the same device+domain, so a stricter
    guardian-authored limit can never be silently loosened by a second, looser rule.

    Canonical-device_id-keyed, same as _currently_blocked_website_domains itself; re-keyed onto
    every DNS/mitm-visible source identifier by _website_budget_targets_by_source_key below."""
    all_device_ids = list(_list_known_device_ids().keys())
    result: dict = {}
    for rule in _load_rules():
        minutes = rule.get("dailyBudgetMinutes")
        if not isinstance(minutes, (int, float)) or minutes <= 0:
            continue
        domains = [w.get("domain", "") for w in (rule.get("targetWebsites") or []) if w.get("domain")]
        if not domains:
            continue
        device_ids = rule.get("deviceIds") or []
        targeted_devices = all_device_ids if "all" in device_ids else device_ids
        for device_id in targeted_devices:
            bucket = result.setdefault(device_id, {})
            for domain in domains:
                bucket[domain] = min(bucket.get(domain, minutes), minutes)
    return result


def _currently_blocked_website_domains() -> dict:
    """device_id -> set of domains currently blocked by that device's website-targeted habit
    rules, evaluated fresh against the live schedule window and habit completion state -- the
    server-side equivalent of HabitRuleManager.kt's
    isWebsiteCurrentlyBlocked/isWithinWindow/isWebsiteRuleSatisfied (Android's own reference
    implementation, which the phone's VpnFilterService consults on every DNS query). Compares
    requiredHabitIds directly against doneToday rather than Android's fuzzy name-matching, since
    the server already has exact habit ids on both sides. An empty requiredHabitIds list is never
    "satisfied" -- same as Android -- so it blocks unconditionally for the whole window, matching
    isWebsiteRuleSatisfied's documented semantics.

    Also folds in domains whose dailyBudgetMinutes has been used up for the day (see
    _website_budget_minutes_by_device/WEBSITE_USAGE_PATH) -- unlike the schedule-gated rules
    above, a budget-exceeded block ignores time-of-day/day-of-week entirely and simply stays
    blocked for the rest of the day once the tracked usage total crosses the limit.

    Consulted by GET /internal/dns-website-blocks, polled both by dns_classify_mux.py (Mac
    clients) and mitm_nsfw_addon.py (the CONNECT-tunneled 80/443 traffic both platforms already
    route through it -- see that file's _WebsiteRuleBlocks) on their own short cadence, so
    enforcement lifts within one poll interval of a habit being marked done or a new day starting,
    without adding a network round-trip to every DNS query/request for every device.

    Reads the global rule library (RULES_PATH, see _rules_for_device) rather than any one
    device's settings record -- a rule's deviceIds says which device(s) this applies to, with
    ["all"] expanding to every device_id currently known to _list_known_device_ids.
    """
    all_device_ids = list(_list_known_device_ids().keys())
    habits_by_id = {h["id"]: h for h in _habits_with_completion_status()}
    now = time.localtime()
    now_minute_of_day = now.tm_hour * 60 + now.tm_min
    # tm_wday is Monday=0..Sunday=6; the dashboard's schedule.daysOfWeek uses JS's
    # Date.getDay() convention (Sunday=0..Saturday=6), same conversion HabitRuleManager.kt/
    # RuleBlockEnforcer.swift both already do for their own local weekday.
    now_js_weekday = (now.tm_wday + 1) % 7

    result: dict = {}
    for rule in _load_rules():
        domains = [w.get("domain", "") for w in (rule.get("targetWebsites") or []) if w.get("domain")]
        if not domains:
            continue
        schedule = rule.get("schedule") or {}
        start = _parse_time_to_minute_of_day(schedule.get("startTime", ""))
        end = _parse_time_to_minute_of_day(schedule.get("endTime", ""))
        if start is None or end is None:
            continue
        days_of_week = schedule.get("daysOfWeek") or []
        if days_of_week and now_js_weekday not in days_of_week:
            continue
        if not _is_within_window(now_minute_of_day, start, end):
            continue
        required_ids = rule.get("requiredHabitIds") or []
        satisfied = bool(required_ids) and all(
            habits_by_id.get(habit_id, {}).get("doneToday") for habit_id in required_ids
        )
        if satisfied:
            continue
        device_ids = rule.get("deviceIds") or []
        targeted_devices = all_device_ids if "all" in device_ids else device_ids
        for device_id in targeted_devices:
            result.setdefault(device_id, set()).update(domains)

    budget_by_device = _website_budget_minutes_by_device()
    if budget_by_device:
        usage_by_device = _load_website_usage()["usage"]
        for device_id, domain_budgets in budget_by_device.items():
            device_usage = usage_by_device.get(device_id, {})
            for domain, minutes in domain_budgets.items():
                if device_usage.get(domain, 0) >= minutes * 60:
                    result.setdefault(device_id, set()).add(domain)
    return result


def _dns_website_blocks_by_source_key() -> dict:
    """Same data as _currently_blocked_website_domains, but re-keyed onto every identifier a DNS
    query's source IP might actually show up as -- the canonical device_id itself (for a device,
    like a bare home-LAN IP, that's never been assigned a UUID) PLUS every DEVICE_ID_ALIASES entry
    that resolves to it (a Mac's LAN IP, hostname, etc.) -- since dns_classify_mux.py only ever
    sees a raw source IP/hostname on its side, never the canonical id. See DEVICE_ID_ALIASES's own
    doc comment for why a single physical device can have several aliases."""
    blocked_by_device = _currently_blocked_website_domains()
    result: dict = {}
    for device_id, domains in blocked_by_device.items():
        result.setdefault(device_id, set()).update(domains)
    for alias_key, canonical_id in DEVICE_ID_ALIASES.items():
        domains = blocked_by_device.get(canonical_id)
        if domains:
            result.setdefault(alias_key, set()).update(domains)
    return {key: sorted(domains) for key, domains in result.items()}


def _website_budget_targets_by_source_key() -> dict:
    """Same re-keying as _dns_website_blocks_by_source_key, but for _website_budget_minutes_by_device
    -- domain->dailyBudgetMinutes per DNS/mitm-visible source identifier, regardless of whether
    that budget is currently exceeded (see _currently_blocked_website_domains for the "is it
    exceeded" half). Polled by mitm_nsfw_addon.py so it knows which (client, domain) pairs are
    actually worth timing -- see that file's _BudgetTargets/_UsageTracker."""
    by_device = _website_budget_minutes_by_device()
    result: dict = {}
    for device_id, domains in by_device.items():
        result.setdefault(device_id, {}).update(domains)
    for alias_key, canonical_id in DEVICE_ID_ALIASES.items():
        domains = by_device.get(canonical_id)
        if domains:
            result.setdefault(alias_key, {}).update(domains)
    return result


def _default_device_settings(device_id: str = "") -> dict:
    # Default name by detected platform rather than leaving it blank -- a guardian can still
    # rename via the dashboard's Device Name field at any time, this is just so a never-configured
    # device doesn't show its raw device_id in the sidebar/header.
    default_name = "Macbook" if _detect_platform(device_id) == "macos" else "Phone"
    # Guardian-editable fleet-wide baseline (see DEFAULT_TEMPLATE_PATH's comment) overrides these
    # three hardcoded starting points -- one level deep, same shape _device_settings' own merge
    # uses, so a template that only sets protections.guestMode doesn't blank out the rest.
    template = _load_default_template()
    protections = {
        "safeMode": True,
        "factoryReset": True,
        "uninstallBlock": True,
        "guestMode": True,
        "usbDebugging": True,
    }
    protections.update(template.get("protections") or {})
    vpn_filter = {"enabled": True}
    vpn_filter.update(template.get("vpnFilter") or {})
    friction_delay = {"enabled": True, "seconds": 30}
    friction_delay.update(template.get("frictionDelay") or {})
    return {
        "device_name": default_name,
        "protections": protections,
        "vpnFilter": vpn_filter,
        "vpnBypassApps": [],
        "blockedWebsites": [],
        "frictionDelay": friction_delay,
        # habits/rules deliberately NOT here -- both moved to global libraries, see
        # LIST_ENDPOINTS' comment. GET .../settings embeds this device's applicable rules
        # dynamically (see _rules_for_device), it's never a stored key on the record itself.
        "appBudgets": [],
        "triggerWords": [],
        "blockedApps": [],
        "protectedApps": [],
        # Android-only (screenshot capture + server-side classification, see POST
        # /screenshot-classify) -- default ON, matching every other protection field's
        # default-safe stance. Interval is dashboard-tunable so the capture cadence can change
        # without an app update.
        "visualFilterEnabled": True,
        # 30s (TESTING -- was 60) for faster pipeline verification; bump back up once the
        # capture->classify->block->alert flow has been confirmed working end-to-end.
        "visualFilterIntervalSeconds": 30,
        # Reported by the device itself (POST .../installed-apps), NOT guardian-authored -- see
        # that route's comment. Deliberately not in SETTINGS_PATCH_ALLOWED_KEYS: the generic
        # settings PATCH is for guardian opinions, this is a device fact the guardian only ever
        # reads (to search/pick an app for a rule), never writes directly.
        "installedApps": [],
        # Reported by the device itself (POST .../app-info), same "device fact, not guardian
        # opinion" stance as installedApps above -- not in SETTINGS_PATCH_ALLOWED_KEYS. Lets the
        # dashboard flag devices running an old build without the guardian needing to unlock the
        # phone and check Settings themselves. None fields = never reported yet (e.g. an
        # already-provisioned device that hasn't polled since this shipped).
        "appVersion": {"versionName": None, "versionCode": None, "reportedAt": None},
        # macos-only, deliberately None ("no opinion") rather than a concrete value that would
        # only coincidentally match a fresh Mac install's own defaults -- see
        # SETTINGS_PATCH_ALLOWED_KEYS's comment for why. A guardian must explicitly interact with
        # the dashboard's Proxy/Cloud Filter Host controls at least once before DashboardConfigSync
        # reconciles any of these against the Mac.
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
    idempotent). Does NOT tombstone device_id itself -- see _mark_device_removed, called
    separately by the route handler only when this returns True, so a delete of a device_id that
    was never actually registered doesn't tombstone a possibly-mistyped/unrelated id."""
    with _settings_lock:
        settings = _load_settings()
        if device_id not in settings:
            return False
        del settings[device_id]
        _save_settings(settings)
        return True


def _load_removed_devices() -> set:
    try:
        with open(REMOVED_DEVICES_PATH, "r", encoding="utf-8") as fh:
            data = json.load(fh)
            return set(data.get("device_ids", [])) if isinstance(data, dict) else set()
    except (FileNotFoundError, json.JSONDecodeError):
        return set()


def _mark_device_removed(device_id: str) -> None:
    with _removed_devices_lock:
        removed = _load_removed_devices()
        removed.add(device_id)
        os.makedirs(DATA_DIR, exist_ok=True)
        tmp_path = REMOVED_DEVICES_PATH + ".tmp"
        with open(tmp_path, "w", encoding="utf-8") as fh:
            json.dump({"device_ids": sorted(removed)}, fh, indent=2, sort_keys=True)
        os.replace(tmp_path, REMOVED_DEVICES_PATH)


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
    """{device_id: {device_name, updatedAt, alertCount24h, appVersion}} for every device_id seen either in
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
            {
                "device_name": "", "updatedAt": None, "alertCount24h": 0,
                "platform": _detect_platform(device_id),
                "appVersion": {"versionName": None, "versionCode": None, "reportedAt": None},
            },
        )
        if record.get("device_name"):
            entry["device_name"] = record["device_name"]
        if record.get("appVersion"):
            entry["appVersion"] = record["appVersion"]
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
            {
                "device_name": "", "updatedAt": None, "alertCount24h": 0,
                "platform": _detect_platform(device_id),
                "appVersion": {"versionName": None, "versionCode": None, "reportedAt": None},
            },
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
    # Guardian-editable override (report_types.json's customMessage, set via the dashboard's
    # Report Types panel) replaces the default body wording -- `{details}` is substituted with
    # this event's actual details text so a reworded message can still reference what happened,
    # e.g. "Heads up: {details}". Falls back to the original default body when unset/blank, same
    # as every other customMessage consumer (see AlertReporter.kt's formatBody for the Android
    # side of this same mechanism).
    custom_message = (_load_report_config().get(event["type"], {}) or {}).get("customMessage", "")
    if custom_message.strip():
        message = custom_message.replace("{details}", event.get("details") or "")
    else:
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
        _log(f"[lockprofile] ntfy push failed for {event['type']}: {error}")


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


def _register_fcm_token(token: str, device_model: str, device_id: str = "") -> None:
    """Upsert a phone's FCM token. Idempotent -- the phone re-registers on every launch.
    device_id is optional (older app builds and the Mac's own equivalent registrar don't send
    one) -- see _fcm_tokens_for_device's own comment for what that means for per-device
    targeting. Blank/missing device_id is stored as "" rather than omitted, so a record that
    predates this field is still shaped consistently for callers that iterate every token."""
    with _state_lock:
        state = _load_state()
        tokens = state.get(_FCM_TOKENS_KEY) or {}
        tokens[token] = {
            "device_model": device_model,
            "device_id": device_id or tokens.get(token, {}).get("device_id", ""),
            "registered_at": tokens.get(token, {}).get("registered_at", time.time()),
            "last_seen": time.time(),
        }
        state[_FCM_TOKENS_KEY] = tokens
        _save_state(state)


def _all_fcm_tokens() -> list[str]:
    with _state_lock:
        return list((_load_state().get(_FCM_TOKENS_KEY) or {}).keys())


def _fcm_tokens_for_device(device_id: str) -> list[str]:
    """Tokens registered with this exact device_id. Can legitimately be empty even for a real,
    working device -- device_id association was added after FCM registration already existed
    (see FcmTokenRegistrar.kt's backfill-on-mismatch comment), so a device that hasn't reopened
    the app since this shipped won't have one yet; its next app launch fixes that. Callers (the
    per-device "Poll Now" button) should treat an empty result as "falls back to the 15-minute
    poll floor", not an error."""
    with _state_lock:
        tokens = (_load_state().get(_FCM_TOKENS_KEY) or {}).items()
        return [token for token, info in tokens if info.get("device_id") == device_id]


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
            _log(f"[lockprofile] FCM push enabled for project {project_id}")
            return creds, project_id
        except Exception as error:  # noqa: BLE001 -- any failure just disables push
            _log(f"[lockprofile] FCM disabled ({type(error).__name__}: {error})")
            _fcm_unavailable = True
            return None, None


def _send_fcm_wake(event: dict, tokens: list[str] | None = None) -> int:
    """Best-effort FCM 'poll now' wake. Never raises. The data payload is advisory only -- the
    phone re-pulls from /alerts/poll (and, since DashboardConfigStore piggybacks on the same
    MacTamperPollWorker cycle this wakes, the phone's own dashboard/PIN/habits sync too -- see
    MacTamperMessagingService.kt's doc comment: any push at all is treated as "go poll now",
    regardless of this payload's content). Returns how many tokens were actually sent to, so
    callers like the dashboard's "Poll Now" button can tell the guardian whether this reached a
    real device.

    tokens=None (the default, used by every existing caller -- tamper-event pushes and the
    fleet-wide "Poll Now" button) wakes every registered phone. Passing an explicit list (the
    per-device "Poll Now" button, via _fcm_tokens_for_device) scopes this to just those tokens --
    same function, same delivery/retry/dead-token-cleanup logic either way, just a smaller set."""
    creds, project_id = _fcm_credentials()
    if creds is None:
        return 0
    if tokens is None:
        tokens = _all_fcm_tokens()
    if not tokens:
        return 0
    try:
        from google.auth.transport.requests import Request as GoogleAuthRequest
        if not creds.valid:
            creds.refresh(GoogleAuthRequest())
        access_token = creds.token
    except Exception as error:  # noqa: BLE001
        _log(f"[lockprofile] FCM token refresh failed: {error}")
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
            _log(f"[lockprofile] FCM send failed for {event.get('type')}: HTTP {error.code}")
        except (urllib.error.URLError, OSError) as error:
            _log(f"[lockprofile] FCM send failed for {event.get('type')}: {error}")
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
        current_pin = _guardian_pin_value()
        if not current_pin:
            return self._send_json(503, {"error": "dashboard login not configured -- set a Guardian PIN from Global Settings"})
        remaining = _guardian_login_lockout_remaining_seconds()
        if remaining > 0:
            return self._send_json(429, {"error": "locked out", "retryAfterMs": int(remaining * 1000)})
        body = self._read_json_body()
        pin = (body or {}).get("pin", "")
        correct = secrets.compare_digest(pin, current_pin)
        _guardian_login_record_result(correct)
        if not correct:
            return self._send_json(401, {"error": "invalid PIN"})
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

    def _handle_handoff_set_pin(self) -> None:
        """Consumes a one-time handoff token (see HANDOFF_TOKEN_PATH's comment) and sets a brand
        new Guardian PIN -- the `/handoff/` static page's form posts here. Same 4-digit format as
        the regular POST /dashboard-api/pin change flow; same _save_guardian_pin call, so this has
        the identical side effect of invalidating every existing dashboard session (including
        whoever generated the link) and changing what unlocks Settings fleet-wide."""
        body = self._read_json_body()
        token = (body or {}).get("token", "")
        new_pin = (body or {}).get("newPin", "")
        # Checked before consuming the token: a malformed PIN shouldn't burn a one-time link over
        # a mistake unrelated to the token's own validity.
        if not re.fullmatch(r"\d{4}", new_pin):
            return self._send_json(400, {"error": "PIN must be exactly 4 digits."})
        if not _consume_handoff_token(token):
            return self._send_json(400, {"error": "This link is invalid or has expired."})
        _save_guardian_pin(new_pin)
        self._send_json(200, {"status": "ok"})

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
        # Dashboard login/logout authenticate on the dashboard's own Guardian PIN or session
        # cookie, not the Bearer TOKEN -- a browser has neither, so these too must run before the
        # Bearer gate below.
        if parsed.path == "/dashboard-auth/login":
            return self._handle_dashboard_login()
        if parsed.path == "/dashboard-auth/logout":
            return self._handle_dashboard_logout()
        # Handoff link (see HANDOFF_TOKEN_PATH's comment): authenticates on the one-time token in
        # the request body, not a session cookie or the Bearer TOKEN -- the whole point is that
        # whoever's using this doesn't have either of those yet. Must run before the Bearer gate
        # below for the same reason the login routes above do.
        if parsed.path == "/handoff-auth/set-pin":
            return self._handle_handoff_set_pin()

        if not self._authorized():
            return self._send_json(401, {"error": "unauthorized"})

        if self.path == "/internal/website-usage-tick":
            # mitm_nsfw_addon.py's _UsageTracker posts a batched elapsed-seconds delta here every
            # ~15s for any (client, domain) pair under an active dailyBudgetMinutes rule --
            # source_key is whatever identifier that container's client_conn.peername gave it (a
            # LAN IP in practice), resolved to a canonical device_id the same way every other
            # source-keyed report in this file is (_canonicalize_device_id).
            body = self._read_json_body()
            source_key = ((body or {}).get("source_key") or "").strip()
            domain = ((body or {}).get("domain") or "").strip().lower()
            seconds = (body or {}).get("seconds")
            if not source_key or not domain or not isinstance(seconds, (int, float)):
                return self._send_json(400, {"error": "source_key, domain and seconds required"})
            _add_website_usage_seconds(_canonicalize_device_id(source_key), domain, float(seconds))
            return self._send_json(200, {"status": "ok"})

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
            # device_id is optional (blank for older app builds and the Mac's own registrar, which
            # doesn't send one at all) -- see _fcm_tokens_for_device's doc for what that means for
            # per-device "Poll Now" targeting. Same canonicalization as every other device_id-
            # accepting route, so this lines up with whatever _list_known_device_ids/
            # DASHBOARD_DEVICE_RE already knows this device as.
            device_id = _safe_device_id((body or {}).get("device_id", "").strip()) or ""
            _register_fcm_token(token, (body or {}).get("device_model", "").strip() or "unknown", device_id)
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

        if self.path == "/screenshot-classify":
            # See FocusGuardAccessibilityService.kt's screenshot-capture path and
            # ScreenshotUploader.kt. A SAFE or classifier-error result is discarded immediately
            # (decoded into memory, never written to disk) -- only a positive NSFW result is
            # persisted, as evidence for the guardian (see /screenshot-review/* below).
            body = self._read_json_body(MAX_SCREENSHOT_BODY_BYTES)
            device_id = _safe_device_id((body or {}).get("device_id", "").strip())
            package_name = (body or {}).get("package_name", "").strip() if body else ""
            image_b64 = (body or {}).get("image_base64", "") if body else ""
            if not device_id or not package_name or not image_b64:
                return self._send_json(400, {"error": "device_id, package_name and image_base64 required"})
            try:
                image_bytes = base64.b64decode(image_b64, validate=True)
            except (binascii.Error, ValueError):
                return self._send_json(400, {"error": "invalid image_base64"})

            # Server-side kill switch: a guardian can disable this fleet- or per-device-wide
            # without an app update even if a stale APK keeps uploading.
            settings = _device_settings(device_id)
            if not settings.get("visualFilterEnabled", True):
                return self._send_json(200, {"status": "ok", "classification": "skipped"})

            verdict = nsfw_image_classifier.classify_screenshot(image_bytes)
            if verdict is None:
                _log(f"[lockprofile] screenshot classification failed/unavailable for {device_id}")
                return self._send_json(200, {"status": "ok", "classification": "error"})
            if not verdict:
                return self._send_json(200, {"status": "ok", "classification": "safe"})

            _store_screenshot(device_id, package_name, image_bytes)
            # Alerting the guardian happens on-device (AlertReporter.report(), same local-SMS
            # pipeline as every other Android-detected type like WATCHED_APP/TRIGGER_WORD), not
            # here -- unlike Mac/server-origin events, Android alerts never round-trip through
            # this server's own ntfy/FCM push. See FocusGuardAccessibilityService.kt's
            # reportNsfwDetection call site.
            block_until_millis = int(time.time() * 1000) + 15 * 60 * 1000
            return self._send_json(200, {
                "status": "ok",
                "classification": "nsfw",
                "blockUntilMillis": block_until_millis,
            })

        if self.path.startswith("/dashboard-api/"):
            # Habit completions can carry a base64 photo (see HABIT_PROOFS_DIR) -- needs more
            # room than every other dashboard-api POST body.
            max_bytes = MAX_LOG_BODY_BYTES if HABIT_ITEM_RE.match(self.path) else MAX_BODY_BYTES
            body = self._read_json_body(max_bytes)
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
    def _dashboard_access_denied(self, method: str, path: str) -> bool:
        """True (and a 403 already sent) when this caller may not use this route.

        Every /dashboard-api/* verb funnels through _handle_dashboard_route, so this is the single
        place the device-vs-guardian boundary needs to hold. It used to live only in the Caddyfile's
        path allowlist, which meant anything reaching :8091 by another route held full guardian
        authority using a bearer token extracted from a phone. See route_policy.py.

        A browser request arrives with BOTH the Caddy-injected bearer and the guardian's session
        cookie, so checking the cookie here does not break the dashboard; a device request has the
        bearer and no cookie, and is confined to route_policy's explicit device list.
        """
        # do_GET passes parsed.path; do_POST/PATCH/DELETE pass self.path, which still carries any
        # query string. Strip it before matching -- otherwise a device request with a query string
        # would miss its own allowlist entry and be refused. That direction is safe (fail closed)
        # but it would be a self-inflicted client outage, so normalize rather than rely on it.
        path = urllib.parse.urlparse(path).path
        if not route_policy.governs(path):
            return False
        if route_policy.required_access(method, path) == route_policy.DEVICE_BEARER_OK:
            return False
        cookie = _dashboard_cookie_from_headers(self.headers)
        if cookie and _dashboard_session_valid(cookie):
            return False
        _log(
            f"[lockprofile] denied {method} {path}: guardian session required, "
            f"device bearer is not sufficient"
        )
        self._send_json(403, {
            "error": "This route requires a guardian dashboard login, not a device token.",
        })
        return True

    def _handle_dashboard_route(self, method: str, path: str, parsed, body: dict | None) -> bool:
        if self._dashboard_access_denied(method, path):
            return True
        if path == "/dashboard-api/devices" and method == "GET":
            devices = [{"device_id": k, **v} for k, v in _list_known_device_ids().items()]
            self._send_json(200, {"devices": devices})
            return True

        # Fleet-wide baseline a brand-new device_id starts with -- see DEFAULT_TEMPLATE_PATH's
        # comment. GET always returns the effective values (template override merged onto the
        # hardcoded starting point, same _default_device_settings a new device itself would get),
        # not just what's been explicitly overridden, so the dashboard can show real toggle states
        # on first load rather than a separate "unset" affordance. Guardian-only, same as every
        # other route not in Caddy's device-bearer allowlist.
        if path == "/dashboard-api/default-settings" and method == "GET":
            defaults = _default_device_settings()
            self._send_json(200, {
                "protections": defaults["protections"],
                "vpnFilter": defaults["vpnFilter"],
                "frictionDelay": defaults["frictionDelay"],
            })
            return True

        if path == "/dashboard-api/default-settings" and method == "PATCH":
            if body is None:
                self._send_json(400, {"error": "bad json"})
                return True
            updates = {k: v for k, v in body.items() if k in DEFAULT_TEMPLATE_ALLOWED_KEYS}
            _save_default_template(updates)
            defaults = _default_device_settings()
            self._send_json(200, {
                "protections": defaults["protections"],
                "vpnFilter": defaults["vpnFilter"],
                "frictionDelay": defaults["frictionDelay"],
            })
            return True

        # Guardian PIN: one shared secret for the whole fleet, not per-device -- see
        # GUARDIAN_PIN_PATH's comment. GET here returns the raw plaintext and is guardian-
        # browser-session-only (Caddy does NOT let the device bearer through to this path -- see
        # its route{} block) so the dashboard can show/copy the PIN the guardian themselves set.
        # Devices verify a guess via POST .../pin/verify below instead, which never reveals the
        # actual value.
        if path == "/dashboard-api/pin" and method == "GET":
            # Returns whether a PIN is configured and when it changed -- never the value.
            #
            # This used to return the plaintext PIN so the dashboard could display and copy it.
            # That made one HTTP GET, authenticated by a token extracted from any shipped client,
            # sufficient to learn the secret that unlocks phone Settings, this dashboard and
            # /review. A guardian who has forgotten it can set a new one (POST below) or use the
            # one-time handoff link; neither needs the old value, so nothing legitimate required
            # this endpoint to reveal it.
            record = _load_guardian_pin()
            self._send_json(200, {
                "configured": bool(record.get("pin")),
                "updatedAt": record.get("updatedAt"),
            })
            return True

        if path == "/dashboard-api/pin" and method == "POST":
            pin = (body or {}).get("pin", "").strip()
            if not re.fullmatch(r"\d{4}", pin):
                self._send_json(400, {"error": "pin must be exactly 4 digits"})
                return True
            record = _save_guardian_pin(pin)
            self._send_json(200, record)
            return True

        # Device-safe: reveals only whether a PIN is currently configured, never the value itself
        # -- PinAuthManager.kt caches this to decide whether Settings needs a PIN at all (defaults
        # to "yes, needs one" until a fetch says otherwise, so a fresh/not-yet-synced device fails
        # closed, not open).
        if path == "/dashboard-api/pin/exists" and method == "GET":
            self._send_json(200, {"hasPin": _load_guardian_pin().get("pin") is not None})
            return True

        # Device-safe PIN check: the phone submits one guess, gets back correct/incorrect and
        # nothing else, under escalating lockout (_pin_verify_record_result). This is the ONLY way
        # a device is allowed to check a PIN guess -- see GUARDIAN_PIN_PATH's comment for why GET
        # .../pin itself is kept off the device bearer's allowlist entirely.
        if path == "/dashboard-api/pin/verify" and method == "POST":
            remaining = _pin_verify_lockout_remaining_seconds()
            if remaining > 0:
                self._send_json(429, {"error": "locked out", "retryAfterMs": int(remaining * 1000)})
                return True
            guess = (body or {}).get("pin", "")
            record = _load_guardian_pin()
            actual = record.get("pin")
            if actual is None:
                self._send_json(200, {"hasPin": False, "correct": False})
                return True
            correct = isinstance(guess, str) and secrets.compare_digest(guess, actual)
            _pin_verify_record_result(correct)
            self._send_json(200, {"hasPin": True, "correct": correct})
            return True

        # HabitShare account: see HABITSHARE_ACCOUNT_PATH's comment for why GET is device-bearer
        # reachable (Caddyfile allowlist) while POST/DELETE are guardian-browser-session-only.
        if path == "/dashboard-api/habitshare-account" and method == "GET":
            self._send_json(200, _load_habitshare_account())
            return True

        if path == "/dashboard-api/habitshare-account" and method == "POST":
            username = ((body or {}).get("username") or "").strip()
            password = (body or {}).get("password") or ""
            if not username or not password:
                self._send_json(400, {"error": "username and password required"})
                return True
            self._send_json(200, _save_habitshare_account(username, password))
            return True

        if path == "/dashboard-api/habitshare-account" and method == "DELETE":
            self._send_json(200, _clear_habitshare_account())
            return True

        # One-time account-handoff link (see HANDOFF_TOKEN_PATH's comment) -- guardian-only to
        # generate/inspect/cancel, same as the password-change route above, but unlike that route
        # this doesn't require knowing the CURRENT password to use: the resulting link is what
        # proves the right person is setting the new one, not knowledge of the old value. GET
        # reports pending-link status (for the dashboard to show "link pending, expires at X"
        # without needing to regenerate); the token itself is only ever returned once, from the
        # POST that creates it -- GET deliberately omits it, so refreshing the Global Settings
        # page can't leak an already-sent link's value to anyone glancing at devtools/network logs.
        if path == "/dashboard-api/handoff-link" and method == "GET":
            pending = _load_handoff_token()
            self._send_json(200, {"pending": pending is not None, "expiresAt": pending["expiresAt"] if pending else None})
            return True

        if path == "/dashboard-api/handoff-link" and method == "POST":
            data = _save_handoff_token()
            self._send_json(200, {"token": data["token"], "expiresAt": data["expiresAt"]})
            return True

        if path == "/dashboard-api/handoff-link" and method == "DELETE":
            _clear_handoff_token()
            self._send_json(200, {"status": "ok"})
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
            # Defaults TRUE. A completion report is otherwise an unverifiable claim made with a
            # bearer token that ships inside the APK -- see HABIT_PROOFS_DIR's comment, which
            # correctly calls that a "zero-evidence bypass" and names requiresProof as the
            # mitigation. A mitigation that is off unless the guardian finds the toggle means the
            # default configuration is the bypassable one, and a guardian gating an app behind a
            # habit gets no warning. Opting OUT is still one field away for habits where proof is
            # impractical.
            requires_proof = bool((body or {}).get("requiresProof", True))
            with _habits_lock:
                habits = _load_habits()
                habits.append({"id": uuid.uuid4().hex, "name": name, "requiresProof": requires_proof})
                _save_habits(habits)
            self._send_json(200, {"habits": _habits_with_completion_status()})
            return True

        # Guardian-only (not in Caddy's device-bearer allowlist, same as the plain POST above) --
        # pulls the connected HabitShare account's habit names and creates any not already in the
        # library by name (case-insensitive), so the guardian doesn't have to retype each one to
        # get HabitCompletionReporter.kt's name-matching working. See
        # _fetch_habitshare_habit_names's doc for why this is safe to do server-side.
        if path == "/dashboard-api/habits/import-from-habitshare" and method == "POST":
            account = _load_habitshare_account()
            username, password = account.get("username"), account.get("password")
            if not username or not password:
                self._send_json(400, {"error": "Connect a HabitShare account first"})
                return True
            try:
                habitshare_names = _fetch_habitshare_habit_names(username, password)
            except Exception as error:  # noqa: BLE001 -- any failure just fails the import cleanly
                _log(f"[lockprofile] HabitShare import failed: {error}")
                self._send_json(502, {"error": "Could not reach HabitShare -- check the connected account"})
                return True
            with _habits_lock:
                habits = _load_habits()
                existing_lower = {(h.get("name") or "").strip().lower() for h in habits}
                imported = 0
                for name in habitshare_names:
                    if name.lower() in existing_lower:
                        continue
                    # Same default-on reasoning as the manual add path above. HabitShare-imported
                    # habits are ordinary habits once here and get the same bar.
                    habits.append({"id": uuid.uuid4().hex, "name": name, "requiresProof": True})
                    existing_lower.add(name.lower())
                    imported += 1
                if imported:
                    _save_habits(habits)
            self._send_json(200, {"habits": _habits_with_completion_status(), "imported": imported})
            return True

        # Global habit-rule library: one shared list for the whole fleet, not nested under any one
        # device -- see RULES_PATH's comment. Each rule carries its own targetApps/targetWebsites
        # (either or both, so one rule can gate an app AND a website together) and deviceIds
        # (explicit ids, or ["all"]) saying which device(s) enforce it. GET .../devices/<id>/settings
        # embeds the subset that applies to that device (see _rules_for_device) in the same "rules"
        # key devices have always read, so DashboardConfigStore.kt / DashboardConfigSync.swift need
        # no endpoint changes, only new fields to parse.
        if path == "/dashboard-api/rules" and method == "GET":
            self._send_json(200, {"rules": _load_rules()})
            return True

        if path == "/dashboard-api/rules" and method == "POST":
            if body is None:
                self._send_json(400, {"error": "bad json"})
                return True
            rule = _build_rule(body)
            if rule is None:
                self._send_json(400, {"error": "invalid payload"})
                return True
            with _rules_lock:
                rules = _load_rules()
                rules.append(rule)
                _save_rules(rules)
            self._send_json(200, {"rules": rules})
            return True

        rule_match = RULE_ITEM_RE.match(path)
        if rule_match and method == "DELETE":
            rule_id = rule_match.group(1)
            with _rules_lock:
                rules = [r for r in _load_rules() if str(r.get("id")) != rule_id]
                _save_rules(rules)
            self._send_json(200, {"rules": rules})
            return True

        if rule_match and method == "PATCH":
            if body is None:
                self._send_json(400, {"error": "bad json"})
                return True
            rule_id = rule_match.group(1)
            with _rules_lock:
                rules = _load_rules()
                existing = next((r for r in rules if str(r.get("id")) == rule_id), None)
                if existing is None:
                    self._send_json(404, {"error": "no such rule"})
                    return True
                # Re-validate through _build_rule (same as creating a new one) rather than a raw
                # merge, so a PATCH can't leave targetApps/targetWebsites/deviceIds in a shape the
                # rest of this file doesn't expect -- then keep the original id/createdAt.
                merged = {**existing, **body}
                rebuilt = _build_rule(merged)
                if rebuilt is None:
                    self._send_json(400, {"error": "invalid payload"})
                    return True
                rebuilt["id"] = existing["id"]
                rebuilt["createdAt"] = existing.get("createdAt", time.time())
                rules = [rebuilt if str(r.get("id")) == rule_id else r for r in rules]
                _save_rules(rules)
            self._send_json(200, {"rules": rules})
            return True

        # Guardian-only report-type enable/disable list -- deliberately a SEPARATE path from the
        # plain `/report-config` route (see Caddyfile), which is device-bearer-reachable so the
        # phone's own ReportConfigStore.kt can fetch it. That bearer token is embedded in the
        # shipped APK and extractable by the person these reports are ABOUT (see this project's
        # existing "device bearer must never reach a guardian-authoring route" rule, e.g. Caddyfile's
        # comment on why a bare "has a bearer" match was rejected in review) -- letting that same
        # token silence its own tamper reports (PROTECTION_OFF, ACCESSIBILITY_DISABLED, etc.) would
        # defeat the entire point of this file. This richer GET (source/description included, for
        # the dashboard UI) and its PATCH live only under /dashboard-api/*, which Caddy does NOT
        # let the device bearer through to for this path (see @dashboardApiDeviceGet's allowlist).
        if path == "/dashboard-api/report-types" and method == "GET":
            self._send_json(200, _load_report_types_file())
            return True

        report_type_match = REPORT_TYPE_ITEM_RE.match(path)
        if report_type_match and method == "PATCH":
            report_type = report_type_match.group(1)
            body = body or {}
            updates: dict = {}
            if "enabled" in body:
                if not isinstance(body["enabled"], bool):
                    self._send_json(400, {"error": "enabled must be a boolean"})
                    return True
                updates["enabled"] = body["enabled"]
            if "customMessage" in body:
                if not isinstance(body["customMessage"], str):
                    self._send_json(400, {"error": "customMessage must be a string"})
                    return True
                updates["customMessage"] = body["customMessage"][:500]
            if "suspicion" in body:
                if body["suspicion"] not in REPORT_SUSPICION_LEVELS:
                    self._send_json(400, {"error": f"suspicion must be one of {REPORT_SUSPICION_LEVELS}"})
                    return True
                updates["suspicion"] = body["suspicion"]
            if not updates:
                self._send_json(400, {"error": "enabled (boolean), customMessage (string), and/or suspicion required"})
                return True
            updated = _update_report_type(report_type, updates)
            if updated is None:
                self._send_json(404, {"error": "no such report type"})
                return True
            self._send_json(200, updated)
            return True

        # Guardian-editable wording for the one-time welcome SMS (see AlertReporter.kt's
        # sendWelcomeMessage) -- same guardian-only /dashboard-api/* gating as report-types above.
        # isDefault tells the dashboard whether it's showing the built-in default or a saved
        # override, so it can offer "Reset to default" only when there's actually something to
        # reset.
        if path == "/dashboard-api/welcome-message" and method == "GET":
            self._send_json(200, {
                "message": _effective_welcome_message(),
                "isDefault": not _load_welcome_message_override().strip(),
            })
            return True

        if path == "/dashboard-api/welcome-message" and method == "PATCH":
            body = body or {}
            if "message" not in body or not isinstance(body["message"], str):
                self._send_json(400, {"error": "message (string) required"})
                return True
            # "" clears back to the default -- same convention as report-types' customMessage.
            _save_welcome_message_override(body["message"][:1000])
            self._send_json(200, {
                "message": _effective_welcome_message(),
                "isDefault": not _load_welcome_message_override().strip(),
            })
            return True

        habit_match = HABIT_ITEM_RE.match(path)
        if habit_match:
            habit_id, suffix = habit_match.group(1), habit_match.group(2)

            # Guardian-only (never in Caddy's device-bearer allowlist, see Caddyfile): toggles
            # whether this habit needs photo proof to satisfy a rule anywhere in the fleet -- see
            # HABIT_PROOFS_DIR's comment.
            if suffix is None and method == "PATCH":
                with _habits_lock:
                    habits = _load_habits()
                    habit = next((h for h in habits if h.get("id") == habit_id), None)
                    if habit is None:
                        self._send_json(404, {"error": "no such habit"})
                        return True
                    if "requiresProof" in (body or {}):
                        habit["requiresProof"] = bool(body["requiresProof"])
                    _save_habits(habits)
                self._send_json(200, {"habits": _habits_with_completion_status()})
                return True

            if suffix == "/complete" and method == "POST":
                # Reported by whichever device just verified the habit (see
                # HabitCompletionReporter.kt / the Mac equivalent once built) -- bearer-token
                # authenticated like every other phone/mac -> server call. `date` is the
                # REPORTING DEVICE's own local calendar date, trusted as-is -- see _today_str's
                # comment on why this server does no timezone conversion.
                date = ((body or {}).get("date") or "").strip()
                if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", date):
                    self._send_json(400, {"error": "date must be YYYY-MM-DD"})
                    return True
                with _habits_lock:
                    habit = next((h for h in _load_habits() if h.get("id") == habit_id), None)
                if habit is None:
                    self._send_json(404, {"error": "no such habit"})
                    return True
                reporting_device_id = _safe_device_id(((body or {}).get("device_id") or "").strip()) or ""
                has_proof = False
                if habit.get("requiresProof"):
                    # See HABIT_PROOFS_DIR's comment: this is what actually raises the bar above
                    # "just has the shared bearer token" for a habit the guardian flagged as
                    # needing it -- no photo, no completion, full stop.
                    photo_b64 = (body or {}).get("photo")
                    if not isinstance(photo_b64, str) or not photo_b64:
                        self._send_json(400, {"error": "photo proof required for this habit"})
                        return True
                    try:
                        photo_bytes = base64.b64decode(photo_b64, validate=True)
                    except (binascii.Error, ValueError):
                        self._send_json(400, {"error": "photo must be valid base64"})
                        return True
                    if not photo_bytes or len(photo_bytes) > MAX_HABIT_PROOF_BYTES:
                        self._send_json(400, {"error": "photo missing or too large"})
                        return True
                    proof_path = _habit_proof_path(habit_id, date)
                    os.makedirs(os.path.dirname(proof_path), exist_ok=True)
                    tmp_path = proof_path + ".tmp"
                    with open(tmp_path, "wb") as fh:
                        fh.write(photo_bytes)
                    os.replace(tmp_path, proof_path)
                    has_proof = True
                with _habit_completions_lock:
                    completions = _load_habit_completions()
                    completions[habit_id] = {
                        "date": date,
                        "verifiedAt": time.time(),
                        "device_id": reporting_device_id,
                        "hasProof": has_proof,
                    }
                    _save_habit_completions(completions)
                # Audit trail: every completion (proof-required or not) shows up in the reporting
                # device's Activity Log, so a guardian can catch a suspicious one even for habits
                # they didn't mark requiresProof for -- see report_types.json's "habit_completed".
                event = _append_alert({
                    "device_id": reporting_device_id or "unknown-device",
                    "type": "habit_completed",
                    "details": f"\"{habit.get('name')}\" marked done for {date}" + (
                        " (photo proof attached)" if has_proof else ""
                    ),
                    "reported_at": time.time(),
                    "received_at": time.time(),
                })
                if event:
                    _push_event(event)
                self._send_json(200, {"status": "ok"})
                return True

            # Guardian-only: revoke just today's completion (e.g. a suspicious one spotted in the
            # Activity Log) without deleting the habit itself -- see HABIT_PROOFS_DIR's comment.
            if suffix == "/complete" and method == "DELETE":
                with _habit_completions_lock:
                    completions = _load_habit_completions()
                    if completions.pop(habit_id, None) is not None:
                        _save_habit_completions(completions)
                for date_str in (_today_str(),):
                    try:
                        os.remove(_habit_proof_path(habit_id, date_str))
                    except FileNotFoundError:
                        pass
                self._send_json(200, {"habits": _habits_with_completion_status()})
                return True

            # Guardian-only: today's stored proof photo for this habit, if any -- lets the
            # guardian actually look at what was submitted, not just trust "hasProof: true".
            if suffix == "/proof" and method == "GET":
                proof_path = _habit_proof_path(habit_id, _today_str())
                try:
                    with open(proof_path, "rb") as fh:
                        photo_bytes = fh.read()
                except FileNotFoundError:
                    self._send_json(404, {"error": "no proof for today"})
                    return True
                self.send_response(200)
                self.send_header("Content-Type", "image/jpeg")
                self.send_header("Content-Length", str(len(photo_bytes)))
                self.end_headers()
                self.wfile.write(photo_bytes)
                return True

            if suffix is None and method == "DELETE":
                with _habits_lock:
                    habits = [h for h in _load_habits() if h.get("id") != habit_id]
                    _save_habits(habits)
                with _habit_completions_lock:
                    completions = _load_habit_completions()
                    completions.pop(habit_id, None)
                    _save_habit_completions(completions)
                shutil.rmtree(os.path.join(HABIT_PROOFS_DIR, habit_id), ignore_errors=True)
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

        # "Send test report" button (Global Settings' Report Types panel): fires a synthetic
        # TEST_REPORT event through the exact same pipeline as every real alert (_append_alert ->
        # JSONL + ntfy push -> phone's /alerts/poll -> SMS relay), so a guardian can confirm the
        # whole chain works without waiting for a real tamper event. Honors TEST_REPORT's own
        # enabled flag in report_types.json like any other type, so disabling it there also
        # disables this button's effect.
        if path == "/dashboard-api/report-types/test" and method == "POST":
            event = _append_alert({
                "device_id": "dashboard",
                "type": "TEST_REPORT",
                "details": "Test report triggered from Global Settings.",
                "reported_at": time.time(),
                "received_at": time.time(),
            })
            if event:
                _push_event(event)
            self._send_json(200, {"status": "ok", "sent": event is not None})
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
            # Android-only: tombstoning + the remote disable/uninstall signal below is
            # DeviceRemovalHandler.kt's territory (Device Owner clearing, self-uninstall) -- macOS
            # has no equivalent mechanism (no Device Owner concept, a completely different
            # daemon-based enforcement model), so a Mac device_id keeps the old plain-delete
            # behavior: settings record removed, reappears with defaults if it connects again.
            # _detect_platform is a pure function of device_id's own shape (UUID = macos), so this
            # still works correctly even though the settings record is already gone by this point.
            existed = _delete_device(device_id)
            if existed and _detect_platform(device_id) == "android":
                # Unlike this route's old behavior, this is no longer just a bookkeeping delete:
                # the next authenticated settings poll from this device_id gets {"removed": true}
                # instead of defaults, which DashboardConfigStore.kt on the phone reads as
                # "disable everything and offer to uninstall" (DeviceRemovalHandler.kt). Reuses the
                # same synchronous FCM wake the "Poll Now" button already uses (same reasoning: at
                # most a couple of tokens, brief blocking is fine) so this reaches the device in
                # seconds instead of waiting out the ~15-minute poll floor.
                _mark_device_removed(device_id)
                _send_fcm_wake({"type": "dashboard_poll_requested"})
            self._send_json(200, {"status": "ok"})
            return True

        # Per-device version of the fleet-wide "Poll Now" button (POST /dashboard-api/poll-now,
        # below) -- guardian-only, same as everything else keyed off DASHBOARD_DEVICE_RE. Scoped
        # via _fcm_tokens_for_device instead of _send_fcm_wake's default "every registered token",
        # so nudging one device's sync doesn't also wake every other device in the household.
        # `notified` can legitimately be 0 for a real, working device whose token predates
        # per-device association -- see that function's own doc; this device still gets synced,
        # just on the normal ~15-minute floor instead of within seconds.
        if parts == ["poll-now"] and method == "POST":
            notified = _send_fcm_wake({"type": "dashboard_poll_requested"}, tokens=_fcm_tokens_for_device(device_id))
            self._send_json(200, {"status": "ok", "notified": notified})
            return True

        if parts == ["settings"] and method in ("GET", "PATCH"):
            if method == "GET" and device_id in _load_removed_devices():
                self._send_json(200, {"removed": True})
                return True
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
            # rules is likewise computed, not stored on the record -- see _rules_for_device.
            self._send_json(200, {
                **record,
                "platform": _detect_platform(device_id),
                "rules": _rules_for_device(device_id),
            })
            return True

        # Device-reported: what's actually installed on this phone/Mac, so the dashboard's Habit
        # Rule Wizard can search real apps instead of a hardcoded common-apps list or requiring
        # the guardian to type an exact package/executable name from memory. Device-bearer only
        # (see Caddyfile's @dashboardApiDevicePost) -- a guardian never writes this directly, only
        # ever reads it back via GET .../settings. Wholesale-replaces the list each report rather
        # than merging, since a report is a full snapshot of what's currently installed (an
        # uninstalled app should disappear, not linger forever).
        if parts == ["installed-apps"] and method == "POST":
            apps = (body or {}).get("apps")
            if not isinstance(apps, list):
                self._send_json(400, {"error": "apps must be a list"})
                return True
            sanitized = _sanitize_installed_apps(apps)
            record = _device_settings(device_id, {"installedApps": sanitized})
            self._send_json(200, {"installedApps": record.get("installedApps", [])})
            return True

        # Device-reported: same stance as installed-apps above (device fact, guardian only ever
        # reads it back via GET .../settings). Piggybacks MacTamperPollWorker's ~15-minute cycle
        # (AppVersionReporter.kt) rather than a dedicated job, same as installed-apps.
        if parts == ["app-info"] and method == "POST":
            version_name = str((body or {}).get("versionName", "")).strip()[:32]
            try:
                version_code = int((body or {}).get("versionCode", 0))
            except (TypeError, ValueError):
                version_code = 0
            record = _device_settings(device_id, {
                "appVersion": {
                    "versionName": version_name or None,
                    "versionCode": version_code or None,
                    "reportedAt": time.time(),
                },
            })
            self._send_json(200, {"appVersion": record.get("appVersion")})
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
            # PATCH (in-place edit): only app-budgets supports this among the per-device
            # LIST_ENDPOINTS -- websites/bypass-apps/habits are add-only-with-guardian-remove per
            # the design doc, no edit-in-place case. Rules have their own PATCH at
            # /dashboard-api/rules/<id> now (see RULE_ITEM_RE above), not routed through here.
            if parts[0] != "app-budgets":
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

        if parsed.path == "/internal/dns-website-blocks":
            # Polled by dns_classify_mux.py and mitm_nsfw_addon.py (separate containers/processes
            # with no direct access to device_settings.json or the habit library) so each can
            # enforce website-targeted habit/budget rules for its own slice of traffic -- see
            # _dns_website_blocks_by_source_key's doc comment.
            return self._send_json(200, {"blocks": _dns_website_blocks_by_source_key()})

        if parsed.path == "/debug/server-log":
            # Recent lines from this process's own diagnostics (_log() above) -- for debugging
            # this service itself when there's no shell access to `docker logs`/journald on the
            # host. `?tail=N` caps how many of the most recent lines come back (default/max
            # _LOG_BUFFER_MAX_LINES, the same cap the in-memory buffer itself keeps).
            query = urllib.parse.parse_qs(parsed.query)
            try:
                tail = int(query.get("tail", [str(_LOG_BUFFER_MAX_LINES)])[0])
            except ValueError:
                return self._send_json(400, {"error": "tail must be an integer"})
            tail = max(1, min(tail, _LOG_BUFFER_MAX_LINES))
            with _log_buffer_lock:
                lines = list(_log_buffer)[-tail:]
            return self._send_json(200, {"lines": lines})

        if parsed.path == "/internal/website-budget-targets":
            # Polled by mitm_nsfw_addon.py (see that file's _BudgetTargets) so it knows which
            # (client, domain) pairs currently sit under a dailyBudgetMinutes rule, independent of
            # whether that budget is already exceeded -- it needs this to know what to *time*, not
            # just what's already blocked (the /internal/dns-website-blocks route above covers
            # that half). See _website_budget_targets_by_source_key's doc comment.
            return self._send_json(200, {"targets": _website_budget_targets_by_source_key()})

        if parsed.path == "/report-config":
            # Lets the phone's own AlertReporter (Android-origin types never touch this server --
            # see report_types.json's "_readme") honor the same enabled/disabled list AND the same
            # guardian-editable customMessage/suspicion overrides this server's own
            # _send_ntfy_notification already applies (customMessage) or that live only here
            # (suspicion) -- see AlertReporter.kt's formatBody. source/description are for humans
            # editing the file, not needed on the wire.
            config = _load_report_config()
            return self._send_json(200, {
                "types": {
                    k: {
                        "enabled": v.get("enabled", True) is not False,
                        "customMessage": v.get("customMessage", ""),
                        "suspicion": v.get("suspicion") if v.get("suspicion") in REPORT_SUSPICION_LEVELS else "medium",
                    }
                    for k, v in config.items()
                },
                # Guardian-editable wording for the one-time welcome SMS (dashboard's Accountability
                # screen) -- see AlertReporter.kt's sendWelcomeMessage/ReportConfigStore.kt.
                "welcomeMessage": _effective_welcome_message(),
            })

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

        # Guardian evidence viewer for NSFW-flagged screenshots (see POST /screenshot-classify
        # and _store_screenshot). Bearer-gated like /device-logs/view/* above -- deliberately NOT
        # following /review-data/*'s newer unauthenticated stance, since these are more sensitive
        # images than diagnostic log text.
        # Guardian-only, enforced here as well as in the Caddyfile: these are the most sensitive
        # objects this service stores (captured screenshots flagged as adult content), and the
        # bearer that reaches this route ships inside the phone that produced them.
        if parsed.path.startswith("/screenshot-review/"):
            cookie = _dashboard_cookie_from_headers(self.headers)
            if not (cookie and _dashboard_session_valid(cookie)):
                return self._send_json(403, {
                    "error": "Screenshot review requires a guardian dashboard login.",
                })

        if parsed.path == "/screenshot-review/list":
            return self._send_json(200, {"devices": _list_screenshots()})

        if parsed.path.startswith("/screenshot-review/"):
            remainder = parsed.path[len("/screenshot-review/"):]
            parts = remainder.split("/", 1)
            if len(parts) != 2:
                return self._send_json(404, {"error": "not found"})
            device_id, filename = parts
            if not DEVICE_ID_RE.match(device_id) or "/" in filename or ".." in filename:
                return self._send_json(400, {"error": "invalid path"})
            path = os.path.join(SCREENSHOTS_DIR, device_id, filename)
            if not os.path.isfile(path):
                return self._send_json(404, {"error": "not found"})
            with open(path, "rb") as fh:
                content = fh.read()
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
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
    _migrate_legacy_device_rules()
    # Load/validate the ONNX NSFW models on a background thread, not a request thread (section
    # 8.3/15.1 of the migration plan) and NOT inline here -- with real model files dropped under
    # ./models/ (a few hundred MB combined), onnxruntime.InferenceSession loading can take long
    # enough (or enough memory) on modest hardware to delay or stall this call, which previously
    # blocked the HTTP server from ever binding -- taking down every route on this process,
    # including /dashboard-auth/*, with nothing at all listening on LISTEN_PORT for Caddy to
    # proxy to (502 Bad Gateway) for as long as loading took. classify() calls
    # onnx_nsfw_pipeline.available() lazily anyway, so nsfw_image_classifier just keeps using the
    # claude -p fallback until this thread finishes.
    threading.Thread(target=onnx_nsfw_pipeline.initialize, daemon=True, name="onnx-nsfw-init").start()
    server = ThreadingHTTPServer((LISTEN_HOST, LISTEN_PORT), Handler)
    _log(f"[lockprofile] listening on {LISTEN_HOST}:{LISTEN_PORT}")
    server.serve_forever()


if __name__ == "__main__":
    main()
