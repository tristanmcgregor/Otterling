#!/bin/bash
# Assembles Otterling.app (display name; internal executable/bundle IDs stay FocusLock*) from the
# SwiftPM build products and code-signs it, including the embedded FocusLockHelperd LaunchDaemon
# that SMAppService registers.
#
# Usage: Scripts/build_app.sh ["--keychain <path>"] "<code signing identity>"
#
# Run `security find-identity -v -p codesigning` first to see available identities once the
# certificate from developer.apple.com is installed in your login keychain.
#
# --keychain <path>: pass a specific keychain to every codesign invocation instead of relying on
# the ambient keychain search list. Needed for non-interactive/headless signing (see
# build_agent_build_and_upload.sh) -- a LaunchDaemon has no login session and no unlocked default
# keychain, so codesign can't find the identity without being told exactly where to look. The
# caller is responsible for unlocking that keychain first (`security unlock-keychain`); this
# script doesn't touch keychain passwords at all, keeping that secret's blast radius to the one
# small script that needs it. Omit entirely for the normal interactive/manual path -- unchanged.

set -euo pipefail

KEYCHAIN_PATH=""
if [ "${1:-}" = "--keychain" ]; then
  KEYCHAIN_PATH="${2:?--keychain needs a path argument}"
  shift 2
fi

SIGN_IDENTITY="${1:-}"
if [ -z "$SIGN_IDENTITY" ]; then
  echo "Usage: $0 [--keychain <path>] \"<code signing identity>\""
  echo "Available identities:"
  security find-identity -v -p codesigning || true
  exit 1
fi

# Empty array (not appended inline) so `codesign "${CODESIGN_KEYCHAIN_ARGS[@]}" ...` is a true
# no-op when $KEYCHAIN_PATH is unset -- bash has no clean way to conditionally splice extra args
# into a fixed positional command otherwise without either duplicating every codesign line or
# risking a stray empty-string argument. Expanded below as
# `${CODESIGN_KEYCHAIN_ARGS[@]+"${CODESIGN_KEYCHAIN_ARGS[@]}"}`, not the plain form, because macOS's
# stock /bin/bash (3.2) throws "unbound variable" under `set -u` when a *plain* empty-array
# expansion has no elements -- this guarded form is the standard bash 3.2-safe workaround.
CODESIGN_KEYCHAIN_ARGS=()
if [ -n "$KEYCHAIN_PATH" ]; then
  CODESIGN_KEYCHAIN_ARGS=(--keychain "$KEYCHAIN_PATH")
fi

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="$PROJECT_DIR/.build/release"
# EXECUTABLE_NAME matches the SwiftPM product names in Package.swift (unchanged -- renaming these
# would mean large Package.swift/target churn for no user-facing benefit). APP_NAME/DISPLAY_NAME
# is purely the install path and Info.plist branding -- bundle/Mach/LaunchDaemon IDs stay as
# app.otterling* so an existing install isn't orphaned by this rename.
EXECUTABLE_NAME="FocusLock"
APP_NAME="Otterling"
DISPLAY_NAME="Otterling"
BUNDLE_ID="app.otterling"
HELPER_LABEL="app.otterling.helperd"
WATCHDOG_LABEL="app.otterling.watchdog"
SCANNER_LABEL="app.otterling.scanner"
# OTTERLING_STAGE_ROOT lets build_pkg.sh reuse this exact assembly/signing logic to stage a payload
# for pkgbuild instead of writing straight into the running system's /Applications + /usr/local/bin
# -- unset (the normal manual/build-agent path, unchanged) writes to the real paths as before.
STAGE_ROOT="${OTTERLING_STAGE_ROOT:-}"
INSTALL_PATH="${STAGE_ROOT}/Applications/${APP_NAME}.app"
CTL_BIN_DIR="${STAGE_ROOT}/usr/local/bin"
# Where the app will actually live once installed, regardless of STAGE_ROOT -- the LaunchDaemon/
# LaunchAgent plists below must always point here, never at $INSTALL_PATH. $INSTALL_PATH is only
# where this script writes files *right now* (the real /Applications on a normal run, or a
# throwaway pkgbuild staging root under build_pkg.sh); the plists launchd loads describe where the
# binaries will be running FROM, which for a staged build is /Applications on the machine the .pkg
# eventually installs onto, not the build machine's staging directory that gets deleted right after
# packaging. Baking $INSTALL_PATH in here directly was exactly that bug: pkg installs shipped
# LaunchDaemon plists whose Program pointed at a `mktemp` staging dir that no longer existed on the
# target machine, so the daemon failed to spawn (EX_CONFIG) on every install from a built .pkg.
FINAL_APP_PATH="/Applications/${APP_NAME}.app"

