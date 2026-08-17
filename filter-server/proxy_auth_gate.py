"""Custom mitmproxy CONNECT/HTTP proxy authentication, replacing the built-in `--proxyauth` option
with the same Basic-auth semantics PLUS an IP exemption for the household's own egress.

Why this exists: this mitmproxy is deliberately reachable from the public internet (the phone
connects to it from outside the home network too, e.g. over cellular), so it must stay password
gated in general -- see docker-compose.yml's mitmproxy service comment. But routing the MAC itself
through it as a system-wide HTTP/HTTPS proxy (see macos/FocusLock ProxyEnforcer.swift) exposed a
real problem: many macOS processes that use the system proxy (background daemons like locationd's
`configuration.ls.apple.com`/`gsp-ssl.ls.apple.com` checks, and even Chrome unreliably) have no way
to answer an interactive Basic-auth challenge. A non-interactive process just loops forever re-
sending the same request and getting 407, hammering the proxy; Chrome's own dialog flow was
unreliable enough to leave real page loads hanging (ERR_TIMED_OUT). Requiring credentials from
literally every client made the system-wide proxy unusable.

The fix: skip the auth challenge entirely for connections from an allowlisted IP
(`PROXY_AUTH_EXEMPT_IPS`, comma-separated) -- meant for the household's own public egress IP (what
mitmproxy sees as the Mac's `client_conn.peername`, since the Mac reaches this proxy via its public
hostname, hairpinning through the same egress as the rest of the household). This doesn't meaningfully
change the threat model: the household's LAN is already Wi-Fi-password gated, and everything on it
was already implicitly trusted by the existing DNS-based filtering (any device forwarding through
the resolved cloud-filter IP is trusted the same way). Auth stays required for every other IP
(the internet at large, including the phone's own cellular connections), so a stranger can't use
this as an open relay.

Mirrors mitmproxy's own `mitmproxy.addons.proxyauth.ProxyAuth` (same Basic-auth-over-CONNECT /
per-request-header logic), not a subclass of it, to avoid depending on that module's internals.
Loaded via `-s proxy_auth_gate.py` INSTEAD OF the `--proxyauth` command-line option (which must not
also be set, or both mechanisms would double-gate the same requests).
"""

from __future__ import annotations

import base64
import os
import weakref

from mitmproxy import http

REALM = "mitmproxy"
PROXY_AUTH_HEADER = "Proxy-Authorization"


def _load_exempt_ips() -> set[str]:
    raw = os.environ.get("PROXY_AUTH_EXEMPT_IPS", "")
    return {ip.strip() for ip in raw.split(",") if ip.strip()}


class HouseholdProxyAuth:
    def __init__(self):
        self.user = os.environ.get("PROXY_USER", "otterling")
        self.password = os.environ.get("PROXY_PASSWORD", "")
        self.exempt_ips = _load_exempt_ips()
        # Tracks connections already authenticated by a prior CONNECT, same as mitmproxy's own
        # addon -- an HTTPS tunnel only sends Proxy-Authorization once, on the CONNECT itself; every
        # decrypted request inside it must be treated as already-authenticated for that connection's
        # lifetime. WeakSet so entries drop away as connections close, no manual cleanup needed.
        self._authenticated: "weakref.WeakSet" = weakref.WeakSet()

    def _client_ip(self, flow: http.HTTPFlow) -> str | None:
        peer = getattr(flow.client_conn, "peername", None)
        return peer[0] if peer else None

    def _is_exempt(self, flow: http.HTTPFlow) -> bool:
        return self._client_ip(flow) in self.exempt_ips

    def _check_credentials(self, flow: http.HTTPFlow) -> bool:
        value = flow.request.headers.get(PROXY_AUTH_HEADER, "")
        if not value.startswith("Basic "):
            return False
        try:
            decoded = base64.b64decode(value[len("Basic "):]).decode("utf-8", "replace")
            user, _, password = decoded.partition(":")
        except Exception:
            return False
        ok = user == self.user and password == self.password
        if ok:
            del flow.request.headers[PROXY_AUTH_HEADER]
        return ok

    def _challenge(self, flow: http.HTTPFlow) -> None:
        flow.response = http.Response.make(
            407,
            b"Proxy authentication required.",
            {"Proxy-Authenticate": f'Basic realm="{REALM}"'},
        )

    def http_connect(self, flow: http.HTTPFlow) -> None:
        if not self.password or self._is_exempt(flow):
            return
        if self._check_credentials(flow):
            self._authenticated.add(flow.client_conn)
        else:
            self._challenge(flow)

    def requestheaders(self, flow: http.HTTPFlow) -> None:
        if not self.password or self._is_exempt(flow):
            return
        if flow.client_conn in self._authenticated:
            return
        if not self._check_credentials(flow):
            self._challenge(flow)
