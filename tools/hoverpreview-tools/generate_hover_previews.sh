#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
UKGEO_TOOLS_DIR="${UKGEO_TOOLS_DIR:-$SCRIPT_DIR/../ukgeo-tools}"
REPO_ROOT="$(cd -- "$UKGEO_TOOLS_DIR/../.." && pwd)"
ROOT="${ROOT:-$UKGEO_TOOLS_DIR/uk_world_data_gb}"
OUT_DIR=""
MAX_SIZE="${MAX_SIZE:-12000}"
STYLE="${STYLE:-auto}"
HOVERPREVIEW_GPU="${HOVERPREVIEW_GPU:-auto}"
HOVERPREVIEW_TILE_SIZE="${HOVERPREVIEW_TILE_SIZE:-256}"
HOVERPREVIEW_WORKERS="${HOVERPREVIEW_WORKERS:-0}"
HOVERPREVIEW_VISUAL_FORMAT="${HOVERPREVIEW_VISUAL_FORMAT:-png}"
HOVERPREVIEW_FORCE="${HOVERPREVIEW_FORCE:-0}"
HOVERPREVIEW_CLEAN_STALE="${HOVERPREVIEW_CLEAN_STALE:-0}"
HOVERPREVIEW_PROFILE="${HOVERPREVIEW_PROFILE:-0}"
DATA_DIR="${DATA_DIR:-$REPO_ROOT/data}"

OS_TERRAIN_ZIP="${OS_TERRAIN_ZIP:-$DATA_DIR/terr50_gagg_gb.zip}"
BGS_GEOLOGY_ZIP="${BGS_GEOLOGY_ZIP:-$DATA_DIR/BGS_Geology_625k_bedrock_gpkg.zip}"
COAL_RESOURCES_ZIP="${COAL_RESOURCES_ZIP:-$DATA_DIR/OGC_CoalResourcesForNewTechnologies.zip}"
GOLD_OCCURRENCES="${GOLD_OCCURRENCES:-$DATA_DIR/bgs_gold_occurrences.geojson}"
OSNI_DTM_ZIP="${OSNI_DTM_ZIP:-$DATA_DIR/osni_opendata_50m_dtm.zip}"
RIVERS_ZIP="${RIVERS_ZIP:-$DATA_DIR/oprvrs_gpkg_gb.zip}"
LANDCOVER_ZIP="${LANDCOVER_ZIP:-$DATA_DIR/FME_3564346A_1778997494261_5633.zip}"
IRON_OVERLAY_IMAGE="${IRON_OVERLAY_IMAGE:-$DATA_DIR/uk_iron_ore_reference_overlay.svg}"
IRON_OVERLAY_SCORE="${IRON_OVERLAY_SCORE:-180}"
IRON_OVERLAY_FIT="${IRON_OVERLAY_FIT:-outline}"
IRON_STAMP="${IRON_STAMP:-$ROOT/.iron_ore_inputs.sha256}"
ORE_RULES="${ORE_RULES:-$UKGEO_TOOLS_DIR/examples/ore_rules_625k.yml}"
SURFACE_RULES="${SURFACE_RULES:-$UKGEO_TOOLS_DIR/examples/surface_geology_625k.yml}"
ORE_JOBS="${ORE_JOBS:-1}"
VEGETATION_JOBS="${VEGETATION_JOBS:-1}"