# --- Auto version bump -------------------------------------------------------------------------
# FocusLockConstants.appVersionCode and Scripts/version.txt (this build's CFBundleShortVersionString)
# used to be bumped by hand before every release -- forgetting that step is exactly what caused a
# shipped build to keep reporting itself as an old version to UpdateManager's check, forever
# redownloading/reinstalling the "update" it had already installed (see git history on this file
# and on Constants.swift for the incident this fixes). Auto-bump instead, but only when something
# under macos/ actually changed since the last local publish (tracked in .release/last_published_*,
# written by publish_release.sh) -- so a build with no macOS changes just reuses the existing
# version instead of bumping on every single build.
#
# OTTERLING_VERSION_CODE / OTTERLING_VERSION_NAME (set together) pin an exact version instead and
# skip this logic entirely -- used by build_agent_build_and_upload.sh, which must bake in whatever
# version the review host already decided for this job, not whatever this repo's git history
# happens to suggest.
CONSTANTS_FILE="$PROJECT_DIR/Sources/FocusLockShared/Constants.swift"
VERSION_FILE="$PROJECT_DIR/Scripts/version.txt"
RELEASE_DIR="$PROJECT_DIR/.release"
REPO_ROOT="$(git -C "$PROJECT_DIR" rev-parse --show-toplevel 2>/dev/null || echo "")"

CURRENT_VERSION_CODE="$(sed -n 's/.*appVersionCode = \([0-9]*\).*/\1/p' "$CONSTANTS_FILE" | head -1)"
CURRENT_VERSION_NAME="$(cat "$VERSION_FILE" 2>/dev/null || echo "0.0")"

if [ -n "${OTTERLING_VERSION_CODE:-}" ] || [ -n "${OTTERLING_VERSION_NAME:-}" ]; then
  : "${OTTERLING_VERSION_CODE:?OTTERLING_VERSION_CODE and OTTERLING_VERSION_NAME must be set together}"
  : "${OTTERLING_VERSION_NAME:?OTTERLING_VERSION_CODE and OTTERLING_VERSION_NAME must be set together}"
  NEW_VERSION_CODE="$OTTERLING_VERSION_CODE"
  NEW_VERSION_NAME="$OTTERLING_VERSION_NAME"
  echo "==> Pinning version $NEW_VERSION_NAME ($NEW_VERSION_CODE) from OTTERLING_VERSION_CODE/OTTERLING_VERSION_NAME"
  # Must actually patch the Swift constant here too, not just set $APP_VERSION below for Info.plist
  # -- Info.plist's CFBundleShortVersionString is cosmetic, but UpdateManager's own up-to-date check
  # compares manifest.versionCode against FocusLockConstants.appVersionCode AS COMPILED INTO THIS
  # BINARY. A build-agent checkout is a fresh `git clone` of whatever's actually committed, which
  # will still be some earlier value unless this file is bumped in the same commit -- skipping this
  # patch left the compiled daemon silently reporting an old build forever while Info.plist claimed
  # the new one, recreating the exact 7555c01 bug this whole auto-bump exists to prevent.
  if [ "$CURRENT_VERSION_CODE" != "$NEW_VERSION_CODE" ]; then
    sed -i '' "s/appVersionCode = [0-9]*/appVersionCode = ${NEW_VERSION_CODE}/" "$CONSTANTS_FILE"
  fi
  if [ "$CURRENT_VERSION_NAME" != "$NEW_VERSION_NAME" ]; then
    printf '%s\n' "$NEW_VERSION_NAME" > "$VERSION_FILE"
  fi
else
  LAST_SHA=""
  LAST_VERSION_CODE="$CURRENT_VERSION_CODE"
  LAST_VERSION_NAME="$CURRENT_VERSION_NAME"
  [ -f "$RELEASE_DIR/last_published_sha" ] && LAST_SHA="$(cat "$RELEASE_DIR/last_published_sha")"
  [ -f "$RELEASE_DIR/last_published_version_code" ] && LAST_VERSION_CODE="$(cat "$RELEASE_DIR/last_published_version_code")"
  [ -f "$RELEASE_DIR/last_published_version_name" ] && LAST_VERSION_NAME="$(cat "$RELEASE_DIR/last_published_version_name")"

  MACOS_CHANGED=1
  if [ -n "$REPO_ROOT" ] && [ -n "$LAST_SHA" ] && git -C "$REPO_ROOT" cat-file -e "${LAST_SHA}^{commit}" 2>/dev/null; then
    if git -C "$REPO_ROOT" diff --quiet "$LAST_SHA" HEAD -- macos/ 2>/dev/null; then
      MACOS_CHANGED=0
    fi
  fi

  if [ "$MACOS_CHANGED" = "1" ]; then
    NEW_VERSION_CODE=$((LAST_VERSION_CODE + 1))
    LAST_PATCH="${LAST_VERSION_NAME##*.}"
    LAST_PREFIX="${LAST_VERSION_NAME%.*}"
    if [[ "$LAST_PATCH" =~ ^[0-9]+$ ]]; then
      NEW_VERSION_NAME="${LAST_PREFIX}.$((LAST_PATCH + 1))"
    else
      NEW_VERSION_NAME="$NEW_VERSION_CODE"
    fi
    echo "==> macos/ changed since last publish (${LAST_SHA:-none}) -- bumping version to $NEW_VERSION_NAME ($NEW_VERSION_CODE)"
    sed -i '' "s/appVersionCode = [0-9]*/appVersionCode = ${NEW_VERSION_CODE}/" "$CONSTANTS_FILE"
    printf '%s\n' "$NEW_VERSION_NAME" > "$VERSION_FILE"
  else
    NEW_VERSION_CODE="$CURRENT_VERSION_CODE"
    NEW_VERSION_NAME="$CURRENT_VERSION_NAME"
    echo "==> No macos/ changes since last publish -- keeping version $NEW_VERSION_NAME ($NEW_VERSION_CODE)"
  fi
