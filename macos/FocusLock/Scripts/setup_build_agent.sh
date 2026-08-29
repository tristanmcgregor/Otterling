#!/bin/bash
# One-time setup for the macOS build agent (see RELEASE.md's "Automated macOS builds" section) --
# automates everything that CAN be automated: creating a dedicated signing keychain, generating and
# storing its unlock password, importing your code-signing certificate, auto-detecting the
# resulting signing identity string, writing config.env, and installing the LaunchDaemon.
#
# What this script deliberately CANNOT do for you: obtain a code-signing certificate in the first
# place. RELEASE.md's "One-time setup" section explains this project's stance in detail -- the free
# "Apple Development" identity Xcode already generates for any Apple ID works fine here (the actual
# trust check is SHA-256 + pinned Team ID, not the certificate class), so a paid Apple Developer
# Program membership is NOT required unless you specifically want a real Developer ID Application
# identity instead. Either way, exporting *some* certificate + its private key as a .p12 is a step
# only you can do (via Keychain Access), since Apple's code-signing trust model has no path around
# a human holding that credential.
#
# Usage: Scripts/setup_build_agent.sh <path-to-your.p12>
#   You'll be prompted (not passed on the command line, so it never lands in shell history or a
#   process listing) for the .p12's own export password.
set -euo pipefail

P12_PATH="${1:-}"
if [[ -z "$P12_PATH" || ! -f "$P12_PATH" ]]; then
  echo "Usage: $0 <path-to-your.p12>" >&2
  echo "  Export this from Keychain Access (My Certificates -> right-click your identity -> Export)." >&2
  echo "  The free 'Apple Development' identity Xcode already set up for you works fine -- see" >&2
  echo "  RELEASE.md's One-time setup section. No paid Apple Developer account required." >&2
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
ALL_IDENTITIES=$(security find-identity -v -p codesigning "$KEYCHAIN_PATH")
# Prefer a real Developer ID Application identity if present, but this project deliberately also
# accepts the free "Apple Development" identity (see RELEASE.md's "One-time setup" section) -- the
# actual trust check (SHA-256 + pinned Team Identifier) works identically either way, and requiring
# a paid Apple Developer Program membership here would contradict that documented, deliberate
# choice. publish_release.sh's own ALLOW_NON_DEVELOPER_ID stance is the precedent this mirrors.
IDENTITY_LINE=$(echo "$ALL_IDENTITIES" | grep "Developer ID Application" | head -1 || true)
if [[ -z "$IDENTITY_LINE" ]]; then
  IDENTITY_LINE=$(echo "$ALL_IDENTITIES" | grep -E '"(Apple Development|3rd Party Mac Developer Application)' | head -1 || true)
  if [[ -n "$IDENTITY_LINE" ]]; then
    echo "    No Developer ID Application identity found -- using the free identity below instead"
    echo "    (this project's documented, intentional stance; see RELEASE.md's One-time setup)."
  fi
fi
if [[ -z "$IDENTITY_LINE" ]]; then
  echo "ERROR: no usable code-signing identity found in $KEYCHAIN_PATH after import." >&2
  echo "Expected either a 'Developer ID Application' or 'Apple Development' identity." >&2
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
  echo "    GitHub fine-grained PAT for cloning ${GITHUB_REPO} (read-only, Contents scope --"
  echo "    required if that repo is private: this account's LaunchDaemon can't use the normal"
  echo "    osxkeychain credential helper, which needs an unlocked login session. Leave blank"
  echo "    only if the repo is genuinely public. Input hidden):"
  read -r -s GITHUB_CLONE_TOKEN
  echo

  umask 077
  cat > "$AGENT_HOME/config.env" <<ENVEOF
OTTERLING_HOST=${OTTERLING_HOST}
MACOS_BUILD_AGENT_TOKEN=${MACOS_BUILD_AGENT_TOKEN}
GITHUB_REPO=${GITHUB_REPO}
GITHUB_CLONE_TOKEN=${GITHUB_CLONE_TOKEN}
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
