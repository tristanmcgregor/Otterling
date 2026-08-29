"""Who may call which `/dashboard-api/*` route: the device bearer, or a logged-in guardian.

WHY THIS EXISTS
---------------
`lockprofile_service.py` authenticates every request with one shared bearer token
(`_authorized()`), and that token ships inside the Android APK and the macOS app bundle -- so the
person the system restrains can extract it. The distinction between "a phone reporting in" and "a
guardian changing settings" therefore cannot come from the token. Until now it came *entirely* from
a path+method allowlist in the Caddyfile, with the service itself accepting the bearer for
everything.

That worked only as long as every request arrived through Caddy. Any path that did not -- a
co-tenant port mux forwarding to :8091 (see port8080_mux.py), a debug tunnel, a future container on
the same Docker network -- got full guardian authority with a token extracted from a phone:
`GET /dashboard-api/pin` for the plaintext Guardian PIN, `PATCH` a device's settings to disable
filtering, `POST /dashboard-api/pin` to lock the guardian out. The Caddyfile's own comment records
that a broader version of its matcher shipped once and was caught in review for exactly this
reason, which shows how much was resting on one config file being right.

This module moves that decision into the service, next to the code it protects. Caddy keeps its
allowlist -- defence in depth, and it still usefully rejects unauthorized traffic before it reaches
Python -- but it is no longer the only thing enforcing the boundary.

KEEPING THE TWO IN SYNC
-----------------------
`DEVICE_BEARER_ROUTES` below is a deliberate mirror of the `@dashboardApiDeviceGet` and
`@dashboardApiDevicePost` matchers in `filter-server/Caddyfile`. If you add a device-reachable
route, change both. `tests/test_route_policy.py` asserts the shape of this table so an accidental
widening shows up as a test failure rather than as a quiet privilege grant.

ONE DELIBERATE DIFFERENCE FROM CADDY
------------------------------------
`GET /dashboard-api/habitshare-account` is device-reachable in the Caddyfile but is
guardian-only here. It returns a third-party account's username AND password in plaintext; the
Caddyfile's comment argues that is acceptable because HabitShare is unrelated to anything Otterling
enforces. Perhaps -- but no device code path fetches it (the Mac and phone read habits, not the
account), so device access buys nothing and costs a credential handed to the restrained user.
"""

from __future__ import annotations

# Access classes. Strings rather than an enum so a log line reads plainly.
DEVICE_BEARER_OK = "device_bearer_ok"
GUARDIAN_SESSION_REQUIRED = "guardian_session_required"

# The prefix this policy governs. Paths outside it are not this module's business -- notably
# /alerts/*, /integrity/*, /device-logs/upload and /screenshot-classify, which are genuine
# device-to-server reporting routes gated by the bearer alone, by design.
DASHBOARD_API_PREFIX = "/dashboard-api/"

# (method, path template) pairs a device may call. "*" matches exactly one path segment -- NOT a
# greedy wildcard, so "/dashboard-api/devices/*/settings" cannot be stretched to reach a different
# route by stuffing slashes into the device id.
DEVICE_BEARER_ROUTES: frozenset[tuple[str, str]] = frozenset({
    # The Mac's DashboardConfigSync and the phone's DashboardConfigStore poll their own settings.
    ("GET", "/dashboard-api/devices/*/settings"),
    # PinAuthManager caches "does a PIN exist" to decide whether Settings needs one at all.
    ("GET", "/dashboard-api/pin/exists"),
    # The habit library, read by RuleBlockEnforcer / HabitRuleManager.
    ("GET", "/dashboard-api/habits"),
    # One guess in, correct-or-not out. Never reveals the PIN.
    ("POST", "/dashboard-api/pin/verify"),
    # HabitCompletionReporter marks a habit done.
    ("POST", "/dashboard-api/habits/*/complete"),
    # InstalledAppsReporter / DashboardConfigSync.reportInstalledApps.
    ("POST", "/dashboard-api/devices/*/installed-apps"),
    ("POST", "/dashboard-api/devices/*/app-info"),
    # DELIBERATE, ACCEPTED WIDENING: AdminPasswordSync.swift polls this to learn a PIN just set
    # through the one-time account-handoff flow (see lockprofile_service.py's
    # ADMIN_PASSWORD_SYNC_PATH), so it can also apply that PIN as the local macOS admin account's
    # login password. This DOES hand a device holding the shared bearer token a one-time read of
    # the plaintext Guardian PIN -- exactly the class of exposure this module exists to keep off
    # the device-reachable list (see the habitshare-account precedent above). Accepted anyway,
    # explicitly, because it's scoped as tightly as that tradeoff allows: populated only at the
    # moment a handoff link is actually consumed (not on every ordinary PIN change), expires in
    # minutes, and is destroyed on first read so at most one device can ever claim it.
    ("GET", "/dashboard-api/admin-password-sync"),
})


def _segments(path: str) -> list[str]:
    return [segment for segment in path.split("/") if segment != ""]


def _matches(template: str, path: str) -> bool:
    """Segment-wise match where "*" stands for exactly one segment."""
    template_segments = _segments(template)
    path_segments = _segments(path)
    if len(template_segments) != len(path_segments):
        return False
    return all(
        expected == "*" or expected == actual
        for expected, actual in zip(template_segments, path_segments)
    )


def governs(path: str) -> bool:
    """True if `path` is a /dashboard-api/* route this policy has an opinion about."""
    return path.startswith(DASHBOARD_API_PREFIX)


def required_access(method: str, path: str) -> str:
    """The access class needed to call `method path`.

    Fails CLOSED for anything unrecognized: a route added without being classified requires a
    guardian session, so forgetting to update this table denies a device rather than silently
    granting it guardian authority. A denied device surfaces immediately as a visible malfunction;
    the opposite mistake is invisible.
    """
    normalized = method.upper()
    for template_method, template_path in DEVICE_BEARER_ROUTES:
        if template_method == normalized and _matches(template_path, path):
            return DEVICE_BEARER_OK
    return GUARDIAN_SESSION_REQUIRED
