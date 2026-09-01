"""Screenshot evidence storage (NSFW / error / safe) and the review-dashboard payload builder.

See POST /screenshot-classify in lockprofile_service.py for the route that calls into this module.
Extracted as a leaf module (imports nothing back from lockprofile_service.py) so it can be
imported without a circular import.
"""

from __future__ import annotations

import json
import os
import threading
import time

DATA_DIR = os.environ.get("LOCKPROFILE_DATA_DIR", "/data")

# Flagged-only screenshot evidence for POST /screenshot-classify -- see that route's comment and
# _store_screenshot below. Under DATA_DIR (not a new top-level path) so it inherits
# deploy_filter_server.sh's existing `--exclude 'lockprofile-data/'` rsync protection for free.
SCREENSHOTS_DIR = os.path.join(DATA_DIR, "screenshots")
# Screenshots the classifier failed to classify at all (pipeline unavailable, ONNX exception,
# etc.) -- unlike SCREENSHOTS_DIR, these are NOT a positive NSFW finding, just whatever app
# happened to be open when classification errored, so a much smaller retention cap
# (MAX_ERROR_SCREENSHOT_FILES_PER_DEVICE) applies. Exists so a 100%-erroring pipeline is
# debuggable from the dashboard (see /screenshot-review/list) instead of only showing an "N
# errored" count with nothing to actually look at.
SCREENSHOT_ERRORS_DIR = os.path.join(DATA_DIR, "screenshot_errors")
# Every screenshot classified "safe" -- previously discarded right after classification (only the
# aggregate counters below survived). A guardian asked to see "all recent screenshots", not just
# the flagged/errored ones, so these are now persisted too, with their own retention cap
# (MAX_SAFE_SCREENSHOT_FILES_PER_DEVICE) -- see /screenshot-review/list.
SCREENSHOT_SAFE_DIR = os.path.join(DATA_DIR, "screenshot_safe")
# Lightweight per-device activity counters (total/safe/nsfw/error/skipped classifications, last
# result) for every POST /screenshot-classify call -- unlike SCREENSHOTS_DIR, this tracks every
# call, not just positive (NSFW) ones, and never stores image bytes. Backs the "is this actually
# running" view in /screenshot-review/list -- see _record_screenshot_classification below.
SCREENSHOT_STATS_PATH = os.path.join(DATA_DIR, "screenshot_stats.json")

# A downscaled (720px max dimension), JPEG-compressed phone screenshot (see
# FocusGuardAccessibilityService.kt's capture path) should be well under 1-2MB base64-encoded;
# this caps the whole JSON request body (see MAX_SCREENSHOT_BODY_BYTES in lockprofile_service.py).
# Only NSFW-classified screenshots are ever stored (see _store_screenshot) -- this is a "recent
# incidents" cap, not a full history, so a much lower number than device-log retention is fine.
MAX_SCREENSHOT_FILES_PER_DEVICE = 50
# Errored screenshots can arrive far more often than genuine NSFW hits (e.g. the whole classifier
# pipeline down -- every single upload errors), so this stays well below
# MAX_SCREENSHOT_FILES_PER_DEVICE: enough recent samples to debug why, not a running log of
# whatever the device owner had on screen.
MAX_ERROR_SCREENSHOT_FILES_PER_DEVICE = 12
# Safe screenshots arrive far more often than either of the above (most classifications ARE
# safe), so this is deliberately just "what's on screen recently" -- a short rolling window, not
# an incident record like MAX_SCREENSHOT_FILES_PER_DEVICE/MAX_ERROR_SCREENSHOT_FILES_PER_DEVICE.
MAX_SAFE_SCREENSHOT_FILES_PER_DEVICE = 30

_screenshot_stats_lock = threading.Lock()


