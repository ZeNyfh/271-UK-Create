#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
UKGEO_TOOLS_DIR="${UKGEO_TOOLS_DIR:-$SCRIPT_DIR/../ukgeo-tools}"
REPO_ROOT="$(cd -- "$UKGEO_TOOLS_DIR/../.." && pwd)"
ROOT="${1:-$UKGEO_TOOLS_DIR/uk_world_data_gb}"
OUT_DIR="${2:-$ROOT/hoverpreviews}"
MAX_SIZE="${MAX_SIZE:-12000}"
STYLE="${STYLE:-auto}"
CLEAN="${CLEAN:-1}"
HOVERPREVIEW_GPU="${HOVERPREVIEW_GPU:-auto}"
DATA_DIR="${DATA_DIR:-$REPO_ROOT/data}"
IRON_OVERLAY_IMAGE="${IRON_OVERLAY_IMAGE:-$DATA_DIR/uk_iron_ore_reference_overlay.svg}"
IRON_OVERLAY_SCORE="${IRON_OVERLAY_SCORE:-255}"
IRON_OVERLAY_FIT="${IRON_OVERLAY_FIT:-outline}"
BGS_GEOLOGY_ZIP="${BGS_GEOLOGY_ZIP:-$DATA_DIR/BGS_Geology_625k_bedrock_gpkg.zip}"
ORE_RULES="${ORE_RULES:-$UKGEO_TOOLS_DIR/examples/ore_rules_625k.yml}"

if [[ -x "$UKGEO_TOOLS_DIR/.venv/bin/ukgeo" ]]; then
  PYTHON="$UKGEO_TOOLS_DIR/.venv/bin/python"
else
  PYTHON="${PYTHON:-python3}"
fi

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

if [[ -n "$IRON_OVERLAY_IMAGE" && -f "$IRON_OVERLAY_IMAGE" && -f "$ROOT/manifest.json" ]]; then
  if [[ -f "$BGS_GEOLOGY_ZIP" && -f "$ORE_RULES" ]]; then
    PYTHONPATH="$UKGEO_TOOLS_DIR/src${PYTHONPATH:+:$PYTHONPATH}" "$PYTHON" -m ukgeo.cli make-ore-tiles \
      --bgs "$BGS_GEOLOGY_ZIP" \
      --rules "$ORE_RULES" \
      --only-ore iron \
      --manifest "$ROOT/manifest.json" \
      --out "$ROOT"
  else
    echo "Could not reset iron from geology before overlay; missing $BGS_GEOLOGY_ZIP or $ORE_RULES" >&2
  fi
  PYTHONPATH="$UKGEO_TOOLS_DIR/src${PYTHONPATH:+:$PYTHONPATH}" "$PYTHON" -m ukgeo.cli apply-ore-image-overlay \
    --image "$IRON_OVERLAY_IMAGE" \
    --ore iron \
    --score "$IRON_OVERLAY_SCORE" \
    --fit "$IRON_OVERLAY_FIT" \
    --manifest "$ROOT/manifest.json" \
    --out "$ROOT"
fi

ARGS=("$PYTHON" -m hoverpreview_tools.cli export "$ROOT" --out "$OUT_DIR" --max-size "$MAX_SIZE" --style "$STYLE")
if [[ "$CLEAN" == "1" ]]; then
  ARGS+=(--clean)
fi

PYTHONPATH="$SCRIPT_DIR/src:$UKGEO_TOOLS_DIR/src${PYTHONPATH:+:$PYTHONPATH}" "${ARGS[@]}"

echo "Wrote hover preview stack to $OUT_DIR"
