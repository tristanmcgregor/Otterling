#!/usr/bin/env python3
"""DNS resolution-time domain classifier -- sits in front of AdGuardHome on the LAN-facing DNS
port.

Cert-pinned/exempt apps (banking apps, YouTube, ...) never get their HTTPS content inspected by
mitm_nsfw_addon.py -- by design, since decrypting their traffic is exactly what pinning prevents.
This closes part of that gap by judging *the domain itself* at resolution time, so it applies to
every app equally, pinned or not. It can't replace the HTTP-level checks: a DNS query only ever
carries a hostname, never a path, and a domain gets resolved once but reused for many different
page loads afterward -- this can only ever judge "is this domain's own content bad," never "is
this specific page bad."

Per query: check the shared adult-domain blocklist (domain_blocklist.py, same source as
mitm_nsfw_addon.py) -- NXDOMAIN immediately if matched, no network call. Otherwise check a local
allow/deny cache (24h, separate from mitm_nsfw_addon.py's own cache -- different container,
different process, not shared; a known, accepted v1 simplification). Otherwise fetch the domain's
homepage and run it through the shared AI classifier (ai_classifier.py), all within one bounded
budget -- on any failure or budget overrun, fail open (forward the query normally) and don't
cache, so it's retried fresh on a future lookup rather than stuck on a transient failure.

Pure stdlib asyncio -- no dnspython/third-party dependency, matching this project's existing
preference for stdlib-only scripts (see port8080_mux.py). Hand-parsing a DNS question is simple
(a length-prefixed label sequence terminated by a zero byte); the "allowed" path never needs to
parse AdGuardHome's response at all, just relay the raw bytes back verbatim. The "blocked" path
builds a synthetic NXDOMAIN response the same way DnsMessage.kt's buildBlockedResponse already
does on the Android side: mutate the original query's header and echo back the question section.

Every query is handled as its own asyncio task with its own try/except, so one slow/failing
domain can't block concurrent lookups for anything else -- same "each flow independent" approach
already used throughout TcpRelayManager/port8080_mux.py.

Explicitly out of scope for v1: TCP-based DNS (UDP covers the overwhelming majority of real
lookups; a truncated response needing a TCP retry falls through this mux specifically -- rare for
ordinary A-record lookups, but a real, named gap, not silently dropped).
"""

from __future__ import annotations

import asyncio
import http.client
import ipaddress
import logging
import os
import signal
import socket
import ssl
import time

import ai_classifier
import domain_blocklist

LISTEN_HOST = os.environ.get("DNS_MUX_LISTEN_HOST", "0.0.0.0")
LISTEN_PORT = int(os.environ.get("DNS_MUX_LISTEN_PORT", "53"))
UPSTREAM_DNS_HOST = os.environ.get("UPSTREAM_DNS_HOST", "adguardhome")
UPSTREAM_DNS_PORT = int(os.environ.get("UPSTREAM_DNS_PORT", "53"))

CLASSIFY_CACHE_TTL_SECONDS = 24 * 60 * 60
HOMEPAGE_FETCH_TIMEOUT_SECONDS = 5
# Overall budget for fetch + AI classification together (ai_classifier's own call already caps at
# ANTHROPIC_TIMEOUT_SECONDS) -- past this, fail open rather than let one slow domain hang a lookup
# indefinitely.
CLASSIFY_BUDGET_SECONDS = 15
UPSTREAM_TIMEOUT_SECONDS = 5

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("dns_classify_mux")


def parse_query_domain(data: bytes) -> str | None:
    """Reads just the first question's name -- same approach as DnsMessage.kt's parseQuery on the
    Android side (a query's own question section is always the first name in the message, no
    compression pointers to resolve)."""
    if len(data) < 12:
        return None
    question_count = (data[4] << 8) | data[5]
    if question_count < 1:
        return None
    labels: list[str] = []
    offset = 12
    while offset < len(data):
        length = data[offset]
        offset += 1
        if length == 0:
            break
        if offset + length > len(data):
            return None
        labels.append(data[offset : offset + length].decode("ascii", errors="ignore"))
        offset += length
    name = ".".join(labels)
    return name or None


