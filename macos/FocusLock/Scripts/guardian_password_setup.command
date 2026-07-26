#!/bin/bash
# Double-clickable launcher for the one-time Guardian password-setup tool.
#
# The work (wiping admin's Chrome, deleting Guardian, running the one-time LAN
# password form) needs root. This machine's day-to-day account is Standard and
# can't sudo, so we self-elevate through the standard macOS authorization dialog,
# which any admin credential can approve. After the run, the new admin password
# is chosen by the other person and is unknown to whoever launched this.
#
# Usage: just double-click this file in Finder, or run it from Terminal.

set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$DIR/guardian_password_setup.py"

if [ ! -f "$SCRIPT" ]; then
  echo "Cannot find guardian_password_setup.py next to this launcher." >&2
  exit 1
fi

# Already root? Run directly and keep the live output/link in this terminal.
if [ "$(id -u)" -eq 0 ]; then
  exec /usr/bin/python3 "$SCRIPT"
fi

# Elevate. `do shell script ... with administrator privileges` pops the macOS
# auth dialog; enter any admin account's password to approve. Output (including
# the one-time link) is teed to a log so you can read the URL to share.
LOG="/tmp/guardian_password_setup.log"
: > "$LOG"

echo "Requesting administrator authorization (approve with any admin password)..."
echo "The one-time link will appear below as soon as setup starts."
echo "A copy of all output is saved to: $LOG"
echo

# Escape the paths for embedding inside the AppleScript string literal.
esc_script=${SCRIPT//\\/\\\\}; esc_script=${esc_script//\"/\\\"}
esc_log=${LOG//\\/\\\\}; esc_log=${esc_log//\"/\\\"}

# `do shell script ... with administrator privileges` blocks until the elevated
# process exits, so run it in the background and stream its live output (which it
# tees to the log) so the shareable link shows up immediately, not 30 min later.
/usr/bin/osascript -e "do shell script \"/usr/bin/python3 '${esc_script}' 2>&1 | tee '${esc_log}'\" with administrator privileges" >/dev/null 2>&1 &
OSA_PID=$!

tail -f "$LOG" 2>/dev/null &
TAIL_PID=$!

# Wait for the elevated job to finish (success, timeout, or cancelled auth), then
# stop streaming.
wait "$OSA_PID" 2>/dev/null || true
sleep 1
kill "$TAIL_PID" 2>/dev/null || true
echo
echo "Setup process has exited."
