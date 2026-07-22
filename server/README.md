# Guardian setup relay

A minimal, deliberately dumb dead-drop that lets your Guardian set the Mac's Guardian-account
password and the phone app's PIN via a single link, **without the relay server (or whoever runs
it -- you) ever seeing either secret in plaintext.**

## Why this needs to be zero-knowledge

If you're hosting this yourself (e.g. on your own home server), you're the server's admin -- you
can read its logs, its memory, its process list. Any plaintext secret that ever touched this
process would be trivially visible to you, defeating the entire point of a Guardian-held secret.

So this server only ever stores and relays **ciphertext**. The Mac and the phone each generate
their own RSA-OAEP keypair and keep the private half to themselves (root-owned file on the Mac,
non-exportable Android Keystore entry on the phone). Their public keys are embedded directly in
the setup link; the Guardian's browser encrypts against those keys with WebCrypto before anything
is sent here. This server has no private key and no crypto code at all -- check `guardian_relay.py`
if you want to verify that yourself.

## Running it

```bash
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
python3 guardian_relay.py     # listens on 0.0.0.0:8787, plain HTTP
```

Put a real TLS reverse proxy in front of it (Caddy, nginx, a Cloudflare Tunnel -- whatever you
already use for your domain). A two-line Caddyfile is enough:

```
setup.yourdomain.example {
    reverse_proxy localhost:8787
}
```

Don't serve the setup page over plain HTTP: the ciphertext itself is opaque, but the page's origin
still needs to be trustworthy, otherwise someone on the network path could swap in their own public
key and read the password themselves (a classic MITM on the *key*, not the ciphertext).

## The flow

1. **Mac:** `focuslockctl guardian-link https://your-relay.example <phone-pubkey-base64>` --
   prints a one-time setup URL. Get the phone's public key from the app's "Set up via a Guardian
   link" section on the create-PIN screen (there's a "Copy this phone's public key" button).
2. Send that URL to your Guardian, however you'd normally send them a link.
3. They open it, type a Mac password and a phone PIN, hit submit. Their browser encrypts each
   separately and posts only ciphertext to `/drop/<token>`.
4. **Mac:** `focuslockctl guardian-claim https://your-relay.example <token>` -- fetches and
   decrypts the Mac's half, then creates the Guardian account (if it doesn't exist yet) or resets
   its password (if it does). You only ever see `OK`/`DENIED`, never the password.
5. **Phone:** open the same "Set up via a Guardian link" section, paste the full setup URL, tap
   "Claim PIN from link" -- fetches and decrypts the phone's half and sets it as the app's PIN.

Each half can only be claimed once; the whole link expires after 30 minutes if unclaimed.

## What this doesn't do

This only covers *setting the two secrets*. It doesn't remotely create the Mac's Guardian account
from scratch if FileVault needs a new enabled user added (that step still needs someone's existing
FileVault password typed in locally -- see `../GUARDIAN_SETUP.md`), and it doesn't replace the rest
of the one-time Guardian-account split (demoting your own account to Standard is still a manual
step).