def build_blocked_response(query: bytes) -> bytes:
    """NXDOMAIN, reusing the original query's header/question section -- mirrors DnsMessage.kt's
    buildBlockedResponse exactly."""
    if len(query) < 12:
        return query
    response = bytearray(query)
    response[2] |= 0x80  # QR=1: this is a response
    response[3] = 0x83  # RA=1, RCODE=3 (NXDOMAIN)
    response[6:8] = (0).to_bytes(2, "big")  # ANCOUNT
    response[8:10] = (0).to_bytes(2, "big")  # NSCOUNT
    response[10:12] = (0).to_bytes(2, "big")  # ARCOUNT
    return bytes(response)


class _ClassifyCache:
    """In-memory allow/deny cache keyed by domain -- same TTL pattern as mitm_nsfw_addon.py's own
    _DenyCache/_AllowCache, but a separate instance in a separate process/container, not shared."""

    def __init__(self):
        self._entries: dict[str, tuple[bool, float]] = {}

    def get(self, domain: str) -> bool | None:
        entry = self._entries.get(domain)
        if entry is None:
            return None
        verdict, expiry = entry
        if expiry <= time.time():
            del self._entries[domain]
            return None
        return verdict

    def set(self, domain: str, verdict: bool):
        self._entries[domain] = (verdict, time.time() + CLASSIFY_CACHE_TTL_SECONDS)


def _resolve_public_ipv4(domain: str) -> str:
    """Resolves `domain` and raises if it points at anything other than a public IPv4 address.

    Every DNS query this mux sees comes from a household device -- without this check, a domain
    an attacker controls (or a DNS-rebinding setup) could simply resolve to an internal Docker-
    network host or a loopback/link-local address, and this fetch would happily connect to it.
    Only IPv4 is considered (matching AdultBlocklistManager's own scope elsewhere in this project).
    """
    infos = socket.getaddrinfo(domain, 443, family=socket.AF_INET, type=socket.SOCK_STREAM)
    if not infos:
        raise ValueError(f"could not resolve {domain}")
    ip = infos[0][4][0]
    if not ipaddress.ip_address(ip).is_global:
        raise ValueError(f"{domain} resolved to non-public address {ip}")
    return ip


class _PinnedHTTPSConnection(http.client.HTTPSConnection):
    """Connects to a single, already-validated IP (never re-resolving `domain`, which would
    reopen the same SSRF/DNS-rebinding gap `_resolve_public_ipv4` exists to close) while still
    doing normal TLS hostname verification -- via SNI/`server_hostname` -- against the original
    domain name, so a mismatched/invalid certificate still fails the connection as usual."""

    def __init__(self, ip: str, domain: str, timeout: float):
        super().__init__(ip, 443, timeout=timeout)
        self._verify_hostname = domain

    def connect(self):
        sock = socket.create_connection((self.host, self.port), self.timeout)
        context = ssl.create_default_context()
        self.sock = context.wrap_socket(sock, server_hostname=self._verify_hostname)


def _fetch_and_classify(domain: str) -> bool | None:
    """Fetches the domain's homepage and runs it through the shared AI classifier. Blocking
    (plain http.client), meant to be run via run_in_executor. Returns None on any failure so the
    caller fails open -- same title/excerpt extraction mitm_nsfw_addon.py's response() does for
    HTML bodies. Redirects are deliberately not followed -- a page could otherwise redirect this
    fetcher at an unvalidated target, reopening the same SSRF gap the IP pinning above closes."""
    try:
        ip = _resolve_public_ipv4(domain)
        conn = _PinnedHTTPSConnection(ip, domain, timeout=HOMEPAGE_FETCH_TIMEOUT_SECONDS)
        try:
            conn.request(
                "GET", "/",
                headers={"Host": domain, "user-agent": "otterling-dns-classifier"},
            )
            resp = conn.getresponse()
            body = resp.read(200_000).decode("utf-8", errors="replace")
        finally:
            conn.close()
    except Exception as error:
        log.info("homepage fetch failed for %s: %s", domain, error)
        return None
    title, excerpt = ai_classifier.extract_title_and_excerpt(body)
    return ai_classifier.classify_with_ai(f"https://{domain}/", title, excerpt)