REGENERATE_ARG=""
POSITIONAL=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --regenerate)
      if [[ $# -lt 2 ]]; then
        echo "--regenerate requires a comma-separated task list." >&2
        exit 2
      fi
      REGENERATE_ARG="$2"
      shift 2
      ;;
    --regenerate=*)
      REGENERATE_ARG="${1#*=}"
      shift
      ;;
    --root)
      if [[ $# -lt 2 ]]; then
        echo "--root requires a path." >&2
        exit 2
      fi
      ROOT="$2"
      shift 2
      ;;
    --root=*)
      ROOT="${1#*=}"
      shift
      ;;
    --out)
      if [[ $# -lt 2 ]]; then
        echo "--out requires a path." >&2
        exit 2
      fi
      OUT_DIR="$2"
      shift 2
      ;;
    --out=*)
      OUT_DIR="${1#*=}"
      shift
      ;;
    --max-size)
      if [[ $# -lt 2 ]]; then
        echo "--max-size requires a value." >&2
        exit 2
      fi
      MAX_SIZE="$2"
      shift 2
      ;;
    --max-size=*)
      MAX_SIZE="${1#*=}"
      shift
      ;;
    --style)
      if [[ $# -lt 2 ]]; then
        echo "--style requires a value." >&2
        exit 2
      fi
      STYLE="$2"
      shift 2
      ;;
    --style=*)
      STYLE="${1#*=}"
      shift
      ;;
    --tile-size)
      if [[ $# -lt 2 ]]; then
        echo "--tile-size requires a value." >&2
        exit 2
      fi
      HOVERPREVIEW_TILE_SIZE="$2"
      shift 2
      ;;
    --tile-size=*)
      HOVERPREVIEW_TILE_SIZE="${1#*=}"
      shift
      ;;
    --workers)
      if [[ $# -lt 2 ]]; then
        echo "--workers requires a value." >&2
        exit 2
      fi
      HOVERPREVIEW_WORKERS="$2"
      shift 2
      ;;
    --workers=*)
      HOVERPREVIEW_WORKERS="${1#*=}"
      shift
      ;;
    --visual-format)
      if [[ $# -lt 2 ]]; then
        echo "--visual-format requires png or webp." >&2
        exit 2
      fi
      HOVERPREVIEW_VISUAL_FORMAT="$2"
      shift 2
      ;;
    --visual-format=*)
      HOVERPREVIEW_VISUAL_FORMAT="${1#*=}"
      shift
      ;;
    --force)
      HOVERPREVIEW_FORCE=1
      shift
      ;;
    --clean-stale)
      HOVERPREVIEW_CLEAN_STALE=1
      shift
      ;;
    --profile)
      HOVERPREVIEW_PROFILE=1
      shift
      ;;
    --help|-h)
      cat <<'HELP'
Usage: generate_hover_previews.sh [ROOT] [OUT_DIR] [--regenerate TASKS]

Tasks: preview, height, rivers, vegetation, geology, ores, iron-overlay, clean, all, none
Examples:
  ./generate_hover_previews.sh --regenerate preview
  ./generate_hover_previews.sh --regenerate rivers,ores,preview
  HOVERPREVIEW_TILE_SIZE=256 HOVERPREVIEW_WORKERS=0 ./generate_hover_previews.sh
  REGENERATE=all ./generate_hover_previews.sh

Interactive menu is shown only when stdin is a TTY and neither --regenerate nor REGENERATE is set.
Non-interactive default is preview only. Use --regenerate all for a full data rebuild.
HELP
      exit 0
      ;;
    --*)
      echo "Unknown option: $1" >&2
      exit 2
      ;;
    *)
      POSITIONAL+=("$1")
      shift
      ;;
  esac
done

if [[ ${#POSITIONAL[@]} -gt 0 ]]; then
  ROOT="${POSITIONAL[0]}"
fi
if [[ ${#POSITIONAL[@]} -gt 1 ]]; then
  OUT_DIR="${POSITIONAL[1]}"
fi
if [[ ${#POSITIONAL[@]} -gt 2 ]]; then
  echo "Too many positional arguments." >&2
  exit 2
fi
OUT_DIR="${OUT_DIR:-$ROOT/hoverpreviews}"
IRON_STAMP="${IRON_STAMP:-$ROOT/.iron_ore_inputs.sha256}"

if [[ -x "$UKGEO_TOOLS_DIR/.venv/bin/ukgeo" ]]; then
  PYTHON="$UKGEO_TOOLS_DIR/.venv/bin/python"
else
  PYTHON="${PYTHON:-python3}"
fi
UKGEO_ENV=(env "PYTHONPATH=$UKGEO_TOOLS_DIR/src${PYTHONPATH:+:$PYTHONPATH}")
HOVER_ENV=(env "PYTHONPATH=$SCRIPT_DIR/src:$UKGEO_TOOLS_DIR/src${PYTHONPATH:+:$PYTHONPATH}" "HOVERPREVIEW_GPU=$HOVERPREVIEW_GPU")

TASKS=()
TASK_SOURCE=""
AUTO_IRON=0
SUPPRESS_LEGACY_TASKS=0

is_truthy() {
  case "${1:-}" in
    1|true|TRUE|yes|YES|on|ON) return 0 ;;
    *) return 1 ;;
  esac
}

is_falsey() {
  case "${1:-}" in
    0|false|FALSE|no|NO|off|OFF) return 0 ;;
    *) return 1 ;;
  esac
}

has_task() {
  local wanted="$1"
  local task
  for task in "${TASKS[@]}"; do
    [[ "$task" == "$wanted" ]] && return 0
  done
  return 1
}

add_task() {
  local task="$1"
  has_task "$task" || TASKS+=("$task")
}

remove_task() {
  local unwanted="$1"
  local kept=()
  local task
  for task in "${TASKS[@]}"; do
    [[ "$task" == "$unwanted" ]] || kept+=("$task")
  done
  TASKS=("${kept[@]}")
}

normalize_task() {
  local raw="${1,,}"
  raw="${raw//_/-}"
  raw="${raw// /}"
  case "$raw" in
    0|exit|nothing|none) printf 'none' ;;
    1|hover|hover-preview|hover-previews|preview|previews) printf 'preview' ;;
    2|height|heightmap|height-map) printf 'height' ;;
    3|river|rivers|river-order|river-widths) printf 'rivers' ;;
    4|vegetation|biomes|vegetation-biomes|vegetation/biomes) printf 'vegetation' ;;
    5|geology|surface|surface-layers|surface-geology) printf 'geology' ;;
    6|ore|ores) printf 'ores' ;;
    7|iron|iron-overlay|ironoverlay) printf 'iron-overlay' ;;
    8|all|everything) printf 'all' ;;
    9|clean|clean-output|cache) printf 'clean' ;;
    *) return 1 ;;
  esac
}