fi

# Must match FocusLockConstants.appVersionCode in Sources/FocusLockShared/Constants.swift --
# kept in sync automatically by the block above (see that constant's doc comment); UpdateManager
# compares against the Swift constant, not this plist value, but they should always read the same
# to a human checking either.
APP_VERSION="$NEW_VERSION_NAME"

echo "==> Building with SwiftPM (release)"
swift build -c release --package-path "$PROJECT_DIR"

echo "==> Assembling ${APP_NAME}.app at ${INSTALL_PATH}"
rm -rf "$INSTALL_PATH"
mkdir -p "$INSTALL_PATH/Contents/MacOS"
mkdir -p "$INSTALL_PATH/Contents/Library/LaunchDaemons"
mkdir -p "$INSTALL_PATH/Contents/Library/LaunchAgents"
mkdir -p "$INSTALL_PATH/Contents/Resources"

cp "$BUILD_DIR/${EXECUTABLE_NAME}" "$INSTALL_PATH/Contents/MacOS/${EXECUTABLE_NAME}"
cp "$BUILD_DIR/FocusLockHelperd" "$INSTALL_PATH/Contents/MacOS/FocusLockHelperd"
cp "$BUILD_DIR/FocusLockWatchdog" "$INSTALL_PATH/Contents/MacOS/FocusLockWatchdog"
cp "$BUILD_DIR/FocusLockScanner" "$INSTALL_PATH/Contents/MacOS/FocusLockScanner"
if [ -f "$PROJECT_DIR/Resources/AppIcon.icns" ]; then
  cp "$PROJECT_DIR/Resources/AppIcon.icns" "$INSTALL_PATH/Contents/Resources/AppIcon.icns"
fi

# Build provenance for IntegrityReporter.swift: lets the daemon tell the server whether it was
# built from a clean, committed working tree, or from local changes that were never committed --
# the exact "edited the code and installed it locally" scenario the tamper check exists to catch.
# Scoped to `macos/FocusLock` specifically (not the whole monorepo) so unrelated in-progress work
# elsewhere in the tree (Android/server) doesn't produce false "tampered" reports.
REPO_ROOT="$(cd "$PROJECT_DIR/../.." && pwd)"
GIT_SHA="$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || echo unknown)"
if [ -n "$(git -C "$REPO_ROOT" status --porcelain -- macos/FocusLock 2>/dev/null)" ]; then
  GIT_DIRTY=true
else
  GIT_DIRTY=false
fi
BUILT_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
cat > "$INSTALL_PATH/Contents/Resources/build-info.json" <<JSON
{"git_sha": "${GIT_SHA}", "dirty": ${GIT_DIRTY}, "built_at": "${BUILT_AT}"}
JSON

tee "$INSTALL_PATH/Contents/Info.plist" > /dev/null <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key>
    <string>${EXECUTABLE_NAME}</string>
    <key>CFBundleIdentifier</key>
    <string>${BUNDLE_ID}</string>
    <key>CFBundleName</key>
    <string>${APP_NAME}</string>
    <key>CFBundleDisplayName</key>
    <string>${DISPLAY_NAME}</string>
    <key>CFBundleIconFile</key>
    <string>AppIcon</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>CFBundleShortVersionString</key>
    <string>${APP_VERSION}</string>
    <key>LSMinimumSystemVersion</key>
    <string>13.0</string>
</dict>
</plist>
PLIST

