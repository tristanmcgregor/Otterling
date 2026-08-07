# Out-of-band updates (self-lockout)

Goal: the person using Otterling day-to-day (no sudo on this server) cannot publish a
poisoned APK or rewrite `manifest.json`. Only GitHub Actions after an AI `VERDICT: PASS`
can write the update host.

## Server (already set up on this host)

| Piece | Role |
|--------|------|
| `otterling-deploy` | system user, **SFTP only**, chrooted to `/var/lib/otterling` |
| `/var/lib/otterling/updates/` | only that user can write; Caddy serves it read-only |
| Daily accounts (`tritty`, etc.) | **cannot** write updates (permission denied) |
| `admin` | has sudo — treat as the lockbox account, not a daily login |

CI connects as `otterling-deploy` and uploads into `/updates/` (chroot home).

## GitHub secrets (Actions)

Set these repository secrets (Settings → Secrets and variables → Actions):

- `ANTHROPIC_API_KEY`
- `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`
- `RELEASE_CERT_SHA256`
- `UPDATE_HOST` (e.g. `vpn.bartholomew.help`)
- `UPDATE_HOST_SSH_USER` = `otterling-deploy`
- `UPDATE_HOST_SSH_KEY` = contents of the CI private key generated on the server  
  (on this host: `filter-server/.ci-otterling-deploy-key` — **never commit it**)

## GitHub rules so *you* cannot skip AI

If you still own the repo, you can always weaken the workflow. To make “AI only” stick:

1. **Ruleset** (or classic branch protection) on `main`:
   - Require the `AI diff review (deny checklist)` check to pass before merge
   - **Do not allow administrators to bypass** the ruleset
2. Prefer merging only via PRs (no direct push to `main`) under that ruleset
3. Keep release keystore + `ANTHROPIC_API_KEY` **only** in GitHub Actions secrets — not on a laptop you use daily

True “I can never undo this” requires giving away GitHub admin (org/owner you don’t control).
Without that, the hard floor is: **unsigned APKs never install on the phone**, and **your non-sudo
account cannot rewrite `/var/lib/otterling/updates`**.

## Phone trust (unchanged)

`ApprovedUpdateManager` still refuses anything that doesn’t match the pinned release cert
SHA-256 + manifest hash. Compromising the update directory alone is not enough without the
release signing key.
