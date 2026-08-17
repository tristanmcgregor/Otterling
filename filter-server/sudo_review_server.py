#!/usr/bin/env python3
"""Host-level AI review server for `SudoBroker.swift`'s tier-3 fallback -- see that file's doc
comment for the full decision pipeline this is one piece of.

DEPLOYMENT NOTE: this must run on the HOST, not inside a container. `lockprofile_service.py` (which
calls this over HTTP) runs in a slim Python container with no Node.js/Claude Code CLI available, and
that CLI needs the host's own already-authenticated `admin` account (see the reasoning in the
private, git-excluded `anthropic_review_stream.py` this mirrors -- same reasoning, same mechanism:
`sudo -u admin claude -p ...` drops root's privilege to run the CLI, since `--dangerously-skip-
permissions` is refused outright for root/sudo). Per SELF_LOCKOUT.md's own reasoning for keeping the
release pipeline's reviewer out of git, this file's FINAL production copy should live outside git too
(e.g. /var/lib/otterling/ci/sudo_review_server.py) once deployed -- so that a compromised local commit
to this repo can't rewrite the very reviewer meant to catch it trying to bypass this project's
protections. It's committed here in the meantime only because deployment access wasn't available when
this was written; move it before relying on it.

Listens on 127.0.0.1 only -- `lockprofile_service.py`'s container reaches it via
`host.docker.internal` (see docker-compose.yml's `extra_hosts` on the `lockprofile` service), which
resolves to the Docker bridge gateway on Linux with the `host-gateway` special value. Never exposed
through Caddy directly; only the container-facing side is on a real listen port.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LISTEN_HOST = os.environ.get("SUDO_REVIEW_LISTEN_HOST", "127.0.0.1")
LISTEN_PORT = int(os.environ.get("SUDO_REVIEW_LISTEN_PORT", "9072"))
REVIEW_USER = os.environ.get("SUDO_REVIEW_USER", "admin")
REVIEW_TIMEOUT_SECONDS = 15
MAX_BODY_BYTES = 16 * 1024

# Shown to the reviewer as context, not enforced here -- SudoBroker.swift's own copies are the ones
# actually enforced locally on the Mac before a request ever reaches this far. Kept in sync by hand;
# a mismatch just means the AI has slightly stale context, not a security hole, since the Mac's own
# denylist is what actually blocks a denylisted command regardless of what this server says.
KNOWN_DENYLIST_THEMES = """
- Anything referencing this app's own components: Otterling, FocusLock, FocusLockHelperd,
  FocusLockWatchdog, FocusLockScanner, app.otterling.* (bundle IDs, launchd labels, profile
  identifiers).
- macOS security primitives: csrutil, spctl --master-disable, nvram, firmwarepasswd, bless,
  sudoers/visudo, dscl/dseditgroup/sysadminctl (account or group membership changes), fdesetup,
  diskutil erase/delete operations, pfctl -d / -F all, launchctl bootout/unload/remove/disable,
  codesign --remove-signature, profiles remove, tccutil, rm -rf and similar recursive-force deletes.
"""

PROMPT_TEMPLATE = """You are the tier-3 reviewer in a privilege-elevation broker. A macOS account \
that has been deliberately downgraded to Standard (no direct sudo) is requesting to run a command \
with root privilege through this broker, because that account's own admin rights were intentionally \
removed as a personal accountability measure -- the account holder chose this themselves, and is now \
trying to find ways to reduce or remove it, which is exactly the scenario this review exists to catch.

A local, hardcoded, unconditional denylist on the Mac itself has ALREADY rejected any command \
matching these themes (so if you see a command like this, something is wrong -- treat it as highly \
suspicious rather than assuming the local gate must have been correct to let it through):
{denylist_themes}

Your job is different from that denylist: catch commands ENGINEERED to reach a denylisted outcome \
through a roundabout, obfuscated, or multi-step path that doesn't literally match those patterns. \
Examples of the shape of thing to watch for: renaming/copying files before deleting them, using a \
different tool to achieve the same effect (e.g. a scripting language's file-delete instead of `rm`, \
or `mv` to a location that gets garbage-collected), base64-encoded or otherwise obfuscated commands, \
commands that look individually benign but are clearly a step toward disabling protection based on \
the stated reason, or a reason that doesn't plausibly justify the command.

Also weigh the STATED REASON critically. A plausible-sounding justification is not the same as a true \
one -- if the reason given doesn't clearly and specifically justify exactly this command, that's \
itself a reason to deny.

If you are not confident this command is safe and unrelated to reducing this app's protections, \
you MUST deny. Ambiguity is a deny, not an allow.

Command: {command}
Stated reason: {reason}

Respond with EXACTLY two lines, nothing else:
VERDICT: ALLOW
or
VERDICT: DENY
EXPLANATION: <one sentence>
"""

VERDICT_RE = re.compile(r"^VERDICT:\s*(ALLOW|DENY)\s*$", re.MULTILINE)
EXPLANATION_RE = re.compile(r"^EXPLANATION:\s*(.+)$", re.MULTILINE)


def _review(command: str, reason: str) -> tuple[str, str]:
    """Returns (verdict, explanation). verdict is always exactly "allow" or "deny" -- any failure
    of the CLI call itself, or a response that doesn't parse cleanly, is "deny". Never raises."""
    prompt = PROMPT_TEMPLATE.format(
        denylist_themes=KNOWN_DENYLIST_THEMES, command=command, reason=reason or "(none given)"
    )
    try:
        result = subprocess.run(
            ["sudo", "-u", REVIEW_USER, "claude", "-p", prompt, "--output-format", "text"],
            capture_output=True,
            text=True,
            timeout=REVIEW_TIMEOUT_SECONDS,
            cwd=f"/home/{REVIEW_USER}",
        )
    except (subprocess.TimeoutExpired, OSError) as error:
        return "deny", f"Reviewer invocation failed ({error}) -- denying on failure."

    output = result.stdout or ""
    verdict_match = VERDICT_RE.search(output)
    if result.returncode != 0 or not verdict_match:
        return "deny", f"Reviewer produced no clear verdict (exit {result.returncode}) -- denying on ambiguity."

    explanation_match = EXPLANATION_RE.search(output)
    explanation = explanation_match.group(1).strip() if explanation_match else "(no explanation)"
    verdict = verdict_match.group(1).lower()
    return verdict, explanation


class Handler(BaseHTTPRequestHandler):
    server_version = "OtterlingSudoReview/1.0"

    def _send_json(self, code: int, body: dict) -> None:
        payload = json.dumps(body).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_POST(self):  # noqa: N802
        if self.path != "/review":
            return self._send_json(404, {"error": "not found"})
        try:
            length = int(self.headers.get("Content-Length", "0") or "0")
        except ValueError:
            return self._send_json(400, {"error": "bad content-length"})
        if length <= 0 or length > MAX_BODY_BYTES:
            return self._send_json(400, {"error": "bad body size"})
        try:
            body = json.loads(self.rfile.read(length).decode("utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError):
            return self._send_json(400, {"error": "bad json"})

        command = str(body.get("command", "")).strip()
        if not command:
            return self._send_json(400, {"error": "command required"})
        reason = str(body.get("reason", ""))

        verdict, explanation = _review(command, reason)
        self._send_json(200, {"verdict": verdict, "explanation": explanation})

    def log_message(self, fmt, *args):  # quiet -- avoid logging command text to a shared log by default
        pass


def main() -> None:
    server = ThreadingHTTPServer((LISTEN_HOST, LISTEN_PORT), Handler)
    print(f"[sudo-review] listening on {LISTEN_HOST}:{LISTEN_PORT}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
