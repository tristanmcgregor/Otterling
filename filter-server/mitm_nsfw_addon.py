"""
Otterling NSFW filter addon for mitmproxy.

Blocks entire requests/responses rather than scrubbing in-page content (no Canopy-style image
blanking): a domain-list hit or a strong path/query token match short-circuits the request before
it ever reaches the origin server; an HTML response whose <title>/og:description match
high-confidence porn keywords gets its whole body replaced with a block page instead of being
edited field-by-field. Once blocked, the deny cache fails closed on repeat hits for the rest of
the day without re-fetching or re-classifying anything -- scoped to the *whole host* for a
domain-list hit (the entire domain is known-bad), but only to the *exact host+path* for a path-
pattern/title-keyword hit, so one flagged URL on an otherwise-fine site doesn't take down the
rest of that site for the whole day.

Domain list sources are the same two hosts-format lists the Android app's DomainBlocklistManager
uses, so a host blocked on-device is also blocked here (defense in depth, not two independent
policies) -- see app/src/main/java/.../content/DomainBlocklistManager.kt. The domain list and the
AI classifier below both live in domain_blocklist.py / ai_classifier.py, shared (via volume mount,
not import -- separate containers) with dns_classify_mux.py's DNS-resolution-time check.

Beyond the fast/cheap checks above, a third tier handles pages that mention mature/adult themes
without matching any of the narrow high-confidence keywords (e.g. a platform with an internal
"Mature" content rating, like Webtoons, rather than a dedicated porn site) -- BORDERLINE_PATTERNS
is deliberately broad/fuzzy and never blocks by itself; a hit just escalates that one page's text
to a real AI classification call (see ai_classifier.classify_with_ai) that makes the actual safe/unsafe
judgment a fixed keyword list can't. This only runs for pages that reach this third tier (most
pages never do), and both outcomes are cached by exact host+path for a day, so repeat visits to
the same page never re-pay the classification cost.

Separately (and unrelated to NSFW filtering above), this addon also enforces dashboard-configured
website rules -- schedule/habit-gated, or an exceeded dailyBudgetMinutes usage cap -- by redirecting
to a real /blocked page (see _WebsiteRuleBlocks, _BudgetTargets, _UsageTracker, BLOCKED_PAGE_URL).
This used to be pure DNS-layer NXDOMAIN (see dns_classify_mux.py/VpnFilterService.kt), which never
let a browser get far enough to load any page at all; those domains now resolve normally and this
proxy -- which essentially all of the household's 80/443 traffic already tunnels through via
CONNECT, using a CA every managed device already trusts -- shows the real page instead.
"""
import asyncio
import concurrent.futures
import json
import os
import re
import threading
import time
import urllib.error
import urllib.request

from mitmproxy import http, ctx

import ai_classifier
import block_reporter
import domain_blocklist
import trigger_words

DENY_CACHE_TTL_SECONDS = 24 * 60 * 60

# lockprofile_service.py's website-rule/budget endpoints (see _WebsiteRuleBlocks/_BudgetTargets
# below). Reached via the PUBLIC vpn.bartholomew.help URL through Caddy, same as
# block_reporter.py's ALERTS_URL -- this container's DNS is overridden to public resolvers (see
# docker-compose.yml's mitmproxy `dns:` block, needed so it can still dial arbitrary sites for
# classification), so it can't resolve the internal `lockprofile` service name dns_classify_mux.py
# uses directly.
LOCKPROFILE_INTERNAL_URL = os.environ.get("LOCKPROFILE_INTERNAL_URL", "https://vpn.bartholomew.help").rstrip("/")
LOCKPROFILE_TOKEN = os.environ.get("LOCKPROFILE_TOKEN", "").strip()
WEBSITE_RULE_POLL_SECONDS = 10

# Where a habit-gated or budget-exceeded website rule redirects to (see _respond_website_blocked)
# -- its own subdomain, not a path under vpn.bartholomew.help, specifically so it's a fresh
# same-site navigation Caddy can serve with a normal, publicly-issued cert. A raw DNS sinkhole
# can't do this for HTTPS sites (no valid cert for a domain we don't own), which is why this
# redirect happens here, over the TLS session mitmproxy already terminated using a CA every
# managed device already trusts -- see this file's module docstring.
BLOCKED_PAGE_URL = os.environ.get("BLOCKED_PAGE_URL", "https://blocked.vpn.bartholomew.help/").strip()

