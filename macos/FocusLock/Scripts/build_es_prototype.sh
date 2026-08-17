#!/bin/bash
# Builds the observe-only EndpointSecurity self-protection prototype (see
# ../Prototypes/es_selfprotect.c). Standalone clang build on purpose -- it's isolated from the
# signed SwiftPM app so the "can an unsigned ES client even run here?" test isn't entangled with
# the app's own signing.
#
#   ./Scripts/build_es_prototype.sh
#   sudo ../Prototypes/es_selfprotect            # must be root; Ctrl-C to stop
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$PROJECT_DIR/Prototypes/es_selfprotect.c"
OUT="$PROJECT_DIR/Prototypes/es_selfprotect"

echo "==> Compiling $SRC"
clang -O2 -Wall -Wextra -o "$OUT" "$SRC" -lEndpointSecurity -lbsm

echo "==> Built $OUT"
echo "    Run it (as root):  sudo \"$OUT\""
