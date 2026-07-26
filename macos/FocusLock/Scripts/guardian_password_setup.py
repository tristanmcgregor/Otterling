#!/usr/bin/env python3
"""One-time Guardian handoff tool for this Mac.

In a single run (as root) it:
  1. Wipes the `admin` account's entire Google Chrome profile.
  2. Deletes the `Guardian` account (up front).
  3. Serves a one-time, token-gated web form on the LAN so a trusted person can
     set a NEW password for the macOS `admin` account -- a password this machine's
     day-to-day user never sees.

Why it's shaped this way (verified on this machine):
  - `Tristan` is a Standard account (can't sudo); `admin` is the admin account and
    holds a SecureToken; `Guardian` has NO SecureToken (safe to delete under FileVault).
  - FileVault is ON, and both `admin` and `Tristan` can unlock it, so a root reset of
    `admin`'s password cannot lock anyone out of booting the disk.

Password handling / threat model:
  - `admin` holds a SecureToken and there is no known-password SecureToken admin to
    authorise a "proper" reset, so we do a plain root reset via `sysadminctl
    -resetPasswordFor admin -newPassword ...`. We drive that through a PTY and type
    the password at its interactive prompt instead of passing it as a CLI argument,
    because on macOS `ps` can expose another process's argv and the Standard user must
    not be able to read the new password. The password is never placed in argv, written
    to disk, or logged.

Known limits (accepted for a trusted home LAN, one-time use):
  - The form is plain HTTP on the LAN. Anyone on the Wi-Fi who holds the random token
    URL during the short window could submit. The Standard user can't packet-sniff
    without root; the guardian submits from their own device.
  - After a root reset `admin` may lose its SecureToken (FileVault pre-boot). The disk
    still unlocks via `Tristan`, and `admin`'s new password still authorises admin
    prompts / sudo after boot, so the handoff goal is met.
  - Deleting `Guardian` is irreversible and is done up front by design.

Run it via the sibling `guardian_password_setup.command` launcher (which self-elevates),
or directly with root:  sudo /usr/bin/python3 guardian_password_setup.py
"""

from __future__ import annotations

import html
import os
import pty
import secrets
import select
import shutil
import socket
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs

# --- Configuration -----------------------------------------------------------

ADMIN_USER = "admin"          # macOS shortname of the admin account to (re)password
GUARDIAN_USER = "Guardian"    # macOS shortname of the account to delete
CHROME_PROFILE = f"/Users/{ADMIN_USER}/Library/Application Support/Google/Chrome"

PORT = 8765
MIN_PASSWORD_LEN = 8
SERVER_TIMEOUT_SECONDS = 30 * 60  # auto-exit after 30 min even if never used


# --- Small helpers -----------------------------------------------------------

def log(message: str) -> None:
    print(f"[guardian-setup] {message}", flush=True)


def fail(message: str, code: int = 1) -> "NoReturn":  # type: ignore[name-defined]
    print(f"[guardian-setup] ERROR: {message}", file=sys.stderr, flush=True)
    sys.exit(code)


