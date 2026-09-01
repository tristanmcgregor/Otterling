"""Host-level AI reviewer/translator calls for the Mac's sudo-elevation broker.

See lockprofile_service.py's module doc comment ("Sudo-elevation review" / "AI assistant") for the
full picture: `SudoBroker.swift` on the Mac forwards commands its own local allowlist/denylist
didn't resolve to `POST /sudo-review/check` (-> `_check_sudo_command`), and
`AIAssistantClient.swift` turns natural-language requests into candidate commands via
`POST /ai-assistant/translate` (-> `_translate_request`) -- every returned command still goes
through `_check_sudo_command` individually before anything executes.
"""

from __future__ import annotations

import json
import os
import urllib.error
import urllib.request

# Host-level AI reviewer for SudoBroker.swift's tier-3 fallback (see sudo_review_server.py's module
# doc comment for why this has to be a separate host-level process rather than something this
# container does itself). `host.docker.internal` needs docker-compose.yml's `extra_hosts:
# ["host.docker.internal:host-gateway"]` on this service to resolve on Linux.
SUDO_REVIEW_URL = os.environ.get("SUDO_REVIEW_URL", "http://host.docker.internal:9072/review")
SUDO_TRANSLATE_URL = os.environ.get("SUDO_TRANSLATE_URL", "http://host.docker.internal:9072/translate")
SUDO_REVIEW_TIMEOUT = 20
SUDO_TRANSLATE_TIMEOUT = 25


def _check_sudo_command(command: str, reason: str) -> tuple[str, str]:
    """Calls out to the host-level `sudo_review_server.py` (see its own doc comment for why this
    can't happen inside this container). ANY failure -- unreachable, timeout, malformed response --
    is "deny", never "allow": this is the one place in the whole system that fails closed on purpose,
    since an admin command denied on a hiccup is safe and recoverable, unlike DNS/proxy enforcement
    failing open elsewhere in this project."""
    payload = json.dumps({"command": command, "reason": reason}).encode("utf-8")
    request = urllib.request.Request(
        SUDO_REVIEW_URL, data=payload, method="POST", headers={"Content-Type": "application/json"}
    )
    try:
        with urllib.request.urlopen(request, timeout=SUDO_REVIEW_TIMEOUT) as response:
            parsed = json.loads(response.read().decode("utf-8"))
    except (urllib.error.URLError, OSError, json.JSONDecodeError, UnicodeDecodeError) as error:
        return "deny", f"Could not reach the AI reviewer ({error}) -- denying on failure."

    verdict = str(parsed.get("verdict", "")).lower()
    explanation = str(parsed.get("explanation", "(no explanation)"))
    if verdict not in ("allow", "deny"):
        return "deny", f"Reviewer returned an unrecognized verdict -- denying on ambiguity. {explanation}"
    return verdict, explanation


def _translate_request(request_text: str) -> tuple[list[str], str]:
    """Calls out to the host-level reviewer's `/translate` route -- pure natural-language-to-shell
    translation, no safety reasoning (see that route's own doc comment for why). Every command it
    returns still goes through `_check_sudo_command` individually before anything executes."""
    payload = json.dumps({"request": request_text}).encode("utf-8")
    request = urllib.request.Request(
        SUDO_TRANSLATE_URL, data=payload, method="POST", headers={"Content-Type": "application/json"}
    )
    try:
        with urllib.request.urlopen(request, timeout=SUDO_TRANSLATE_TIMEOUT) as response:
            parsed = json.loads(response.read().decode("utf-8"))
    except (urllib.error.URLError, OSError, json.JSONDecodeError, UnicodeDecodeError) as error:
        return [], f"Could not reach the AI assistant ({error})."
    commands = parsed.get("commands", [])
    if not isinstance(commands, list):
        return [], "Assistant returned a malformed response."
    return [str(c) for c in commands], str(parsed.get("explanation", ""))
