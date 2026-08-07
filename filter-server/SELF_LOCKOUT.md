# Out-of-band updates (self-lockout)

Goal: the person using Otterling day-to-day cannot publish a poisoned APK, rewrite
`manifest.json`, or **change the release pipeline**. GitHub/git must not be able to
gate or alter signing/publishing.

## Why not GitHub Actions?

If the workflow lives in the repo, anyone who can push (or who owns the GitHub
account) can edit it — skip AI review, drop checks, or publish anything. That is
not self-lockout. Production release therefore lives **only on this server**,
owned by **root**, outside git.

## Server (control plane)

| Piece | Role |
|--------|------|
| `/var/lib/otterling/ci/release.sh` | **Only** path that AI-reviews, signs, and publishes (`sudo otterling-release`) |
| `/var/lib/otterling/ci/checklist.md` | Pinned deny checklist (root-owned; overrides anything in a source tree) |
| `/var/lib/otterling/ci/secrets.env` | API key + keystore paths (mode 600, root only) — copy from `secrets.env.example` |
| `/var/lib/otterling/updates/` | Published APK + `manifest.json`; writable by `otterling-deploy` / root |
| `otterling-deploy` | SFTP-only chroot (legacy/optional); preferred publish is local copy from `release.sh` |
| Daily accounts (`tritty`, etc.) | Cannot write updates or edit `/var/lib/otterling/ci/` |
| `admin` | Has sudo — lockbox account, not a daily login |

### Release command

```bash
sudo otterling-release /path/to/Otterling   # or a .tar.gz under /var/lib/otterling/incoming/
```

Flow: load root checklist → ask Anthropic → require `VERDICT: PASS` → `assembleRelease` → write APK + manifest under `/var/lib/otterling/updates/`.

Non-root cannot run or edit the script (mode `700`, root-owned).

## What git still does

- Source history, PRs, advisory CI noise — fine.
- `.github/workflows/update-review.yml` must **not** sign or publish (intentionally gutted).
- Checklist copy in `scripts/update_review_checklist.md` is documentation; the live gate uses `/var/lib/otterling/ci/checklist.md`.

## Remaining honest limits

- Whoever has **root/sudo** on this host can still change the pipeline (that is intentional for recovery).
- Keep the release keystore and `ANTHROPIC_API_KEY` only under `/var/lib/otterling/ci/secrets*`, not on a daily laptop.
- Phone trust is unchanged: `ApprovedUpdateManager` still requires the pinned release cert SHA-256 + manifest hash.

## Setup checklist (once)

1. `cp /var/lib/otterling/ci/secrets.env.example /var/lib/otterling/ci/secrets.env` (as root)
2. Fill secrets; place `release.jks` under `/var/lib/otterling/ci/secrets/`
3. `chmod 600` secrets; keep ownership `root:root`
4. Run a dry release with a known-good tree when ready
