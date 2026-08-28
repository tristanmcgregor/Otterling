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
# risking a stray empty-string argument.
CODESIGN_KEYCHAIN_ARGS=()
if [ -n "$KEYCHAIN_PATH" ]; then
  CODESIGN_KEYCHAIN_ARGS=(--keychain "$KEYCHAIN_PATH")
fi

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="$PROJECT_DIR/.build/debug"
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
INSTALL_PATH="/Applications/${APP_NAME}.app"
# Must match FocusLockConstants.appVersionCode in Sources/FocusLockShared/Constants.swift -- kept
# in sync by hand (see that constant's doc comment); UpdateManager compares against the Swift
# constant, not this plist value, but they should always read the same to a human checking either.
APP_VERSION="0.2"

echo "==> Building with SwiftPM"
swift build --package-path "$PROJECT_DIR"

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
    <string>${INSTALL_PATH}/Contents/MacOS/FocusLockHelperd</string>
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
    <string>${INSTALL_PATH}/Contents/MacOS/FocusLockWatchdog</string>
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
    <string>${INSTALL_PATH}/Contents/MacOS/FocusLockScanner</string>
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
codesign --force --options runtime "${CODESIGN_KEYCHAIN_ARGS[@]}" --sign "$SIGN_IDENTITY" "$INSTALL_PATH/Contents/MacOS/FocusLockHelperd"
codesign --force --options runtime "${CODESIGN_KEYCHAIN_ARGS[@]}" --sign "$SIGN_IDENTITY" "$INSTALL_PATH/Contents/MacOS/FocusLockWatchdog"
codesign --force --options runtime "${CODESIGN_KEYCHAIN_ARGS[@]}" --sign "$SIGN_IDENTITY" "$INSTALL_PATH/Contents/MacOS/FocusLockScanner"
codesign --force --options runtime "${CODESIGN_KEYCHAIN_ARGS[@]}" --sign "$SIGN_IDENTITY" "$INSTALL_PATH/Contents/MacOS/${EXECUTABLE_NAME}"
codesign --force --options runtime "${CODESIGN_KEYCHAIN_ARGS[@]}" --sign "$SIGN_IDENTITY" "$INSTALL_PATH"

echo "==> Verifying signatures"
codesign -dv --verbose=4 "$INSTALL_PATH" 2>&1 | grep -E "Identifier|TeamIdentifier|Authority"
codesign -dv --verbose=4 "$INSTALL_PATH/Contents/MacOS/FocusLockHelperd" 2>&1 | grep -E "Identifier|TeamIdentifier|Authority"
codesign -dv --verbose=4 "$INSTALL_PATH/Contents/MacOS/FocusLockWatchdog" 2>&1 | grep -E "Identifier|TeamIdentifier|Authority"

echo "==> Installing otterlingctl to /usr/local/bin"
mkdir -p /usr/local/bin
rm -f /usr/local/bin/focuslockctl
cp "$BUILD_DIR/otterlingctl" /usr/local/bin/otterlingctl
codesign --force --options runtime "${CODESIGN_KEYCHAIN_ARGS[@]}" --sign "$SIGN_IDENTITY" /usr/local/bin/otterlingctl

echo "==> Done. Launch with: open ${INSTALL_PATH}"
echo "    Guardian CLI: otterlingctl status"
