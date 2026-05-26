#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
UKGEO_TOOLS_DIR="${UKGEO_TOOLS_DIR:-$SCRIPT_DIR/../ukgeo-tools}"
ROOT="${1:-$UKGEO_TOOLS_DIR/uk_world_data_gb}"
PREVIEWS="${PREVIEWS:-$ROOT/hoverpreviews}"

if [[ -x "$UKGEO_TOOLS_DIR/.venv/bin/ukgeo" ]]; then
  UKGEO="$UKGEO_TOOLS_DIR/.venv/bin/ukgeo"
else
  UKGEO="ukgeo"
fi

"$UKGEO" hover-map "$ROOT" --previews "$PREVIEWS"
