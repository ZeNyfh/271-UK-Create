#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
ROOT="${1:-./uk_world_data_gb}"
OUT_DIR="${2:-$ROOT/previews}"
MAX_SIZE="${MAX_SIZE:-12000}"
LEGEND_SCALE="${LEGEND_SCALE:-20}"
DATA_DIR="${DATA_DIR:-$REPO_ROOT/data}"
IRON_OVERLAY_IMAGE="${IRON_OVERLAY_IMAGE:-$DATA_DIR/uk_iron_ore_reference_overlay.svg}"
IRON_OVERLAY_SCORE="${IRON_OVERLAY_SCORE:-255}"
IRON_OVERLAY_FIT="${IRON_OVERLAY_FIT:-outline}"
BGS_GEOLOGY_ZIP="${BGS_GEOLOGY_ZIP:-$DATA_DIR/BGS_Geology_625k_bedrock_gpkg.zip}"
ORE_RULES="${ORE_RULES:-$SCRIPT_DIR/examples/ore_rules_625k.yml}"

if [[ -x "$SCRIPT_DIR/.venv/bin/ukgeo" ]]; then
  UKGEO="$SCRIPT_DIR/.venv/bin/ukgeo"
else
  UKGEO="ukgeo"
fi

mkdir -p "$OUT_DIR"

if [[ -n "$IRON_OVERLAY_IMAGE" && -f "$IRON_OVERLAY_IMAGE" && -f "$ROOT/manifest.json" ]]; then
  if [[ -f "$BGS_GEOLOGY_ZIP" && -f "$ORE_RULES" ]]; then
    "$UKGEO" make-ore-tiles \
      --bgs "$BGS_GEOLOGY_ZIP" \
      --rules "$ORE_RULES" \
      --only-ore iron \
      --manifest "$ROOT/manifest.json" \
      --out "$ROOT"
  else
    echo "Could not reset iron from geology before overlay; missing $BGS_GEOLOGY_ZIP or $ORE_RULES" >&2
  fi
  "$UKGEO" apply-ore-image-overlay \
    --image "$IRON_OVERLAY_IMAGE" \
    --ore iron \
    --score "$IRON_OVERLAY_SCORE" \
    --fit "$IRON_OVERLAY_FIT" \
    --manifest "$ROOT/manifest.json" \
    --out "$ROOT"
fi

"$UKGEO" preview "$ROOT" --layer height --max-size "$MAX_SIZE" --out "$OUT_DIR/height.png"

preview_optional() {
  local layer="$1"
  local out="$2"
  if ! "$UKGEO" preview "$ROOT" --layer "$layer" --max-size "$MAX_SIZE" --legend-scale "$LEGEND_SCALE" --out "$out"; then
    echo "Skipped optional preview layer: $layer" >&2
  fi
}

preview_optional rivers "$OUT_DIR/rivers.png"
preview_optional surface "$OUT_DIR/surface_geology.png"
preview_optional vegetation "$OUT_DIR/vegetation.png"
preview_optional ores "$OUT_DIR/ores_all.png"

echo "Wrote previews to $OUT_DIR"
