#!/bin/bash
# Double-clickable launcher for install_lock_profile.py -- run once, after GUARDIAN_SETUP.md steps
# 1-4, while logged in as the Guardian (they should be the one approving the profile install
# dialog this triggers).
#
# Usage: just double-click this file in Finder, or run it from Terminal.

set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$DIR/install_lock_profile.py"

if [ ! -f "$SCRIPT" ]; then
  echo "Cannot find install_lock_profile.py next to this launcher." >&2
  exit 1
fi

HOST=$(/usr/bin/osascript -e 'text returned of (display dialog "Filter server hostname" default answer "vpn.bartholomew.help" with title "Otterling lock profile setup")') || {
  echo "Cancelled." >&2
  exit 1
}

TOKEN=$(/usr/bin/osascript -e 'text returned of (display dialog "LOCKPROFILE_TOKEN (from filter-server/.env on your server)" default answer "" with hidden answer with title "Otterling lock profile setup")') || {
  echo "Cancelled." >&2
  exit 1
}
if [ -z "$TOKEN" ]; then
  echo "No token entered. Nothing was changed." >&2
  exit 1
fi

# Token goes through a private temp file, not inline in the elevated shell string, so it never
# appears in `ps` -- same reasoning as guardian_password_setup.command's PWFILE.
TOKENFILE=$(mktemp /tmp/lockprofile_token.XXXXXX)
chmod 600 "$TOKENFILE"
printf '%s' "$TOKEN" > "$TOKENFILE"
unset TOKEN

esc_script=${SCRIPT//\\/\\\\}; esc_script=${esc_script//\"/\\\"}
esc_tokenfile=${TOKENFILE//\\/\\\\}; esc_tokenfile=${esc_tokenfile//\"/\\\"}
esc_host=${HOST//\\/\\\\}; esc_host=${esc_host//\"/\\\"}

echo "Requesting administrator authorization (approve with the Guardian's admin password)..."
# `do shell script ... with administrator privileges` runs synchronously and returns the
# script's combined output as its AppleScript result -- captured here and printed once it's back,
# unlike guardian_password_setup.command's launcher (that one backgrounds a long-running server and
# tails a log instead; install_lock_profile.py finishes in one shot, nothing to tail).
if ! OUTPUT=$(/usr/bin/osascript -e "do shell script \"LOCKPROFILE_SERVER_HOST='${esc_host}' LOCKPROFILE_TOKEN_FILE='${esc_tokenfile}' /usr/bin/python3 '${esc_script}' 2>&1\" with administrator privileges" 2>&1); then
  rm -f "$TOKENFILE"
  echo "$OUTPUT"
  echo "Authorization was cancelled, or the install failed -- see output above." >&2
  exit 1
fi
rm -f "$TOKENFILE"
echo "$OUTPUT"
echo "Done."
