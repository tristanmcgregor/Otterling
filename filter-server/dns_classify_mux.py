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

Per query: first check whether the querying device currently has an active, habit-gated website
rule against this domain (_WebsiteRuleBlocks, polling lockprofile_service.py's
/internal/dns-website-blocks) -- this is the macOS-side enforcement of the same dashboard
"Habit Rule Wizard" website rules the Android app enforces locally via VpnFilterService, and it
runs before every other check below since a habit-gated domain (e.g. youtube.com) is often
content none of the other checks would ever flag. Then check the shared adult-domain blocklist
(domain_blocklist.py, same source as mitm_nsfw_addon.py) -- NXDOMAIN immediately if matched, no
network call. Otherwise check a local allow/deny cache (24h, separate from mitm_nsfw_addon.py's
own cache -- different container, different process, not shared; a known, accepted v1
simplification). Otherwise fetch the domain's homepage and run it through the shared AI classifier
(ai_classifier.py), all within one bounded budget -- on any failure or budget overrun, fail open
(forward the query normally) and don't cache, so it's retried fresh on a future lookup rather than
stuck on a transient failure.

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
import concurrent.futures
import http.client
import ipaddress
import json
import logging
import os
import signal
import socket
import ssl
import threading
import time

import ai_classifier
import domain_blocklist

LISTEN_HOST = os.environ.get("DNS_MUX_LISTEN_HOST", "0.0.0.0")
LISTEN_PORT = int(os.environ.get("DNS_MUX_LISTEN_PORT", "53"))
UPSTREAM_DNS_HOST = os.environ.get("UPSTREAM_DNS_HOST", "adguardhome")
UPSTREAM_DNS_PORT = int(os.environ.get("UPSTREAM_DNS_PORT", "53"))

# lockprofile_service.py's internal endpoint for habit-gated website rules (see
# _WebsiteRuleBlocks below) -- reached over the compose network by service name, same as
# UPSTREAM_DNS_HOST/adguardhome above.
LOCKPROFILE_HOST = os.environ.get("LOCKPROFILE_HOST", "lockprofile")
LOCKPROFILE_PORT = int(os.environ.get("LOCKPROFILE_PORT", "8091"))
LOCKPROFILE_TOKEN = os.environ.get("LOCKPROFILE_TOKEN", "")
WEBSITE_RULE_POLL_SECONDS = 10

# Durable record of every domain this deployment's AI classifier has ever judged BLOCKED --
# volume-mounted read-write here and read-only into the `updates` (Caddy) container, which serves
# it to the Android app at /filter-lists/classified-bad-domains.txt. See _PersistedBadDomains.
CLASSIFIED_BAD_DOMAINS_PATH = os.environ.get(
    "CLASSIFIED_BAD_DOMAINS_PATH", "/data/classified-domains/classified-bad-domains.txt"
)
CLASSIFIED_BAD_DOMAINS_MAX_ENTRIES = 50_000

CLASSIFY_CACHE_TTL_SECONDS = 24 * 60 * 60
HOMEPAGE_FETCH_TIMEOUT_SECONDS = 5
# Overall budget for fetch + AI classification together (ai_classifier's own call already caps at
# CLAUDE_TIMEOUT_SECONDS -- higher than the old raw-API timeout since `claude -p` has real startup
# overhead) -- past this, fail open rather than let one slow domain hang a lookup indefinitely.
CLASSIFY_BUDGET_SECONDS = 35
UPSTREAM_TIMEOUT_SECONDS = 5

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("dns_classify_mux")

# Dedicated pool for the slow fetch+classify work (homepage fetch + a `claude -p` subprocess call,
# together up to ~30s per domain). Deliberately *not* asyncio's default executor (run_in_executor's
# implicit pool, sized off CPU count -- min(32, cpu_count+4), 12 on this host): that pool used to
# also carry the per-query refresh_if_stale() check, so once enough of these slow calls piled up
# (e.g. one ad-heavy page load resolving a dozen distinct tracker domains at once -- much easier to
# hit now that `claude -p` is far slower to start than the old raw API call), later queries queued
# up behind them for a *fast, non-blocking* check that had no business sharing a pool with slow I/O
# in the first place -- stalling every device's DNS, not just the one hitting new domains.
#
# Small on purpose, NOT sized generously: unlike a plain network fetch, `claude -p` startup is
# real CPU work (Node.js/V8 init), not just I/O wait. At the old value of 64 a single ad-heavy page
# load -- resolving a dozen-plus distinct tracker/CDN domains at once -- could spawn a dozen-plus
# concurrent `claude -p` processes with no cap, saturating this host's 8 cores at once. That
# starved everything else on the box, including interactive SSH: a logged-in session's cgroup gets
# none of ssh.service's own CPU-priority boost (see 99-priority.conf on the host), so it stalled
# right along with everything else during a burst. Kept low enough that a worst-case simultaneous
# burst still leaves most cores free.
_CLASSIFY_EXECUTOR = concurrent.futures.ThreadPoolExecutor(max_workers=4, thread_name_prefix="classify")


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


