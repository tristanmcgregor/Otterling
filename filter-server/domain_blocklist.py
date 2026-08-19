"""Shared adult-domain blocklist.

Downloads and caches the same two hosts-format lists used by both `mitm_nsfw_addon.py` (mitmproxy
container) and `dns_classify_mux.py` (DNS-classifier container) -- a domain blocked one way is
blocked the other way too. Those two run in **separate containers**, so this file is
volume-mounted into both rather than imported normally.
"""
import threading
import time
import urllib.request

DOMAIN_LIST_URLS = [
    "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts",
    "https://raw.githubusercontent.com/blocklistproject/Lists/master/porn.txt",
]
DOMAIN_REFRESH_SECONDS = 24 * 60 * 60


class DomainList:
    """Downloads and caches the adult-domain hosts lists. Refresh runs synchronously inside a
    caller's own hot path the first time it's stale, which means whichever request/query is due
    for the refresh pays the download cost -- acceptable since that's once a day, but worth
    knowing about if a single lookup seems to hang occasionally.
    """

    def __init__(self):
        self._lock = threading.Lock()
        self._domains: set[str] = set()
        self._last_refresh = 0.0
        # Non-blocking-acquire lock, not a data lock: guards against N concurrent callers all
        # seeing "stale" at once (every DNS query calls refresh_if_stale) and each independently
        # kicking off a duplicate ~40s refresh. Only the caller that wins the acquire refreshes;
        # everyone else just uses the current (possibly still-stale) cached set and moves on.
        self._refreshing = threading.Lock()

    def refresh_if_stale(self):
        with self._lock:
            stale = (time.time() - self._last_refresh) >= DOMAIN_REFRESH_SECONDS
        if not stale:
            return
        if not self._refreshing.acquire(blocking=False):
            return
        try:
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
                # Every source failed or changed format -- keep whatever's cached rather than
                # wiping out existing coverage (same "parsed to zero is a failure" rule used on
                # both clients).
                return
            with self._lock:
                self._domains = combined
                self._last_refresh = time.time()
        finally:
            self._refreshing.release()

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
