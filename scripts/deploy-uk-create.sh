#!/usr/bin/env bash
# Build workspace mods, update local client instance, optionally sync+start remote dedicated server.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT_DIR/scripts/load-minecraft-instance-config.sh"
load_minecraft_instance_config "$ROOT_DIR"

TARGET="${1:-all}" # all | client | server | start | stop | status
DO_BUILD="${DO_BUILD:-1}"
DO_DATA="${DO_DATA:-1}"
DO_START="${DO_START:-0}"

LOCAL_JAVA="${JAVA_DIR:-$HOME/.jdks/temurin-21.0.11}"
if [[ -x "$LOCAL_JAVA/bin/java" ]]; then
  export JAVA_HOME="$LOCAL_JAVA"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

CLIENT_ONLY_GLOBS=(
  'iris-*.jar'
  'Iris-*.jar'
  'MouseTweaks-*.jar'
  'Controlling-*.jar'
  'oculus-*.jar'
  'rubidium-*.jar'
  'embeddium-*.jar'
  'sodium-*.jar'
  'entityculling-*.jar'
  'notenoughanimations-*.jar'
  'skinlayers-*.jar'
  'fancymenu-*.jar'
  'mcqoy-*.jar'
  'better-advanced-tooltips-*.jar'
  'betteradvancedtooltips-*.jar'
  'Searchables-*.jar'
  'appleskin-*.jar'
  'veil-*.jar'
  'sable-companion-*.jar'
  'exposure-*.jar'
  'ponder-*.jar'
)

PROJECT_MOD_PREFIXES=(
  ukgeo-
  ukgeo-animals-
  animalhunger-
  foodspoilage-
  pollution-
  vanillaadjust-
  vanilla_adjustments-
  createenginebalance-
  enginebalance-
  realtime_localised_weather-
  realtime-localised-weather-
)

log() { printf '[deploy] %s\n' "$*" >&2; }

sync_dir() {
  local src="$1"
  local dest="$2"
  mkdir -p "$dest"
  if command -v rsync >/dev/null 2>&1; then
    rsync -a --delete "$src"/ "$dest"/
  else
    # Flatpak/dev shells may lack rsync; mirror with tar.
    local tmp
    tmp="$(mktemp -d)"
    # delete dest contents except we replace wholesale via temp swap for safety on large trees
    tar -C "$src" -cf - . | tar -C "$tmp" -xf -
    # swap
    find "$dest" -mindepth 1 -maxdepth 1 -exec rm -rf {} +
    tar -C "$tmp" -cf - . | tar -C "$dest" -xf -
    rm -rf "$tmp"
  fi
}

remote_sync_dir() {
  local src="$1"
  local remote_dest="$2"
  if command -v rsync >/dev/null 2>&1; then
    rsync -az --info=stats1       --exclude 'logs/'       --exclude 'world/'       --exclude 'world_*/'       --exclude 'crash-reports/'       "$src"/ "$SERVER_SSH_HOST:$remote_dest"/
  else
    ssh "$SERVER_SSH_HOST" "mkdir -p '$remote_dest'"
    tar -C "$src" --exclude='logs' --exclude='world' --exclude='crash-reports' -cf - . \
      | ssh "$SERVER_SSH_HOST" "tar -C '$remote_dest' -xf -"
  fi
}


require_dir() {
  [[ -d "$1" ]] || { echo "Missing directory: $1" >&2; exit 1; }
}

latest_mod_jar() {
  local mod_dir="$1"
  find "$mod_dir/build/libs" -maxdepth 1 -type f -name '*.jar' ! -name '*-sources.jar' -printf '%T@ %p\n' 2>/dev/null \
    | sort -nr | head -n1 | cut -d' ' -f2-
}

