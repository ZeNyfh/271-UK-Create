#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
source "$ROOT_DIR/scripts/load-minecraft-instance-config.sh"
load_minecraft_instance_config "$ROOT_DIR"
TARGET_DIR="$INSTANCE_DIR/kubejs/server_scripts"

cd "$(dirname "$0")"

if ! compgen -G "server_scripts/*.js" > /dev/null; then
  echo "No KubeJS server scripts found in $(pwd)/server_scripts"
  exit 1
fi

mkdir -p "$TARGET_DIR"
find "$TARGET_DIR" -maxdepth 1 -type f -name '*.js' -delete
cp -v server_scripts/*.js "$TARGET_DIR/"
echo "Updated KubeJS server scripts -> $TARGET_DIR/"
