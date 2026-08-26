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
"""
import asyncio
import concurrent.futures
import re
import threading
import time

from mitmproxy import http, ctx

import ai_classifier
import block_reporter
import domain_blocklist
import trigger_words

DENY_CACHE_TTL_SECONDS = 24 * 60 * 60

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

    @staticmethod
    def _path_key(flow: http.HTTPFlow) -> str:
        host = flow.request.pretty_host.lower().rstrip(".")
        return f"{host}{flow.request.path or ''}"

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
        host = flow.request.pretty_host.lower().rstrip(".")
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
