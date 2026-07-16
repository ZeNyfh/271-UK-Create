#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
CONFIG_FILE="$SCRIPT_DIR/config.yml"
cd "$SCRIPT_DIR"

eval "$(
  PYTHONPATH="$SCRIPT_DIR/src" \
    python3 -m ukgeo.config --config "$CONFIG_FILE" --section rebuild --format shell
)"

OUT_ROOT="${1:-$SCRIPT_DIR/uk_world_data_gb}"

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
if [[ "$USE_LEGACY_OSNI_HEIGHT" == "true" ]]; then
  require_file "$OSNI_DTM_ZIP"
else
  require_file "$COP30_ARCHIVE"
fi
require_file "$RIVERS_ZIP"
require_file "$LANDCOVER_ZIP"
require_file "$ORE_RULES"
require_file "$SURFACE_RULES"
require_file "$ANIMALS_SVG"
if [[ -n "$IRON_OVERLAY_IMAGE" ]]; then
  require_file "$IRON_OVERLAY_IMAGE"
fi
if [[ -n "$ROI_ORES_SVG" ]]; then
  require_file "$ROI_ORES_SVG"
fi

if [[ ! -x ".venv/bin/ukgeo" ]]; then
  python3 -m venv .venv
  .venv/bin/python -m pip install -e ".[test]"
fi

UKGEO="$SCRIPT_DIR/.venv/bin/ukgeo"
TMP_ROOT="$OUT_ROOT.rebuild.$$"
BACKUP_ROOT="$OUT_ROOT.backup.$(date +%Y%m%d-%H%M%S)"

if [[ "$INCLUDE_IRELAND" == "true" ]]; then
  # Western Ireland projects to negative British National Grid eastings. Expand
  # westward while preserving the existing 26 m/block scale and the 0..1300000
  # northing range used by the GB dataset.
  BNG_MIN_EASTING="${BNG_MIN_EASTING_IRELAND}"
  BNG_MIN_NORTHING="${BNG_MIN_NORTHING_IRELAND}"
  BNG_MAX_EASTING="${BNG_MAX_EASTING}"
  BNG_MAX_NORTHING="${BNG_MAX_NORTHING}"
  WORLD_DEPTH_BASE="${WORLD_DEPTH}"
  WORLD_WIDTH_BASE="${WORLD_WIDTH_IRELAND}"
else
  BNG_MIN_EASTING="0"
  BNG_MIN_NORTHING="0"
  BNG_MAX_EASTING="${BNG_MAX_EASTING}"
  BNG_MAX_NORTHING="${BNG_MAX_NORTHING}"
  WORLD_WIDTH_BASE="${WORLD_WIDTH_GB}"
  WORLD_DEPTH_BASE="${WORLD_DEPTH}"
fi

read -r WORLD_WIDTH WORLD_DEPTH < <("$SCRIPT_DIR/.venv/bin/python" - <<PY
width = max(1, int(round(float("$WORLD_WIDTH_BASE") * float("${WORLD_X_SCALE:-1.0}"))))
depth = max(1, int(round(float("$WORLD_DEPTH_BASE") * float("${WORLD_Z_SCALE:-1.0}"))))
print(width, depth)
PY
)

read -r MINECRAFT_MIN_X MINECRAFT_MIN_Z < <("$SCRIPT_DIR/.venv/bin/python" - <<PY
from ukgeo.coords import minecraft_min_for_bng_origin, NOTTINGHAM_ORIGIN_BNG_EASTING, NOTTINGHAM_ORIGIN_BNG_NORTHING
x, z = minecraft_min_for_bng_origin(
    bng_easting=NOTTINGHAM_ORIGIN_BNG_EASTING,
    bng_northing=NOTTINGHAM_ORIGIN_BNG_NORTHING,
    bng_min_easting=float("$BNG_MIN_EASTING"),
    bng_min_northing=float("$BNG_MIN_NORTHING"),
    bng_max_easting=float("$BNG_MAX_EASTING"),
    bng_max_northing=float("$BNG_MAX_NORTHING"),
    world_width=int("$WORLD_WIDTH"),
    world_depth=int("$WORLD_DEPTH"),
)
print(x, z)
PY
)

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
echo "Tile compression: $UKGEO_TILE_COMPRESSION (none writes .r16/.u8; gzip writes .r16.gz/.u8.gz)"
echo "Height extent: E $BNG_MIN_EASTING..$BNG_MAX_EASTING, N $BNG_MIN_NORTHING..$BNG_MAX_NORTHING, world ${WORLD_WIDTH}x${WORLD_DEPTH}"
echo "Axis scale: x ${WORLD_X_SCALE:-1.0}, z ${WORLD_Z_SCALE:-1.0}"
if [[ "$USE_LEGACY_OSNI_HEIGHT" == "true" ]]; then
  echo "Height overlay: legacy OSNI DTM"