parse_tasks() {
  local spec="$1"
  local part task
  IFS=',' read -ra parts <<< "$spec"
  for part in "${parts[@]}"; do
    [[ -z "${part// /}" ]] && continue
    if ! task="$(normalize_task "$part")"; then
      echo "Unknown regenerate task: $part" >&2
      echo "Known tasks: preview, height, rivers, vegetation, geology, ores, iron-overlay, clean, all, none" >&2
      exit 2
    fi
    if [[ "$task" == "none" ]]; then
      TASKS=()
      SUPPRESS_LEGACY_TASKS=1
      continue
    fi
    if [[ "$task" == "all" ]]; then
      TASKS=()
      SUPPRESS_LEGACY_TASKS=0
      add_task height
      add_task vegetation
      add_task geology
      add_task rivers
      add_task ores
      add_task iron-overlay
      add_task preview
      continue
    fi
    SUPPRESS_LEGACY_TASKS=0
    add_task "$task"
  done
}

prompt_yes_no() {
  local prompt="$1"
  local default="$2"
  local answer
  read -r -p "$prompt" answer
  answer="${answer:-$default}"
  case "${answer,,}" in
    y|yes) return 0 ;;
    *) return 1 ;;
  esac
}

prompt_tasks() {
  cat <<'MENU'
What do you want to regenerate?

[1] Hover preview only, using existing tiles
[2] Heightmap
[3] Rivers / river order / river widths
[4] Vegetation / biome tiles
[5] Geology / surface layers
[6] Ores
[7] Iron overlay only
[8] All world data
[9] Clean output/cache
[0] Exit

You can choose multiple, e.g. 1,3,6: 
MENU
  local selection
  read -r selection
  parse_tasks "$selection"
}

task_label() {
  case "$1" in
    clean) printf 'Clean output/cache' ;;
    height) printf 'Heightmap' ;;
    vegetation) printf 'Vegetation / biome tiles' ;;
    geology) printf 'Geology / surface layers' ;;
    rivers) printf 'Rivers / river order / river widths' ;;
    ores) printf 'Ores' ;;
    iron-overlay) printf 'Iron overlay only' ;;
    preview) printf 'Hover preview' ;;
    *) printf '%s' "$1" ;;
  esac
}

ordered_tasks() {
  local order=(clean height vegetation geology rivers ores iron-overlay preview)
  local task
  for task in "${order[@]}"; do
    has_task "$task" && printf '%s\n' "$task"
  done
}

print_plan() {
  echo "Selected:"
  local any=0 task
  while IFS= read -r task; do
    any=1
    echo "- $(task_label "$task")"
  done < <(ordered_tasks)
  if [[ "$any" == "0" ]]; then
    echo "- Nothing"
  fi
}

require_file() {
  local path="$1"
  if [[ ! -f "$path" ]]; then
    echo "Missing required input: $path" >&2
    exit 1
  fi
}

require_manifest() {
  if [[ ! -f "$ROOT/manifest.json" ]]; then
    echo "Missing manifest: $ROOT/manifest.json" >&2
    echo "Run --regenerate height or --regenerate all to create the dataset manifest." >&2
    exit 1
  fi
}

