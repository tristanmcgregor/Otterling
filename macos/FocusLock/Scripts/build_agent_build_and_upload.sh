#!/bin/bash
# Builds, signs, (optionally) notarizes, zips, and uploads a macOS release for one build-agent job
# -- called by build_agent_poll.sh with an already-checked-out-to-the-exact-SHA repo. Never invoked
# directly except for manual testing (see RELEASE.md).
#
# Usage: build_agent_build_and_upload.sh <repo_path> <git_sha> <version_code> <version_name>
#
# Security note: this script computes a LOCAL sha256 of the zip purely for its own log output --
# it is never sent to, or trusted by, the server. The Linux host independently recomputes the
# SHA-256 from the raw bytes it actually receives (see webhook_server.py's
# /ci/macos-build-result handler) before ever attesting to anything. That independent recomputation
# is the whole point of this architecture: a compromised build agent cannot get a trusted
# attestation for bytes other than what it actually uploaded, even if it lies about its own hash.
set -euo pipefail

REPO_PATH="${1:?repo path required}"
GIT_SHA="${2:?git sha required}"
VERSION_CODE="${3:?version code required}"
VERSION_NAME="${4:?version name required}"

CONFIG_FILE="$HOME/.otterling-build-agent/config.env"
[[ -f "$CONFIG_FILE" ]] || { echo "Missing $CONFIG_FILE -- see build_agent.env.example" >&2; exit 1; }
# shellcheck source=/dev/null
source "$CONFIG_FILE"

: "${OTTERLING_HOST:?set in config.env}"
: "${MACOS_BUILD_AGENT_TOKEN:?set in config.env}"
: "${SIGNING_IDENTITY:?set in config.env -- e.g. 'Developer ID Application: Name (TEAMID)' or the free 'Apple Development: Name (TEAMID)', see RELEASE.md}"
: "${BUILD_KEYCHAIN_PATH:?set in config.env}"
: "${BUILD_KEYCHAIN_PASSWORD_FILE:?set in config.env, chmod 600 file containing just the password}"
# NOTARY_PROFILE is optional -- notarization is skipped entirely if unset (see below).

FOCUSLOCK_DIR="$REPO_PATH/macos/FocusLock"
[[ -d "$FOCUSLOCK_DIR" ]] || { echo "No macos/FocusLock in checked-out repo" >&2; exit 1; }
cd "$FOCUSLOCK_DIR"

echo "==> Unlocking build keychain"
security unlock-keychain -p "$(cat "$BUILD_KEYCHAIN_PASSWORD_FILE")" "$BUILD_KEYCHAIN_PATH"

echo "==> Building + signing (Scripts/build_app.sh --keychain)"
./Scripts/build_app.sh --keychain "$BUILD_KEYCHAIN_PATH" "$SIGNING_IDENTITY"

APP_PATH="/Applications/Otterling.app"
CODESIGN_INFO=$(codesign -dv --verbose=4 "$APP_PATH" 2>&1)
TEAM_ID=$(echo "$CODESIGN_INFO" | sed -n 's/^TeamIdentifier=//p')
[[ -n "$TEAM_ID" && "$TEAM_ID" != "not set" ]] || { echo "Built app has no Team Identifier" >&2; exit 1; }

PINNED_TEAM_ID=$(sed -n 's/.*pinnedUpdateTeamID = "\(.*\)".*/\1/p' Sources/FocusLockShared/Constants.swift | head -1)
if [[ "$TEAM_ID" != "$PINNED_TEAM_ID" ]]; then
  echo "ERROR: built Team ID ($TEAM_ID) doesn't match pinnedUpdateTeamID ($PINNED_TEAM_ID) -- no" >&2
  echo "installed copy of Otterling would ever accept this as an update. Refusing to upload." >&2
  exit 1
fi
echo "==> Team ID confirmed: $TEAM_ID"

if [[ -n "${NOTARY_PROFILE:-}" ]]; then
  echo "==> Notarizing (profile: $NOTARY_PROFILE)…"
  NOTARY_ZIP=$(mktemp "${TMPDIR:-/tmp}/otterling-notary-XXXXXX.zip")
  ditto -c -k --sequesterRsrc --keepParent "$APP_PATH" "$NOTARY_ZIP"
  xcrun notarytool submit "$NOTARY_ZIP" --keychain-profile "$NOTARY_PROFILE" --wait
  xcrun stapler staple "$APP_PATH"
  rm -f "$NOTARY_ZIP"
else
  echo "==> NOTARY_PROFILE not set in config.env -- skipping notarization"
fi

OUT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/otterling-release-XXXXXX")"
ZIP_NAME="otterling-macos-${VERSION_NAME}.zip"
ZIP_PATH="$OUT_DIR/$ZIP_NAME"
echo "==> Zipping -> $ZIP_PATH"
ditto -c -k --sequesterRsrc --keepParent "$APP_PATH" "$ZIP_PATH"

LOCAL_SHA=$(shasum -a 256 "$ZIP_PATH" | awk '{print $1}')
echo "    Local SHA-256 (informational only, not trusted by the server): $LOCAL_SHA"

echo "==> Uploading to https://${OTTERLING_HOST}/ci/macos-build-result"
HTTP_CODE=$(curl -sS --max-time 300 -o "$OUT_DIR/response.json" -w '%{http_code}' \
  -X POST \
  -H "Authorization: Bearer ${MACOS_BUILD_AGENT_TOKEN}" \
  -H "Content-Type: application/octet-stream" \
  -H "X-Git-Sha: ${GIT_SHA}" \
  -H "X-Version-Code: ${VERSION_CODE}" \
  -H "X-Version-Name: ${VERSION_NAME}" \
  -H "X-Codesign-Team-Id: ${TEAM_ID}" \
  --data-binary "@${ZIP_PATH}" \
  "https://${OTTERLING_HOST}/ci/macos-build-result")

echo "==> Upload response ($HTTP_CODE):"
cat "$OUT_DIR/response.json" 2>/dev/null || true
echo

rm -rf "$OUT_DIR"

if [[ "$HTTP_CODE" -lt 200 || "$HTTP_CODE" -ge 300 ]]; then
  echo "Upload failed (HTTP $HTTP_CODE) -- job stays pending on the server for retry" >&2
  exit 1
fi
echo "==> Done."
