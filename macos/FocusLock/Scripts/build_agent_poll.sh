#!/bin/bash
# Runs on a timer (LaunchDaemon, ~15 min, see build_agent.launchd.plist.example) on the isolated
# macOS build-agent admin account -- polls the Linux review host for a queued macOS build job
# (written by release.sh on the Linux host whenever an AI-reviewed push touches macos/ paths),
# and if one is pending, does a clean, from-scratch, exact-SHA checkout and hands off to
# build_agent_build_and_upload.sh. See macos/FocusLock/RELEASE.md for the full picture.
#
# Deliberately does its own git clone+checkout into a fresh temp directory every run rather than
# reusing/pulling a persistent local checkout -- the whole point of this pipeline is "build exactly
# what passed AI review," not "build whatever happens to be sitting in a local working tree" (which
# could be stale, or, on this specific account, tampered with).
#
# Config: reads $HOME/.otterling-build-agent/config.env (chmod 600, owned by this account only) --
# see build_agent.env.example in this same directory for the expected keys.
set -euo pipefail

CONFIG_FILE="$HOME/.otterling-build-agent/config.env"
[[ -f "$CONFIG_FILE" ]] || { echo "Missing $CONFIG_FILE -- see build_agent.env.example" >&2; exit 1; }
# shellcheck source=/dev/null
source "$CONFIG_FILE"

: "${OTTERLING_HOST:?set in config.env, e.g. vpn.bartholomew.help}"
: "${MACOS_BUILD_AGENT_TOKEN:?set in config.env}"
: "${GITHUB_REPO:?set in config.env, e.g. tristanmcgregor/Otterling}"

LOG_DIR="$HOME/.otterling-build-agent/logs"
mkdir -p "$LOG_DIR"
LOG="$LOG_DIR/poll-$(date -u +%Y%m%dT%H%M%SZ).log"
exec > >(tee -a "$LOG") 2>&1
echo "==> Polling https://${OTTERLING_HOST}/ci/pending-macos-build"

RESPONSE=$(curl -fsS --max-time 20 \
  -H "Authorization: Bearer ${MACOS_BUILD_AGENT_TOKEN}" \
  "https://${OTTERLING_HOST}/ci/pending-macos-build") || {
  echo "Poll request failed -- will retry next timer tick" >&2
  exit 0
}

GIT_SHA=$(python3 -c 'import json,sys; d=json.loads(sys.argv[1]); print(d.get("gitSha") or "")' "$RESPONSE")
if [[ -z "$GIT_SHA" ]]; then
  echo "No pending macOS build job."
  exit 0
fi
VERSION_CODE=$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["versionCode"])' "$RESPONSE")
VERSION_NAME=$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["versionName"])' "$RESPONSE")
echo "==> Job claimed: gitSha=${GIT_SHA} versionCode=${VERSION_CODE} versionName=${VERSION_NAME}"

WORKDIR=$(mktemp -d "${TMPDIR:-/tmp}/otterling-build-XXXXXX")
cleanup() { rm -rf "$WORKDIR"; }
trap cleanup EXIT

echo "==> Cloning ${GITHUB_REPO}…"
git clone --quiet "https://github.com/${GITHUB_REPO}.git" "$WORKDIR/repo"
git -C "$WORKDIR/repo" checkout --quiet --force --detach "$GIT_SHA"

ACTUAL_SHA=$(git -C "$WORKDIR/repo" rev-parse HEAD)
if [[ "$ACTUAL_SHA" != "$GIT_SHA" ]]; then
  echo "ERROR: checked-out HEAD ($ACTUAL_SHA) doesn't match requested job SHA ($GIT_SHA) -- refusing to build" >&2
  exit 1
fi
echo "==> Verified checkout is exactly ${GIT_SHA}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
"$SCRIPT_DIR/build_agent_build_and_upload.sh" \
  "$WORKDIR/repo" "$GIT_SHA" "$VERSION_CODE" "$VERSION_NAME"