install_project_mods_into() {
  local dest_mods="$1"
  mkdir -p "$dest_mods"
  for prefix in "${PROJECT_MOD_PREFIXES[@]}"; do
    find "$dest_mods" -maxdepth 1 -type f -name "${prefix}*.jar" -delete 2>/dev/null || true
  done
  for mod_dir in "$ROOT_DIR"/mods/*; do
    [[ -d "$mod_dir/build/libs" ]] || continue
    local jar
    jar="$(latest_mod_jar "$mod_dir" || true)"
    [[ -n "${jar:-}" ]] || continue
    cp -f "$jar" "$dest_mods/"
    log "installed $(basename "$jar") -> $dest_mods"
  done
}

sync_kubejs_into() {
  local dest="$1"
  if [[ -d "$ROOT_DIR/mods/kubejs" ]]; then
    mkdir -p "$dest/kubejs"
    # Prefer repo kubejs scripts; keep other kubejs folders from pack if present.
    for sub in server_scripts startup_scripts client_scripts assets data; do
      if [[ -d "$ROOT_DIR/mods/kubejs/$sub" ]]; then
        mkdir -p "$dest/kubejs/$sub"
        sync_dir "$ROOT_DIR/mods/kubejs/$sub" "$dest/kubejs/$sub"
      fi
    done
    log "synced kubejs scripts -> $dest/kubejs"
  fi
}

sync_world_data_into() {
  local dest="$1"
  local src="$WORLD_DATA_DIR"
  if [[ ! -f "$src/manifest.json" ]]; then
    src="$INSTANCE_DIR/uk_world_data"
  fi
  if [[ ! -f "$src/manifest.json" ]]; then
    echo "No world data found at $WORLD_DATA_DIR or $INSTANCE_DIR/uk_world_data" >&2
    return 1
  fi
  mkdir -p "$dest/uk_world_data"
  # Keep hoverpreviews out of runtime game data when huge.
  local filtered
  filtered="$(mktemp -d)"
  if command -v rsync >/dev/null 2>&1; then
    rsync -a --delete \
      --exclude 'hoverpreviews/' \
      --exclude 'previews/' \
      --exclude '*.png' \
      --exclude '*.webp' \
      "$src/" "$filtered/"
  else
    tar -C "$src" --exclude='hoverpreviews' --exclude='previews' --exclude='*.png' --exclude='*.webp' -cf - . \
      | tar -C "$filtered" -xf -
  fi
  sync_dir "$filtered" "$dest/uk_world_data"
  rm -rf "$filtered"
  log "synced world data from $src -> $dest/uk_world_data"
}

build_mods() {
  log "building all mods"
  "$ROOT_DIR/buildall.sh"
}

update_client() {
  require_dir "$INSTANCE_DIR"
  require_dir "$MOD_DIR"
  install_project_mods_into "$MOD_DIR"
  sync_kubejs_into "$INSTANCE_DIR"
  if [[ "$DO_DATA" == "1" ]]; then
    sync_world_data_into "$INSTANCE_DIR"
  fi
  log "client updated: $INSTANCE_DIR"
}

is_client_only_jar() {
  local base="$1"
  local g
  for g in "${CLIENT_ONLY_GLOBS[@]}"; do
    # shellcheck disable=SC2254
    case "$base" in
      $g) return 0 ;;
    esac
  done
  [[ "$base" == *.disabled ]] && return 0
  return 1
}

ensure_remote_server_install() {
  ssh "$SERVER_SSH_HOST" bash -s << REMOTE
set -euo pipefail
REMOTE_DIR="$SERVER_REMOTE_DIR"
JAVA_HOME_REMOTE="$SERVER_JAVA_HOME"
NF_VER="$SERVER_NEOFORGE_VERSION"
export JAVA_HOME="\$JAVA_HOME_REMOTE"
export PATH="\$JAVA_HOME/bin:\$PATH"
mkdir -p "\$REMOTE_DIR"
cd "\$REMOTE_DIR"
if [[ ! -f run.sh && ! -f neoforge-"\$NF_VER"-installer.jar ]]; then
  echo "[remote] downloading NeoForge \$NF_VER installer"
  curl -fsSL -o "neoforge-\${NF_VER}-installer.jar" \
    "https://maven.neoforged.net/releases/net/neoforged/neoforge/\${NF_VER}/neoforge-\${NF_VER}-installer.jar"
fi
if [[ ! -f libraries/net/neoforged/neoforge/\$NF_VER/unix_args.txt && ! -f run.sh ]]; then
  echo "[remote] installing NeoForge server"
  java -jar "neoforge-\${NF_VER}-installer.jar" --installServer
fi
# Accept EULA
printf 'eula=true\n' > eula.txt
# Minimal server.properties
if [[ ! -f server.properties ]]; then
  cat > server.properties << PROP
motd=UK Create
max-players=10
online-mode=true
difficulty=hard
gamemode=survival
spawn-protection=0
view-distance=10
simulation-distance=8
server-port=${SERVER_PORT}
level-name=world
level-type=ukgeo\:uk_geological_create
generator-settings={}
PROP
fi
# Keep ukgeo preset pinned when properties already exist.
if grep -q '^level-type=' server.properties; then
  sed -i 's/^level-type=.*/level-type=ukgeo\\:uk_geological_create/' server.properties
else
  echo 'level-type=ukgeo\:uk_geological_create' >> server.properties
fi
if grep -q '^difficulty=' server.properties; then
  sed -i 's/^difficulty=.*/difficulty=hard/' server.properties
else
  echo 'difficulty=hard' >> server.properties
fi
mkdir -p mods config kubejs uk_world_data logs
REMOTE
}

