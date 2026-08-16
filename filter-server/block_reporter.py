"""Best-effort 'a trigger word was seen on a blocked page' reporter.

Posts a `trigger_word_detected` event to the lockprofile service's `/alerts/tamper` endpoint (the
same ingestion the macOS daemon's TamperReporter and the Fleet webhook already feed), which fans it
out to ntfy + the phone's FCM wake / `/alerts/poll` -> SMS relay. Imported by `mitm_nsfw_addon.py`.

Design constraints (this runs on the proxy's hot request/response path):
  * Never blocks the caller -- the POST happens on a daemon thread, fire-and-forget.
  * Never raises -- a down/unreachable alerts endpoint must not turn a block into a 500.
  * Deduplicates -- a single blocked page load fires many requests for the same host, and the phone
    already debounces the same word 10 min; matching that here keeps us from hammering the endpoint
    (and the SMS relay downstream) with identical events.

Stdlib only (urllib): the dns-classifier/mitmproxy containers have no guaranteed `requests`, and
this keeps the mounted-script deployment dependency-free.

Config via env:
  ALERTS_URL         full URL to POST to. Default is the PUBLIC endpoint, https://<host>/alerts/tamper,
                     because mitmproxy's container overrides DNS to public resolvers (see
                     docker-compose.yml `dns:`), so it can't resolve the internal `lockprofile`
                     service name -- it reaches the endpoint the same way phones do, back through
                     Caddy. Override to an internal URL only if you also give the container internal
                     DNS.
  LOCKPROFILE_TOKEN  Bearer token the endpoint requires (same value as everywhere else).
If either is unset, reporting is inert (blocking still works).
"""

from __future__ import annotations

import json
import os
import threading
import time
import urllib.error
import urllib.request

ALERTS_URL = os.environ.get("ALERTS_URL", "https://vpn.bartholomew.help/alerts/tamper").strip()
TOKEN = os.environ.get("LOCKPROFILE_TOKEN", "").strip()

# Match the phone's GuardianAlertSettings.DEBOUNCE_MS (10 min) so the same word+host doesn't
# re-report while someone sits on a blocked page reloading it.
DEDUP_TTL_SECONDS = 10 * 60

_dedup_lock = threading.Lock()
_last_sent: dict[str, float] = {}


def _recently_sent(key: str) -> bool:
    now = time.time()
    with _dedup_lock:
        # Opportunistic prune so the dict can't grow unbounded on a long-lived process.
        if len(_last_sent) > 4096:
            for stale_key in [k for k, t in _last_sent.items() if now - t > DEDUP_TTL_SECONDS]:
                _last_sent.pop(stale_key, None)
        last = _last_sent.get(key)
        if last is not None and now - last < DEDUP_TTL_SECONDS:
            return True
        _last_sent[key] = now
        return False


def _post(payload: bytes) -> None:
    request = urllib.request.Request(
        ALERTS_URL,
        data=payload,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {TOKEN}",
        },
    )
    try:
        urllib.request.urlopen(request, timeout=10).close()
    except (urllib.error.URLError, OSError) as error:
        print(f"[block_reporter] report failed: {error}", flush=True)


def report(device_id: str, word: str, host: str, reason: str, dedupe_key: str | None = None) -> None:
    """Fire a `trigger_word_detected` alert for `word` seen on `host`. Fire-and-forget; safe to call
    on the hot path. `device_id` is the client IP (best identifier the proxy has for which machine)."""
    if not ALERTS_URL or not TOKEN:
        return
    key = dedupe_key or f"{word}|{host}"
    if _recently_sent(key):
        return
    event = {
        "device_id": device_id or "lan-client",
        "type": "trigger_word_detected",
        "details": f'"{word}" seen on {host} ({reason})',
        "ts": time.time(),
    }
    payload = json.dumps(event).encode("utf-8")
    threading.Thread(target=_post, args=(payload,), daemon=True).start()
