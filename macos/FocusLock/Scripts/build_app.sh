#!/bin/bash
# Assembles FocusLock.app from the SwiftPM build products and code-signs it, including the
# embedded FocusLockHelperd LaunchDaemon that SMAppService registers.
#
# Usage: Scripts/build_app.sh "Apple Development: Your Name (TEAMID)"
#
# Run `security find-identity -v -p codesigning` first to see available identities once the
# certificate from developer.apple.com is installed in your login keychain.

set -euo pipefail

SIGN_IDENTITY="${1:-}"
if [ -z "$SIGN_IDENTITY" ]; then
  echo "Usage: $0 \"<code signing identity>\""
  echo "Available identities:"
  security find-identity -v -p codesigning || true
  exit 1
fi

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="$PROJECT_DIR/.build/debug"
APP_NAME="FocusLock"
BUNDLE_ID="au.com.tbmcgregor.bwparker.focuslock"
HELPER_LABEL="au.com.tbmcgregor.bwparker.focuslock.helperd"
INSTALL_PATH="/Applications/${APP_NAME}.app"

echo "==> Building with SwiftPM"
swift build --package-path "$PROJECT_DIR"

echo "==> Assembling ${APP_NAME}.app at ${INSTALL_PATH}"
rm -rf "$INSTALL_PATH"
mkdir -p "$INSTALL_PATH/Contents/MacOS"
mkdir -p "$INSTALL_PATH/Contents/Library/LaunchDaemons"
mkdir -p "$INSTALL_PATH/Contents/Resources"

cp "$BUILD_DIR/FocusLock" "$INSTALL_PATH/Contents/MacOS/FocusLock"
cp "$BUILD_DIR/FocusLockHelperd" "$INSTALL_PATH/Contents/MacOS/FocusLockHelperd"

tee "$INSTALL_PATH/Contents/Info.plist" > /dev/null <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key>
    <string>FocusLock</string>
    <key>CFBundleIdentifier</key>
    <string>${BUNDLE_ID}</string>
    <key>CFBundleName</key>
    <string>${APP_NAME}</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>CFBundleShortVersionString</key>
    <string>0.1</string>
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

echo "==> Code-signing (daemon first, then app bundle)"
codesign --force --options runtime --sign "$SIGN_IDENTITY" "$INSTALL_PATH/Contents/MacOS/FocusLockHelperd"
codesign --force --options runtime --sign "$SIGN_IDENTITY" "$INSTALL_PATH/Contents/MacOS/FocusLock"
codesign --force --options runtime --sign "$SIGN_IDENTITY" "$INSTALL_PATH"

echo "==> Verifying signatures"
codesign -dv --verbose=4 "$INSTALL_PATH" 2>&1 | grep -E "Identifier|TeamIdentifier|Authority"
codesign -dv --verbose=4 "$INSTALL_PATH/Contents/MacOS/FocusLockHelperd" 2>&1 | grep -E "Identifier|TeamIdentifier|Authority"

echo "==> Installing focuslockctl to /usr/local/bin"
mkdir -p /usr/local/bin
cp "$BUILD_DIR/focuslockctl" /usr/local/bin/focuslockctl
codesign --force --options runtime --sign "$SIGN_IDENTITY" /usr/local/bin/focuslockctl

echo "==> Done. Launch with: open ${INSTALL_PATH}"
echo "    Guardian CLI: focuslockctl status"