# How long a gap between two requests to the same tracked domain still counts as one continuous
# browsing session for _UsageTracker -- generous enough to cover normal page-load/reading pauses,
# short enough that leaving a tab open and walking away doesn't quietly keep racking up minutes.
WEBSITE_USAGE_ACTIVE_GAP_SECONDS = 30
# How often _UsageTracker batches its accumulated deltas to lockprofile_service.py, rather than one
# HTTP round-trip per request (a tracked page can easily fire several requests a second).
WEBSITE_USAGE_FLUSH_SECONDS = 15


def _lockprofile_get(path: str) -> dict | None:
    if not LOCKPROFILE_TOKEN:
        return None
    request = urllib.request.Request(
        f"{LOCKPROFILE_INTERNAL_URL}{path}",
        headers={"Authorization": f"Bearer {LOCKPROFILE_TOKEN}"},
    )
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            return json.loads(response.read().decode("utf-8"))
    except (urllib.error.URLError, OSError, json.JSONDecodeError, UnicodeDecodeError) as error:
        print(f"[mitm_nsfw_addon] GET {path} failed: {error}", flush=True)
        return None


def _lockprofile_post(path: str, payload: dict) -> None:
    if not LOCKPROFILE_TOKEN:
        return
    request = urllib.request.Request(
        f"{LOCKPROFILE_INTERNAL_URL}{path}",
        data=json.dumps(payload).encode("utf-8"),
        method="POST",
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {LOCKPROFILE_TOKEN}",
        },
    )
    try:
        urllib.request.urlopen(request, timeout=5).close()
    except (urllib.error.URLError, OSError) as error:
        print(f"[mitm_nsfw_addon] POST {path} failed: {error}", flush=True)


class _WebsiteRuleBlocks:
    """Polls lockprofile_service.py's /internal/dns-website-blocks on the same short cadence as
    dns_classify_mux.py's own class of the same purpose (see that file's docstring for the
    polling-vs-per-query tradeoff this mirrors exactly).

    This is what actually shows a guardian's website-rule block (schedule/habit-gated, or an
    exceeded dailyBudgetMinutes budget) as a real page in a browser: DNS alone can only ever
    NXDOMAIN, which stops the browser before it makes any HTTP request at all -- see
    VpnFilterService.kt's handleDnsPacket and dns_classify_mux.py's _handle_query, both of which
    now let these domains resolve normally specifically so this proxy (which essentially all of
    the household's 80/443 traffic already tunnels through via CONNECT, using a CA every managed
    device already trusts) gets a chance to intercept and redirect instead."""

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
            body = _lockprofile_get("/internal/dns-website-blocks")
            if not isinstance(body, dict):
                return
            blocks = body.get("blocks") or {}
            parsed = {k: set(v) for k, v in blocks.items() if isinstance(v, list)}
            with self._lock:
                self._by_source_key = parsed
                self._last_poll = time.time()
        except Exception as error:
            print(f"[mitm_nsfw_addon] website-rule poll failed: {error}", flush=True)
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
            candidate = candidate[dot + 1:]
        return False


class _BudgetTargets:
    """Polls lockprofile_service.py's /internal/website-budget-targets on the same cadence as
    _WebsiteRuleBlocks -- domain->dailyBudgetMinutes per source key, for every website rule that
    sets a budget, independent of whether that budget is currently exceeded (_WebsiteRuleBlocks
    above covers the "is it exceeded/blocked" half). Consulted on every request so _UsageTracker
    only ever times (client, host) pairs that are actually under a budget rule."""

    def __init__(self):
        self._lock = threading.Lock()
        self._by_source_key: dict[str, dict[str, float]] = {}
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
            body = _lockprofile_get("/internal/website-budget-targets")
            if not isinstance(body, dict):
                return
            targets = body.get("targets") or {}
            parsed = {
                key: {
                    domain: minutes for domain, minutes in domains.items()
                    if isinstance(minutes, (int, float))
                }
                for key, domains in targets.items() if isinstance(domains, dict)
            }
            with self._lock:
                self._by_source_key = parsed
                self._last_poll = time.time()
        except Exception as error:
            print(f"[mitm_nsfw_addon] budget-targets poll failed: {error}", flush=True)
        finally:
            self._polling.release()

    def registrable_domain_for(self, source_ip: str, host: str) -> str | None:
        """Walks host up to its parent domains and returns the first one this source_ip has a
        budget rule for, or None -- same walk-up-parents matching every other domain-rule check in
        this project (HabitRuleManager.kt's domainMatches, dns_classify_mux.py's
        blocked_domain_for)."""
        with self._lock:
            domains = self._by_source_key.get(source_ip)
        if not domains:
            return None
        candidate = host
        while candidate:
            if candidate in domains:
                return candidate
            dot = candidate.find(".")
            if dot == -1:
                break
            candidate = candidate[dot + 1:]
        return None