class _WebsiteRuleBlocks:
    """Polls lockprofile_service.py's /internal/dns-website-blocks on its own short cadence and
    caches the result in memory, so a habit-gated website rule (e.g. "block youtube.com unless 2
    habits are done") lifts within one poll interval of the habit being marked done --mirroring
    HabitRuleManager.kt's isWebsiteCurrentlyBlocked, which re-evaluates fresh on every DNS query
    on the Android side. Polling instead of a per-query HTTP round-trip keeps every OTHER DNS
    query (the overwhelming majority, which no rule applies to at all) free of network-hop
    latency -- same "own slower cadence, cheap is_stale() check on the hot path" shape as
    domain_blocklist.py's DomainList, which this deliberately mirrors.

    Keyed by whatever source identifier lockprofile_service.py's _dns_website_blocks_by_source_key
    used -- a LAN IP, a DEVICE_ID_ALIASES hostname, or a raw device_id -- matched here against the
    literal UDP source IP, so this only ever recognizes devices already covered by that mapping
    (see DEVICE_ID_ALIASES's own doc comment; a Mac queried from an unaliased IP -- e.g. off the
    home LAN -- simply has no rule applied here, an accepted v1 gap)."""

    def __init__(self):
        self._lock = threading.Lock()
        self._by_source_key: dict[str, set[str]] = {}
        self._last_poll = 0.0
        self._polling = threading.Lock()

    def is_stale(self) -> bool:
        with self._lock:
            return (time.time() - self._last_poll) >= WEBSITE_RULE_POLL_SECONDS

    def refresh_if_stale(self):
        if not self.is_stale():
            return
        if not self._polling.acquire(blocking=False):
            return
        try:
            if not LOCKPROFILE_TOKEN:
                return
            conn = http.client.HTTPConnection(LOCKPROFILE_HOST, LOCKPROFILE_PORT, timeout=5)
            try:
                conn.request(
                    "GET",
                    "/internal/dns-website-blocks",
                    headers={"Authorization": f"Bearer {LOCKPROFILE_TOKEN}"},
                )
                resp = conn.getresponse()
                body = resp.read()
                if resp.status != 200:
                    log.warning("website-rule poll got HTTP %s", resp.status)
                    return
            finally:
                conn.close()
            blocks = json.loads(body.decode("utf-8")).get("blocks") or {}
            parsed = {
                key: set(domains) for key, domains in blocks.items() if isinstance(domains, list)
            }
            with self._lock:
                self._by_source_key = parsed
                self._last_poll = time.time()
        except Exception as error:
            # Fail open (keep whatever was last cached, or empty on first-ever failure) --
            # a transient lockprofile outage must not either wedge every DNS query behind a
            # blocking retry or start blocking domains based on stale-forever data with no way
            # to tell staleness apart from "no rule configured."
            log.warning("website-rule poll failed: %s", error)
        finally:
            self._polling.release()

    def blocked_domain_for(self, source_ip: str, host: str) -> bool:
        with self._lock:
            domains = self._by_source_key.get(source_ip)
        if not domains:
            return False
        candidate = host
        while candidate:
            if candidate in domains:
                return True
            dot = candidate.find(".")
            if dot == -1:
                break
            candidate = candidate[dot + 1 :]
        return False


