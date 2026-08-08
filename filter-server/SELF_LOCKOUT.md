# Out-of-band updates (self-lockout)

Goal: edit source in git → push → server AI-reviews → on PASS publish APK → phones update.
Daily accounts cannot change the **pipeline** (only root can). GitHub Actions does not release.

## Flow

```text
You edit Otterling source → git push origin main
        ↓
GitHub webhook → https://vpn.bartholomew.help/hooks/github
        ↓
Host service (root) verifies secret → pulls that commit
        ↓
AI review of last_published_sha..candidate (cumulative — not push parent..HEAD)
  Queued pushes coalesce to the newest tip — one review of last_published..tip,
  not one build per intermediate commit.
        ↓
PASS → sign APK → write /var/lib/otterling/updates/
      → update last_published_sha (+ manifest/index gitSha)
FAIL → refuse publish; last_published_sha unchanged
      (next push still reviews the failed commits until fixed/reverted)
        ↓
Commit status on GitHub: context otterling/release
  pending → success ("AI approved — published vX") or failure ("AI rejected: …")
        ↓
Phones: Settings → App updates → Check for update
```

**Why cumulative:** if commit A fails review and commit B is a tiny unrelated fix, reviewing
only `B^..B` would miss A's changes still in the tree. The gate always diffs everything
since the last *published* SHA (`/var/lib/otterling/updates/last_published_sha`).

First successful publish (2026-08-07): `otterling-0.1.0.apk` at
`https://vpn.bartholomew.help/updates/` with GitHub status `otterling/release` = success
(Details → `/review`).

The live gate always uses the pinned host checklist. A release compares any candidate
`scripts/update_review_checklist.md` to the pinned copy and **rejects weakenings**; only
`STRENGTH: STRONGER` / `EQUIVALENT` (via `strengthen_checklist.sh`) may update the pin.

## Server (control plane — not in git)

| Piece | Role |
|--------|------|
| `otterling-github-webhook.service` | Listens `0.0.0.0:9070`; Caddy proxies `/hooks/github` |
| `/var/lib/otterling/ci/webhook_server.py` | HMAC-verify + spawn release |
| `/var/lib/otterling/ci/release.sh` | Pull → cumulative AI review → sign → publish |
| `/var/lib/otterling/ci/github_status.sh` | Posts `otterling/release` commit status |
| `/var/lib/otterling/ci/deploy_filter_server.sh` | Rsyncs AI-approved `filter-server/` onto live stack |
| `/var/lib/otterling/ci/apps.conf` | Which monorepo components release may publish/deploy |
| `/var/lib/otterling/ci/checklist.md` | Pinned deny checklist (**ratchet**: only strengthen) |
| `/var/lib/otterling/ci/strengthen_checklist.sh` | Root-only promote of a stronger checklist (AI-gated) |
| `/var/lib/otterling/ci/secrets.env` | Keys (mode 600, root only) |
| `/var/lib/otterling/ci/secrets/release.jks` | Release signing keystore |
| `/var/lib/otterling/ci/android-sdk` | Android SDK for `assembleRelease` |
| `/var/lib/otterling/ci/release.lock` | Serializes overlapping releases (`flock`) |
| `/var/lib/otterling/updates/` | Live APK + `manifest.json` + `index.json` |
| `/var/lib/otterling/updates/last_published_sha` | Review base for the next release (updated only on PASS) |
| `/var/lib/otterling/review/` | `status.json`/`history.json` for the `/review` dashboard (see `review-dashboard/README.md`) |

## Host networking notes

This host’s outbound firewall often blocks direct HTTPS. `secrets.env` must include:

```bash
HTTP_PROXY=http://127.0.0.1:3128
HTTPS_PROXY=http://127.0.0.1:3128
NO_PROXY=127.0.0.1,localhost
```

`release.sh` also sets `JAVA_TOOL_OPTIONS` proxy props so Gradle/Java can download.
Anthropic model currently used: `claude-sonnet-4-5-20250929`.

## One-time setup

### 1. Secrets on the host

