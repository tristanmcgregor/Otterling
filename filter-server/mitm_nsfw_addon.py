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
"""
import re
import threading
import time
import urllib.request

from mitmproxy import http

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


class NsfwFilter:
    def __init__(self):
        self.domains = _DomainList()
        self.deny_cache = _DenyCache()

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


addons = [NsfwFilter()]
