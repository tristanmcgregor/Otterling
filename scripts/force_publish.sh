#!/usr/bin/env bash
# Convenience wrapper around `sudo otterling-release --force-publish`.
# Skips the AI review gate and publishes the given commit directly.
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <reason> [git-sha]" >&2
  echo "  reason   Required. Logged and posted to the GitHub commit status." >&2
  echo "  git-sha  Optional. Defaults to current HEAD." >&2
  exit 1
fi

REASON="$1"
SHA="${2:-$(git -C "$(dirname "${BASH_SOURCE[0]}")/.." rev-parse HEAD)}"

echo "==> Force-publishing $SHA"
echo "==> Reason: $REASON"
exec sudo otterling-release --git-sha "$SHA" --force-publish "$REASON"
