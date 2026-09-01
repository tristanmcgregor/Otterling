"""Device diagnostic log upload/storage -- see lockprofile_service.py's module doc comment
("Device log upload") for the full route-level picture.

Extracted as a leaf module (imports nothing back from lockprofile_service.py) so it can be
imported without a circular import.
"""

from __future__ import annotations

import os
import time

DATA_DIR = os.environ.get("LOCKPROFILE_DATA_DIR", "/data")
LOGS_DIR = os.path.join(DATA_DIR, "logs")
MAX_LOG_FILES_PER_DEVICE = 20


def _store_device_log(device_id: str, logs: str) -> str:
    """Writes `logs` to a new timestamped file under LOGS_DIR/<device_id>/, then prunes to the
    newest MAX_LOG_FILES_PER_DEVICE files for that device so a device stuck retrying uploads can't
    fill the disk. Returns the filename written."""
    device_dir = os.path.join(LOGS_DIR, device_id)
    os.makedirs(device_dir, exist_ok=True)
    stamp = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    filename = f"{stamp}.log"
    path = os.path.join(device_dir, filename)
    counter = 1
    while os.path.exists(path):
        filename = f"{stamp}-{counter}.log"
        path = os.path.join(device_dir, filename)
        counter += 1
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(logs)
    existing = sorted(os.listdir(device_dir))
    for stale in existing[: max(0, len(existing) - MAX_LOG_FILES_PER_DEVICE)]:
        try:
            os.remove(os.path.join(device_dir, stale))
        except OSError:
            pass
    return filename


def _list_device_logs() -> dict:
    if not os.path.isdir(LOGS_DIR):
        return {}
    result = {}
    for device_id in sorted(os.listdir(LOGS_DIR)):
        device_dir = os.path.join(LOGS_DIR, device_id)
        if not os.path.isdir(device_dir):
            continue
        files = []
        for filename in sorted(os.listdir(device_dir)):
            path = os.path.join(device_dir, filename)
            try:
                stat = os.stat(path)
            except OSError:
                continue
            files.append({"filename": filename, "size": stat.st_size, "mtime": stat.st_mtime})
        result[device_id] = files
    return result
