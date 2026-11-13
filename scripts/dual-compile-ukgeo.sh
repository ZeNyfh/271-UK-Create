#!/usr/bin/env bash
# Split ukgeo layer builds across local host + zenyfh@zenvibelaptop.
# Always rsync outputs home and wipe the remote job directory.
set -euo pipefail

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
UKGEO_DIR="$REPO_ROOT/tools/ukgeo-tools"
DATA_DIR="$REPO_ROOT/data"
SSH_HOST="${DUAL_SSH_HOST:-zenyfh@zenvibelaptop}"
JOB_ID="${DUAL_JOB_ID:-$(date +%Y%m%d-%H%M%S)-$$}"
REMOTE_ROOT="${DUAL_REMOTE_ROOT:-tmp/mapcreator-dual-compile}/${JOB_ID}"
REMOTE_ABS="\$HOME/${REMOTE_ROOT}"
LOCAL_OUT="${DUAL_LOCAL_OUT:-$UKGEO_DIR/uk_world_data_gb}"
LOCAL_JOBS="${DUAL_LOCAL_JOBS:-14}"
REMOTE_JOBS="${DUAL_REMOTE_JOBS:-14}"
KEEP_REMOTE="${DUAL_KEEP_REMOTE:-0}"

usage() {
  cat <<USAGE
Usage:
  $0 --remote-cmd '<ukgeo args...>' [--local-cmd '<ukgeo args...>']
  $0 --remote-layers ores,vegetation [--local-layers rivers,coal]

Env:
  DUAL_SSH_HOST DUAL_JOB_ID DUAL_REMOTE_ROOT DUAL_LOCAL_OUT
  DUAL_LOCAL_JOBS DUAL_REMOTE_JOBS DUAL_KEEP_REMOTE=1 (debug only)
  DUAL_DATA_GLOBS  comma globs under data/ to push (default: needed zips from config)

Remote scratch: ~/${REMOTE_ROOT}  (deleted on exit unless DUAL_KEEP_REMOTE=1)
Outputs merge into: ${LOCAL_OUT}
USAGE
}

REMOTE_CMD=""
LOCAL_CMD=""
REMOTE_LAYERS=""
LOCAL_LAYERS=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --remote-cmd) REMOTE_CMD="$2"; shift 2 ;;
    --local-cmd) LOCAL_CMD="$2"; shift 2 ;;
    --remote-layers) REMOTE_LAYERS="$2"; shift 2 ;;
    --local-layers) LOCAL_LAYERS="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; usage; exit 2 ;;
  esac
done

layer_to_cmd() {
  local layer="$1" jobs="$2"
  case "$layer" in
    ores) echo "make-ore-tiles --jobs $jobs" ;;
    vegetation) echo "make-vegetation-tiles --jobs $jobs" ;;
    rivers) echo "make-river-tiles" ;;
    coal) echo "make-coal-resource-tiles" ;;
    gold) echo "make-gold-occurrence-tiles" ;;
    surface) echo "make-surface-geology-tiles" ;;
    animals) echo "make-animal-habitat-tiles" ;;
    height) echo "make-height-tiles --height-jobs $jobs" ;;
    *) echo "Unknown layer: $layer" >&2; return 1 ;;
  esac
}

expand_layers() {
  local layers="$1" jobs="$2" out=()
  IFS=',' read -ra parts <<< "$layers"
  for p in "${parts[@]}"; do
    p="$(echo "$p" | xargs)"
    [[ -z "$p" ]] && continue
    out+=("$(layer_to_cmd "$p" "$jobs")")
  done
  printf '%s\n' "${out[@]}"
}

if [[ -n "$REMOTE_LAYERS" ]]; then
  REMOTE_CMD="$(expand_layers "$REMOTE_LAYERS" "$REMOTE_JOBS" | paste -sd'; -)"
fi
if [[ -n "$LOCAL_LAYERS" ]]; then
  LOCAL_CMD="$(expand_layers "$LOCAL_LAYERS" "$LOCAL_JOBS" | paste -sd'; -)"
fi

if [[ -z "$REMOTE_CMD" && -z "$LOCAL_CMD" ]]; then
  usage
  exit 2
fi

log() { printf '[dual-compile] %s\n' "$*" >&2; }

cleanup_remote() {
  if [[ "$KEEP_REMOTE" == "1" ]]; then
    log "KEEP_REMOTE=1 — leaving ${SSH_HOST}:~/${REMOTE_ROOT}"
    return 0
  fi
  log "Wiping remote job dir ~/${REMOTE_ROOT}"
  ssh "$SSH_HOST" "rm -rf -- \"\$HOME/${REMOTE_ROOT}\"" || true
}
trap cleanup_remote EXIT

mkdir -p "$LOCAL_OUT" "$UKGEO_DIR/.dual-compile-logs"
STAGE="$UKGEO_DIR/.dual-compile-logs/$JOB_ID"
mkdir -p "$STAGE/remote-out"

log "job=$JOB_ID remote=~/${REMOTE_ROOT} local_out=$LOCAL_OUT"

# Ensure local venv
if [[ ! -x "$UKGEO_DIR/.venv/bin/ukgeo" ]]; then
  log "Creating local ukgeo .venv"
  (cd "$UKGEO_DIR" && python3 -m venv .venv && .venv/bin/pip install -e ".[test]")
fi

# Prepare remote scratch: code + selected data + empty out
log "Provisioning remote scratch"
ssh "$SSH_HOST" "mkdir -p \"\$HOME/${REMOTE_ROOT}\"/{tools/ukgeo-tools,data,out,logs}"