def user_exists(shortname: str) -> bool:
    return subprocess.run(
        ["/usr/bin/dscl", ".", "-read", f"/Users/{shortname}"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    ).returncode == 0


def console_user() -> str:
    """The user currently logged into the GUI (the `stat -f%Su /dev/console` idiom)."""
    try:
        out = subprocess.run(
            ["/usr/bin/stat", "-f", "%Su", "/dev/console"],
            capture_output=True, text=True, check=True,
        )
        return out.stdout.strip()
    except Exception:
        return ""


def lan_ip() -> str:
    for iface in ("en0", "en1", "en2"):
        out = subprocess.run(["/usr/sbin/ipconfig", "getifaddr", iface],
                             capture_output=True, text=True)
        ip = out.stdout.strip()
        if ip:
            return ip
    # Fallback: ask the routing table which source IP reaches the internet.
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect(("8.8.8.8", 80))
            return s.getsockname()[0]
        finally:
            s.close()
    except Exception:
        return "127.0.0.1"


# --- Destructive setup steps -------------------------------------------------

def wipe_admin_chrome() -> None:
    log(f"Closing any Chrome owned by '{ADMIN_USER}' and wiping its profile...")
    # Best-effort: kill Chrome running as the admin user so files aren't held open.
    subprocess.run(["/usr/bin/pkill", "-u", ADMIN_USER, "-x", "Google Chrome"],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run(["/usr/bin/pkill", "-u", ADMIN_USER, "-f", "Google Chrome"],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(1)
    if os.path.isdir(CHROME_PROFILE):
        shutil.rmtree(CHROME_PROFILE, ignore_errors=True)
        if os.path.isdir(CHROME_PROFILE):
            log("WARNING: Chrome profile still present after wipe attempt.")
        else:
            log("Chrome profile removed.")
    else:
        log("No Chrome profile found for admin (nothing to wipe).")


def delete_guardian() -> None:
    if not user_exists(GUARDIAN_USER):
        log(f"'{GUARDIAN_USER}' does not exist -- skipping deletion.")
        return
    if console_user() == GUARDIAN_USER:
        fail(f"'{GUARDIAN_USER}' is the currently logged-in user; log out of it first.")
    log(f"Deleting the '{GUARDIAN_USER}' account (and its home directory)...")
    result = subprocess.run(
        ["/usr/sbin/sysadminctl", "-deleteUser", GUARDIAN_USER],
        capture_output=True, text=True,
    )
    # sysadminctl writes status to stderr even on success; verify by re-reading.
    if user_exists(GUARDIAN_USER):
        fail(f"Failed to delete '{GUARDIAN_USER}': {result.stderr.strip()}")
    log(f"'{GUARDIAN_USER}' deleted.")


# --- Password reset (PTY-driven so the password never hits argv/logs) ---------

def set_admin_password(new_password: str) -> tuple[bool, str]:
    """Reset ADMIN_USER's password via `sysadminctl`, feeding the value over a PTY.

    Returns (success, detail). The password is written only into the child's PTY,
    never into this process's argv or any log line.
    """
    argv = ["/usr/sbin/sysadminctl", "-resetPasswordFor", ADMIN_USER, "-newPassword", "-"]
    pid, fd = pty.fork()
    if pid == 0:
        # Child: become sysadminctl. exec never returns on success.
        try:
            os.execv(argv[0], argv)
        except Exception:  # pragma: no cover - exec failure path
            os._exit(127)

    # Parent: answer every interactive prompt by typing the password + newline.
    captured = bytearray()
    deadline = time.time() + 30
    typed = False
    try:
        while True:
            if time.time() > deadline:
                break
            r, _, _ = select.select([fd], [], [], 0.5)
            if fd in r:
                try:
                    chunk = os.read(fd, 1024)
                except OSError:
                    break
                if not chunk:
                    break
                captured.extend(chunk)
                low = bytes(captured).lower()
                # sysadminctl prompts with something containing "password".
                if (not typed) and b"password" in low:
                    os.write(fd, (new_password + "\n").encode("utf-8"))
                    typed = True
            # If we've typed and the child produced a bit more output, keep draining
            # until it exits (handled by the read returning empty / OSError above).
    finally:
        try:
            os.close(fd)
        except OSError:
            pass

    _, status = os.waitpid(pid, 0)
    exit_code = os.waitstatus_to_exitcode(status)
    # Scrub any echoed password out of the detail we surface/log.
    detail = bytes(captured).decode("utf-8", "replace")
    detail = detail.replace(new_password, "********")
    ok = exit_code == 0 and "error" not in detail.lower()
    return ok, detail.strip()


# --- One-time web form -------------------------------------------------------

PAGE_STYLE = """
  body{font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;
       background:#0f172a;color:#e2e8f0;margin:0;padding:0;}
  .card{max-width:420px;margin:8vh auto;background:#1e293b;padding:32px;
        border-radius:16px;box-shadow:0 10px 40px rgba(0,0,0,.4);}
  h1{font-size:20px;margin:0 0 8px;} p{color:#94a3b8;font-size:14px;line-height:1.5;}
  label{display:block;margin:18px 0 6px;font-size:13px;color:#cbd5e1;}
  input[type=password]{width:100%;box-sizing:border-box;padding:12px;border-radius:10px;
        border:1px solid #334155;background:#0f172a;color:#e2e8f0;font-size:15px;}
  button{margin-top:22px;width:100%;padding:13px;border:0;border-radius:10px;
        background:#6366f1;color:#fff;font-size:15px;font-weight:600;cursor:pointer;}
  .err{background:#7f1d1d;color:#fecaca;padding:10px 12px;border-radius:8px;
       font-size:13px;margin-top:16px;}
  .ok{background:#064e3b;color:#a7f3d0;padding:10px 12px;border-radius:8px;font-size:14px;}
"""


def form_page(error: str | None = None) -> bytes:
    err_html = f'<div class="err">{html.escape(error)}</div>' if error else ""
    body = f"""<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Set admin password</title><style>{PAGE_STYLE}</style></head>
<body><div class="card">
<h1>Set the admin password</h1>
<p>You are setting the password for the <b>{html.escape(ADMIN_USER)}</b> account on this Mac.
Choose a password only you know and keep it private. This link works once.</p>
{err_html}
<form method="POST">
  <label for="p1">New password (at least {MIN_PASSWORD_LEN} characters)</label>
  <input id="p1" type="password" name="password" autocomplete="new-password" required>
  <label for="p2">Confirm new password</label>
  <input id="p2" type="password" name="confirm" autocomplete="new-password" required>
  <button type="submit">Set password</button>
</form>
</div></body></html>"""
    return body.encode("utf-8")


def done_page() -> bytes:
    body = f"""<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Done</title><style>{PAGE_STYLE}</style></head>
<body><div class="card">
<h1>Password set</h1>
<div class="ok">The <b>{html.escape(ADMIN_USER)}</b> account password has been updated.
You can close this page. This link is now disabled.</div>
</div></body></html>"""
    return body.encode("utf-8")


class OneTimeState:
    """Shared flags between the request handler and the main thread."""
    def __init__(self, token_path: str):
        self.token_path = token_path
        self.done = threading.Event()
        self.lock = threading.Lock()


def make_handler(state: OneTimeState):
    class Handler(BaseHTTPRequestHandler):
        server_version = "GuardianSetup/1.0"

        def _send(self, code: int, body: bytes, content_type="text/html; charset=utf-8"):
            self.send_response(code)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def _authorized(self) -> bool:
            # Constant-time compare of the whole path against the secret token path.
            return secrets.compare_digest(self.path.split("?", 1)[0], state.token_path)

        def do_GET(self):  # noqa: N802 (http.server API)
            if state.done.is_set():
                return self._send(410, b"This link has already been used.", "text/plain; charset=utf-8")
            if not self._authorized():
                return self._send(404, b"Not found", "text/plain; charset=utf-8")
            self._send(200, form_page())

        def do_POST(self):  # noqa: N802
            if not self._authorized():
                return self._send(404, b"Not found", "text/plain; charset=utf-8")
            with state.lock:
                if state.done.is_set():
                    return self._send(410, done_page())

                length = int(self.headers.get("Content-Length", "0") or "0")
                if length <= 0 or length > 64 * 1024:
                    return self._send(200, form_page("Invalid submission."))
                raw = self.rfile.read(length).decode("utf-8", "replace")
                fields = parse_qs(raw, keep_blank_values=True)
                password = (fields.get("password", [""])[0])
                confirm = (fields.get("confirm", [""])[0])

                if password != confirm:
                    return self._send(200, form_page("The two passwords didn't match."))
                if len(password) < MIN_PASSWORD_LEN:
                    return self._send(200, form_page(
                        f"Password must be at least {MIN_PASSWORD_LEN} characters."))

                log("Received a password submission; applying to the admin account...")
                ok, detail = set_admin_password(password)
                # Never log the password; `detail` is already scrubbed.
                if ok:
                    log("Admin password updated successfully.")
                    state.done.set()
                    self._send(200, done_page())
                    return
                log(f"Password reset failed: {detail}")
                self._send(200, form_page(
                    "Setting the password failed on the Mac. Please try again."))

        def log_message(self, fmt, *args):  # silence default noisy logging
            return

    return Handler


def serve_once(token_path: str, ip: str) -> bool:
    state = OneTimeState(token_path)
    httpd = ThreadingHTTPServer(("0.0.0.0", PORT), make_handler(state))
    httpd.timeout = 1

    url = f"http://{ip}:{PORT}{token_path}"
    log("")
    log("=" * 60)
    log("One-time setup link (send this to the person setting the password):")
    log(f"    {url}")
    log("=" * 60)
    log(f"Waiting for a submission (auto-exits after {SERVER_TIMEOUT_SECONDS // 60} minutes)...")
    log("")

    deadline = time.time() + SERVER_TIMEOUT_SECONDS
    try:
        while not state.done.is_set() and time.time() < deadline:
            httpd.handle_request()
    except KeyboardInterrupt:
        log("Interrupted.")
    finally:
        httpd.server_close()
    return state.done.is_set()


# --- Entry point -------------------------------------------------------------

def main() -> None:
    if os.geteuid() != 0:
        fail("This must run as root. Use the guardian_password_setup.command launcher, "
             "or: sudo /usr/bin/python3 guardian_password_setup.py")

    if not user_exists(ADMIN_USER):
        fail(f"Admin account '{ADMIN_USER}' not found on this Mac.")

    log("Starting one-time Guardian handoff.")
    log(f"  - target admin account : {ADMIN_USER}")
    log(f"  - guardian to delete   : {GUARDIAN_USER}")
    log("")

    # Step 1 + 2 (up front, per plan): wipe admin Chrome, then delete Guardian.
    wipe_admin_chrome()
    delete_guardian()

    # Step 3: one-time LAN form to set the new admin password.
    ip = lan_ip()
    token = secrets.token_urlsafe(24)
    token_path = f"/{token}"

    success = serve_once(token_path, ip)
    if success:
        log("Done. The admin password is now set to a value chosen by the other person.")
        sys.exit(0)
    else:
        log("No password was set before the server stopped. The admin account still has "
            "its previous password. Re-run this tool to try again.")
        sys.exit(2)


if __name__ == "__main__":
    main()
