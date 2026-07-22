#!/usr/bin/env python3
"""
Guardian setup relay -- a deliberately dumb, zero-knowledge dead-drop.

This server NEVER sees a plaintext password or PIN, and never holds a private key. The Mac and
the phone each generate their own RSA-OAEP keypair and keep the private half to themselves; their
public keys are embedded directly in the setup link as query params. The Guardian's browser
encrypts the Mac password and phone PIN client-side (Web Crypto, RSA-OAEP/SHA-256) against those
public keys before ever sending anything here -- this process only stores and relays ciphertext
it has no way to decrypt.

This matters specifically because whoever runs this server (you) can read its logs and memory.
If plaintext ever touched this process, the whole point of a Guardian-held secret would be
defeated. Don't "simplify" this by decrypting or logging payloads here.

Run:
    pip install flask
    python3 guardian_relay.py            # listens on 0.0.0.0:8787 (plain HTTP)

Then put a real TLS reverse proxy (Caddy, nginx, a Cloudflare Tunnel, whatever you already use
for bartholomew.help) in front of it. Never serve the setup page over plain HTTP -- the
ciphertext is opaque, but the page's origin still needs to be trustworthy (no on-path tampering
with the embedded public keys, which would let an attacker substitute their own key and read the
password themselves).
"""
from __future__ import annotations

import html
import secrets
import threading
import time
from datetime import datetime, timezone

from flask import Flask, jsonify, request, abort, Response

app = Flask(__name__)

TTL_SECONDS = 30 * 60  # setup links are single-use and short-lived on purpose
_lock = threading.Lock()
_drops: dict[str, dict] = {}


def _purge_expired() -> None:
    now = time.time()
    expired = [token for token, entry in _drops.items() if now - entry["created_at"] > TTL_SECONDS]
    for token in expired:
        del _drops[token]


def _new_token() -> str:
    return secrets.token_urlsafe(32)


@app.get("/healthz")
def healthz() -> Response:
    return jsonify(status="ok", time=datetime.now(timezone.utc).isoformat())


@app.get("/setup/<token>")
def setup_page(token: str) -> Response:
    mac_pub = request.args.get("mac_pub", "")
    phone_pub = request.args.get("phone_pub", "")
    if not mac_pub or not phone_pub:
        abort(400, "Missing mac_pub or phone_pub query params -- use the full link you were sent.")

    page = _SETUP_PAGE_TEMPLATE.format(
        token=html.escape(token),
        mac_pub=html.escape(mac_pub),
        phone_pub=html.escape(phone_pub),
    )
    return Response(page, mimetype="text/html")


@app.post("/drop/<token>")
def submit_drop(token: str) -> Response:
    with _lock:
        _purge_expired()
        if token in _drops:
            abort(409, "This setup link has already been submitted or claimed.")

        body = request.get_json(silent=True) or {}
        mac_ct = body.get("mac")
        phone_ct = body.get("phone")
        if not mac_ct and not phone_ct:
            abort(400, "Nothing to store -- expected at least one of mac/phone ciphertext.")

        _drops[token] = {
            "created_at": time.time(),
            "mac": mac_ct,
            "phone": phone_ct,
        }
    return jsonify(status="stored")


def _claim(token: str, field: str) -> Response:
    with _lock:
        _purge_expired()
        entry = _drops.get(token)
        if entry is None:
            abort(404, "Unknown, expired, or already-fully-claimed setup link.")

        ciphertext = entry.get(field)
        if ciphertext is None:
            abort(404, f"No {field} payload here (already claimed, or none was submitted).")

        entry[field] = None
        if entry.get("mac") is None and entry.get("phone") is None:
            del _drops[token]

    return jsonify(ciphertext=ciphertext)


@app.get("/drop/<token>/mac")
def claim_mac(token: str) -> Response:
    return _claim(token, "mac")


@app.get("/drop/<token>/phone")
def claim_phone(token: str) -> Response:
    return _claim(token, "phone")