class _UsageTracker:
    """Estimates active browsing time per (client, budget-tracked domain) from raw HTTP request
    timestamps -- the same request-gap heuristic any web-analytics tool uses when discrete request
    timestamps are all it has to work with, not a continuous per-tab heartbeat: two consecutive
    requests to the same tracked domain from the same client count as one continuous session (the
    gap between them added to the running total) as long as that gap is no more than
    WEBSITE_USAGE_ACTIVE_GAP_SECONDS; a longer gap is simply dropped, not counted, and doesn't
    extend the session either -- the next request starts a fresh one.

    Ticks accumulate in memory and flush to lockprofile_service.py's
    /internal/website-usage-tick every WEBSITE_USAGE_FLUSH_SECONDS, batched rather than one HTTP
    round-trip per request -- a tracked domain's pages can easily fire several requests a second
    (images, XHRs, etc.), and this must never add latency to the request path."""

    def __init__(self):
        self._lock = threading.Lock()
        self._sessions: dict[tuple[str, str], dict] = {}  # (source_ip, domain) -> state
        self._last_flush = time.time()

    def record_request(self, source_ip: str, domain: str) -> None:
        now = time.time()
        with self._lock:
            key = (source_ip, domain)
            session = self._sessions.get(key)
            if session is None:
                self._sessions[key] = {"last_seen": now, "pending_seconds": 0.0}
                return
            gap = now - session["last_seen"]
            if gap <= WEBSITE_USAGE_ACTIVE_GAP_SECONDS:
                session["pending_seconds"] += gap
            session["last_seen"] = now

    def flush_if_due(self) -> None:
        now = time.time()
        with self._lock:
            if now - self._last_flush < WEBSITE_USAGE_FLUSH_SECONDS:
                return
            self._last_flush = now
            due = []
            for (source_ip, domain), session in self._sessions.items():
                pending = session["pending_seconds"]
                if pending > 0:
                    due.append((source_ip, domain, pending))
                    session["pending_seconds"] = 0.0
        for source_ip, domain, seconds in due:
            _lockprofile_post(
                "/internal/website-usage-tick",
                {"source_key": source_ip, "domain": domain, "seconds": seconds},
            )

# Dedicated pool for classify_with_ai's `claude -p` subprocess call, deliberately *not* the
# default run_in_executor pool (also used by the cheap, non-blocking refresh_if_stale() check
# below) -- and deliberately small. `claude -p` startup is real CPU work (Node.js/V8 init), not
# just I/O wait, so an uncapped burst of these (e.g. several tabs each escalating a borderline page
# around the same time) can saturate the host's CPU. That starves everything else on the box,
# including interactive SSH sessions -- a logged-in session's cgroup gets none of ssh.service's own
# CPU-priority boost (see 99-priority.conf on the host). Same fix, same reasoning, as
# dns_classify_mux.py's own _CLASSIFY_EXECUTOR.
_CLASSIFY_EXECUTOR = concurrent.futures.ThreadPoolExecutor(max_workers=3, thread_name_prefix="classify")

