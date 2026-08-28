#!/usr/bin/env python3
"""One-time install of Otterling's macOS "lock profile" -- see
`GUARDIAN_SETUP.md` §6 and `filter-server/lockprofile_service.py`'s module docstring for what this
profile does and doesn't protect against before running this. Short version: it's a tamper
*tripwire* (DNS moves onto a profile-managed resolver that can't be hand-edited without removing
the whole profile, and removal gets reported), not a lock an admin account can't defeat -- macOS
honors a local admin's own password over a profile's `RemovalPasscode`, always.

Run this once, as root, after completing `GUARDIAN_SETUP.md` steps 1-4. Use the sibling
`install_lock_profile.command` launcher (self-elevates and prompts for the server token), or
directly:

    sudo LOCKPROFILE_SERVER_HOST=vpn.bartholomew.help \\
         LOCKPROFILE_TOKEN=<the LOCKPROFILE_TOKEN from filter-server/.env> \\
         /usr/bin/python3 install_lock_profile.py

What it does:
  1. Computes this Mac's stable device id from IOPlatformUUID (`ioreg`).
  2. POSTs to the filter-server's `/lockprofile/provision`, which returns a signed-nothing (see
     below) `.mobileconfig` built specifically for this device id -- idempotent, so re-running this
     script is safe and returns the same profile rather than a new one.
  3. Saves it to the console user's Downloads folder and opens it, which hands off to System
     Settings' Profiles pane for the Guardian to review and click Install themselves. **There is no
     CLI install path any more**: `profiles install` was removed in macOS 11 (confirmed live
     against `profiles help` on macOS 15 -- the tool now says "Clients should use the Profiles
     System Settings pane to install configuration profiles"), so this script cannot finish the
     install unattended, only get the file in front of the Guardian and open the right pane.
  4. Saves the server host + token next to the daemon's own state file
     (`/Library/Application Support/FocusLock/`, root-only 0600) so `TamperReporter` can later post
     to `/alerts/tamper` without any credential re-entry. Done regardless of whether the Guardian
     has clicked Install yet -- `LockProfileGuard` just won't see the identifier until they do.

Note: the profile from `lockprofile_service.py` is unsigned (no code-signing identity involved --
this project doesn't have one for profiles, only for binaries). macOS will show an "unverified"
warning in the install dialog; that's expected and does not weaken anything described above.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import urllib.error
import urllib.request

STATE_DIR = "/Library/Application Support/FocusLock"
TOKEN_PATH = os.path.join(STATE_DIR, "lockprofile_token")
HOST_PATH = os.path.join(STATE_DIR, "lockprofile_host")
LOCK_PROFILE_IDENTIFIER = "app.otterling.lockprofile"


def fail(message: str, code: int = 1) -> "NoReturn":  # type: ignore[name-defined]
    print(f"[install-lock-profile] ERROR: {message}", file=sys.stderr)
    sys.exit(code)


def device_id() -> str:
    out = subprocess.run(
        ["/usr/sbin/ioreg", "-rd1", "-c", "IOPlatformExpertDevice"],
        capture_output=True, text=True, check=True,
    ).stdout
    for line in out.splitlines():
        if "IOPlatformUUID" in line:
            # Line looks like: "IOPlatformUUID" = "XXXXXXXX-XXXX-...."
            return line.split("=", 1)[1].strip().strip('"')
    fail("could not read IOPlatformUUID from ioreg output")


def provision_profile(host: str, token: str, dev_id: str) -> bytes:
    url = f"https://{host}/lockprofile/provision"
    body = json.dumps({"device_id": dev_id}).encode("utf-8")
    request = urllib.request.Request(
        url, data=body, method="POST",
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            return response.read()
    except urllib.error.HTTPError as error:
        fail(f"server rejected provisioning request ({error.code}): {error.read().decode('utf-8', 'replace')}")
    except urllib.error.URLError as error:
        fail(f"could not reach {url}: {error.reason}")


def console_user() -> str:
    """The user currently logged into the GUI -- same `stat -f%Su /dev/console` idiom
    guardian_password_setup.py already uses."""
    out = subprocess.run(
        ["/usr/bin/stat", "-f", "%Su", "/dev/console"],
        capture_output=True, text=True,
    ).stdout.strip()
    return out


def hand_off_to_system_settings(profile_bytes: bytes) -> None:
    user = console_user()
    if not user or user in ("root", "_windowserver"):
        fail("no GUI user is currently logged in -- log in as the Guardian and re-run this "
             "while they're at the screen, so they're the one who sees and approves the install")

    uid_out = subprocess.run(["/usr/bin/id", "-u", user], capture_output=True, text=True)
    if uid_out.returncode != 0:
        fail(f"could not resolve uid for console user '{user}'")
    uid = uid_out.stdout.strip()

    downloads_dir = f"/Users/{user}/Downloads"
    dest_path = os.path.join(downloads_dir, "Otterling-Lock.mobileconfig")
    try:
        with open(dest_path, "wb") as fh:
            fh.write(profile_bytes)
        shutil.chown(dest_path, user=user)
        os.chmod(dest_path, 0o600)
    except OSError as error:
        fail(f"could not write profile to {dest_path}: {error}")

    # Opens in the console user's own Aqua session, not root's (which has none) -- same
    # `launchctl asuser <uid> open ...` technique AppProtector.relaunchIfNeeded already uses.
    result = subprocess.run(["/bin/launchctl", "asuser", uid, "/usr/bin/open", dest_path])
    print(f"[install-lock-profile] Profile saved to {dest_path}.")
    if result.returncode == 0:
        print("[install-lock-profile] System Settings should now be open on the Profiles pane -- "
              f"have {user} (the Guardian) review and click Install, entering their admin "
              "password when prompted.")
    else:
        print(f"[install-lock-profile] Could not auto-open it (launchctl exit {result.returncode}) "
              f"-- have {user} double-click {dest_path} in Finder to install it manually.")


def save_credentials(host: str, token: str) -> None:
    # 0o711, not 0o700: STATE_DIR also holds proxy_ca.pem (deliberately world-readable -- see
    # Constants.swift's proxyCACertPath doc comment), which a non-root process can't reach at all
    # if it can't even traverse into the directory. `--x` for group/other grants exactly
    # traverse-by-known-name, not directory listing; this file's own 0o600 below is unaffected.
    os.makedirs(STATE_DIR, exist_ok=True, mode=0o711)
    with open(TOKEN_PATH, "w", encoding="utf-8") as fh:
        fh.write(token)
    os.chmod(TOKEN_PATH, 0o600)
    with open(HOST_PATH, "w", encoding="utf-8") as fh:
        fh.write(host)
    os.chmod(HOST_PATH, 0o600)


def main() -> None:
    if os.geteuid() != 0:
        fail("must run as root (writes into /Library/Application Support/FocusLock). "
             "Use install_lock_profile.command, or: sudo /usr/bin/python3 install_lock_profile.py")

    host = os.environ.get("LOCKPROFILE_SERVER_HOST", "").strip()
    token = os.environ.get("LOCKPROFILE_TOKEN", "").strip()
    token_file = os.environ.get("LOCKPROFILE_TOKEN_FILE", "")
    if not token and token_file and os.path.isfile(token_file):
        with open(token_file, "r", encoding="utf-8") as fh:
            token = fh.read().strip()
        try:
            os.remove(token_file)
        except OSError:
            pass

    if not host:
        fail("LOCKPROFILE_SERVER_HOST not set (e.g. vpn.bartholomew.help)")
    if not token:
        fail("LOCKPROFILE_TOKEN (or LOCKPROFILE_TOKEN_FILE) not set -- this is the "
             "LOCKPROFILE_TOKEN value from filter-server/.env on your server")

    dev_id = device_id()
    print(f"[install-lock-profile] provisioning for device {dev_id} from {host}...")
    profile_bytes = provision_profile(host, token, dev_id)

    hand_off_to_system_settings(profile_bytes)

    save_credentials(host, token)
    token = ""  # noqa: F841 -- best-effort clear before exit, matches guardian_password_setup.py

    print("[install-lock-profile] Once installed, verify with: "
          f"sudo profiles list -type configuration | grep {LOCK_PROFILE_IDENTIFIER}")


if __name__ == "__main__":
    main()