```bash
sudo cp /var/lib/otterling/ci/secrets.env.example /var/lib/otterling/ci/secrets.env
sudo nano /var/lib/otterling/ci/secrets.env   # fill everything
sudo chmod 600 /var/lib/otterling/ci/secrets.env
```

Required:

- Release keystore fields + `ANTHROPIC_API_KEY`
- `ANDROID_HOME=/var/lib/otterling/ci/android-sdk`
- `GITHUB_REPO=tristanmcgregor/Otterling`
- `GITHUB_WEBHOOK_SECRET` — long random string (same as in GitHub webhook)
- `GITHUB_TOKEN` — fine-grained PAT: **Contents: Read**, **Commit statuses: Read and write**
- HTTP(S)_PROXY as above

Generate a webhook secret:

```bash
openssl rand -hex 32
```

### 2. Start webhook + reload Caddy

```bash
sudo systemctl enable --now otterling-github-webhook
cd /home/admin/Otterling/filter-server && sudo docker compose up -d updates
```

### 3. GitHub webhook

Repo → **Settings → Webhooks → Add webhook**:

| Field | Value |
|--------|--------|
| Payload URL | `https://vpn.bartholomew.help/hooks/github` |
| Content type | `application/json` |
| Secret | same as `GITHUB_WEBHOOK_SECRET` |
| Events | Just the **push** event |
| Active | yes |

Delivery should get `202` / `pong` on ping.

### 4. Seeing approval on GitHub

Each release posts a **commit status** with context **`otterling/release`**:

- yellow/pending while reviewing
- green/success when AI approved and APK published
- red/failure when AI rejected or build failed

**Details** opens `https://vpn.bartholomew.help/review` (the AI review dashboard).

Example success on commit `d8e50cc`: “AI approved — published v0.1.0”.

## Watching a review happen

`https://vpn.bartholomew.help/review` (HTTP Basic Auth -- see `.env.example`) shows the current/
last release's status, the AI's full raw review output, and recent history. It's a static page in
git (`filter-server/review-dashboard/`); the actual data comes from `/var/lib/otterling/review/`,
which `release.sh` writes on every run -- see
[`review-dashboard/README.md`](review-dashboard/README.md) for the JSON shape. If those files
are missing, the page says "no review data yet".

## Manual release (debug)

```bash
sudo otterling-release --git-sha <commitsha>
# Emergency only (overrides last_published_sha; logged loudly):
sudo otterling-release --git-sha <commitsha> --before <review-base-sha>
# Emergency only — skip AI and publish (root-only; reason logged + GitHub status):
sudo otterling-release --git-sha <commitsha> --force-publish 'operator reason'
# or a local tree (still prefers last_published_sha when that commit is in the tree):
sudo otterling-release /home/admin/Otterling
```

Seed / repair the review base (must match the live published APK's commit):

```bash
echo '<full-sha>' | sudo tee /var/lib/otterling/updates/last_published_sha
sudo chmod 644 /var/lib/otterling/updates/last_published_sha
```

If `last_published_sha` is missing, releases **fail closed** (no publish) until seeded.

Logs: `/var/lib/otterling/ci/logs/`

## Phone updates

Each PASS rebuild auto-bumps `versionCode` / `versionName` from the **live**
`/updates/manifest.json` (live+1) before Gradle runs. Phones only install when the
manifest `versionCode` is greater than the installed app, so filter-server-only
commits still produce an installable update. The values in git `app/build.gradle.kts`
may lag; the published manifest is authoritative.


`ApprovedUpdateManager` only installs APKs whose signing cert matches
`BuildConfig.RELEASE_CERT_SHA256` (from the release keystore). A debug/`adb install`
build will refuse these updates until the phone’s installed app was signed with the
same release key (or you provision with a release build first).

## What git must not do

- `.github/workflows/*` must not sign or publish (advisory only).
- Changing the workflow in the repo cannot bypass this host gate.

## Remaining honest limits

- Root/sudo on this host can still change the pipeline (recovery).
- Keep keystore + tokens only in `secrets.env`, not on a daily account.
- Phone trust: pinned release cert + manifest SHA still required.
