#!/usr/bin/env bash
# Reusable launcher so Kidoo logs are always visible in the IDE terminal AND saved to logs/.
# Everything the process prints streams live to this terminal and is tee'd to a timestamped
# logs/<target>-<stamp>.log you can open or tail in the IDE (a "logcat").
#
# Usage (run in the IDE terminal):
#   ./run.sh kidoo              # Spring Boot app  (gradlew bootRun)
#   ./run.sh kidoo --h2         # app with the h2 dev profile
#   ./run.sh capture            # netmirror tools/netmirror-capture/run-capture.mjs
#   ./run.sh capture --no-auto-click
#   ./run.sh discover-ott-feed  # any tools/netmirror-capture/<name>.mjs (name, with or without .mjs)
# Extra args after the target are passed straight through to the process.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$root"
mkdir -p logs
stamp="$(date +%Y%m%d-%H%M%S)"

target="${1:-}"; shift || true
case "$target" in
  kidoo)
    gargs=(bootRun)
    if [ "${1:-}" = "--h2" ]; then shift; gargs+=(-Ph2 "--args=--spring.profiles.active=h2"); fi
    gargs+=("$@")
    log="logs/kidoo-$stamp.log"
    echo "[run] kidoo -> ./gradlew ${gargs[*]}"
    echo "[run] logcat -> $log"
    ./gradlew "${gargs[@]}" 2>&1 | tee "$log"
    ;;
  ""|-h|--help)
    echo "Usage: ./run.sh <kidoo|capture|<capture-script-name>> [args...]"
    exit 0
    ;;
  *)
    name="$target"; [ "$name" = "capture" ] && name="run-capture"
    case "$name" in *.mjs) ;; *) name="$name.mjs" ;; esac
    script="tools/netmirror-capture/$name"
    if [ ! -f "$script" ]; then
      echo "Unknown target '$target'. Use 'kidoo', 'capture', or a tools/netmirror-capture/<name>.mjs script." >&2
      exit 1
    fi
    base="$(basename "$script" .mjs)"
    log="logs/$base-$stamp.log"
    echo "[run] node $script $*"
    echo "[run] logcat -> $log"
    node "$script" "$@" 2>&1 | tee "$log"
    ;;
esac