setup_venv_if_needed() {
  if [[ ! -x "$UKGEO_TOOLS_DIR/.venv/bin/ukgeo" ]]; then
    python3 -m venv "$UKGEO_TOOLS_DIR/.venv"
    "$UKGEO_TOOLS_DIR/.venv/bin/python" -m pip install -e "$UKGEO_TOOLS_DIR[test]"
    PYTHON="$UKGEO_TOOLS_DIR/.venv/bin/python"
  fi
}

check_gpu() {
  export HOVERPREVIEW_GPU
  if [[ "$HOVERPREVIEW_GPU" != "0" && "$HOVERPREVIEW_GPU" != "false" && "$HOVERPREVIEW_GPU" != "off" ]]; then
    if command -v nvidia-smi >/dev/null 2>&1; then
      if "$PYTHON" -c "import cupy" >/dev/null 2>&1; then
        echo "Hover preview GPU acceleration enabled with CuPy."
      elif [[ "$HOVERPREVIEW_GPU" == "1" || "$HOVERPREVIEW_GPU" == "true" || "$HOVERPREVIEW_GPU" == "on" || "$HOVERPREVIEW_GPU" == "gpu" ]]; then
        echo "HOVERPREVIEW_GPU=$HOVERPREVIEW_GPU requested, but CuPy is not installed." >&2
        echo "Install with: $PYTHON -m pip install -e '$SCRIPT_DIR[gpu]'" >&2
        exit 1
      else
        echo "NVIDIA GPU detected, but CuPy is not installed. Install with: $PYTHON -m pip install -e '$SCRIPT_DIR[gpu]'" >&2
        echo "Continuing with CPU hover preview rendering." >&2
      fi
    elif [[ "$HOVERPREVIEW_GPU" == "1" || "$HOVERPREVIEW_GPU" == "true" || "$HOVERPREVIEW_GPU" == "on" || "$HOVERPREVIEW_GPU" == "gpu" ]]; then
      echo "HOVERPREVIEW_GPU=$HOVERPREVIEW_GPU requested, but nvidia-smi was not found." >&2
      exit 1
    fi
  fi
}

iron_input_hash() {
  {
    printf 'version=%s\n' "5"
    printf 'bgs_path=%s\n' "$BGS_GEOLOGY_ZIP"
    if [[ -f "$BGS_GEOLOGY_ZIP" ]]; then sha256sum "$BGS_GEOLOGY_ZIP"; else printf 'missing  %s\n' "$BGS_GEOLOGY_ZIP"; fi
    printf 'rules_path=%s\n' "$ORE_RULES"
    if [[ -f "$ORE_RULES" ]]; then sha256sum "$ORE_RULES"; else printf 'missing  %s\n' "$ORE_RULES"; fi
    printf 'overlay_path=%s\n' "$IRON_OVERLAY_IMAGE"
    if [[ -n "$IRON_OVERLAY_IMAGE" && -f "$IRON_OVERLAY_IMAGE" ]]; then sha256sum "$IRON_OVERLAY_IMAGE"; else printf 'missing  %s\n' "$IRON_OVERLAY_IMAGE"; fi
    printf 'overlay_score=%s\n' "$IRON_OVERLAY_SCORE"
    printf 'overlay_fit=%s\n' "$IRON_OVERLAY_FIT"
  } | sha256sum | awk '{print $1}'
}

run_height() {
  require_file "$OS_TERRAIN_ZIP"
  require_file "$OSNI_DTM_ZIP"
  require_file "$BGS_GEOLOGY_ZIP"
  mkdir -p "$ROOT"
  "${UKGEO_ENV[@]}" "$PYTHON" -m ukgeo.cli make-height-tiles \
    --os-zip "$OS_TERRAIN_ZIP" \
    --out "$ROOT" \
    --bng-min-easting 0 \
    --bng-min-northing 0 \
    --bng-max-easting 650000 \
    --bng-max-northing 1300000 \
    --world-width 25000 \
    --world-depth 50000 \
    --minecraft-min-x -17588 \
    --minecraft-min-z -36925 \
    --sea-level-y 64 \
    --height-resampling bilinear \
    --height-smoothing light \
    --height-deterrace
  "${UKGEO_ENV[@]}" "$PYTHON" -m ukgeo.cli add-osni-height-tiles \
    --osni-dtm "$OSNI_DTM_ZIP" \
    --manifest "$ROOT/manifest.json" \
    --out "$ROOT" \
    --resampling bilinear
  "${UKGEO_ENV[@]}" "$PYTHON" -m ukgeo.cli mask-height-to-bgs-land \
    --bgs "$BGS_GEOLOGY_ZIP" \
    --manifest "$ROOT/manifest.json" \
    --out "$ROOT" \
    --layer 625k_V5_BEDROCK_Geology \
    --layer 625k_V5_SUPERFICIAL_Geology \
    --buffer-metres 0 \
    --max-height-metres 30
}