def _store_screenshot_in(
    root_dir: str, max_files: int, device_id: str, package_name: str, image_bytes: bytes, reason: str | None = None,
) -> str:
    """Writes a screenshot to root_dir/<device_id>/, then prunes to the newest max_files for that
    device. Shared by _store_screenshot (NSFW evidence, SCREENSHOTS_DIR) and
    _store_error_screenshot (classifier-error samples, SCREENSHOT_ERRORS_DIR) -- see
    POST /screenshot-classify. `package_name` is sanitized the same way _sanitize_installed_apps
    caps a field, since it becomes part of a filename below.

    `reason`, when given, is written to a same-name `.txt` sidecar (e.g. "foo.jpg" ->
    "foo.jpg.txt") -- see _list_screenshots_in for how that's read back. Pruning below counts
    only `.jpg` files toward max_files (a sidecar doesn't count as a second "screenshot") and
    always removes a pruned image's sidecar alongside it, so the two can never desync."""
    safe_package = "".join(c for c in package_name if c.isalnum() or c in "._-")[:200] or "unknown"
    device_dir = os.path.join(root_dir, device_id)
    os.makedirs(device_dir, exist_ok=True)
    stamp = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    filename = f"{stamp}-{safe_package}.jpg"
    path = os.path.join(device_dir, filename)
    counter = 1
    while os.path.exists(path):
        filename = f"{stamp}-{safe_package}-{counter}.jpg"
        path = os.path.join(device_dir, filename)
        counter += 1
    with open(path, "wb") as fh:
        fh.write(image_bytes)
    if reason:
        with open(path + ".txt", "w", encoding="utf-8") as fh:
            fh.write(reason[:500])
    existing_images = sorted(f for f in os.listdir(device_dir) if f.endswith(".jpg"))
    for stale in existing_images[: max(0, len(existing_images) - max_files)]:
        for target in (stale, stale + ".txt"):
            try:
                os.remove(os.path.join(device_dir, target))
            except OSError:
                pass
    return filename


def _store_screenshot(device_id: str, package_name: str, image_bytes: bytes) -> str:
    """Only ever called for a positive (NSFW) classification result; a safe/error result is never
    written here at all -- see _store_error_screenshot for the separate error-sample path."""
    return _store_screenshot_in(SCREENSHOTS_DIR, MAX_SCREENSHOT_FILES_PER_DEVICE, device_id, package_name, image_bytes)


def _store_error_screenshot(device_id: str, package_name: str, image_bytes: bytes, reason: str | None) -> str:
    """Only ever called when classify_screenshot() returns a None verdict (pipeline
    unavailable/exception) -- see MAX_ERROR_SCREENSHOT_FILES_PER_DEVICE's comment for why this
    keeps far fewer than _store_screenshot's NSFW evidence. `reason` is classify_screenshot()'s
    error_reason, saved alongside the image so the review page can show why, not just that."""
    return _store_screenshot_in(
        SCREENSHOT_ERRORS_DIR, MAX_ERROR_SCREENSHOT_FILES_PER_DEVICE, device_id, package_name, image_bytes,
        reason=reason,
    )


def _store_safe_screenshot(device_id: str, package_name: str, image_bytes: bytes) -> str:
    """Only ever called for a "safe" classification result -- the review page's "recent
    screenshots" feed, distinct from _store_screenshot's NSFW evidence. See
    MAX_SAFE_SCREENSHOT_FILES_PER_DEVICE for why this keeps only a short rolling window."""
    return _store_screenshot_in(
        SCREENSHOT_SAFE_DIR, MAX_SAFE_SCREENSHOT_FILES_PER_DEVICE, device_id, package_name, image_bytes,
    )


def _list_screenshots_in(root_dir: str) -> dict:
    """`.jpg.txt` sidecars (see _store_screenshot_in's `reason` param) are read into each entry's
    `reason` key, not listed as their own file -- only SCREENSHOT_ERRORS_DIR ever has any."""
    if not os.path.isdir(root_dir):
        return {}
    result = {}
    for device_id in sorted(os.listdir(root_dir)):
        device_dir = os.path.join(root_dir, device_id)
        if not os.path.isdir(device_dir):
            continue
        files = []
        for filename in sorted(os.listdir(device_dir)):
            if not filename.endswith(".jpg"):
                continue
            path = os.path.join(device_dir, filename)
            try:
                stat = os.stat(path)
            except OSError:
                continue
            entry = {"filename": filename, "size": stat.st_size, "mtime": stat.st_mtime}
            try:
                with open(path + ".txt", "r", encoding="utf-8") as fh:
                    entry["reason"] = fh.read().strip()
            except OSError:
                pass
            files.append(entry)
        result[device_id] = files
    return result


