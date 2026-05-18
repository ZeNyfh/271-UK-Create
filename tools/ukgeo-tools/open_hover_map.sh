#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${1:-$SCRIPT_DIR/uk_world_data_gb}"
MAX_SIZE="${MAX_SIZE:-12000}"

if [[ -x "$SCRIPT_DIR/.venv/bin/ukgeo" ]]; then
  UKGEO="$SCRIPT_DIR/.venv/bin/ukgeo"
else
  UKGEO="ukgeo"
fi

"$UKGEO" hover-map "$ROOT" --max-size "$MAX_SIZE"