async def _query_upstream(loop: asyncio.AbstractEventLoop, data: bytes) -> bytes | None:
    """Relays raw query bytes to AdGuardHome over its own short-lived UDP socket and returns its
    raw response bytes verbatim -- the caller never needs to parse this."""
    response_future: asyncio.Future[bytes] = loop.create_future()

    class _UpstreamProtocol(asyncio.DatagramProtocol):
        def connection_made(self, transport):
            transport.sendto(data)

        def datagram_received(self, resp_data, addr):
            if not response_future.done():
                response_future.set_result(resp_data)

        def error_received(self, exc):
            if not response_future.done():
                response_future.set_exception(exc)

    transport, _protocol = await loop.create_datagram_endpoint(
        _UpstreamProtocol, remote_addr=(UPSTREAM_DNS_HOST, UPSTREAM_DNS_PORT)
    )
    try:
        return await response_future
    finally:
        transport.close()


class _DnsMuxProtocol(asyncio.DatagramProtocol):
    def __init__(self, domains: domain_blocklist.DomainList, cache: _ClassifyCache):
        self.domains = domains
        self.cache = cache
        self.transport: asyncio.DatagramTransport | None = None

    def connection_made(self, transport):
        self.transport = transport

    def datagram_received(self, data: bytes, addr):
        # Each query is its own task -- one slow/failing domain can't block concurrent lookups.
        asyncio.ensure_future(self._handle(data, addr))

    async def _handle(self, data: bytes, addr):
        try:
            await self._handle_query(data, addr)
        except Exception as error:
            log.warning("query handler error from %s: %s", addr, error)

    async def _handle_query(self, data: bytes, addr):
        loop = asyncio.get_running_loop()
        # Blocking (up to ~40s on a real refresh, once/day) -- run off the event loop so it can't
        # stall every other concurrent lookup on this single-threaded mux.
        await loop.run_in_executor(None, self.domains.refresh_if_stale)
        domain = parse_query_domain(data)
        if not domain:
            await self._forward(data, addr)
            return
        domain = domain.lower().rstrip(".")

        if self.domains.matches(domain):
            self.transport.sendto(build_blocked_response(data), addr)
            return

        cached = self.cache.get(domain)
        if cached is True:
            self.transport.sendto(build_blocked_response(data), addr)
            return
        if cached is False:
            await self._forward(data, addr)
            return

        try:
            verdict = await asyncio.wait_for(
                loop.run_in_executor(None, _fetch_and_classify, domain),
                timeout=CLASSIFY_BUDGET_SECONDS,
            )
        except asyncio.TimeoutError:
            log.info("classification budget exceeded for %s -- failing open", domain)
            verdict = None
        except Exception as error:
            log.warning("classification failed for %s: %s", domain, error)
            verdict = None

        if verdict is True:
            self.cache.set(domain, True)
            log.info("AI classifier: BLOCKED %s", domain)
            self.transport.sendto(build_blocked_response(data), addr)
            return
        if verdict is False:
            self.cache.set(domain, False)
            log.info("AI classifier: allowed %s", domain)
        # verdict is None -- classification itself failed or ran out of budget; fail open and
        # don't cache, so the next lookup gets a fresh attempt instead of being stuck on it.
        await self._forward(data, addr)

    async def _forward(self, data: bytes, addr):
        loop = asyncio.get_running_loop()
        try:
            response = await asyncio.wait_for(
                _query_upstream(loop, data), timeout=UPSTREAM_TIMEOUT_SECONDS
            )
        except Exception as error:
            log.warning("upstream query failed: %s", error)
            return
        if response is not None and self.transport is not None:
            self.transport.sendto(response, addr)


async def main():
    domains = domain_blocklist.DomainList()
    domains.refresh_if_stale()
    cache = _ClassifyCache()

    loop = asyncio.get_running_loop()
    transport, _protocol = await loop.create_datagram_endpoint(
        lambda: _DnsMuxProtocol(domains, cache),
        local_addr=(LISTEN_HOST, LISTEN_PORT),
    )
    log.info(
        "listening on %s:%s ; upstream %s:%s",
        LISTEN_HOST,
        LISTEN_PORT,
        UPSTREAM_DNS_HOST,
        UPSTREAM_DNS_PORT,
    )

    stop = asyncio.Event()

    def _stop(*_args):
        stop.set()

    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, _stop)

    try:
        await stop.wait()
    finally:
        transport.close()


if __name__ == "__main__":
    asyncio.run(main())
