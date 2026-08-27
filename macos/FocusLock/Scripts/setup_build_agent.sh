#!/bin/bash
# One-time setup for the macOS build agent (see RELEASE.md's "Automated macOS builds" section) --
# automates everything that CAN be automated: creating a dedicated signing keychain, generating and
# storing its unlock password, importing your Developer ID certificate, auto-detecting the
# resulting signing identity string, writing config.env, and installing the LaunchDaemon.
#
# What this script deliberately CANNOT do for you: obtain a Developer ID Application certificate
# in the first place. That requires an Apple Developer Program membership (a paid Apple account)
# and either generating one via Xcode/developer.apple.com yourself, or already having one exported
# as a .p12 file -- Apple's code-signing trust model has no path around a human holding that
# credential. Everything else here is just local machine setup around a cert you already have.
#
# Usage: Scripts/setup_build_agent.sh <path-to-developer-id.p12>
#   You'll be prompted (not passed on the command line, so it never lands in shell history or a
#   process listing) for the .p12's own export password.
set -euo pipefail

P12_PATH="${1:-}"
if [[ -z "$P12_PATH" || ! -f "$P12_PATH" ]]; then
  echo "Usage: $0 <path-to-developer-id.p12>" >&2
  echo "  (export this from Keychain Access, or from wherever you generated your Developer ID" >&2
  echo "  Application certificate -- see RELEASE.md's One-time setup section if you don't have one)" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AGENT_HOME="$HOME/.otterling-build-agent"
KEYCHAIN_PATH="$HOME/Library/Keychains/otterling-build.keychain-db"
mkdir -p "$AGENT_HOME/logs"

echo "==> Developer ID .p12 export password (input hidden):"
read -r -s P12_PASSWORD
echo

if [[ -f "$KEYCHAIN_PATH" ]]; then
  echo "==> Keychain already exists at $KEYCHAIN_PATH -- reusing it (delete it first with"
  echo "    'security delete-keychain $KEYCHAIN_PATH' if you want a completely fresh one)."
  echo "==> Existing password file assumed still valid at $AGENT_HOME/keychain-password."
  [[ -f "$AGENT_HOME/keychain-password" ]] || { echo "ERROR: keychain exists but $AGENT_HOME/keychain-password is missing -- can't unlock it. Delete the keychain and re-run." >&2; exit 1; }
  KEYCHAIN_PASSWORD=$(cat "$AGENT_HOME/keychain-password")
else
  echo "==> Generating a random keychain password (you never need to type or remember this --"
  echo "    it's only ever read back from the file this script writes)"
  KEYCHAIN_PASSWORD=$(openssl rand -base64 32)
  echo "==> Creating dedicated signing keychain at $KEYCHAIN_PATH"
  security create-keychain -p "$KEYCHAIN_PASSWORD" "$KEYCHAIN_PATH"
  security set-keychain-settings -lut 21600 "$KEYCHAIN_PATH"
  umask 077
  printf '%s' "$KEYCHAIN_PASSWORD" > "$AGENT_HOME/keychain-password"
  chmod 600 "$AGENT_HOME/keychain-password"
fi

echo "==> Importing $P12_PATH"
security import "$P12_PATH" -k "$KEYCHAIN_PATH" -P "$P12_PASSWORD" -T /usr/bin/codesign
# Lets codesign use this identity non-interactively (no GUI "always allow" prompt), same as
# Apple's own documented CI pattern for a per-purpose keychain.
security set-key-partition-list -S apple-tool:,apple: -s -k "$KEYCHAIN_PASSWORD" "$KEYCHAIN_PATH" >/dev/null

echo "==> Detecting signing identity in the new keychain…"
IDENTITY_LINE=$(security find-identity -v -p codesigning "$KEYCHAIN_PATH" | grep "Developer ID Application" | head -1 || true)
if [[ -z "$IDENTITY_LINE" ]]; then
  echo "ERROR: no 'Developer ID Application' identity found in $KEYCHAIN_PATH after import." >&2
  echo "The .p12 may be an ad-hoc/Apple Development identity instead -- see RELEASE.md." >&2
  exit 1
