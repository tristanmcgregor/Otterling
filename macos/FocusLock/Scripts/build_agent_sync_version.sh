#!/bin/bash
# Commits the actually-live published versionCode/versionName back to origin/main -- called by
# build_agent_poll.sh after every build attempt, success or failure (see that script).
#
# build_agent_build_and_upload.sh pins the version into a THROWAWAY clone purely so the build it
# uploads matches what the review host decided; it never touches the real GitHub repo. Without this
# step, Sources/FocusLockShared/Constants.swift and Scripts/version.txt in git silently fall behind
# every single automated publish -- exactly the drift that required manually re-syncing them by
# hand after the fact (see commits 7555c01, c5bd389, 874b1c9). This script closes that loop so it
# stops recurring.
#
# Usage: build_agent_sync_version.sh
#
# Deliberately fetches the LIVE manifest at /updates/macos-manifest.json rather than trusting the
# versionCode/versionName the calling job originally intended to publish: a slow multi-MB upload
# over a home connection can make curl's own response-read time out (client-side failure) even
# though the server had already finished processing and publishing by then -- confirmed live, this
# happened for build 9 and silently left git one version behind a build that was actually published
# successfully. Syncing to whatever the manifest ACTUALLY says is live is correct in every case:
# nothing to do if the upload really did fail (manifest didn't change), and correctly catches up if
# it secretly succeeded despite the client-side error.
#
# Deliberately non-fatal to the caller on failure (see build_agent_poll.sh) -- by the time this
# runs the build attempt is already over one way or another, so a git-side hiccup here shouldn't
# be conflated with a build failure. It just means a manual sync commit (like 874b1c9) is needed
# again; this script logs loudly when that's the case.
#
# The commit it makes carries an "Otterling-Build-Agent-Sync: true" trailer, which release.sh
# checks for before queuing a new macOS build -- without that, this commit (which touches macos/
# paths) would itself trigger another build, whose own sync commit triggers another, forever.
# Confirmed live: this looped through versionCode 6->7->8->9, unattended, before being killed by
# hand. Do not drop the trailer without also fixing release.sh's queuing check.
set -euo pipefail

CONFIG_FILE="$HOME/.otterling-build-agent/config.env"
[[ -f "$CONFIG_FILE" ]] || { echo "Missing $CONFIG_FILE -- see build_agent.env.example" >&2; exit 1; }
# shellcheck source=/dev/null
source "$CONFIG_FILE"

# See build_agent_proxy_env.sh's own doc comment: applies this account's already-provisioned
# proxy env vars (if this Mac has proxy/firewall force-through enabled on itself) so the
# manifest-fetch/clone/push calls below aren't silently dropped by this Mac's own enforcement.
# shellcheck source=/dev/null
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/build_agent_proxy_env.sh"

: "${OTTERLING_HOST:?set in config.env}"
: "${GITHUB_REPO:?set in config.env}"

echo "==> Fetching live manifest from https://${OTTERLING_HOST}/updates/macos-manifest.json..."
MANIFEST_JSON=$(curl -fsS --max-time 20 "https://${OTTERLING_HOST}/updates/macos-manifest.json") \
  || { echo "ERROR: couldn't fetch live manifest -- nothing to sync against, giving up." >&2; exit 1; }
VERSION_CODE=$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["versionCode"])' "$MANIFEST_JSON") \
  || { echo "ERROR: live manifest missing/invalid versionCode" >&2; exit 1; }
VERSION_NAME=$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["versionName"])' "$MANIFEST_JSON") \
  || { echo "ERROR: live manifest missing/invalid versionName" >&2; exit 1; }
echo "==> Live manifest says versionCode=${VERSION_CODE} versionName=${VERSION_NAME}"
GITHUB_CLONE_TOKEN="${GITHUB_CLONE_TOKEN:-}"
if [[ -z "$GITHUB_CLONE_TOKEN" ]]; then
  echo "GITHUB_CLONE_TOKEN not set -- can't push a version-bump commit to a private repo" >&2
  exit 1