# Sync tooling (no local tiles / venv / caches)
rsync -a --delete \
  --exclude '.venv/' \
  --exclude 'uk_world_data_gb/' \
  --exclude 'uk_world_data_gb.*/' \
  --exclude '.dual-compile-logs/' \
  --exclude '__pycache__/' \
  --exclude '*.pyc' \
  --exclude '.pytest_cache/' \
  "$UKGEO_DIR/" "$SSH_HOST:~/${REMOTE_ROOT}/tools/ukgeo-tools/"

# Sync data inputs (can be large; prefer needed archives)
if [[ -n "${DUAL_DATA_GLOBS:-}" ]]; then
  IFS=',' read -ra globs <<< "$DUAL_DATA_GLOBS"
  for g in "${globs[@]}"; do
    g="$(echo "$g" | xargs)"
    rsync -a "$DATA_DIR/"$g "$SSH_HOST:~/${REMOTE_ROOT}/data/" || true
  done
else
  # Default: sync whole data/ tree referenced by rebuild (operator can narrow with DUAL_DATA_GLOBS)
  rsync -a --delete \
    --exclude '*.part*' \
    "$DATA_DIR/" "$SSH_HOST:~/${REMOTE_ROOT}/data/"
fi

# Point remote config DATA_DIR at scratch data via env overrides in the runner
run_remote() {
  local cmd="$1"
  log "Remote start: $cmd"
  ssh "$SSH_HOST" bash -s <<REMOTE
set -euo pipefail
ROOT="\$HOME/${REMOTE_ROOT}"
cd "\$ROOT/tools/ukgeo-tools"
export DATA_DIR="\$ROOT/data"
# config.yml uses ../../data from tools/ukgeo-tools → rewrite relative by symlink
rm -rf "\$ROOT/tools/data" 2>/dev/null || true
ln -sfn "\$ROOT/data" "\$ROOT/tools/data"
if [[ ! -x .venv/bin/ukgeo ]]; then
  python3 -m venv .venv
  .venv/bin/pip -q install -U pip
  .venv/bin/pip -q install -e ".[test]"
fi
OUT="\$ROOT/out"
mkdir -p "\$OUT"
# Each semicolon-separated ukgeo subcommand
IFS=';' read -ra CMDS <<< "$cmd"
for c in "\${CMDS[@]}"; do
  c="\$(echo "\$c" | xargs)"
  [[ -z "\$c" ]] && continue
  echo "[remote] ukgeo \$c --out \$OUT" | tee -a "\$ROOT/logs/remote.log"
  # Inject --out / --manifest when missing
  if [[ "\$c" != *"--out"* ]]; then
    c="\$c --out \$OUT"
  fi
  if [[ "\$c" != *"--manifest"* ]] && [[ -f "\$OUT/manifest.json" ]]; then
    c="\$c --manifest \$OUT/manifest.json"
  fi
  # shellcheck disable=SC2086
  .venv/bin/ukgeo \$c 2>&1 | tee -a "\$ROOT/logs/remote.log"
done
REMOTE
}

run_local() {
  local cmd="$1"
  [[ -z "$cmd" ]] && return 0
  log "Local start: $cmd"
  (
    cd "$UKGEO_DIR"
    IFS=';' read -ra CMDS <<< "$cmd"
    for c in "${CMDS[@]}"; do
      c="$(echo "$c" | xargs)"
      [[ -z "$c" ]] && continue
      if [[ "$c" != *"--out"* ]]; then
        c="$c --out $LOCAL_OUT"
      fi
      if [[ "$c" != *"--manifest"* ]] && [[ -f "$LOCAL_OUT/manifest.json" ]]; then
        c="$c --manifest $LOCAL_OUT/manifest.json"
      fi
      log "ukgeo $c"
      .venv/bin/ukgeo $c 2>&1 | tee -a "$STAGE/local.log"
    done
  )
}

# Parallel: remote + local
REMOTE_STATUS=0
LOCAL_STATUS=0
if [[ -n "$REMOTE_CMD" ]]; then
  run_remote "$REMOTE_CMD" &
  REMOTE_PID=$!
else
  REMOTE_PID=""
fi
if [[ -n "$LOCAL_CMD" ]]; then
  run_local "$LOCAL_CMD" &
  LOCAL_PID=$!
else
  LOCAL_PID=""
fi

if [[ -n "${REMOTE_PID}" ]]; then
  if ! wait "$REMOTE_PID"; then REMOTE_STATUS=1; fi
fi
if [[ -n "${LOCAL_PID}" ]]; then
  if ! wait "$LOCAL_PID"; then LOCAL_STATUS=1; fi
fi

# Pull remote outputs before cleanup trap
if [[ -n "$REMOTE_CMD" ]]; then
  log "Pulling remote out/ → $LOCAL_OUT"
  mkdir -p "$LOCAL_OUT"
  rsync -a "$SSH_HOST:~/${REMOTE_ROOT}/out/" "$LOCAL_OUT/"
  rsync -a "$SSH_HOST:~/${REMOTE_ROOT}/logs/" "$STAGE/remote-logs/" || true
fi

if [[ "$REMOTE_STATUS" -ne 0 || "$LOCAL_STATUS" -ne 0 ]]; then
  log "FAILED remote=$REMOTE_STATUS local=$LOCAL_STATUS (remote still wiped on exit)"
  exit 1
fi

log "OK — outputs in $LOCAL_OUT ; remote will be wiped"
