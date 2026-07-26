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
# Keep this in sync with LOG_PATH in guardian_password_setup.py.
LOG="/tmp/guardian_password_setup.log"
: > "$LOG"

echo "Requesting administrator authorization (approve with any admin password)..."
echo "The one-time link will appear below as soon as setup starts."
echo "A copy of all output is saved to: $LOG"
echo

# Escape the path for embedding inside the AppleScript string literal.
esc_script=${SCRIPT//\\/\\\\}; esc_script=${esc_script//\"/\\\"}

# The Python tool daemonizes itself (double-fork) and redirects its own output to
# LOG, so this elevation returns almost immediately while the one-time server keeps
# running in the background. Stream the log so the shareable link shows up live.
/usr/bin/osascript -e "do shell script \"/usr/bin/python3 '${esc_script}'\" with administrator privileges" >/dev/null 2>&1 || {
  echo "Authorization was cancelled or failed. Nothing was changed." >&2
  exit 1
}

echo "Setup is running in the background. Streaming its output (Ctrl-C to stop watching):"
echo

# Follow the log until the tool signals it has finished (server exited), then stop.
tail -n +1 -f "$LOG" 2>/dev/null &
TAIL_PID=$!
for _ in $(seq 1 $((31 * 60))); do
  if grep -qE "The admin password is now set|No password was set before the server stopped|ERROR:" "$LOG" 2>/dev/null; then
    break
  fi
  sleep 1
done
sleep 1
kill "$TAIL_PID" 2>/dev/null || true
echo
echo "Done watching. Full output is in: $LOG"