fi
CLONE_URL="https://x-access-token:${GITHUB_CLONE_TOKEN}@github.com/${GITHUB_REPO}.git"

WORKDIR=$(mktemp -d "${TMPDIR:-/tmp}/otterling-versionsync-XXXXXX")
cleanup() { rm -rf "$WORKDIR"; }
trap cleanup EXIT

echo "==> Cloning ${GITHUB_REPO} (main) to sync version..."
if ! git clone --quiet --depth 1 --branch main "$CLONE_URL" "$WORKDIR/repo" \
    2> >(sed -E 's#x-access-token:[^@]*@#x-access-token:REDACTED@#g' >&2); then
  echo "ERROR: clone failed -- GITHUB_CLONE_TOKEN likely needs Contents: Read and write" >&2
  echo "  (a read-only PAT can build but can't push this commit)." >&2
  exit 1
fi

REPO="$WORKDIR/repo"
CONSTANTS_FILE="$REPO/macos/FocusLock/Sources/FocusLockShared/Constants.swift"
VERSION_FILE="$REPO/macos/FocusLock/Scripts/version.txt"

CURRENT_VERSION_CODE="$(sed -n 's/.*appVersionCode = \([0-9]*\).*/\1/p' "$CONSTANTS_FILE" | head -1)"
CURRENT_VERSION_NAME="$(cat "$VERSION_FILE" 2>/dev/null || echo "")"

if [[ "$CURRENT_VERSION_CODE" == "$VERSION_CODE" && "$CURRENT_VERSION_NAME" == "$VERSION_NAME" ]]; then
  echo "==> main already at appVersionCode=$VERSION_CODE / version.txt=$VERSION_NAME -- nothing to sync."
  exit 0
fi

echo "==> Bumping committed version: $CURRENT_VERSION_CODE/$CURRENT_VERSION_NAME -> $VERSION_CODE/$VERSION_NAME"
sed -i '' "s/appVersionCode = [0-9]*/appVersionCode = ${VERSION_CODE}/" "$CONSTANTS_FILE"
printf '%s\n' "$VERSION_NAME" > "$VERSION_FILE"

git -C "$REPO" add \
  macos/FocusLock/Sources/FocusLockShared/Constants.swift \
  macos/FocusLock/Scripts/version.txt

# Scoped to this commit only (-c, not --global) -- doesn't touch this account's ambient git config.
COMMIT_MSG="Sync macOS appVersionCode/version.txt to published build ${VERSION_CODE} (${VERSION_NAME})

Automated by build_agent_sync_version.sh after a successful publish, so this stops needing a
manual reconciliation commit (see 874b1c9) every time the build agent ships a release.

Otterling-Build-Agent-Sync: true"

git -C "$REPO" \
  -c user.name="Otterling Build Agent" \
  -c user.email="build-agent@otterling.app" \
  commit --quiet -m "$COMMIT_MSG"

echo "==> Pushing to origin/main..."
ATTEMPTS=0
until git -C "$REPO" push --quiet origin HEAD:main \
    2> >(sed -E 's#x-access-token:[^@]*@#x-access-token:REDACTED@#g' >&2); do
  ATTEMPTS=$((ATTEMPTS + 1))
  if [[ "$ATTEMPTS" -ge 3 ]]; then
    echo "ERROR: push failed after $ATTEMPTS attempts (main likely moved concurrently)." >&2
    echo "  Manual sync commit needed -- appVersionCode/version.txt in git still lag the" >&2
    echo "  published build ${VERSION_CODE} (${VERSION_NAME}). See 874b1c9 for precedent." >&2
    exit 1
  fi
  echo "    Push rejected -- rebasing onto the new origin/main and retrying ($ATTEMPTS/3)..."
  git -C "$REPO" fetch --quiet origin main
  git -C "$REPO" rebase --quiet origin/main
done

echo "==> Done. main now has appVersionCode=${VERSION_CODE} / version.txt=${VERSION_NAME}."