stage_server_pack_local() {
  local stage="$ROOT_DIR/.local-keep-before-origin-sync/server-stage"
  rm -rf "$stage"
  mkdir -p "$stage/mods" "$stage/config" "$stage/kubejs"
  # Third-party mods from client instance (minus client-only)
  local jar base
  while IFS= read -r -d '' jar; do
    base="$(basename "$jar")"
    if is_client_only_jar "$base"; then
      continue
    fi
    cp -f "$jar" "$stage/mods/"
  done < <(find "$MOD_DIR" -maxdepth 1 -type f -name '*.jar' -print0)
  install_project_mods_into "$stage/mods"
  # Config + kubejs from client, overlay repo kubejs
  if [[ -d "$INSTANCE_DIR/config" ]]; then
    sync_dir "$INSTANCE_DIR/config" "$stage/config"
  fi
  if [[ -d "$INSTANCE_DIR/kubejs" ]]; then
    sync_dir "$INSTANCE_DIR/kubejs" "$stage/kubejs"
  fi
  sync_kubejs_into "$stage"
  if [[ "$DO_DATA" == "1" ]]; then
    sync_world_data_into "$stage"
  fi
  # defaultconfigs if present
  if [[ -d "$INSTANCE_DIR/defaultconfigs" ]]; then
    mkdir -p "$stage/defaultconfigs"
    sync_dir "$INSTANCE_DIR/defaultconfigs" "$stage/defaultconfigs"
  fi
  echo "$stage"
}

update_server() {
  require_dir "$INSTANCE_DIR"
  require_dir "$MOD_DIR"
  ensure_remote_server_install
  local stage
  stage="$(stage_server_pack_local)"
  log "sync pack -> $SERVER_SSH_HOST:$SERVER_REMOTE_DIR"
  remote_sync_dir "$stage" "$SERVER_REMOTE_DIR"
  ssh "$SERVER_SSH_HOST" "mkdir -p '$SERVER_REMOTE_DIR/config'"
  log "server pack synced"
  if [[ "$DO_START" == "1" ]]; then
    start_server
  fi
}

sync_jvm_args() {
  local src="$ROOT_DIR/config/server/user_jvm_args.txt"
  if [[ ! -f "$src" ]]; then
    log "missing $src"
    return 1
  fi
  scp -q "$src" "$SERVER_SSH_HOST:$SERVER_REMOTE_DIR/user_jvm_args.txt"
  log "synced user_jvm_args.txt (8G Aikar-style)"
}


