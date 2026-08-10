# Handoff: real proxy CA cert flow

Context for continuing this task on the VPS (`ssh.bartholomew.help` / `vpn.bartholomew.help`).
Started from a Mac-side Claude Code session; picking up here so nothing gets re-derived.

## Task

Replace the placeholder mitmproxy CA cert bundled with the Android app so HTTPS content
filtering actually works on a real device, instead of failing TLS validation for every site.

## Why this matters

- `app/src/main/res/raw/otterling_proxy_ca.pem` is currently a throwaway self-signed cert
  generated with `openssl`, **not** by a real mitmproxy instance (see
  `app/src/main/java/app/otterling/content/CaCertInstaller.kt:18` and
  `filter-server/ca/README.md`).
- `CaCertInstaller` installs this cert device-wide via Device Owner's
  `DevicePolicyManager.installCaCert` so mitmproxy's per-host leaf certs are trusted.
- Until the *real* CA (matching whatever mitmproxy instance is actually intercepting traffic) is
  in that resource file, every HTTPS site fails TLS validation as soon as "Filter proxy" is
  turned on in the app's Content Filter VPN settings.

## Key constraint: the CA is deployment-specific, not portable

Per `filter-server/docker-compose.yml`'s comment and `filter-server/ca/README.md`: mitmproxy
generates a **fresh, unique** CA (private key + cert) the first time it starts against an empty
`./mitm-ca/` directory. There is no way to pre-generate one and share it across deployments — the
cert baked into the Android app must come from *the exact mitmproxy instance the phone will
actually talk to* (`PROXY_HOST`/`PROXY_PORT` in Content Filter VPN settings, currently
`vpn.bartholomew.help:8090` per the app's placeholder text).

## Self-lockout note (from `AGENTS.md`)

This repo deliberately keeps the release/update pipeline root-only on the update host, not
reachable via GitHub Actions or (by extension) an agent acting on the Mac side. I did **not** SSH
into `vpn.bartholomew.help` / `ssh.bartholomew.help` from the Mac session to go looking for an
existing CA — that was left for you to do directly, which is presumably why you're picking this
up on the VPS itself.

## Open question (unresolved when this was handed off)

Is `filter-server` already deployed and running on the VPS with mitmproxy having already
generated a real CA in `./mitm-ca/`, or does it need to be brought up fresh? This determines the
first step:

- **Already running** → just extract the existing cert (step 1 below skips straight to `cp`).
- **Not running yet** → `docker compose up -d mitmproxy` first, wait for it to generate
  `mitm-ca/mitmproxy-ca-cert.pem` on first start, *then* extract.

## Steps (from `filter-server/ca/README.md`, run these ON the VPS, in `filter-server/`)

```bash
# Only if mitmproxy isn't already running:
docker compose up -d mitmproxy
# wait a few seconds for it to generate its CA on first start

# Extract the public cert (the private key stays in ./mitm-ca/, gitignored, never leaves the host):
cp mitm-ca/mitmproxy-ca-cert.pem ca/otterling-proxy-ca.pem

# Copy into the Android app's resource location (note the filename change: dashes -> underscores,
# Android resource names must be lowercase_with_underscores):
cp ca/otterling-proxy-ca.pem ../app/src/main/res/raw/otterling_proxy_ca.pem
```

Both `filter-server/ca/*.pem` and `filter-server/mitm-ca/` are gitignored — only the Android
resource copy (`app/src/main/res/raw/otterling_proxy_ca.pem`) is meant to be committed, since
that's a public cert baked into the app on purpose. Double check `git status` shows only that one
file changed before committing, and that nothing under `filter-server/ca/` or `filter-server/mitm-ca/`
got staged.

## After swapping the cert

- Rebuild the app (`./gradlew assembleDebug` or via the normal release pipeline) so the new
  resource is baked in.
- README's Phase 7 verification checklist (never yet run against real hardware, per its own
  caveat) is the natural next test once this is in place: confirm adult domains NXDOMAIN through
  the cloud filter, ordinary HTTPS sites load cleanly (proof the CA is trusted), and the
  local-blocklist fallback still works if the cloud filter is briefly stopped.

## Broader context (from the same session, for reference)

This CA-cert fix was picked as item #1 off a larger gap list for making the Android app
"complete." The rest, not yet started:

1. Physical-hardware verification never run (Phase 7 checklist above; also the bootloader
   recovery-menu factory-reset path in Phase 3).
2. Unmerged worktree `worktree-macos-admin-hardening` (in `.claude/worktrees/` on the Mac) is 11
   commits ahead of `main`/1 behind, includes one Android fix ("SMS confirmation, DoT bypass,
   exemption cap") not yet in `main`.
3. Thin test coverage: 5 unit test files vs ~98 Kotlin source files; core enforcement managers
   (restriction reapply/drift, package suspension, VPN filter, alert reporting) have no tests.
4. No crash reporting wired into this line of the app at all.

There's also a separate, diverged copy of this app (`Otterling - Production (DO NOT TOUCH)/`,
different local git remote pointed at an old `TrittyBlocker` path) that pivoted to a Knox-only
architecture + Firebase-based alerts a while back and was never reconciled with `main`. Out of
scope for this task, flagged here only so it doesn't get rediscovered as if new.