run_vegetation() {
  require_manifest
  require_file "$LANDCOVER_ZIP"
  "${UKGEO_ENV[@]}" "$PYTHON" -m ukgeo.cli make-vegetation-tiles \
    --landcover "$LANDCOVER_ZIP" \
    --manifest "$ROOT/manifest.json" \
    --out "$ROOT" \
    --cell-metres 50 \
    --vegetation-smoothing light \
    --jobs "$VEGETATION_JOBS"
}

run_geology() {
  require_manifest
  require_file "$BGS_GEOLOGY_ZIP"
  require_file "$SURFACE_RULES"
  "${UKGEO_ENV[@]}" "$PYTHON" -m ukgeo.cli make-surface-geology-tiles \
    --bgs "$BGS_GEOLOGY_ZIP" \
    --rules "$SURFACE_RULES" \
    --manifest "$ROOT/manifest.json" \
    --out "$ROOT"
}

run_rivers() {
  require_manifest
  require_file "$RIVERS_ZIP"
  "${UKGEO_ENV[@]}" "$PYTHON" -m ukgeo.cli make-river-tiles \
    --rivers "$RIVERS_ZIP" \
    --manifest "$ROOT/manifest.json" \
    --out "$ROOT" \
    --width-metres 220
}

run_ores() {
  require_manifest
  require_file "$BGS_GEOLOGY_ZIP"
  require_file "$ORE_RULES"
  require_file "$COAL_RESOURCES_ZIP"
  "${UKGEO_ENV[@]}" "$PYTHON" -m ukgeo.cli make-ore-tiles \
    --bgs "$BGS_GEOLOGY_ZIP" \
    --rules "$ORE_RULES" \
    --manifest "$ROOT/manifest.json" \
    --out "$ROOT" \
    --jobs "$ORE_JOBS"
  "${UKGEO_ENV[@]}" "$PYTHON" -m ukgeo.cli make-coal-resource-tiles \
    --coal-resources "$COAL_RESOURCES_ZIP" \
    --manifest "$ROOT/manifest.json" \
    --out "$ROOT"
  if [[ -f "$GOLD_OCCURRENCES" ]]; then
    "${UKGEO_ENV[@]}" "$PYTHON" -m ukgeo.cli make-gold-occurrence-tiles \
      --gold-occurrences "$GOLD_OCCURRENCES" \
      --manifest "$ROOT/manifest.json" \
      --out "$ROOT"
  fi
}

run_iron_overlay() {
  require_manifest
  require_file "$BGS_GEOLOGY_ZIP"
  require_file "$ORE_RULES"
  require_file "$IRON_OVERLAY_IMAGE"
  local current_iron_hash previous_iron_hash
  current_iron_hash="$(iron_input_hash)"
  previous_iron_hash=""
  if [[ -f "$IRON_STAMP" ]]; then
    previous_iron_hash="$(cat "$IRON_STAMP")"
  fi
  if [[ "$AUTO_IRON" == "1" && "$current_iron_hash" == "$previous_iron_hash" ]]; then
    echo "Skipping iron overlay; iron input stamp is unchanged."
    return
  fi
  "${UKGEO_ENV[@]}" "$PYTHON" -m ukgeo.cli make-ore-tiles \
    --bgs "$BGS_GEOLOGY_ZIP" \
    --rules "$ORE_RULES" \
    --only-ore iron \
    --manifest "$ROOT/manifest.json" \
    --out "$ROOT"
  "${UKGEO_ENV[@]}" "$PYTHON" -m ukgeo.cli apply-ore-image-overlay \
    --image "$IRON_OVERLAY_IMAGE" \
    --ore iron \
    --score "$IRON_OVERLAY_SCORE" \
    --fit "$IRON_OVERLAY_FIT" \
    --manifest "$ROOT/manifest.json" \
    --out "$ROOT"
  mkdir -p "$(dirname "$IRON_STAMP")"
  printf '%s\n' "$current_iron_hash" > "$IRON_STAMP"
}

