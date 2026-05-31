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
REBUILD_IRON="${REBUILD_IRON:-auto}"
IRON_STAMP="${IRON_STAMP:-$ROOT/.iron_ore_inputs.sha256}"

if [[ -x "$SCRIPT_DIR/.venv/bin/ukgeo" ]]; then
  UKGEO="$SCRIPT_DIR/.venv/bin/ukgeo"
else
  UKGEO="ukgeo"
fi

mkdir -p "$OUT_DIR"

iron_input_hash() {
  {
    printf 'version=%s\n' "3"
    printf 'bgs_path=%s\n' "$BGS_GEOLOGY_ZIP"
    if [[ -f "$BGS_GEOLOGY_ZIP" ]]; then
      sha256sum "$BGS_GEOLOGY_ZIP"
    else
      printf 'missing  %s\n' "$BGS_GEOLOGY_ZIP"
    fi
    printf 'rules_path=%s\n' "$ORE_RULES"
    if [[ -f "$ORE_RULES" ]]; then
      sha256sum "$ORE_RULES"
    else
      printf 'missing  %s\n' "$ORE_RULES"
    fi
    printf 'overlay_path=%s\n' "$IRON_OVERLAY_IMAGE"
    if [[ -n "$IRON_OVERLAY_IMAGE" && -f "$IRON_OVERLAY_IMAGE" ]]; then
      sha256sum "$IRON_OVERLAY_IMAGE"
    else
      printf 'missing  %s\n' "$IRON_OVERLAY_IMAGE"
    fi
    printf 'overlay_score=%s\n' "$IRON_OVERLAY_SCORE"
    printf 'overlay_fit=%s\n' "$IRON_OVERLAY_FIT"
  } | sha256sum | awk '{print $1}'
}

if [[ -n "$IRON_OVERLAY_IMAGE" && -f "$IRON_OVERLAY_IMAGE" && -f "$ROOT/manifest.json" ]]; then
  current_iron_hash="$(iron_input_hash)"
  previous_iron_hash=""
  if [[ -f "$IRON_STAMP" ]]; then
    previous_iron_hash="$(cat "$IRON_STAMP")"
  fi
  if [[ "$REBUILD_IRON" == "0" || "$REBUILD_IRON" == "false" || "$REBUILD_IRON" == "off" ]]; then
    echo "Skipping iron rebuild because REBUILD_IRON=$REBUILD_IRON."
  elif [[ "$REBUILD_IRON" == "auto" && "$current_iron_hash" == "$previous_iron_hash" ]]; then
    echo "Skipping iron rebuild; iron input stamp is unchanged."
  elif [[ -f "$BGS_GEOLOGY_ZIP" && -f "$ORE_RULES" ]]; then
    "$UKGEO" make-ore-tiles \
      --bgs "$BGS_GEOLOGY_ZIP" \
      --rules "$ORE_RULES" \
      --only-ore iron \
      --manifest "$ROOT/manifest.json" \
      --out "$ROOT"
    "$UKGEO" apply-ore-image-overlay \
      --image "$IRON_OVERLAY_IMAGE" \
      --ore iron \
      --score "$IRON_OVERLAY_SCORE" \
      --fit "$IRON_OVERLAY_FIT" \
      --manifest "$ROOT/manifest.json" \
      --out "$ROOT"
    mkdir -p "$(dirname "$IRON_STAMP")"
    printf '%s\n' "$current_iron_hash" > "$IRON_STAMP"
  else
    echo "Could not reset iron from geology before overlay; missing $BGS_GEOLOGY_ZIP or $ORE_RULES" >&2
  fi
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
