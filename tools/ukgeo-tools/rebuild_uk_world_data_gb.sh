#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"

OUT_ROOT="${1:-$SCRIPT_DIR/uk_world_data_gb}"
DATA_DIR="${DATA_DIR:-$REPO_ROOT/data}"
GENERATE_PREVIEWS="${GENERATE_PREVIEWS:-0}"
MAX_SIZE="${MAX_SIZE:-12000}"
LEGEND_SCALE="${LEGEND_SCALE:-20}"
ORE_JOBS="${ORE_JOBS:-1}"
VEGETATION_JOBS="${VEGETATION_JOBS:-1}"

OS_TERRAIN_ZIP="${OS_TERRAIN_ZIP:-$DATA_DIR/terr50_gagg_gb.zip}"
BGS_GEOLOGY_ZIP="${BGS_GEOLOGY_ZIP:-$DATA_DIR/BGS_Geology_625k_bedrock_gpkg.zip}"
COAL_RESOURCES_ZIP="${COAL_RESOURCES_ZIP:-$DATA_DIR/OGC_CoalResourcesForNewTechnologies.zip}"
GOLD_OCCURRENCES="${GOLD_OCCURRENCES:-$DATA_DIR/bgs_gold_occurrences.geojson}"
OSNI_DTM_ZIP="${OSNI_DTM_ZIP:-$DATA_DIR/osni_opendata_50m_dtm.zip}"
RIVERS_ZIP="${RIVERS_ZIP:-$DATA_DIR/oprvrs_gpkg_gb.zip}"
LANDCOVER_ZIP="${LANDCOVER_ZIP:-$DATA_DIR/FME_3564346A_1778997494261_5633.zip}"

ORE_RULES="${ORE_RULES:-$SCRIPT_DIR/examples/ore_rules_625k.yml}"
SURFACE_RULES="${SURFACE_RULES:-$SCRIPT_DIR/examples/surface_geology_625k.yml}"

require_file() {
  local path="$1"
  if [[ ! -f "$path" ]]; then
    echo "Missing required input: $path" >&2
    exit 1
  fi
}

require_file "$OS_TERRAIN_ZIP"
require_file "$BGS_GEOLOGY_ZIP"
require_file "$COAL_RESOURCES_ZIP"
require_file "$OSNI_DTM_ZIP"
require_file "$RIVERS_ZIP"
require_file "$LANDCOVER_ZIP"
require_file "$ORE_RULES"
require_file "$SURFACE_RULES"

cd "$SCRIPT_DIR"

if [[ ! -x ".venv/bin/ukgeo" ]]; then
  python3 -m venv .venv
  .venv/bin/python -m pip install -e ".[test]"
fi

UKGEO="$SCRIPT_DIR/.venv/bin/ukgeo"
TMP_ROOT="$OUT_ROOT.rebuild.$$"
BACKUP_ROOT="$OUT_ROOT.backup.$(date +%Y%m%d-%H%M%S)"

cleanup() {
  if [[ -d "$TMP_ROOT" ]]; then
    echo "Removing incomplete rebuild directory: $TMP_ROOT"
    rm -rf "$TMP_ROOT"
  fi
}
trap cleanup EXIT

rm -rf "$TMP_ROOT"
mkdir -p "$TMP_ROOT"

echo "Rebuilding GB runtime tiles into: $TMP_ROOT"

"$UKGEO" make-height-tiles \
  --os-zip "$OS_TERRAIN_ZIP" \
  --out "$TMP_ROOT" \
  --bng-min-easting 0 \
  --bng-min-northing 0 \
  --bng-max-easting 650000 \
  --bng-max-northing 1300000 \
  --world-width 25000 \
  --world-depth 50000 \
  --minecraft-min-x -12500 \
  --minecraft-min-z -25000 \
  --sea-level-y 64

"$UKGEO" add-osni-height-tiles \
  --osni-dtm "$OSNI_DTM_ZIP" \
  --manifest "$TMP_ROOT/manifest.json" \
  --out "$TMP_ROOT" \
  --resampling bilinear

"$UKGEO" mask-height-to-bgs-land \
  --bgs "$BGS_GEOLOGY_ZIP" \
  --manifest "$TMP_ROOT/manifest.json" \
  --out "$TMP_ROOT" \
  --layer 625k_V5_BEDROCK_Geology \
  --layer 625k_V5_SUPERFICIAL_Geology \
  --buffer-metres 0 \
  --max-height-metres 30

"$UKGEO" make-ore-tiles \
  --bgs "$BGS_GEOLOGY_ZIP" \
  --rules "$ORE_RULES" \
  --manifest "$TMP_ROOT/manifest.json" \
  --out "$TMP_ROOT" \
  --jobs "$ORE_JOBS"

"$UKGEO" make-coal-resource-tiles \
  --coal-resources "$COAL_RESOURCES_ZIP" \
  --manifest "$TMP_ROOT/manifest.json" \
  --out "$TMP_ROOT"

if [[ -f "$GOLD_OCCURRENCES" ]]; then
  "$UKGEO" make-gold-occurrence-tiles \
    --gold-occurrences "$GOLD_OCCURRENCES" \
    --manifest "$TMP_ROOT/manifest.json" \
    --out "$TMP_ROOT"
fi

"$UKGEO" make-surface-geology-tiles \
  --bgs "$BGS_GEOLOGY_ZIP" \
  --rules "$SURFACE_RULES" \
  --manifest "$TMP_ROOT/manifest.json" \
  --out "$TMP_ROOT"

"$UKGEO" make-river-tiles \
  --rivers "$RIVERS_ZIP" \
  --manifest "$TMP_ROOT/manifest.json" \
  --out "$TMP_ROOT" \
  --width-metres 220

"$UKGEO" make-vegetation-tiles \
  --landcover "$LANDCOVER_ZIP" \
  --manifest "$TMP_ROOT/manifest.json" \
  --out "$TMP_ROOT" \
  --cell-metres 50 \
  --jobs "$VEGETATION_JOBS"

"$UKGEO" validate-tiles "$TMP_ROOT"
"$UKGEO" stats "$TMP_ROOT"

if [[ "$GENERATE_PREVIEWS" == "1" ]]; then
  "$SCRIPT_DIR/generate_previews.sh" "$TMP_ROOT" "$TMP_ROOT/previews"
fi

if [[ -e "$OUT_ROOT" ]]; then
  echo "Moving existing dataset to: $BACKUP_ROOT"
  mv "$OUT_ROOT" "$BACKUP_ROOT"
fi

mv "$TMP_ROOT" "$OUT_ROOT"
trap - EXIT

echo "Rebuilt dataset: $OUT_ROOT"
if [[ -d "$BACKUP_ROOT" ]]; then
  echo "Previous dataset kept at: $BACKUP_ROOT"
fi
