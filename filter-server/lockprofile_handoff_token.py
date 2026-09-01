"""Single-use, short-lived tokens for handing off the Guardian dashboard password to a partner.

One-time handoff link: lets whoever currently holds a guardian dashboard session (see
POST /dashboard-api/handoff-link in lockprofile_service.py) generate a single-use, expiring,
unguessable (256-bit) token that lets someone WITHOUT the current PIN set a brand-new one at
GET /handoff/?token=... -> POST /handoff-auth/set-pin -- for the one-time "I'm done setting this
up, here's a link to claim the account" handoff moment, not an ongoing PIN-reset mechanism.
Deliberately does NOT require knowing the current PIN (that's the whole point -- the person
using the link is meant to be someone who doesn't have it), unlike the regular
POST /dashboard-api/pin change flow (which is guardian-session-gated instead). Setting a new PIN
this way has the exact same effect as that route (_save_guardian_pin in lockprofile_auth.py),
including invalidating every existing dashboard session, same as any other PIN change. This does
NOT protect against someone who retains actual server/filesystem access after generating a link
(the PIN is still stored in plaintext, same as every other secret this file manages) -- it's a
clean handoff ceremony, not a technical guarantee against a host operator who keeps root.

Extracted as a leaf module (imports nothing back from lockprofile_service.py) so it can be
imported without a circular import.
"""

from __future__ import annotations

import json
import os
import secrets
import threading
import time

DATA_DIR = os.environ.get("LOCKPROFILE_DATA_DIR", "/data")
HANDOFF_TOKEN_PATH = os.path.join(DATA_DIR, "password_handoff_token.json")
HANDOFF_TOKEN_TTL_SECONDS = 48 * 60 * 60  # 48 hours

_handoff_token_lock = threading.Lock()


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
