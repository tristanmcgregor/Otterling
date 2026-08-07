"""
Otterling NSFW filter addon for mitmproxy.

Blocks entire requests/responses rather than scrubbing in-page content (no Canopy-style image
blanking): a domain-list hit or a strong path/query token match short-circuits the request before
it ever reaches the origin server; an HTML response whose <title>/og:description match
high-confidence porn keywords gets its whole body replaced with a block page instead of being
edited field-by-field. Once a host is blocked for any reason, it goes into an in-memory 24h deny
cache so repeat hits on that host fail closed for the rest of the day without re-fetching or
re-classifying anything.

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
# adult-content path fragments) rather than a broad keyword list, to keep false positives low on
# otherwise-legitimate sites. A fuzzy word-based path filter would misfire constantly.
NSFW_PATH_PATTERNS = [
    re.compile(r"/r/nsfw/?", re.IGNORECASE),
    re.compile(r"/r/porn\w*/?", re.IGNORECASE),
    re.compile(r"\bxxx\b", re.IGNORECASE),
    re.compile(r"/(?:porn|hentai|xvideos|xnxx|redtube|pornhub)\b", re.IGNORECASE),
]

# High-confidence title/description keywords for the HTML-response check -- same
# low-false-positive reasoning as NSFW_PATH_PATTERNS.
TITLE_KEYWORDS = [
    "porn",
    "pornstar",
    "xxx video",
    "hardcore sex",
    "nude cams",
    "hentai",
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
    def __init__(self):
        self._lock = threading.Lock()
        self._denied: dict[str, float] = {}

    def is_denied(self, host: str) -> bool:
        with self._lock:
            expiry = self._denied.get(host)
        return expiry is not None and expiry > time.time()

    def deny(self, host: str):
        with self._lock:
            self._denied[host] = time.time() + DENY_CACHE_TTL_SECONDS


class NsfwFilter:
    def __init__(self):
        self.domains = _DomainList()
        self.deny_cache = _DenyCache()

    def _block(self, flow: http.HTTPFlow, reason: str):
        host = flow.request.pretty_host
        self.deny_cache.deny(host)
        flow.response = http.Response.make(
            403,
            BLOCK_PAGE.encode("utf-8"),
            {"Content-Type": "text/html; charset=utf-8"},
        )
        flow.metadata["otterling_blocked_reason"] = reason

    def request(self, flow: http.HTTPFlow):
        self.domains.refresh_if_stale()
        host = flow.request.pretty_host

        if self.deny_cache.is_denied(host):
            self._block(flow, "deny-cache")
            return

        if self.domains.matches(host):
            self._block(flow, "domain-list")
            return

        path_and_query = flow.request.path or ""
        for pattern in NSFW_PATH_PATTERNS:
            if pattern.search(path_and_query):
                self._block(flow, f"path-pattern:{pattern.pattern}")
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
                self._block(flow, f"title-keyword:{keyword}")
                return


addons = [NsfwFilter()]
