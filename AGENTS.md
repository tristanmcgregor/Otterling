# Otterling — agent notes

Parental-control monorepo: Android `app/`, cloud filter `filter-server/`, macOS `macos/FocusLock/`.

## Releases (self-lockout)

- Phones only install APKs from `https://vpn.bartholomew.help/updates/` after host AI review.
- Pipeline lives on the update host as **root**: `/var/lib/otterling/ci/release.sh` + pinned
  `/var/lib/otterling/ci/checklist.md` — not GitHub Actions.
- Docs: `filter-server/SELF_LOCKOUT.md`, `scripts/update_review_checklist.md`.

## Commit messages for the release AI

Put intent in the commit **body** as `AI-REVIEW:` lines so the gate does not invent FAILS:

```text
AI-REVIEW: Removing unused Private DNS settings UI; VPN cloud filter is the live path.
AI-REVIEW: Enforcement managers unchanged; AppSuspensionManager still used by ProtectionController.
```

The model sees `git log` for `last_published..candidate`. Claims must match the DIFF.

## Protections vs UI

- Prefer keeping runtime enforcement (VPN, Device Owner, mitm, suspension workers).
- Unused / superseded **settings UI** may be removed when a supported path still provides the
  protection (see checklist §7 “Config UI vs enforcement”).
- Do not gut fail-closed proxy, VPN lockdown, or release cert pinning without an explicit
  Guardian/root recovery path.

## Android updates

- In-app updates: release-signed builds only (`RELEASE_CERT_SHA256`).
- Host auto-bumps `versionCode` only when the review range touches app/Gradle paths.
