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
DATA_DIR="${DATA_DIR:-$REPO_ROOT/data}"
IRON_OVERLAY_IMAGE="${IRON_OVERLAY_IMAGE:-$DATA_DIR/uk_iron_ore_reference_overlay.svg}"
IRON_OVERLAY_SCORE="${IRON_OVERLAY_SCORE:-255}"
IRON_OVERLAY_FIT="${IRON_OVERLAY_FIT:-cover}"

if [[ -x "$UKGEO_TOOLS_DIR/.venv/bin/ukgeo" ]]; then
  PYTHON="$UKGEO_TOOLS_DIR/.venv/bin/python"
else
  PYTHON="${PYTHON:-python3}"
fi

if [[ -n "$IRON_OVERLAY_IMAGE" && -f "$IRON_OVERLAY_IMAGE" && -f "$ROOT/manifest.json" ]]; then
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
