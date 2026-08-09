#!/bin/bash
# Builds the macos-manifest.json + zip for a real Otterling release, from the app already built at
# /Applications/Otterling.app (run build_app.sh with your real Developer ID signing identity first
# -- an ad-hoc or "Apple Development" identity has no Team Identifier, and this script refuses to
# publish one; see RELEASE.md).
#
# This is a *local* helper, not part of the AI-gated CI pipeline: the existing release host
# (filter-server/SELF_LOCKOUT.md) is Linux-only and can't build/sign a macOS .app, so there's no
# automated build-on-push path for this yet -- see RELEASE.md for the full picture and what
# building that out would take. This script just does the local packaging + verification part; you
# still copy its output to the server yourself (scp/rsync -- whatever you already use to manage
# that host).
#
# Usage: Scripts/publish_release.sh <versionCode> <versionName>
#   e.g. Scripts/publish_release.sh 2 "0.2"

set -euo pipefail

VERSION_CODE="${1:-}"
VERSION_NAME="${2:-}"
if [ -z "$VERSION_CODE" ] || [ -z "$VERSION_NAME" ]; then
  echo "Usage: $0 <versionCode> <versionName>"
  echo "  versionCode must be a plain integer, strictly greater than the last published one,"
  echo "  and must match FocusLockConstants.appVersionCode in the build you're publishing."
  exit 1
fi
if ! [[ "$VERSION_CODE" =~ ^[0-9]+$ ]]; then
  echo "versionCode must be a plain integer (got: $VERSION_CODE)" >&2
  exit 1
fi

APP_PATH="/Applications/Otterling.app"
if [ ! -d "$APP_PATH" ]; then
  echo "No app at $APP_PATH -- run Scripts/build_app.sh first." >&2
  exit 1
fi

echo "==> Verifying $APP_PATH is signed with a Developer ID (not ad-hoc or Apple Development)"
CODESIGN_INFO=$(codesign -dv --verbose=4 "$APP_PATH" 2>&1)
TEAM_ID=$(echo "$CODESIGN_INFO" | sed -n 's/^TeamIdentifier=//p')
SIGNING_AUTHORITY=$(echo "$CODESIGN_INFO" | sed -n 's/^Authority=//p' | head -1)

if [ -z "$TEAM_ID" ] || [ "$TEAM_ID" = "not set" ]; then
  echo "ERROR: $APP_PATH has no Team Identifier -- it's signed ad-hoc (no identity at all)." >&2
  echo "Sign with a real Developer ID Application identity to publish a release." >&2
  exit 1
fi
# Team ID alone isn't enough -- a free "Apple Development" identity has a perfectly stable Team ID
# too (Xcode's free "Personal Team"), so the check above wouldn't catch it. That certificate class
# is for running your own build on your own registered Mac, not for software downloaded and
# launched elsewhere (exactly what an update is) -- Gatekeeper treats it accordingly regardless of
# what this project's own SHA-256/Team-ID verification says. See RELEASE.md.
case "$SIGNING_AUTHORITY" in
  "Developer ID Application:"*|"3rd Party Mac Developer Application:"*) ;;
  *)
    echo "ERROR: $APP_PATH is signed with '$SIGNING_AUTHORITY', not a Developer ID Application" >&2
    echo "identity. A free 'Apple Development' certificate (or Mac App Store distribution cert)" >&2
    echo "is not meant for software downloaded and launched outside Xcode/the App Store -- see" >&2
    echo "RELEASE.md's one-time setup section for why this needs a paid Developer Program" >&2
    echo "membership. Rebuild with: ./Scripts/build_app.sh \"Developer ID Application: ...\"" >&2
    exit 1
    ;;
esac
echo "    Team Identifier: $TEAM_ID"
echo "    Signing authority: $SIGNING_AUTHORITY"

CONSTANTS_FILE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/Sources/FocusLockShared/Constants.swift"
PINNED_TEAM_ID=$(sed -n 's/.*pinnedUpdateTeamID = "\(.*\)".*/\1/p' "$CONSTANTS_FILE" | head -1)
if [ "$TEAM_ID" != "$PINNED_TEAM_ID" ]; then
  echo "ERROR: this build's Team Identifier ($TEAM_ID) doesn't match" >&2
  echo "FocusLockConstants.pinnedUpdateTeamID (\"$PINNED_TEAM_ID\") in $CONSTANTS_FILE." >&2
  echo "Every installed copy of Otterling checks a downloaded update against ITS OWN pinned" >&2
  echo "value, not whatever this script prints -- if these don't match, no running install" >&2
  echo "will ever accept this build as an update. Fix the constant (or the signing identity)" >&2
  echo "and rebuild before publishing." >&2
  exit 1
fi

OUT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/.release"
mkdir -p "$OUT_DIR"
ZIP_NAME="otterling-macos-${VERSION_NAME}.zip"
ZIP_PATH="$OUT_DIR/$ZIP_NAME"
MANIFEST_PATH="$OUT_DIR/macos-manifest.json"

echo "==> Zipping $APP_PATH -> $ZIP_PATH"
rm -f "$ZIP_PATH"
# ditto (not plain zip -r) -- preserves resource forks/extended attributes/code-signing metadata
# the way Apple's own notarization/distribution tooling expects.
ditto -c -k --sequesterRsrc --keepParent "$APP_PATH" "$ZIP_PATH"

SHA256=$(shasum -a 256 "$ZIP_PATH" | awk '{print $1}')
echo "    SHA-256: $SHA256"

cat > "$MANIFEST_PATH" <<JSON
{
  "versionCode": ${VERSION_CODE},
  "versionName": "${VERSION_NAME}",
  "downloadUrl": "https://\${UPDATE_HOST}/updates/${ZIP_NAME}",
  "sha256": "${SHA256}",
  "codesignTeamId": "${TEAM_ID}"
}
JSON

echo
echo "==> Done. Wrote:"
echo "    $ZIP_PATH"
echo "    $MANIFEST_PATH"
echo
echo "Next (manual -- see RELEASE.md):"
echo "  1. Edit $MANIFEST_PATH: replace \${UPDATE_HOST} with your real update host"
echo "     (e.g. vpn.bartholomew.help), matching what filter-server/.env's UPDATE_HOST is set to."
echo "  2. Copy both files onto the update host at /var/lib/otterling/updates/, e.g.:"
echo "       scp $ZIP_PATH $MANIFEST_PATH your-server:/var/lib/otterling/updates/"
echo "     (as whatever user/method you already use to manage that host -- this script has no"
echo "     access to it and doesn't attempt the upload itself.)"
echo "  3. Caddy already serves /var/lib/otterling/updates/ at https://<host>/updates/ -- nothing"
echo "     else to configure. Existing installs pick this up on their next hourly check, or"
echo "     immediately via 'focuslockctl check-update' / the GUI's 'Check for update' button."
