#!/bin/bash
# Builds a redistributable .pkg installer for Otterling: Otterling.app (with the LaunchDaemons/
# LaunchAgent already embedded, same as build_app.sh produces) plus otterlingctl, in one
# double-clickable installer -- instead of build_app.sh's normal behavior of writing straight into
# THIS machine's /Applications and /usr/local/bin.
#
# Usage: Scripts/build_pkg.sh ["--keychain <path>"] "<app signing identity>" ["<installer signing identity>"]
#
# <app signing identity>: same as build_app.sh -- signs Otterling.app, its embedded daemons/agent,
# and otterlingctl.
#
# <installer signing identity> (optional): a "Developer ID Installer" certificate to sign the .pkg
# itself with `productsign`. Requires a paid Apple Developer Program membership -- the free "Apple
# Development" identity this project otherwise supports (see RELEASE.md) has no Installer
# counterpart, so without this the .pkg is left unsigned and Gatekeeper will show an "unidentified
# developer" warning on it (same trade-off RELEASE.md already documents for the app itself, just on
# the installer wrapper instead of the app bundle).
#
# A .pkg, not a .dmg, is the right shape here: install needs to also place otterlingctl in
# /usr/local/bin and run as root to do it, which a .dmg (drag-the-.app-to-Applications) has no way
# to do on its own.
set -euo pipefail

KEYCHAIN_ARGS=()
if [ "${1:-}" = "--keychain" ]; then
  KEYCHAIN_ARGS=(--keychain "${2:?--keychain needs a path argument}")
  shift 2
fi

APP_SIGN_IDENTITY="${1:-}"
INSTALLER_SIGN_IDENTITY="${2:-}"
if [ -z "$APP_SIGN_IDENTITY" ]; then
  echo "Usage: $0 [--keychain <path>] \"<app signing identity>\" [\"<installer signing identity>\"]"
  echo "Available codesigning identities:"
  security find-identity -v -p codesigning || true
  echo "Available installer-signing identities:"
  security find-identity -v | grep "Developer ID Installer" || echo "  (none found -- pkg will be left unsigned)"
  exit 1
fi

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_NAME="Otterling"
PKG_IDENTIFIER="app.otterling.installer"
RELEASE_DIR="$PROJECT_DIR/.release"
mkdir -p "$RELEASE_DIR"

STAGE_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/otterling-pkg-stage-XXXXXX")"
trap 'rm -rf "$STAGE_ROOT"' EXIT

echo "==> Building + signing app bundle and otterlingctl into staging root: $STAGE_ROOT"
OTTERLING_STAGE_ROOT="$STAGE_ROOT" "$PROJECT_DIR/Scripts/build_app.sh" "${KEYCHAIN_ARGS[@]+"${KEYCHAIN_ARGS[@]}"}" "$APP_SIGN_IDENTITY"

STAGED_APP="$STAGE_ROOT/Applications/${APP_NAME}.app"
APP_VERSION="$(/usr/libexec/PlistBuddy -c "Print :CFBundleShortVersionString" "$STAGED_APP/Contents/Info.plist")"
echo "==> Packaging version $APP_VERSION"

PKG_NAME="Otterling-${APP_VERSION}.pkg"
PKG_PATH_UNSIGNED="$RELEASE_DIR/${PKG_NAME}"

echo "==> Running pkgbuild"
pkgbuild \
  --root "$STAGE_ROOT" \
  --identifier "$PKG_IDENTIFIER" \
  --version "$APP_VERSION" \
  --install-location / \
  --ownership recommended \
  --scripts "$PROJECT_DIR/Scripts/pkg-scripts" \
  "$PKG_PATH_UNSIGNED"

FINAL_PKG_PATH="$PKG_PATH_UNSIGNED"
if [ -n "$INSTALLER_SIGN_IDENTITY" ]; then
  PKG_PATH_SIGNED="$RELEASE_DIR/${PKG_NAME%.pkg}-signed.pkg"
  echo "==> Signing package with: $INSTALLER_SIGN_IDENTITY"
  productsign --sign "$INSTALLER_SIGN_IDENTITY" "$PKG_PATH_UNSIGNED" "$PKG_PATH_SIGNED"
  rm -f "$PKG_PATH_UNSIGNED"
  FINAL_PKG_PATH="$PKG_PATH_SIGNED"
  echo "==> Verifying package signature"
  pkgutil --check-signature "$FINAL_PKG_PATH"
else
  echo "==> No installer signing identity given -- pkg is unsigned (Gatekeeper will warn on open)."
fi

echo "==> Done: $FINAL_PKG_PATH"
echo "    Installs to /Applications/${APP_NAME}.app + /usr/local/bin/otterlingctl,"
echo "    then opens Otterling.app once (as the console user) so it registers its own"
echo "    daemons/agent via SMAppService -- approve them under System Settings > General >"
echo "    Login Items & Extensions if prompted."
