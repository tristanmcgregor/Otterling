# AI review dashboard

A read-only status page for the server-side release pipeline described in
[`../SELF_LOCKOUT.md`](../SELF_LOCKOUT.md) — live AI reasoning (token-streamed), the cumulative
diff under review, changed files, phase progress, and recent release history. Served by the
`updates` Caddy container at `https://<UPDATE_HOST>/review` (HTTP Basic Auth — see `.env.example`).

**This directory (`review-dashboard/`) is the only part that's in git.** Status data comes from
`/var/lib/otterling/review/` on the update host — root-owned, written by `release.sh` (outside
this repo; see `SELF_LOCKOUT.md`). This doc is the integration contract.

## Files `release.sh` writes

Directory: `/var/lib/otterling/review/` (`chmod 755`; files `chmod 644` so the bind-mounted Caddy
container can read them).

| File | Purpose |
|------|---------|
| `status.json` | Current/most recent run (overwritten continuously while streaming) |
| `history.json` | Past runs, newest first, capped ~50 (AI output truncated in history) |
| `current.diff` | Cumulative `git diff last_published..candidate` for this run |
| `current.stat` | `git diff --stat` text |
| `current.files` | `git diff --name-only` lines |

### `status.json` shape

```json
{
  "state": "reviewing",
  "phase": "ai_review",
  "commit_sha": "d8e50cc1234...",
  "commit_message": "Fix update-check TLS failures through the filter VPN.",
  "review_base": "05e05c5bcef1...",
  "review_range": "05e05c5bcef1..d8e50cc1234",
  "started_at": "2026-08-08T09:12:03Z",
  "finished_at": null,
  "verdict": null,
  "verdict_reason": null,
  "ai_output": "partial or full model text…",
  "ai_streaming": true,
  "published_version": null,
  "files_changed": ["app/.../Foo.kt", "filter-server/Caddyfile"],
  "diff_stat": " ...\n 5 files changed, 40 insertions(+), 12 deletions(-)\n",
  "diff_url": "/review-data/current.diff",
  "model": "claude-sonnet-4-5-20250929",
  "updated_at": "2026-08-08T09:12:41Z"
}
```

`state` is `"reviewing"` or `"done"`. `phase` is one of:

- `preparing` — checkout / diff assembly
- `ai_review` — Anthropic stream in progress (`ai_streaming: true` while tokens arrive)
- `building` — Gradle signed release
- `publishing` — APK + filter-server deploy
- `done` / `failed` — terminal

While `ai_streaming` is true, the dashboard polls every ~1s and shows a live caret. `ai_output`
should be updated as tokens arrive (not only at the end).

### `history.json`

Same objects as `status.json` (newest first). Prefer truncating huge `ai_output` in history.

## Host wiring

`release.sh` should:

1. Write `phase=preparing` + copy `review.{diff,stat,files}` → `current.*` before the model runs.
2. Call `anthropic_review_stream.py` (streams Messages API → `review.txt` + live `status.json`).
3. Advance `phase` through `building` / `publishing`, then `state=done` with verdict.

See the installed helpers on the update host:

- `/var/lib/otterling/ci/release.sh`
- `/var/lib/otterling/ci/anthropic_review_stream.py`

## Deploying the page

```bash
cd filter-server
# REVIEW_DASHBOARD_PASSWORD_HASH already in .env
docker compose up -d updates
```

The Compose mount `./review-dashboard:/srv/review-dashboard:ro` means HTML edits here are live
after the container can read them (no image rebuild).