def _list_screenshots() -> dict:
    return _list_screenshots_in(SCREENSHOTS_DIR)


def _list_error_screenshots() -> dict:
    return _list_screenshots_in(SCREENSHOT_ERRORS_DIR)


def _list_safe_screenshots() -> dict:
    return _list_screenshots_in(SCREENSHOT_SAFE_DIR)


def _load_screenshot_stats() -> dict:
    try:
        with open(SCREENSHOT_STATS_PATH, "r", encoding="utf-8") as fh:
            return json.load(fh)
    except (FileNotFoundError, json.JSONDecodeError):
        return {}


def _save_screenshot_stats(stats: dict) -> None:
    os.makedirs(DATA_DIR, exist_ok=True)
    tmp_path = SCREENSHOT_STATS_PATH + ".tmp"
    with open(tmp_path, "w", encoding="utf-8") as fh:
        json.dump(stats, fh, indent=2, sort_keys=True)
    os.replace(tmp_path, SCREENSHOT_STATS_PATH)


def _record_screenshot_classification(device_id: str, package_name: str, result: str) -> None:
    """Tracks lightweight per-device activity counters for every POST /screenshot-classify call --
    unlike _store_screenshot (only ever called for a positive NSFW verdict), this runs for every
    result (safe/nsfw/error/skipped) and never touches image bytes. This is what lets
    /screenshot-review/list show the feature is actually receiving and classifying uploads even on
    a device that has never been flagged."""
    with _screenshot_stats_lock:
        stats = _load_screenshot_stats()
        record = stats.setdefault(device_id, {"total": 0, "safe": 0, "nsfw": 0, "error": 0, "skipped": 0})
        record[result] = record.get(result, 0) + 1
        record["total"] = record.get("total", 0) + 1
        record["lastClassifiedAt"] = int(time.time() * 1000)
        record["lastResult"] = result
        record["lastPackageName"] = package_name
        _save_screenshot_stats(stats)


def _screenshot_review_payload() -> dict:
    """Combines the flagged-screenshot file listing with the activity counters above into what
    /screenshot-review/data (fetched by the /screenshot-review/list GUI) actually returns."""
    files_by_device = _list_screenshots()
    error_files_by_device = _list_error_screenshots()
    safe_files_by_device = _list_safe_screenshots()
    stats = _load_screenshot_stats()
    devices = []
    for device_id in sorted(set(files_by_device) | set(error_files_by_device) | set(safe_files_by_device) | set(stats)):
        files = sorted(files_by_device.get(device_id, []), key=lambda f: f["mtime"], reverse=True)
        error_files = sorted(error_files_by_device.get(device_id, []), key=lambda f: f["mtime"], reverse=True)
        safe_files = sorted(safe_files_by_device.get(device_id, []), key=lambda f: f["mtime"], reverse=True)
        devices.append({
            "deviceId": device_id,
            "stats": stats.get(device_id, {}),
            "screenshots": [
                {
                    "filename": f["filename"],
                    "size": f["size"],
                    "mtime": f["mtime"],
                    "url": f"/screenshot-review/{device_id}/{f['filename']}",
                }
                for f in files
            ],
            "errorScreenshots": [
                {
                    "filename": f["filename"],
                    "size": f["size"],
                    "mtime": f["mtime"],
                    "url": f"/screenshot-review/errors/{device_id}/{f['filename']}",
                    "reason": f.get("reason", ""),
                }
                for f in error_files
            ],
            "safeScreenshots": [
                {
                    "filename": f["filename"],
                    "size": f["size"],
                    "mtime": f["mtime"],
                    "url": f"/screenshot-review/safe/{device_id}/{f['filename']}",
                }
                for f in safe_files
            ],
        })
    devices.sort(key=lambda d: d["stats"].get("lastClassifiedAt", 0), reverse=True)
    return {"devices": devices}
