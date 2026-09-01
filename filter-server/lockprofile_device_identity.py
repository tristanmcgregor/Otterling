"""Device id canonicalization and macOS-vs-Android platform inference.

Extracted from lockprofile_service.py as a leaf module (imports nothing back from there) so it can
be imported by other split-out modules without a circular import.
"""

from __future__ import annotations

import json
import os
import re

# Neither client sends an explicit platform field today, so this is inferred purely from
# device_id shape: the Mac's canonical id (TamperReporter.swift/install_lock_profile.py's
# IOPlatformUUID) is always a dashed UUID; Android's ANDROID_ID (see comment above) is always a
# bare hex token with no dashes. The two never collide for this fleet. Used to decide which
# dashboard-api settings sections actually apply to a device -- most of device_settings.json
# (protections, vpnFilter/vpnBypassApps, blockedWebsites, rules, habits, appBudgets,
# triggerWords, blockedApps) is consumed ONLY by the Android app's DashboardConfigStore
# consumers; nothing on the Mac reads dashboard-api at all today.
_UUID_RE = re.compile(r"^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")


def _detect_platform(device_id: str) -> str:
    return "macos" if _UUID_RE.match(device_id) else "android"

# The Mac has no single canonical identity across every reporter: TamperReporter.swift/
# install_lock_profile.py use IOPlatformUUID (the real, stable identity per-device settings are
# provisioned under), but deadman.py's Fleet lookups key on a configurable hostname and
# block_reporter.py (the mitm proxy addon, which only ever sees network-layer traffic) keys on
# client IP -- so a hostname change or a new DHCP lease used to mint a brand-new "device" in the
# dashboard for a machine that was never actually new. DEVICE_ID_ALIASES lets a deployer declare
# "this hostname/IP is actually this device_id" so every reporting path collapses onto the same
# entry. JSON object in the env var, e.g. {"192.168.0.115": "<canonical-id>", "old-hostname.local":
# "<canonical-id>"} -- unset/invalid means no aliasing (today's behavior).
def _load_device_id_aliases() -> dict:
    raw = os.environ.get("DEVICE_ID_ALIASES", "").strip()
    if not raw:
        return {}
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError:
        return {}
    return {str(k): str(v) for k, v in parsed.items()} if isinstance(parsed, dict) else {}


DEVICE_ID_ALIASES = _load_device_id_aliases()


def _canonicalize_device_id(device_id: str) -> str:
    return DEVICE_ID_ALIASES.get(device_id, device_id)
