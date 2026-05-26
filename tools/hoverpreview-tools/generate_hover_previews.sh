#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
UKGEO_TOOLS_DIR="${UKGEO_TOOLS_DIR:-$SCRIPT_DIR/../ukgeo-tools}"
ROOT="${1:-$UKGEO_TOOLS_DIR/uk_world_data_gb}"
OUT_DIR="${2:-$ROOT/hoverpreviews}"
MAX_SIZE="${MAX_SIZE:-12000}"
STYLE="${STYLE:-auto}"
CLEAN="${CLEAN:-1}"

if [[ -x "$UKGEO_TOOLS_DIR/.venv/bin/ukgeo" ]]; then
  UKGEO="$UKGEO_TOOLS_DIR/.venv/bin/ukgeo"
else
  UKGEO="ukgeo"
fi

ARGS=("$UKGEO" export-hover-previews "$ROOT" --out "$OUT_DIR" --max-size "$MAX_SIZE" --style "$STYLE")
if [[ "$CLEAN" == "1" ]]; then
  ARGS+=(--clean)
fi

"${ARGS[@]}"

echo "Wrote hover preview stack to $OUT_DIR"
