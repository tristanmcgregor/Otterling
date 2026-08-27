"""Screenshot NSFW classifier for POST /screenshot-classify (see lockprofile_service.py).

Sibling to ai_classifier.py, not an edit to it -- image classification has a different prompt
shape, a different input encoding path (temp file vs. text excerpt), and a different (longer)
timeout, so keeping it separate avoids conditional branching inside that file's already-delicate
subprocess-invocation code. Reuses ai_classifier's `_run_claude_with_fallback()` (env-stripping/
CLAUDE_RUNNER_USER drop-privilege logic, and the CLAUDE_CODE_OAUTH_TOKEN_BACKUP retry if the
primary subscription account is out of usage) so none of that is duplicated -- see that file's own
docstring for why this shells out to the `claude` CLI (subscription OAuth) rather than a metered
Anthropic API key.

Confirmed empirically (2026-08-27): `claude -p` reads an image referenced by local file path in
the prompt text when its subprocess `cwd` is the same directory the image lives in -- no `--file`/
`--add-dir` flag needed. This module writes the temp image into `ai_classifier._CLAUDE_CWD` (the
same fixed `/tmp` cwd already used for text classification) specifically so that holds.
"""
from __future__ import annotations

import json
import logging
import os
import tempfile

import ai_classifier

log = logging.getLogger("otterling.nsfw_image_classifier")

CLAUDE_CLASSIFY_MODEL = os.environ.get("CLAUDE_CLASSIFY_MODEL", "haiku")
# Longer than ai_classifier's 25s text-classification timeout -- image encoding plus a
# vision-capable model round trip is slower than a short text-only judgment.
CLAUDE_TIMEOUT_SECONDS = 45

_DISALLOWED_TOOLS = ["Bash", "Edit", "Write", "NotebookEdit", "Agent", "ExitPlanMode", "EnterPlanMode"]

# TESTING ONLY (added 2026-08-27, remove once the capture->classify->block->alert pipeline has
# been confirmed working end-to-end on a real device) -- a dog is obviously not NSFW; this is
# purely to give the pipeline an easy-to-trigger positive without needing to actually feed it real
# adult content. Revert this prompt back to the plain "SAFE unless sexually explicit" wording
# before relying on this for real classification.
_PROMPT = (
    "You are an NSFW content classifier for a personal phone content filter. The attached image "
    "is a screenshot of whatever app was in the foreground on a family member's phone at the "
    "moment it was captured -- it could be any app, any screen. Judge whether the image itself "
    "contains sexually explicit/pornographic content or other graphic adult nudity, OR contains a "
    "dog (of any kind, in any context) -- treat a dog as NSFW too, for pipeline-testing purposes "
    "only. "
    "Ordinary photos, UI screens, messaging apps, games, swimwear/beach photos, art, medical or "
    "educational imagery, and anything not sexually explicit and not a dog are SAFE. "
    "Reply with exactly one word: NSFW or SAFE.\n\n"
    f"Image: {{image_path}}"
)


def classify_screenshot(image_bytes: bytes) -> bool | None:
    """Returns True if the screenshot is NSFW (should block + alert), False if safe, None if the
    classification call itself failed -- callers must fail *open* on None (discard the image,
    don't block, don't alert) so an outage of this classifier can never itself count as a false
    positive, matching ai_classifier.classify_with_ai's contract."""
    fd, path = tempfile.mkstemp(suffix=".png", dir=ai_classifier._CLAUDE_CWD)
    try:
        with os.fdopen(fd, "wb") as fh:
            fh.write(image_bytes)
        prompt = _PROMPT.format(image_path=path)
        args = [
            "claude", "-p",
            "--model", CLAUDE_CLASSIFY_MODEL,
            "--output-format", "json",
            "--disallowedTools", *_DISALLOWED_TOOLS,
            "--permission-mode", "bypassPermissions",
        ]
        try:
            process = ai_classifier._run_claude_with_fallback(
                args, prompt, context="screenshot", timeout=CLAUDE_TIMEOUT_SECONDS, cwd=ai_classifier._CLAUDE_CWD,
            )
            if process is None:
                return None
            if process.returncode != 0:
                log.warning(
                    "Screenshot classification failed: claude -p exited %s: %s",
                    process.returncode, process.stderr.strip()[:500],
                )
                return None
            payload = json.loads(process.stdout)
            if payload.get("is_error"):
                log.warning("Screenshot classification failed: %s", str(payload)[:500])
                return None
            text = (payload.get("result") or "").strip().upper()
            return text.startswith("NSFW")
        except Exception as error:
            log.warning(f"Screenshot classification failed: {error}")
            return None
    finally:
        try:
            os.remove(path)
        except OSError:
            pass