# Strong-signal path/query tokens only -- deliberately narrow (a handful of unambiguous
# adult-content path fragments/site-brand names) rather than a broad keyword list, to keep false
# positives low on otherwise-legitimate sites. A fuzzy word-based path filter would misfire
# constantly.
NSFW_PATH_PATTERNS = [
    re.compile(r"/r/nsfw/?", re.IGNORECASE),
    re.compile(r"/r/porn\w*/?", re.IGNORECASE),
    re.compile(r"/r/gonewild\w*/?", re.IGNORECASE),
    re.compile(r"\bxxx\b", re.IGNORECASE),
    re.compile(
        r"/(?:porn|hentai|xvideos|xnxx|redtube|pornhub|youporn|xhamster|spankbang|"
        r"motherless|chaturbate|brazzers|bangbros)\b",
        re.IGNORECASE,
    ),
]

# High-confidence title/description keywords for the HTML-response check -- same
# low-false-positive reasoning as NSFW_PATH_PATTERNS (multi-word phrases preferred over bare
# generic words like "sex" alone, which would misfire on plenty of legitimate health/news content).
TITLE_KEYWORDS = [
    "porn",
    "pornstar",
    "xxx video",
    "hardcore sex",
    "nude cams",
    "hentai",
    "nude photos",
    "adult video",
    "cam girls",
    "live sex cams",
    "amateur porn",
]

BLOCK_PAGE = """<!doctype html>
<html><head><title>Blocked</title></head>
<body style="font-family: sans-serif; text-align:center; padding-top: 15%;">
<h1>Blocked by Otterling content filter</h1>
<p>This site or page was blocked by the family content filter.</p>
</body></html>"""

# Broad on purpose -- these alone never block anything, they only decide whether a page's text is
# worth spending an AI classification call on. Catches content-rating badges/disclaimers a
# dedicated-porn-site keyword list was never meant to (see TITLE_KEYWORDS above for that list).
#
# \bmature\b (not just "mature content"/"mature audiences") is deliberate: real-world content-
# rating badges are often just the bare word -- e.g. Webtoons' own API responses include literal
# `"contentRating":"MATURE"` with no surrounding phrase -- and a multi-word-only list silently
# misses that. Word-boundaried so it doesn't fire on "premature"/"immature"/"maturity".
BORDERLINE_PATTERNS = [
    re.compile(r"\bmature\b", re.IGNORECASE),
    re.compile(r"\b18\+", re.IGNORECASE),
    re.compile(r"\bnsfw\b", re.IGNORECASE),
    re.compile(r"\badult content\b", re.IGNORECASE),
    re.compile(r"\badults only\b", re.IGNORECASE),
    re.compile(r"\bexplicit content\b", re.IGNORECASE),
    re.compile(r"\bsexual content\b", re.IGNORECASE),
    re.compile(r"\bgraphic content\b", re.IGNORECASE),
]

ALLOW_CACHE_TTL_SECONDS = 24 * 60 * 60

# Caps how much of a response body every regex below (title/og:description/keyword/borderline-
# pattern matching) actually scans -- without this, an arbitrarily large HTML response gets
# decoded and regex-matched in full on every request, an easy memory/CPU cost for a malicious or
# just very large page. Real pages' <title>/<meta og:description> tags are always near the top;
# this is generous enough that legitimate matching is unaffected.
MAX_RESPONSE_BODY_CHARS = 300_000


class _DenyCache:
    """Generic string-keyed deny cache -- callers decide the key's scope (whole host vs.
    host+path); this class doesn't care which."""

    def __init__(self):
        self._lock = threading.Lock()
        self._denied: dict[str, float] = {}

    def is_denied(self, key: str) -> bool:
        with self._lock:
            expiry = self._denied.get(key)
        return expiry is not None and expiry > time.time()

    def deny(self, key: str):
        with self._lock:
            self._denied[key] = time.time() + DENY_CACHE_TTL_SECONDS


class _AllowCache:
    """Remembers pages the AI classifier already cleared, so a repeat visit to the same
    already-classified page never re-pays the classification cost -- separate from _DenyCache
    since a page can only ever be in one of these at a time, but conflating them would risk an
    allow overwriting a deny (or vice versa) depending on call order."""

    def __init__(self):
        self._lock = threading.Lock()
        self._allowed: dict[str, float] = {}

    def is_allowed(self, key: str) -> bool:
        with self._lock:
            expiry = self._allowed.get(key)
        return expiry is not None and expiry > time.time()

    def allow(self, key: str):
        with self._lock:
            self._allowed[key] = time.time() + ALLOW_CACHE_TTL_SECONDS


