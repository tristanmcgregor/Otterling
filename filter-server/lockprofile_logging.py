"""Shared diagnostic logging for lockprofile_service.py and its split-out modules.

Extracted from lockprofile_service.py (which now imports _log from here) so the other extracted
modules (lockprofile_screenshots.py, lockprofile_device_logs.py, etc.) can log without importing
back from lockprofile_service.py -- that would create a circular import, since
lockprofile_service.py itself imports functions from those modules.
"""

from __future__ import annotations

import collections
import threading
from datetime import datetime, timezone

# In-memory ring buffer of this process's own diagnostic output (the _log() calls below), exposed
# read-only via GET /debug/server-log for remote debugging. There is otherwise no way to see this
# process's own diagnostics short of `docker logs`/journald on the host, which this container has
# no access to (see lockprofile_sudo_ai.py's SUDO_REVIEW_URL comment for why host-level things
# stay out of this container).
_LOG_BUFFER_MAX_LINES = 2000
_log_buffer: collections.deque[str] = collections.deque(maxlen=_LOG_BUFFER_MAX_LINES)
_log_buffer_lock = threading.Lock()


def _log(message: str) -> None:
    """Drop-in replacement for the old bare `print(..., flush=True)` calls this file used
    everywhere: still writes to stdout (so `docker logs`/Caddy's log driver keep working exactly
    as before), but also appends a timestamped copy to _log_buffer so GET /debug/server-log can
    show recent diagnostics without shelling out to the host."""
    line = f"{datetime.now(timezone.utc).isoformat(timespec='seconds')} {message}"
    print(line, flush=True)
    with _log_buffer_lock:
        _log_buffer.append(line)