tee "$INSTALL_PATH/Contents/Library/LaunchDaemons/${HELPER_LABEL}.plist" > /dev/null <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>${HELPER_LABEL}</string>
    <key>MachServices</key>
    <dict>
        <key>${HELPER_LABEL}</key>
        <true/>
    </dict>
    <key>Program</key>
    <string>${FINAL_APP_PATH}/Contents/MacOS/FocusLockHelperd</string>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardOutPath</key>
    <string>/var/log/focuslock-helperd.log</string>
    <key>StandardErrorPath</key>
    <string>/var/log/focuslock-helperd.log</string>
</dict>
</plist>
PLIST

# Independent of the daemon's own plist above on purpose -- see FocusLockWatchdog/main.swift's doc
# comment. No MachServices entry: this one has no XPC service of its own, it only calls out to
# FocusLockHelperd's.
tee "$INSTALL_PATH/Contents/Library/LaunchDaemons/${WATCHDOG_LABEL}.plist" > /dev/null <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>${WATCHDOG_LABEL}</string>
    <key>Program</key>
    <string>${FINAL_APP_PATH}/Contents/MacOS/FocusLockWatchdog</string>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardOutPath</key>
    <string>/var/log/focuslock-watchdog.log</string>
    <key>StandardErrorPath</key>
    <string>/var/log/focuslock-watchdog.log</string>
</dict>
</plist>
PLIST

# Per-user LaunchAgent for the trigger-word accessibility scanner. Unlike the two daemons above
# this is a GUI-session agent (Accessibility/TCC is per-user and needs a login session), so it goes
# under LaunchAgents and SMAppService.agent registers it. RunAtLoad + KeepAlive so it stays up and
# comes back on login; it prompts for Accessibility permission itself on first run.
tee "$INSTALL_PATH/Contents/Library/LaunchAgents/${SCANNER_LABEL}.plist" > /dev/null <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>${SCANNER_LABEL}</string>
    <key>Program</key>
    <string>${FINAL_APP_PATH}/Contents/MacOS/FocusLockScanner</string>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardOutPath</key>
    <string>/tmp/focuslock-scanner.log</string>
    <key>StandardErrorPath</key>
    <string>/tmp/focuslock-scanner.log</string>
</dict>
</plist>
PLIST

echo "==> Code-signing (daemons + agent first, then app bundle)"
codesign --force --options runtime "${CODESIGN_KEYCHAIN_ARGS[@]+"${CODESIGN_KEYCHAIN_ARGS[@]}"}" --sign "$SIGN_IDENTITY" "$INSTALL_PATH/Contents/MacOS/FocusLockHelperd"
codesign --force --options runtime "${CODESIGN_KEYCHAIN_ARGS[@]+"${CODESIGN_KEYCHAIN_ARGS[@]}"}" --sign "$SIGN_IDENTITY" "$INSTALL_PATH/Contents/MacOS/FocusLockWatchdog"
codesign --force --options runtime "${CODESIGN_KEYCHAIN_ARGS[@]+"${CODESIGN_KEYCHAIN_ARGS[@]}"}" --sign "$SIGN_IDENTITY" "$INSTALL_PATH/Contents/MacOS/FocusLockScanner"
codesign --force --options runtime "${CODESIGN_KEYCHAIN_ARGS[@]+"${CODESIGN_KEYCHAIN_ARGS[@]}"}" --sign "$SIGN_IDENTITY" "$INSTALL_PATH/Contents/MacOS/${EXECUTABLE_NAME}"
codesign --force --options runtime "${CODESIGN_KEYCHAIN_ARGS[@]+"${CODESIGN_KEYCHAIN_ARGS[@]}"}" --sign "$SIGN_IDENTITY" "$INSTALL_PATH"

echo "==> Verifying signatures"
codesign -dv --verbose=4 "$INSTALL_PATH" 2>&1 | grep -E "Identifier|TeamIdentifier|Authority"
codesign -dv --verbose=4 "$INSTALL_PATH/Contents/MacOS/FocusLockHelperd" 2>&1 | grep -E "Identifier|TeamIdentifier|Authority"
codesign -dv --verbose=4 "$INSTALL_PATH/Contents/MacOS/FocusLockWatchdog" 2>&1 | grep -E "Identifier|TeamIdentifier|Authority"

echo "==> Installing otterlingctl to ${CTL_BIN_DIR}"
mkdir -p "$CTL_BIN_DIR"
rm -f "$CTL_BIN_DIR/focuslockctl"
cp "$BUILD_DIR/otterlingctl" "$CTL_BIN_DIR/otterlingctl"
codesign --force --options runtime "${CODESIGN_KEYCHAIN_ARGS[@]+"${CODESIGN_KEYCHAIN_ARGS[@]}"}" --sign "$SIGN_IDENTITY" "$CTL_BIN_DIR/otterlingctl"

echo "==> Done. Launch with: open ${INSTALL_PATH}"
echo "    Guardian CLI: otterlingctl status"