_SETUP_PAGE_TEMPLATE = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Guardian setup</title>
<style>
  body {{ font-family: -apple-system, system-ui, sans-serif; max-width: 480px; margin: 48px auto; padding: 0 16px; color: #1a1a1a; }}
  h1 {{ font-size: 1.3rem; }}
  p {{ color: #555; line-height: 1.4; }}
  label {{ display: block; margin-top: 20px; font-weight: 600; }}
  input {{ width: 100%; box-sizing: border-box; padding: 10px; font-size: 1rem; margin-top: 6px; border: 1px solid #ccc; border-radius: 6px; }}
  button {{ margin-top: 24px; width: 100%; padding: 12px; font-size: 1rem; border: none; border-radius: 6px; background: #1a73e8; color: white; cursor: pointer; }}
  button:disabled {{ background: #999; }}
  #status {{ margin-top: 16px; font-weight: 600; }}
  .ok {{ color: #1a8a3f; }}
  .err {{ color: #c0392b; }}
</style>
</head>
<body>
<h1>Guardian setup</h1>
<p>
  Choose a password for the Mac's Guardian account and a PIN for the phone app. Only you should
  ever know these -- don't share them back, including with the person who sent you this link.
  Everything below is encrypted in your browser before it's sent anywhere; this page's server
  never sees what you type.
</p>

<label for="macpw">Mac Guardian account password</label>
<input id="macpw" type="password" autocomplete="new-password">

<label for="phonepin">Phone app PIN (4-8 digits)</label>
<input id="phonepin" type="password" inputmode="numeric" pattern="[0-9]*" maxlength="8">

<button id="submit">Set both</button>
<div id="status"></div>

<script>
const token = {token!r};
const macPubB64 = "{mac_pub}";
const phonePubB64 = "{phone_pub}";

function b64ToBytes(b64) {{
  const bin = atob(b64.replace(/-/g, '+').replace(/_/g, '/'));
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return bytes;
}}

function bytesToB64(bytes) {{
  let bin = '';
  bytes.forEach(b => bin += String.fromCharCode(b));
  return btoa(bin);
}}

async function importRsaPublicKey(spkiB64) {{
  const keyData = b64ToBytes(spkiB64);
  return crypto.subtle.importKey(
    'spki', keyData, {{ name: 'RSA-OAEP', hash: 'SHA-256' }}, false, ['encrypt']
  );
}}

async function encryptWith(pubKey, text) {{
  const data = new TextEncoder().encode(text);
  const cipherBuf = await crypto.subtle.encrypt({{ name: 'RSA-OAEP' }}, pubKey, data);
  return bytesToB64(new Uint8Array(cipherBuf));
}}

document.getElementById('submit').addEventListener('click', async () => {{
  const statusEl = document.getElementById('status');
  const btn = document.getElementById('submit');
  const macPw = document.getElementById('macpw').value;
  const phonePin = document.getElementById('phonepin').value;

  if (!macPw || !phonePin) {{
    statusEl.textContent = 'Fill in both fields.';
    statusEl.className = 'err';
    return;
  }}
  if (!/^[0-9]{{4,8}}$/.test(phonePin)) {{
    statusEl.textContent = 'Phone PIN must be 4-8 digits.';
    statusEl.className = 'err';
    return;
  }}

  btn.disabled = true;
  statusEl.textContent = 'Encrypting...';
  statusEl.className = '';
  try {{
    const [macPub, phonePub] = await Promise.all([
      importRsaPublicKey(macPubB64),
      importRsaPublicKey(phonePubB64),
    ]);
    const [macCt, phoneCt] = await Promise.all([
      encryptWith(macPub, macPw),
      encryptWith(phonePub, phonePin),
    ]);

    const res = await fetch(`/drop/${{token}}`, {{
      method: 'POST',
      headers: {{ 'Content-Type': 'application/json' }},
      body: JSON.stringify({{ mac: macCt, phone: phoneCt }}),
    }});

    if (res.ok) {{
      statusEl.textContent = 'Done. Tell them it\\'s ready to claim -- this link is now used up.';
      statusEl.className = 'ok';
      document.getElementById('macpw').value = '';
      document.getElementById('phonepin').value = '';
    }} else {{
      const body = await res.text();
      statusEl.textContent = 'Failed: ' + body;
      statusEl.className = 'err';
      btn.disabled = false;
    }}
  }} catch (e) {{
    statusEl.textContent = 'Error: ' + e.message;
    statusEl.className = 'err';
    btn.disabled = false;
  }}
}});
</script>
</body>
</html>
"""

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8787)
