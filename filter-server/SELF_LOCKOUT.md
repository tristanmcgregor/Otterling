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
AI review vs /var/lib/otterling/ci/checklist.md
        ↓
PASS → sign APK → write /var/lib/otterling/updates/
FAIL → refuse publish
        ↓
Commit status on GitHub: context otterling/release
  pending → success ("AI approved — published vX") or failure ("AI rejected: …")
        ↓
Phones: Settings → App updates → Check for update
```

First successful publish (2026-08-07): `otterling-0.1.0.apk` at
`https://vpn.bartholomew.help/updates/` with GitHub status `otterling/release` = success.

A PASS now also **redeploys `filter-server`** on this host (compose + mux) from the same
reviewed tree, and writes `/updates/index.json` listing android + filter-server (+ macos skip).

## Server (control plane — not in git)

| Piece | Role |
|--------|------|
| `otterling-github-webhook.service` | Listens `0.0.0.0:9070`; Caddy proxies `/hooks/github` |
| `/var/lib/otterling/ci/webhook_server.py` | HMAC-verify + spawn release |
| `/var/lib/otterling/ci/release.sh` | Pull → AI → sign → publish |
| `/var/lib/otterling/ci/github_status.sh` | Posts `otterling/release` commit status |
| `/var/lib/otterling/ci/deploy_filter_server.sh` | Rsyncs AI-approved `filter-server/` onto live stack |
| `/var/lib/otterling/ci/apps.conf` | Which monorepo components release may publish/deploy |
| `/var/lib/otterling/ci/checklist.md` | Pinned deny checklist |
| `/var/lib/otterling/ci/secrets.env` | Keys (mode 600, root only) |
| `/var/lib/otterling/ci/secrets/release.jks` | Release signing keystore |
| `/var/lib/otterling/ci/android-sdk` | Android SDK for `assembleRelease` |
| `/var/lib/otterling/updates/` | Live APK + `manifest.json` |

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

Example success on commit `d8e50cc`: “AI approved — published v0.1.0”.

## Manual release (debug)

```bash
sudo otterling-release --git-sha <commitsha> --before <parentsha>
# or
sudo otterling-release /home/admin/Otterling
```

Logs: `/var/lib/otterling/ci/logs/`

## Phone updates

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