start_server() {
  sync_jvm_args
  local java_bin="$SERVER_JAVA_HOME/bin/java"
  ssh "$SERVER_SSH_HOST" bash -s << REMOTE
set -euo pipefail
REMOTE_DIR="$SERVER_REMOTE_DIR"
JAVA_HOME_REMOTE="$SERVER_JAVA_HOME"
JAVA_BIN="$java_bin"
MEM="$SERVER_MEMORY"
export JAVA_HOME="\$JAVA_HOME_REMOTE"
export PATH="\$JAVA_HOME/bin:\$PATH"
cd "\$REMOTE_DIR"
mkdir -p logs
if [[ -f server.pid ]] && kill -0 "\$(cat server.pid)" 2>/dev/null; then
  echo "[remote] server already running pid=\$(cat server.pid)"
  exit 0
fi
# Prefer NeoForge run script; ensure JDK 21.
if [[ -x ./run.sh ]]; then
  # Rewrite launcher java path idempotently.
  if grep -q '^java ' run.sh; then
    sed -i "s|^java |\"\$JAVA_BIN\" |" run.sh || true
  fi
  nohup env JAVA_HOME="\$JAVA_HOME_REMOTE" PATH="\$JAVA_HOME_REMOTE/bin:\$PATH" ./run.sh nogui > logs/console.out 2>&1 &
elif [[ -f user_jvm_args.txt ]]; then
  ARGS_FILE=\$(ls libraries/net/neoforged/neoforge/*/unix_args.txt | head -n1)
  nohup "\$JAVA_BIN" -Xms\$MEM -Xmx\$MEM @user_jvm_args.txt @"\$ARGS_FILE" nogui > logs/console.out 2>&1 &
else
  echo "[remote] missing NeoForge run files in \$REMOTE_DIR" >&2
  exit 1
fi
echo \$! > server.pid
sleep 2
if kill -0 "\$(cat server.pid)" 2>/dev/null; then
  echo "[remote] started pid=\$(cat server.pid)"
else
  echo "[remote] start failed; last log:" >&2
  tail -n 40 logs/console.out >&2 || true
  exit 1
fi
REMOTE
}

stop_server() {
  ssh "$SERVER_SSH_HOST" bash -s << REMOTE
set -euo pipefail
REMOTE_DIR="$SERVER_REMOTE_DIR"
cd "\$REMOTE_DIR"
if [[ -f server.pid ]] && kill -0 "\$(cat server.pid)" 2>/dev/null; then
  kill "\$(cat server.pid)" || true
  sleep 2
  if kill -0 "\$(cat server.pid)" 2>/dev/null; then
    kill -9 "\$(cat server.pid)" || true
  fi
fi
# Kill NeoForge java still bound to this pack dir (pid file can miss children).
for p in \$(pgrep -f "neoforge/.*/unix_args.txt" || true); do
  if [[ "\$(readlink -f /proc/\$p/cwd 2>/dev/null || true)" == "\$REMOTE_DIR" ]]; then
    kill "\$p" 2>/dev/null || true
    sleep 1
    kill -9 "\$p" 2>/dev/null || true
  fi
done
rm -f server.pid world/session.lock
echo "[remote] stopped"
REMOTE
}

server_status() {
  ssh "$SERVER_SSH_HOST" bash -s << REMOTE
set -euo pipefail
REMOTE_DIR="$SERVER_REMOTE_DIR"
cd "\$REMOTE_DIR" 2>/dev/null || { echo missing; exit 0; }
if [[ -f server.pid ]] && kill -0 "\$(cat server.pid)" 2>/dev/null; then
  echo "running pid=\$(cat server.pid)"
  tail -n 15 logs/console.out 2>/dev/null || true
else
  echo "stopped"
fi
REMOTE
}

case "$TARGET" in
  client)
    [[ "$DO_BUILD" == "1" ]] && build_mods
    update_client
    ;;
  server)
    [[ "$DO_BUILD" == "1" ]] && build_mods
    update_server
    ;;
  start)
    start_server
    ;;
  stop)
    stop_server
    ;;
  status)
    server_status
    ;;
  all)
    [[ "$DO_BUILD" == "1" ]] && build_mods
    update_client
    DO_START=1 update_server
    # force start after sync
    start_server
    ;;
  *)
    echo "Usage: $0 {all|client|server|start|stop|status}" >&2
    exit 2
    ;;
esac
