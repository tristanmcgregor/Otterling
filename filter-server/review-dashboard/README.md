# AI review dashboard

A read-only status page for the server-side release pipeline described in
[`../SELF_LOCKOUT.md`](../SELF_LOCKOUT.md) -- shows what the AI reviewed, its verdict, its full
reasoning output, and recent release history. Served by the `updates` Caddy container at
`https://<UPDATE_HOST>/review` (HTTP Basic Auth protected -- see `.env.example`).

**This directory (`review-dashboard/`) is the only part that's in git.** The actual status data
comes from `/var/lib/otterling/review/` on the update host -- root-owned, written by
`release.sh`, which itself lives outside this repo (see `SELF_LOCKOUT.md`'s "Server (control
plane -- not in git)" table). This doc is the integration contract between the two: what
`release.sh` must write, in what shape, so this page can read it.

## What you need to do on the server

`release.sh` on the update host writes two files to `/var/lib/otterling/review/` (create the
directory if it doesn't exist; `chmod 755` it and `chmod 644` the files so the read-only
bind-mounted Caddy container can read them):

- **`status.json`** -- the current/most recent run only, overwritten each time.
- **`history.json`** -- a JSON array of past runs, most recent first, capped at some reasonable
  length (50 is plenty).

### `status.json` shape

```json
{
  "state": "reviewing",
  "commit_sha": "d8e50cc1234...",
  "commit_message": "Fix update-check TLS failures through the filter VPN.",
  "started_at": "2026-08-08T09:12:03Z",
  "finished_at": null,
  "verdict": null,
  "verdict_reason": null,
  "ai_output": null,
  "published_version": null
}
```

`state` is one of `"reviewing"` (in progress) or `"done"` (finished, check `verdict`). Once
finished:

```json
{
  "state": "done",
  "commit_sha": "d8e50cc1234...",
  "commit_message": "Fix update-check TLS failures through the filter VPN.",
  "started_at": "2026-08-08T09:12:03Z",
  "finished_at": "2026-08-08T09:14:47Z",
  "verdict": "PASS",
  "verdict_reason": "AI approved -- published v0.1.4",
  "ai_output": "<the AI's full raw review output/reasoning text>",
  "published_version": "0.1.4"
}
```

`ai_output` should be the AI's complete response text (whatever `release.sh` already captures to
produce the `VERDICT: PASS`/`VERDICT: FAIL: ...` line for the checklist gate) -- the whole point
of this dashboard is seeing *why*, not just pass/fail. `published_version` is `null` on a FAIL.

### `history.json` shape

Same objects as `status.json`, in a JSON array, newest first:

```json
[
  { "state": "done", "commit_sha": "d8e50cc...", "verdict": "PASS", ... },
  { "state": "done", "commit_sha": "a1b2c3d...", "verdict": "FAIL", ... }
]
```

## Reference snippet for `release.sh`

Not a file this repo ships or runs -- just a worked example for wiring this into your actual
`release.sh`. Adjust variable names to whatever it already has in scope.

```bash
REVIEW_DIR=/var/lib/otterling/review
mkdir -p "$REVIEW_DIR"
chmod 755 "$REVIEW_DIR"

write_status() {
  # Atomic write (temp file + rename) so the dashboard never reads a half-written file mid-update.
  jq -n \
    --arg state "$1" --arg sha "$GIT_SHA" --arg msg "$COMMIT_MESSAGE" \
    --arg started "$STARTED_AT" --arg finished "${FINISHED_AT:-}" \
    --arg verdict "${VERDICT:-}" --arg reason "${VERDICT_REASON:-}" \
    --arg output "${AI_OUTPUT:-}" --arg version "${PUBLISHED_VERSION:-}" \
    '{
      state: $state,
      commit_sha: $sha,
      commit_message: $msg,
      started_at: $started,
      finished_at: ($finished | select(. != "") // null),
      verdict: ($verdict | select(. != "") // null),
      verdict_reason: ($reason | select(. != "") // null),
      ai_output: ($output | select(. != "") // null),
      published_version: ($version | select(. != "") // null)
    }' > "$REVIEW_DIR/status.json.tmp"
  mv "$REVIEW_DIR/status.json.tmp" "$REVIEW_DIR/status.json"
  chmod 644 "$REVIEW_DIR/status.json"
}

# Call at the very start of a release run, before the AI review begins:
STARTED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
write_status "reviewing"

# ... existing pull + AI review + sign + publish logic runs here, setting
# VERDICT, VERDICT_REASON, AI_OUTPUT, and PUBLISHED_VERSION (on PASS) ...

# Call once the run is fully done (PASS or FAIL):
FINISHED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
write_status "done"

# Prepend this run onto history.json, capped at 50 entries.
[ -f "$REVIEW_DIR/history.json" ] || echo '[]' > "$REVIEW_DIR/history.json"
jq -s '[.[0]] + .[1] | .[0:50]' "$REVIEW_DIR/status.json" "$REVIEW_DIR/history.json" \
  > "$REVIEW_DIR/history.json.tmp"
mv "$REVIEW_DIR/history.json.tmp" "$REVIEW_DIR/history.json"
chmod 644 "$REVIEW_DIR/history.json"
```

## Deploying this for the first time

```bash
cd filter-server
docker run --rm caddy:alpine caddy hash-password --plaintext 'your-password-here'
# paste the printed hash into .env as REVIEW_DASHBOARD_PASSWORD_HASH
docker compose up -d updates
```

If `status.json` is missing, the dashboard shows "No review data yet" (no release has written
status yet, or the host `release.sh` predates the dashboard wiring).