fi
SIGNING_IDENTITY=$(echo "$IDENTITY_LINE" | sed -E 's/^[0-9]+\) [0-9A-F]+ "(.*)"$/\1/')
echo "    Found: $SIGNING_IDENTITY"

if [[ -f "$AGENT_HOME/config.env" ]]; then
  echo "==> $AGENT_HOME/config.env already exists -- leaving it as-is."
  echo "    (delete it first if you want this script to regenerate it from scratch)"
else
  echo
  echo "==> Two more values needed for config.env:"
  read -r -p "    Otterling host [vpn.bartholomew.help]: " OTTERLING_HOST_INPUT
  OTTERLING_HOST="${OTTERLING_HOST_INPUT:-vpn.bartholomew.help}"
  read -r -p "    GitHub repo [tristanmcgregor/Otterling]: " GITHUB_REPO_INPUT
  GITHUB_REPO="${GITHUB_REPO_INPUT:-tristanmcgregor/Otterling}"
  echo "    MACOS_BUILD_AGENT_TOKEN (from the Linux host's secrets.env, input hidden):"
  read -r -s MACOS_BUILD_AGENT_TOKEN
  echo

  umask 077
  cat > "$AGENT_HOME/config.env" <<ENVEOF
OTTERLING_HOST=${OTTERLING_HOST}
MACOS_BUILD_AGENT_TOKEN=${MACOS_BUILD_AGENT_TOKEN}
GITHUB_REPO=${GITHUB_REPO}
SIGNING_IDENTITY=${SIGNING_IDENTITY}
BUILD_KEYCHAIN_PATH=${KEYCHAIN_PATH}
BUILD_KEYCHAIN_PASSWORD_FILE=${AGENT_HOME}/keychain-password
# Optional -- set this to a notarytool keychain-profile name (see 'xcrun notarytool
# store-credentials --help') to notarize builds. Blank = skipped, builds still install fine (see
# build_app.sh's own doc comment on why).
NOTARY_PROFILE=
ENVEOF
  chmod 600 "$AGENT_HOME/config.env"
  echo "==> Wrote $AGENT_HOME/config.env"
fi

echo
echo "==> Setting up the checkout the LaunchDaemon will actually run from…"
CHECKOUT_DIR="$HOME/otterling-checkout"
if [[ ! -d "$CHECKOUT_DIR/.git" ]]; then
  echo "    No checkout at $CHECKOUT_DIR -- copying this one there instead of re-cloning."
  REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
  cp -R "$REPO_ROOT" "$CHECKOUT_DIR"
fi
chmod +x "$CHECKOUT_DIR/macos/FocusLock/Scripts/build_agent_poll.sh" \
         "$CHECKOUT_DIR/macos/FocusLock/Scripts/build_agent_build_and_upload.sh"

echo "==> Installing the LaunchDaemon (needs sudo)…"
sed "s|&lt;build-agent-account&gt;|$(whoami)|g" \
  "$CHECKOUT_DIR/macos/FocusLock/Scripts/build_agent.launchd.plist.example" \
  | sudo tee /Library/LaunchDaemons/app.otterling.buildagent.plist > /dev/null
sudo chown root:wheel /Library/LaunchDaemons/app.otterling.buildagent.plist
sudo chmod 644 /Library/LaunchDaemons/app.otterling.buildagent.plist
sudo launchctl bootout system/app.otterling.buildagent 2>/dev/null || true
sudo launchctl bootstrap system /Library/LaunchDaemons/app.otterling.buildagent.plist

echo
echo "==> Done. The build agent will poll every 15 minutes from now on."
echo "    To test immediately instead of waiting:"
echo "      sudo launchctl kickstart -k system/app.otterling.buildagent"
echo "      tail -f $AGENT_HOME/logs/poll-*.log"
