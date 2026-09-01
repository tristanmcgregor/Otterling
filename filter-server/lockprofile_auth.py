"""Guardian PIN storage and dashboard session tokens.

`_guardian_pin_value` is the single read path shared by device Settings-unlock verification
(POST /dashboard-api/pin/verify) AND the dashboard website login (_dashboard_session_create/
_dashboard_session_valid below), so there's exactly one secret to keep in sync -- see
GUARDIAN_PIN_PATH's comment. Extracted as a leaf module (imports nothing back from
lockprofile_service.py, only from session_token.py) so it can be imported without a circular
import.
"""

from __future__ import annotations

import json
import os
import re
import threading
import time

import session_token

DATA_DIR = os.environ.get("LOCKPROFILE_DATA_DIR", "/data")

# Guardian PIN is deliberately NOT part of per-device settings: it's one shared secret for a
# guardian's whole fleet (see /dashboard-api/pin in lockprofile_service.py), not something that
# varies per device. Stored as plaintext on disk (a 4-digit PIN can't meaningfully be protected by
# hashing it at rest anyway -- 10,000 combinations is nothing to brute force once a hash is in
# hand). What matters is who can ask the server for it over the network: GET /dashboard-api/pin
# (which returns the raw value) is guardian-browser-session-only, never reachable via the device
# LOCKPROFILE_TOKEN bearer -- that token ships inside the APK and is trivially extractable by the
# same person the PIN is meant to gate, so a device-reachable plaintext read would hand them the
# real PIN directly. Devices instead call POST /dashboard-api/pin/verify, which checks a guess
# against this file server-side and returns only correct/incorrect, under escalating lockout
# (_PIN_VERIFY_LOCKOUT, in lockprofile_service.py) -- the PIN itself never crosses that boundary.
#
# This same value also gates the dashboard website login now (_guardian_pin_value() below,
# _handle_dashboard_login in lockprofile_service.py) -- the guardian asked to drop the separate
# login password and just reuse the PIN everywhere. (/review used to check this same PIN too,
# until it was made unauthenticated on 2026-08-26 -- see Caddyfile.) That folds two different
# threat models into one secret: the PIN was originally sized (4 digits, escalating lockout) for a
# guardian to fat-finger-enter on their own kid's phone, not to resist unlimited remote guessing
# against a website login -- the escalating lockout on the login routes
# (_guardian_login_record_result, in lockprofile_service.py) is what keeps that acceptable, same
# mechanism as the on-device /pin/verify lockout above.
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

TOKEN = os.environ.get("LOCKPROFILE_TOKEN", "")

_pin_lock = threading.Lock()


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


def _guardian_pin_value() -> str:
    """The Guardian PIN as a string, or "" if none is set yet -- see GUARDIAN_PIN_PATH's comment.
    Single read path shared by device Settings-unlock verification AND the dashboard login
    below, so there is exactly one secret to keep in sync."""
    return _load_guardian_pin().get("pin") or ""


# ─── Dashboard session cookie (custom login, replacing Caddy basic_auth) ───────────────────────
# Self-verifying token, `<expiry>.<hmac>` -- no server-side session store to lose on a restart or
# keep in sync across a future second instance. `expiry` is a plain unix timestamp; `hmac` is
# HMAC-SHA256 keyed on TOKEN (LOCKPROFILE_TOKEN), so a token can only have been minted by this
# server and can't be forged or extended by a client tampering with the expiry.
#
# THE SIGNED PAYLOAD INCLUDES A DIGEST OF THE CURRENT PIN, and that is not incidental. Two comments
# in this file (see HANDOFF_TOKEN_PATH in lockprofile_handoff_token.py and _handle_handoff_set_pin
# in lockprofile_service.py) claimed that changing the PIN "invalidates every existing dashboard
# session". It did not: the signature was over the expiry alone, keyed on a token no PIN change
# touches, so every previously-issued cookie stayed valid for the full 30 days. That broke the
# one-time handoff flow at its whole purpose -- "I have finished setting this up, you take over"
# left the previous holder logged in -- and made rotating a leaked PIN useless. Binding the
# signature to the PIN means a change to the PIN changes what verifies, with no session store and
# no new state file to migrate.
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
