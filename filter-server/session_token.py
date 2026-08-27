"""Guardian dashboard session tokens: mint and verify.

Split out of `lockprofile_service.py` for the same reason `CommandPolicy` was split out of
`SudoBroker` on the macOS side -- this is security-critical logic that had no tests, and it had a
real bug precisely there. The module it came from cannot be imported outside its container (it
depends on the classifier stack and on Python 3.10+ syntax), so extracting the pure part is what
makes the property below assertable at all.

THE BUG THIS ENCODES A FIX FOR: the signature used to cover only the expiry timestamp, keyed on
LOCKPROFILE_TOKEN. Nothing about a PIN change altered either input, so every cookie issued before a
PIN change stayed valid for its full 30 days -- while two comments in the service claimed a PIN
change "invalidates every existing dashboard session". That is the difference between the handoff
flow working and merely appearing to: its entire purpose is that the previous holder loses access.

Binding the signature to a digest of the current PIN gets that for free, with no session store to
keep consistent and no new file to migrate.
"""

from __future__ import annotations

import hashlib
import hmac
import secrets
import time

# 30 days. A backstop only: `_set_dashboard_session_cookie` deliberately sends no Max-Age, making
# this a browser-session cookie, so this bound matters when a browser's "restore previous session"
# resurrects one.
MAX_AGE_SECONDS = 30 * 24 * 60 * 60

COOKIE_NAME = "otterling_dashboard_session"


def pin_binding(pin: str | None) -> str:
    """Digest of the PIN a session is bound to, or "" when no PIN is set.

    An empty binding makes every token invalid, which is correct: logging in requires a PIN, so on
    a deployment without one there is no legitimate session to honour.
    """
    if not pin:
        return ""
    return hashlib.sha256(pin.encode("utf-8")).hexdigest()


def create(server_token: str, pin: str | None, now: float | None = None) -> str:
    """Mint `<expiry>.<hmac>` for the given PIN. Returns "" if there is nothing to bind to."""
    binding = pin_binding(pin)
    if not server_token or not binding:
        return ""
    expiry = str(int((time.time() if now is None else now)) + MAX_AGE_SECONDS)
    signature = hmac.new(
        server_token.encode("utf-8"), f"{expiry}.{binding}".encode("utf-8"), hashlib.sha256
    ).hexdigest()
    return f"{expiry}.{signature}"


def valid(token: str, server_token: str, pin: str | None, now: float | None = None) -> bool:
    """True only for a token this server minted, not expired, bound to the CURRENT pin."""
    if not token or not server_token or "." not in token:
        return False
    expiry, _, signature = token.partition(".")
    if not expiry.isdigit():
        return False
    if int(expiry) < (time.time() if now is None else now):
        return False
    binding = pin_binding(pin)
    if not binding:
        return False
    expected = hmac.new(
        server_token.encode("utf-8"), f"{expiry}.{binding}".encode("utf-8"), hashlib.sha256
    ).hexdigest()
    return secrets.compare_digest(signature, expected)


def cookie_from_header(raw_cookie_header: str) -> str | None:
    """Pulls our cookie out of a raw `Cookie:` header value."""
    for part in (raw_cookie_header or "").split(";"):
        name, _, value = part.strip().partition("=")
        if name == COOKIE_NAME:
            return value
    return None
