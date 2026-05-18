#!/usr/bin/env bash
set -euo pipefail

ROOT="${1:-./uk_world_data_gb}"
OUT_DIR="${2:-$ROOT/previews}"
MAX_SIZE="${MAX_SIZE:-12000}"
LEGEND_SCALE="${LEGEND_SCALE:-20}"

if [[ -x ".venv/bin/ukgeo" ]]; then
  UKGEO=".venv/bin/ukgeo"
else
  UKGEO="ukgeo"
fi

mkdir -p "$OUT_DIR"

"$UKGEO" preview "$ROOT" --layer height --max-size "$MAX_SIZE" --out "$OUT_DIR/height.png"
"$UKGEO" preview "$ROOT" --layer rivers --max-size "$MAX_SIZE" --legend-scale "$LEGEND_SCALE" --out "$OUT_DIR/rivers.png"
"$UKGEO" preview "$ROOT" --layer surface --max-size "$MAX_SIZE" --legend-scale "$LEGEND_SCALE" --out "$OUT_DIR/surface_geology.png"
"$UKGEO" preview "$ROOT" --layer vegetation --max-size "$MAX_SIZE" --legend-scale "$LEGEND_SCALE" --out "$OUT_DIR/vegetation.png"
"$UKGEO" preview "$ROOT" --layer ores --max-size "$MAX_SIZE" --legend-scale "$LEGEND_SCALE" --out "$OUT_DIR/ores_all.png"

echo "Wrote previews to $OUT_DIR"
