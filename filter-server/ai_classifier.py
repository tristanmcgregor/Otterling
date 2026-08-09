"""Shared AI content classifier.

Used by both `mitm_nsfw_addon.py` (mitmproxy container, third-tier HTML classification) and
`dns_classify_mux.py` (DNS-classifier container, whole-domain classification at resolution time).
Those two run in **separate containers**, so this file is volume-mounted into both rather than
imported normally -- see the plan doc / filter-server/README.md for why. Uses the stdlib `logging`
module rather than `mitmproxy.ctx` so it works unmodified outside a live mitmproxy addon context.
"""
import json
import logging
import os
import urllib.request

log = logging.getLogger("otterling.ai_classifier")

ANTHROPIC_MODEL = os.environ.get("ANTHROPIC_MODEL", "claude-haiku-4-5-20251001")
ANTHROPIC_TIMEOUT_SECONDS = 8
# Keeps the classification call cheap/fast -- a verdict on a page's *nature* doesn't need the full
# body, just enough text to judge tone/content, so this is plenty without inflating cost.
CLASSIFY_EXCERPT_CHARS = 4000


def classify_with_ai(url: str, title: str, excerpt: str) -> bool | None:
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
    # True/False/None", since callers treat None as fail-open.
    try:
        with urllib.request.urlopen(req, timeout=ANTHROPIC_TIMEOUT_SECONDS) as resp:
            data = json.loads(resp.read().decode("utf-8", errors="replace"))
        text = "".join(
            block.get("text", "") for block in data.get("content", []) if block.get("type") == "text"
        ).strip().upper()
        return text.startswith("UNSAFE")
    except Exception as error:
        log.warning(f"AI content classification failed for {url}: {error}")
        return None
