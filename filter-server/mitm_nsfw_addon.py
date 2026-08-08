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
policies) -- see app/src/main/java/.../content/DomainBlocklistManager.kt.

Beyond the fast/cheap checks above, a third tier handles pages that mention mature/adult themes
without matching any of the narrow high-confidence keywords (e.g. a platform with an internal
"Mature" content rating, like Webtoons, rather than a dedicated porn site) -- BORDERLINE_PATTERNS
is deliberately broad/fuzzy and never blocks by itself; a hit just escalates that one page's text
to a real AI classification call (see _classify_with_ai) that makes the actual safe/unsafe
judgment a fixed keyword list can't. This only runs for pages that reach this third tier (most
pages never do), and both outcomes are cached by exact host+path for a day, so repeat visits to
the same page never re-pay the classification cost.
"""
import json
import os
import re
import threading
import time
import urllib.request

from mitmproxy import http, ctx

DOMAIN_LIST_URLS = [
    "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts",
    "https://raw.githubusercontent.com/blocklistproject/Lists/master/porn.txt",
]
DOMAIN_REFRESH_SECONDS = 24 * 60 * 60
DENY_CACHE_TTL_SECONDS = 24 * 60 * 60

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

ANTHROPIC_MODEL = os.environ.get("ANTHROPIC_MODEL", "claude-haiku-4-5-20251001")
ANTHROPIC_TIMEOUT_SECONDS = 8
# Keeps the classification call cheap/fast -- a verdict on this page's *nature* doesn't need the
# full body, just enough text to judge tone/content, so this is plenty without inflating cost.
CLASSIFY_EXCERPT_CHARS = 4000
ALLOW_CACHE_TTL_SECONDS = 24 * 60 * 60


def _classify_with_ai(url: str, title: str, excerpt: str) -> bool | None:
    """Returns True if the page should be blocked, False if it's fine, None if the call itself
    failed (missing key, network error, timeout, malformed response) -- callers fail *open* on
    None rather than blocking, since this tier only ever runs on already-borderline (not
    already-confirmed-bad) pages: an AI outage should degrade to "skip the extra check", not
    "block anything that merely mentions a content rating"."""
    api_key = os.environ.get("ANTHROPIC_API_KEY")
    if not api_key:
        return None
    prompt = (
        "You are a content-safety classifier for a personal internet content filter. Given a "
        "webpage's URL, title, and a text excerpt, decide whether the page itself is sexually "
        "explicit/pornographic or otherwise graphic adult content that should be blocked. "
        "A page that merely mentions a content rating, age gate, or 'mature' theme without "
        "actually being pornographic is SAFE -- e.g. a general directory/listing page that "
        "happens to include some mature-rated items alongside all-ages ones is SAFE. "
        "Reply with exactly one word: UNSAFE or SAFE.\n\n"
        f"URL: {url}\nTitle: {title}\nExcerpt: {excerpt}"
    )
    body = json.dumps(
        {
            "model": ANTHROPIC_MODEL,
            "max_tokens": 8,
            "messages": [{"role": "user", "content": prompt}],
        }
    ).encode()
    req = urllib.request.Request(
        "https://api.anthropic.com/v1/messages",
        data=body,
        method="POST",
        headers={
            "content-type": "application/json",
            "x-api-key": api_key,
            "anthropic-version": "2023-06-01",
            "user-agent": "otterling-content-classifier",
        },
    )
    # Broad on purpose -- this function's whole contract is "never raise, always return
    # True/False/None", since callers treat None as fail-open. A narrower except clause here once
    # let a malformed API response (or even the warning log call itself failing outside a live
    # mitmproxy context) escape as an uncaught exception, defeating that guarantee.
    try:
        with urllib.request.urlopen(req, timeout=ANTHROPIC_TIMEOUT_SECONDS) as resp:
            data = json.loads(resp.read().decode("utf-8", errors="replace"))
        text = "".join(
            block.get("text", "") for block in data.get("content", []) if block.get("type") == "text"
        ).strip().upper()
        return text.startswith("UNSAFE")
    except Exception as error:
        try:
            ctx.log.warn(f"AI content classification failed for {url}: {error}")
        except Exception:
            pass
        return None


class _DomainList:
    """Downloads and caches the adult-domain hosts lists. Refresh runs synchronously inside a
    request hook the first time it's stale, which means the very first request after a refresh is
    due pays the download cost -- acceptable since that's once a day and mitmproxy already handles
    many concurrent flows, but worth knowing about if a single request seems to hang occasionally.
    """

    def __init__(self):
        self._lock = threading.Lock()
        self._domains: set[str] = set()
        self._last_refresh = 0.0

    def refresh_if_stale(self):
        with self._lock:
            stale = (time.time() - self._last_refresh) >= DOMAIN_REFRESH_SECONDS
        if not stale:
            return
        combined: set[str] = set()
        for url in DOMAIN_LIST_URLS:
            try:
                with urllib.request.urlopen(url, timeout=20) as resp:
                    text = resp.read().decode("utf-8", errors="ignore")
            except Exception:
                continue
            for line in text.splitlines():
                line = line.split("#", 1)[0].strip()
                if not line:
                    continue
                parts = line.split()
                if len(parts) >= 2 and parts[0] in ("0.0.0.0", "127.0.0.1"):
                    domain = parts[1].lower().rstrip(".")
                    if domain and domain != "localhost":
                        combined.add(domain)
        if not combined:
            # Every source failed or changed format -- keep whatever's cached rather than wiping
            # out existing coverage (same "parsed to zero is a failure" rule used on both clients).
            return
        with self._lock:
            self._domains = combined
            self._last_refresh = time.time()

    def matches(self, host: str) -> bool:
        host = host.lower().rstrip(".")
        with self._lock:
            domains = self._domains
        while host:
            if host in domains:
                return True
            dot = host.find(".")
            if dot == -1:
                break
            host = host[dot + 1 :]
        return False


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
        self.domains = _DomainList()
        self.deny_cache = _DenyCache()
        self.allow_cache = _AllowCache()

    @staticmethod
    def _path_key(flow: http.HTTPFlow) -> str:
        host = flow.request.pretty_host.lower().rstrip(".")
        return f"{host}{flow.request.path or ''}"

    def _respond_blocked(self, flow: http.HTTPFlow, reason: str):
        """Builds the block response only -- does NOT touch the deny cache; callers cache with
        whatever key/scope is appropriate for the reason (see `request`/`response`) before or
        instead of calling this, so a single helper can't accidentally cache at the wrong scope."""
        flow.response = http.Response.make(
            403,
            BLOCK_PAGE.encode("utf-8"),
            {"Content-Type": "text/html; charset=utf-8"},
        )
        flow.metadata["otterling_blocked_reason"] = reason

    def request(self, flow: http.HTTPFlow):
        self.domains.refresh_if_stale()
        host = flow.request.pretty_host.lower().rstrip(".")
        path_key = self._path_key(flow)

        # A prior block may have been scoped to the whole host (domain-list) or just this exact
        # path (path-pattern/title-keyword) -- check both rather than picking one, since checking
        # only the path key would miss a whole-host ban and checking only the host would ignore
        # path-scoped ones entirely.
        if self.deny_cache.is_denied(host) or self.deny_cache.is_denied(path_key):
            self._respond_blocked(flow, "deny-cache")
            return

        if self.domains.matches(host):
            self.deny_cache.deny(host)
            self._respond_blocked(flow, "domain-list")
            return

        path_and_query = flow.request.path or ""
        for pattern in NSFW_PATH_PATTERNS:
            if pattern.search(path_and_query):
                # Path-scoped, not whole-host: a single flagged URL shouldn't take down the rest
                # of an otherwise-fine site for the rest of the day.
                self.deny_cache.deny(path_key)
                self._respond_blocked(flow, f"path-pattern:{pattern.pattern}")
                return

    def response(self, flow: http.HTTPFlow):
        if flow.response is None or flow.response.status_code == 403:
            return
        content_type = flow.response.headers.get("content-type", "")
        if "text/html" not in content_type.lower():
            return
        try:
            body = flow.response.get_text(strict=False) or ""
        except Exception:
            return

        title_match = re.search(r"<title[^>]*>(.*?)</title>", body, re.IGNORECASE | re.DOTALL)
        og_match = re.search(
            r'<meta[^>]+property=["\']og:description["\'][^>]+content=["\'](.*?)["\']',
            body,
            re.IGNORECASE,
        )
        haystack = " ".join(
            part
            for part in (
                title_match.group(1) if title_match else "",
                og_match.group(1) if og_match else "",
            )
            if part
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

        title_text = title_match.group(1) if title_match else ""
        excerpt = re.sub(r"<[^>]+>", " ", body)[:CLASSIFY_EXCERPT_CHARS]
        ctx.log.info(f"AI classifier: escalating {flow.request.pretty_url} (title={title_text!r})")
        verdict = _classify_with_ai(flow.request.pretty_url, title_text, excerpt)
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
