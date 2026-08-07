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

## Server (control plane — not in git)

| Piece | Role |
|--------|------|
| `otterling-github-webhook.service` | Listens `127.0.0.1:9070`; Caddy proxies `/hooks/github` |
| `/var/lib/otterling/ci/webhook_server.py` | HMAC-verify + spawn release |
| `/var/lib/otterling/ci/release.sh` | Pull → AI → sign → publish |
| `/var/lib/otterling/ci/checklist.md` | Pinned deny checklist |
| `/var/lib/otterling/ci/secrets.env` | Keys (mode 600, root only) |
| `/var/lib/otterling/updates/` | Live APK + `manifest.json` |

## One-time setup

### 1. Secrets on the host

```bash
sudo cp /var/lib/otterling/ci/secrets.env.example /var/lib/otterling/ci/secrets.env
sudo nano /var/lib/otterling/ci/secrets.env   # fill everything
sudo chmod 600 /var/lib/otterling/ci/secrets.env
```

Required:

- Release keystore fields + `ANTHROPIC_API_KEY`
- `GITHUB_REPO=tristanmcgregor/Otterling`
- `GITHUB_WEBHOOK_SECRET` — long random string (same as in GitHub webhook)
- `GITHUB_TOKEN` — fine-grained PAT: **Contents: Read**, **Commit statuses: Read and write**

Generate a webhook secret:

```bash
openssl rand -hex 32
```

### 2. Start webhook + reload Caddy

```bash
sudo systemctl enable --now otterling-github-webhook
cd /home/admin/Otterling/filter-server && docker compose up -d updates
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

On the commit page (and on PRs that contain that commit) you see the check. Optional: branch protection can *require* `otterling/release` — usually only useful if you release from PRs; for push-to-`main` the status is informational after the fact.

## Manual release (debug)

```bash
sudo otterling-release --git-sha <commitsha> --before <parentsha>
# or
sudo otterling-release /home/admin/Otterling
```

Logs: `/var/lib/otterling/ci/logs/`

## What git must not do

- `.github/workflows/*` must not sign or publish (advisory only).
- Changing the workflow in the repo cannot bypass this host gate.

## Remaining honest limits

- Root/sudo on this host can still change the pipeline (recovery).
- Keep keystore + tokens only in `secrets.env`, not on a daily account.
- Phone trust: pinned release cert + manifest SHA still required.
