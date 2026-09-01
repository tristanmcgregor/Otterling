"""Report-type config (enable/disable, custom wording, suspicion level), the welcome-SMS override,
and the ntfy.sh push notification that uses both.

Extracted as a leaf module (imports nothing back from lockprofile_service.py, only from
lockprofile_logging.py for _log) so it can be imported without a circular import.
"""

from __future__ import annotations

import json
import os
import threading
import urllib.error
import urllib.request

from lockprofile_logging import _log

DATA_DIR = os.environ.get("LOCKPROFILE_DATA_DIR", "/data")

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
# already excludes -- see SCREENSHOTS_DIR's comment in lockprofile_screenshots.py for the same
# protection) so a release deploy can never revert a live edit the way overwriting
# REPORT_TYPES_CONFIG_PATH itself used to. Sparse: only holds the keys
# (enabled/customMessage/suspicion) a guardian actually changed for a given type -- see
# _merged_report_types().
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

NTFY_SERVER = os.environ.get("NTFY_SERVER", "https://ntfy.sh").rstrip("/")
NTFY_TOPIC = os.environ.get("NTFY_TOPIC", "")

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

_report_types_lock = threading.Lock()


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


def _send_ntfy_notification(event: dict) -> None:
    """Best-effort push via ntfy.sh -- see lockprofile_service.py's module docstring. Never raises:
    a down/unreachable ntfy server must not turn an accepted tamper report into a failed request."""
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