class NsfwFilter:
    def __init__(self):
        self.domains = domain_blocklist.DomainList()
        self.deny_cache = _DenyCache()
        self.allow_cache = _AllowCache()
        self.website_rule_blocks = _WebsiteRuleBlocks()
        self.budget_targets = _BudgetTargets()
        self.usage_tracker = _UsageTracker()

    @staticmethod
    def _path_key(flow: http.HTTPFlow) -> str:
        host = flow.request.pretty_host.lower().rstrip(".")
        return f"{host}{flow.request.path or ''}"

    @staticmethod
    def _client_ip(flow: http.HTTPFlow) -> str:
        peer = getattr(flow.client_conn, "peername", None)
        return peer[0] if peer else "lan-client"

    def _respond_website_blocked(self, flow: http.HTTPFlow):
        """Redirects to the guardian-facing /blocked page on its own subdomain -- a real,
        Caddy-served page with a normal publicly-issued cert, so this shows cleanly in a browser
        with no cert warning even though the request arrived for an arbitrary blocked domain (see
        BLOCKED_PAGE_URL's comment for why that has to be a same-site redirect, not a raw sinkhole).

        Deliberately its own method, not a mode of _respond_blocked: a website-rule block is a
        guardian-authored schedule/budget decision, not a content-safety verdict, so unlike the
        NSFW reasons below it's never reported to block_reporter -- "you're out of time on this
        site" was never something the person visiting had any way to avoid, so there's nothing
        accountability-relevant to report."""
        flow.response = http.Response.make(302, b"", {"Location": BLOCKED_PAGE_URL})
        flow.metadata["otterling_blocked_reason"] = "website-rule"

    def _respond_blocked(self, flow: http.HTTPFlow, reason: str):
        """Builds the block response only -- does NOT touch the deny cache; callers cache with
        whatever key/scope is appropriate for the reason (see `request`/`response`) before or
        instead of calling this, so a single helper can't accidentally cache at the wrong scope.

        Also fires an accountability alert to the Guardian's phone, but ONLY when an actual trigger
        word (trigger_words.TRIGGER_WORDS, the phone's list) appears in the request's own host+path
        -- never the page's title/body text, even for the title-keyword/ai-classifier reasons below,
        which only ever get decided from fetched page content. Scanning that content for a report
        would flag words the person visiting had no way to know would be there (they typed/clicked a
        URL, not the page's eventual text) -- exactly the same reasoning as the "don't report a
        merely-blocked page at all" rule this already applies (a bare domain-list/AI block with no
        trigger word in the URL itself is silent). Reporting is best-effort and never affects the
        block itself (see block_reporter)."""
        flow.response = http.Response.make(
            403,
            BLOCK_PAGE.encode("utf-8"),
            {"Content-Type": "text/html; charset=utf-8"},
        )
        flow.metadata["otterling_blocked_reason"] = reason

        host = flow.request.pretty_host.lower().rstrip(".")
        url_scan_text = f"{host} {flow.request.path or ''}"
        word = trigger_words.find_trigger(url_scan_text)
        if word:
            peer = getattr(flow.client_conn, "peername", None)
            client_ip = peer[0] if peer else "lan-client"
            block_reporter.report(device_id=client_ip, word=word, host=host, reason=reason)

    async def request(self, flow: http.HTTPFlow):
        # Blocking (up to ~40s on a real refresh, once/day) -- mitmproxy's hooks run on its own
        # single-threaded event loop, same as every other flow, so this must not block inline the
        # way a plain synchronous `def request` would (mitmproxy also supports async hooks, which
        # is what makes this off-load possible without changing the calling convention).
        await asyncio.get_running_loop().run_in_executor(None, self.domains.refresh_if_stale)
        await asyncio.get_running_loop().run_in_executor(None, self.website_rule_blocks.refresh_if_stale)
        await asyncio.get_running_loop().run_in_executor(None, self.budget_targets.refresh_if_stale)
        host = flow.request.pretty_host.lower().rstrip(".")
        source_ip = self._client_ip(flow)

        # Habit-gated / budget-exceeded website rules take priority over everything below, same
        # reasoning as dns_classify_mux.py's own website-rule check: a rule-gated domain (e.g.
        # youtube.com) is often perfectly legitimate content none of the NSFW checks below would
        # ever flag. See _WebsiteRuleBlocks' own doc for why this now runs here instead of only
        # NXDOMAIN-ing at DNS resolution time.
        if self.website_rule_blocks.blocked_domain_for(source_ip, host):
            self._respond_website_blocked(flow)
            return

        tracked_domain = self.budget_targets.registrable_domain_for(source_ip, host)
        if tracked_domain:
            self.usage_tracker.record_request(source_ip, tracked_domain)
            self.usage_tracker.flush_if_due()

        path_key = self._path_key(flow)

        # A prior block may have been scoped to the whole host (domain-list) or just this exact
        # path (path-pattern/title-keyword) -- check both rather than picking one, since checking
        # only the path key would miss a whole-host ban and checking only the host would ignore
        # path-scoped ones entirely.
        path_and_query = flow.request.path or ""

        if self.deny_cache.is_denied(host) or self.deny_cache.is_denied(path_key):
            self._respond_blocked(flow, "deny-cache")
            return

        if self.domains.matches(host):
            self.deny_cache.deny(host)
            self._respond_blocked(flow, "domain-list")
            return

        for pattern in NSFW_PATH_PATTERNS:
            if pattern.search(path_and_query):
                # Path-scoped, not whole-host: a single flagged URL shouldn't take down the rest
                # of an otherwise-fine site for the rest of the day.
                self.deny_cache.deny(path_key)
                self._respond_blocked(flow, f"path-pattern:{pattern.pattern}")
                return

    async def response(self, flow: http.HTTPFlow):
        if flow.response is None or flow.response.status_code == 403:
            return
        content_type = flow.response.headers.get("content-type", "")
        if "text/html" not in content_type.lower():
            return
        try:
            body = flow.response.get_text(strict=False) or ""
        except Exception:
            return
        body = body[:MAX_RESPONSE_BODY_CHARS]

        title, excerpt = ai_classifier.extract_title_and_excerpt(body)
        og_match = re.search(
            r'<meta[^>]+property=["\']og:description["\'][^>]+content=["\'](.*?)["\']',
            body,
            re.IGNORECASE,
        )
        haystack = " ".join(
            part for part in (title, og_match.group(1) if og_match else "") if part
        ).lower()
        if not haystack:
            return

        for keyword in TITLE_KEYWORDS:
            if keyword in haystack:
                # Path-scoped, same reasoning as the path-pattern case in request() -- one flagged
                # page's title/description shouldn't ban the rest of the site for the day.
                self.deny_cache.deny(self._path_key(flow))
                self._respond_blocked(flow, f"title-keyword:{keyword}")
                return

        # Third tier: nothing above matched, but the page might still be worth a real judgment
        # call rather than assuming safe -- only escalate if a broad/fuzzy signal is present, and
        # only if this exact page hasn't already been cleared by a prior classification today.
        path_key = self._path_key(flow)
        if self.allow_cache.is_allowed(path_key):
            return
        if not any(pattern.search(body) for pattern in BORDERLINE_PATTERNS):
            return

        ctx.log.info(f"AI classifier: escalating {flow.request.pretty_url} (title={title!r})")
        # Blocking (up to ANTHROPIC_TIMEOUT_SECONDS) -- same reasoning as the refresh_if_stale()
        # off-load in request() above.
        verdict = await asyncio.get_running_loop().run_in_executor(
            _CLASSIFY_EXECUTOR, ai_classifier.classify_with_ai, flow.request.pretty_url, title, excerpt
        )
        if verdict is True:
            self.deny_cache.deny(path_key)
            self._respond_blocked(flow, "ai-classifier")
            ctx.log.info(f"AI classifier: BLOCKED {flow.request.pretty_url}")
        elif verdict is False:
            self.allow_cache.allow(path_key)
            ctx.log.info(f"AI classifier: allowed {flow.request.pretty_url}")
        # verdict is None -- classification itself failed; fail open and don't cache either way,
        # so the next visit gets a fresh attempt instead of being stuck on a transient outage.


addons = [NsfwFilter()]