run_preview() {
  require_manifest
  check_gpu
  local args=(
    "$PYTHON" -m hoverpreview_tools.cli "$ROOT"
    --out "$OUT_DIR"
    --max-size "$MAX_SIZE"
    --style "$STYLE"
    --tile-size "$HOVERPREVIEW_TILE_SIZE"
    --workers "$HOVERPREVIEW_WORKERS"
    --visual-format "$HOVERPREVIEW_VISUAL_FORMAT"
  )
  if has_task clean; then
    args+=(--clean)
  fi
  if is_truthy "$HOVERPREVIEW_FORCE"; then
    args+=(--force)
  fi
  if is_truthy "$HOVERPREVIEW_CLEAN_STALE"; then
    args+=(--clean-stale)
  fi
  if is_truthy "$HOVERPREVIEW_PROFILE"; then
    args+=(--profile)
  fi
  "${HOVER_ENV[@]}" "${args[@]}"
  echo "Wrote hover preview stack to $OUT_DIR"
}

run_clean() {
  if has_task preview; then
    echo "Preview clean selected; hover preview export will clean $OUT_DIR before rendering."
  elif [[ -d "$OUT_DIR" ]]; then
    echo "Removing hover preview output: $OUT_DIR"
    rm -rf "$OUT_DIR"
  else
    echo "No hover preview output to clean: $OUT_DIR"
  fi
}

if [[ -n "$REGENERATE_ARG" ]]; then
  TASK_SOURCE="--regenerate"
  parse_tasks "$REGENERATE_ARG"
elif [[ -n "${REGENERATE:-}" ]]; then
  TASK_SOURCE="REGENERATE"
  parse_tasks "$REGENERATE"
elif [[ -t 0 ]]; then
  TASK_SOURCE="interactive"
  prompt_tasks
else
  TASK_SOURCE="non-interactive default"
  parse_tasks preview
  echo "No --regenerate or REGENERATE supplied and stdin is not interactive; defaulting to preview only."
  echo "Use --regenerate all or REGENERATE=all to force a full world-data rebuild."
fi

# Backward-compatible environment variables. These only imply work when explicitly set.
if [[ "$SUPPRESS_LEGACY_TASKS" != "1" ]]; then
  if [[ -n "${REBUILD_ORES:-}" ]] && is_truthy "$REBUILD_ORES"; then add_task ores; fi
  if [[ -n "${REBUILD_PREVIEW:-}" ]] && is_truthy "$REBUILD_PREVIEW"; then add_task preview; fi
  if [[ -n "${CLEAN:-}" ]] && is_truthy "$CLEAN"; then add_task clean; fi
  if [[ -n "${REBUILD_IRON:-}" ]]; then
    if [[ "$REBUILD_IRON" == "auto" ]]; then
      AUTO_IRON=1
      add_task iron-overlay
    elif is_truthy "$REBUILD_IRON"; then
      add_task iron-overlay
    elif is_falsey "$REBUILD_IRON"; then
      remove_task iron-overlay
    fi
  fi
fi

if [[ "$TASK_SOURCE" == "interactive" ]]; then
  if has_task geology && ! has_task ores; then
    if prompt_yes_no "You selected geology. Ores depend on geology. Regenerate ores too? [Y/n] " "y"; then
      add_task ores
    fi
  fi
  if { has_task height || has_task rivers || has_task vegetation || has_task geology || has_task ores || has_task iron-overlay; } && ! has_task preview; then
    if prompt_yes_no "Selected data changes affect the hover preview. Render hover preview afterward? [Y/n] " "y"; then
      add_task preview
    fi
  fi
else
  if has_task geology && ! has_task ores; then
    echo "Warning: geology selected without ores; ore tiles may be stale. Add ores to regenerate them."
  fi
  if { has_task height || has_task rivers || has_task vegetation || has_task geology || has_task ores || has_task iron-overlay; } && ! has_task preview; then
    echo "Warning: selected data changes affect hover previews, but preview was not selected. Add preview to rerender."
  fi
fi

print_plan
if [[ ${#TASKS[@]} -eq 0 ]]; then
  echo "No regeneration tasks selected."
  exit 0
fi

if [[ "$TASK_SOURCE" == "interactive" ]]; then
  if ! prompt_yes_no "Proceed? [y/N] " "n"; then
    echo "Aborted."
    exit 1
  fi
fi

setup_venv_if_needed

while IFS= read -r task; do
  case "$task" in
    clean) run_clean ;;
    height) run_height ;;
    vegetation) run_vegetation ;;
    geology) run_geology ;;
    rivers) run_rivers ;;
    ores) run_ores ;;
    iron-overlay) run_iron_overlay ;;
    preview) run_preview ;;
  esac
done < <(ordered_tasks)