class _PersistedBadDomains:
    """Durable record of every domain this deployment's AI classifier has ever judged BLOCKED
    (verdict is True in _handle_query below -- never the allowed/False verdict), so the Android
    app's daily DomainBlocklistManager.refresh() can sync them down and keep blocking previously-
    confirmed-bad domains even during a full filter-server outage (see VpnFilterService.kt's
    forwardQuery(), which otherwise has nothing but a public resolver to fall back to for domains
    not already on the static StevenBlack/BlocklistProject lists).

    Deliberately NOT the same TTL/lifecycle as _ClassifyCache above: that cache exists purely to
    avoid re-running an expensive AI classification on every repeat query, and 24h is tuned for
    that cost-control purpose. A domain that's actually bad doesn't stop being bad after 24h, so
    this store has no expiry -- only a generous entry cap as a safety valve against unbounded
    growth over months/years, evicting the oldest-classified entries first if that cap is ever hit.

    File format matches the same hosts-file convention domain_blocklist.py's own sources use
    (`0.0.0.0 <domain>`), plus a third whitespace-separated column (unix timestamp) -- the Android
    parser (DomainBlocklistManager.downloadHostsFile) only ever reads the first two columns, so
    this needs zero changes on the Android side to consume.
    """

    def __init__(self, path: str):
        self._path = path
        self._lock = threading.Lock()
        self._domains: dict[str, float] = {}  # domain -> first-classified unix timestamp
        self._load()

    def _load(self):
        try:
            with open(self._path, "r", encoding="utf-8") as handle:
                lines = handle.readlines()
        except FileNotFoundError:
            return
        except OSError as error:
            log.warning("failed to load persisted bad-domains file %s: %s", self._path, error)
            return
        loaded: dict[str, float] = {}
        for raw_line in lines:
            line = raw_line.strip()
            if not line:
                continue
            parts = line.split()
            if len(parts) < 2 or parts[0] != "0.0.0.0":
                continue  # malformed line -- skip it, don't fail the whole load
            domain = parts[1].lower().rstrip(".")
            if not domain:
                continue
            try:
                classified_at = float(parts[2]) if len(parts) >= 3 else time.time()
            except ValueError:
                classified_at = time.time()
            loaded[domain] = classified_at
        with self._lock:
            self._domains = loaded
        log.info("loaded %d persisted bad domain(s) from %s", len(loaded), self._path)

    def contains(self, domain: str) -> bool:
        with self._lock:
            return domain in self._domains

    def record(self, domain: str):
        """Appends `domain` if it's not already recorded -- a no-op past the first call for a
        given domain, so calling this once per BLOCKED verdict (even though _ClassifyCache already
        suppresses most repeat classifications for 24h) is cheap and safe."""
        with self._lock:
            if domain in self._domains:
                return
            now = time.time()
            self._domains[domain] = now
            entry_count = len(self._domains)
            entries_snapshot = dict(self._domains) if entry_count > CLASSIFIED_BAD_DOMAINS_MAX_ENTRIES else None
        self._append_line(domain, now)
        if entries_snapshot is not None:
            self._evict_oldest(entries_snapshot)

    def _append_line(self, domain: str, classified_at: float):
        try:
            os.makedirs(os.path.dirname(self._path), exist_ok=True)
            with open(self._path, "a", encoding="utf-8") as handle:
                handle.write(f"0.0.0.0 {domain} {int(classified_at)}\n")
            os.chmod(self._path, 0o644)
        except OSError as error:
            log.warning("failed to persist bad domain %s: %s", domain, error)

    def _evict_oldest(self, entries: dict[str, float]):
        """Rare path (only once CLASSIFIED_BAD_DOMAINS_MAX_ENTRIES is exceeded) -- rewrites the
        whole file keeping only the most-recently-classified entries, rather than maintaining a
        more complex bounded structure for a case that essentially never triggers in practice."""
        kept = dict(
            sorted(entries.items(), key=lambda item: item[1], reverse=True)[:CLASSIFIED_BAD_DOMAINS_MAX_ENTRIES]
        )
        with self._lock:
            self._domains = kept
        try:
            with open(self._path, "w", encoding="utf-8") as handle:
                for domain, classified_at in kept.items():
                    handle.write(f"0.0.0.0 {domain} {int(classified_at)}\n")
            os.chmod(self._path, 0o644)
        except OSError as error:
            log.warning("failed to rewrite persisted bad-domains file during eviction: %s", error)
            return
        log.info(
            "persisted bad-domains file exceeded %d entries -- evicted down to newest %d",
            CLASSIFIED_BAD_DOMAINS_MAX_ENTRIES,
            len(kept),
        )


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
    def __init__(
        self,
        domains: domain_blocklist.DomainList,
        cache: _ClassifyCache,
        persisted: _PersistedBadDomains,
        website_rules: _WebsiteRuleBlocks,
    ):
        self.domains = domains
        self.cache = cache
        self.persisted = persisted
        self.website_rules = website_rules
        self.transport: asyncio.DatagramTransport | None = None
        # De-dupes concurrent classification requests for the same not-yet-cached domain --
        # without this, N simultaneous queries for a brand-new domain (e.g. several analytics/CDN
        # subdomains an app hits in a burst) each independently pay the full fetch+AI-classify
        # cost, piling redundant blocking work onto the shared executor pool and stalling every
        # other device's DNS lookups behind it. Confirmed live: duplicate concurrent
        # classification calls for the same domain within the same second.
        self._inflight: dict[str, asyncio.Future] = {}

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
        # is_stale() itself is non-blocking (no I/O), so it's cheap to call directly on the event
        # loop for every query. Only actually dispatch to an executor -- and pay a thread-pool
        # round-trip -- on the rare (~once/day) occasion a real refresh is needed; refresh_if_stale()
        # itself de-dupes concurrent refreshes, so this is cheap for all but one caller even when
        # many queries land at once right as the cache goes stale.
        if self.domains.is_stale():
            await loop.run_in_executor(None, self.domains.refresh_if_stale)
        if self.website_rules.is_stale():
            await loop.run_in_executor(None, self.website_rules.refresh_if_stale)
        domain = parse_query_domain(data)
        if not domain:
            await self._forward(data, addr)
            return
        domain = domain.lower().rstrip(".")

        # Checked first, ahead of the static/persisted/AI-classified paths below: a
        # habit-gated domain (e.g. youtube.com) is often perfectly legitimate content that the
        # AI classifier/adult blocklist would never flag, and its own cached "allowed" verdict
        # must never suppress this dynamic, per-device, time-windowed check.
        if self.website_rules.blocked_domain_for(addr[0], domain):
            self.transport.sendto(build_blocked_response(data), addr)
            return

        if self.domains.matches(domain):
            self.transport.sendto(build_blocked_response(data), addr)
            return

        if self.persisted.contains(domain):
            self.transport.sendto(build_blocked_response(data), addr)
            return

        cached = self.cache.get(domain)
        if cached is True:
            self.transport.sendto(build_blocked_response(data), addr)
            return
        if cached is False:
            await self._forward(data, addr)
            return

        verdict = await self._classify_domain(domain)

        if verdict is True:
            self.cache.set(domain, True)
            self.persisted.record(domain)
            log.info("AI classifier: BLOCKED %s", domain)
            self.transport.sendto(build_blocked_response(data), addr)
            return
        if verdict is False:
            self.cache.set(domain, False)
            log.info("AI classifier: allowed %s", domain)
        # verdict is None -- classification itself failed or ran out of budget; fail open and
        # don't cache, so the next lookup gets a fresh attempt instead of being stuck on it.
        await self._forward(data, addr)

    async def _classify_domain(self, domain: str) -> bool | None:
        """Runs (or joins an already-running) fetch+AI-classify for `domain`. The first caller
        for a given domain does the real work; any concurrent callers for the same domain just
        await that same in-flight result instead of starting their own redundant fetch."""
        loop = asyncio.get_running_loop()
        existing = self._inflight.get(domain)
        if existing is not None:
            try:
                return await asyncio.wait_for(asyncio.shield(existing), timeout=CLASSIFY_BUDGET_SECONDS)
            except asyncio.TimeoutError:
                return None

        future: asyncio.Future = loop.create_future()
        self._inflight[domain] = future
        try:
            verdict = await asyncio.wait_for(
                loop.run_in_executor(_CLASSIFY_EXECUTOR, _fetch_and_classify, domain),
                timeout=CLASSIFY_BUDGET_SECONDS,
            )
        except asyncio.TimeoutError:
            log.info("classification budget exceeded for %s -- failing open", domain)
            verdict = None
        except Exception as error:
            log.warning("classification failed for %s: %s", domain, error)
            verdict = None
        finally:
            self._inflight.pop(domain, None)
            if not future.done():
                future.set_result(verdict)
        return verdict

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
    persisted = _PersistedBadDomains(CLASSIFIED_BAD_DOMAINS_PATH)
    website_rules = _WebsiteRuleBlocks()
    website_rules.refresh_if_stale()

    loop = asyncio.get_running_loop()
    transport, _protocol = await loop.create_datagram_endpoint(
        lambda: _DnsMuxProtocol(domains, cache, persisted, website_rules),
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