else
  echo "Height overlay: COP30 Ireland/Northern Ireland/Isle of Man"
fi

"$UKGEO" make-height-tiles \
  --os-zip "$OS_TERRAIN_ZIP" \
  --out "$TMP_ROOT" \
  --bng-min-easting "$BNG_MIN_EASTING" \
  --bng-min-northing "$BNG_MIN_NORTHING" \
  --bng-max-easting "$BNG_MAX_EASTING" \
  --bng-max-northing "$BNG_MAX_NORTHING" \
  --world-width "$WORLD_WIDTH" \
  --world-depth "$WORLD_DEPTH" \
  --minecraft-min-x "$MINECRAFT_MIN_X" \
  --minecraft-min-z "$MINECRAFT_MIN_Z" \
  --sea-level-y 64 \
  --axis-scale-x "${WORLD_X_SCALE:-1.0}" \
  --axis-scale-z "${WORLD_Z_SCALE:-1.0}" \
  --height-resampling bilinear \
  --height-smoothing light \
  --height-deterrace

"$UKGEO" mask-height-to-bgs-land \
  --bgs "$BGS_GEOLOGY_ZIP" \
  --manifest "$TMP_ROOT/manifest.json" \
  --out "$TMP_ROOT" \
  --layer 625k_V5_BEDROCK_Geology \
  --layer 625k_V5_SUPERFICIAL_Geology \
  --buffer-metres 0 \
  --max-height-metres 30 \
  --preserve-height-overlays

if [[ "$USE_LEGACY_OSNI_HEIGHT" == "true" ]]; then
  "$UKGEO" add-osni-height-tiles \
    --osni-dtm "$OSNI_DTM_ZIP" \
    --manifest "$TMP_ROOT/manifest.json" \
    --out "$TMP_ROOT" \
    --resampling bilinear
else
  "$UKGEO" add-cop30-height-tiles \
    --cop30 "$COP30_ARCHIVE" \
    --manifest "$TMP_ROOT/manifest.json" \
    --out "$TMP_ROOT" \
    --resampling bilinear \
    --smoothing light \
    --height-deterrace \
    --target ireland-iom \
    --minecraft-y-offset "$COP30_MINECRAFT_Y_OFFSET" \
    --protect-mainland-gb
fi

"$UKGEO" make-ore-tiles \
  --bgs "$BGS_GEOLOGY_ZIP" \
  --rules "$ORE_RULES" \
  --manifest "$TMP_ROOT/manifest.json" \
  --out "$TMP_ROOT" \
  --jobs "$ORE_JOBS"

if [[ -n "$IRON_OVERLAY_IMAGE" ]]; then
  "$UKGEO" apply-ore-image-overlay \
    --image "$IRON_OVERLAY_IMAGE" \
    --ore iron \
    --score "$IRON_OVERLAY_SCORE" \
    --fit "$IRON_OVERLAY_FIT" \
    --manifest "$TMP_ROOT/manifest.json" \
    --out "$TMP_ROOT"
fi

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

if [[ -n "$ROI_ORES_SVG" ]]; then
  "$UKGEO" apply-named-svg-ore-overlays \
    --image "$ROI_ORES_SVG" \
    --overlay "coal=Coal:$ROI_ORE_OVERLAY_SCORE" \
    --overlay "zinc=Zinc:$ROI_ORE_OVERLAY_SCORE" \
    --overlay "copper=Copper:$ROI_ORE_OVERLAY_SCORE" \
    --fit ireland-reference \
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
  --vegetation-smoothing light \
  --jobs "$VEGETATION_JOBS"

"$UKGEO" make-animal-habitat-tiles \
  --image "$ANIMALS_SVG" \
  --fit full-frame \
  --manifest "$TMP_ROOT/manifest.json" \
  --out "$TMP_ROOT"

"$UKGEO" validate-tiles "$TMP_ROOT"
"$UKGEO" stats "$TMP_ROOT"

if [[ "$GENERATE_PREVIEWS" == "true" ]]; then
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
