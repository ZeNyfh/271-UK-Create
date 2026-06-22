#!/usr/bin/env bash
set -euo pipefail

INSTANCE_DIR="/media/zenyfh/GoodHDD/Games/Minecraft/Instances/UK Create (1)"
TARGET_DIR="$INSTANCE_DIR/kubejs/server_scripts"

cd "$(dirname "$0")"

if ! compgen -G "server_scripts/*.js" > /dev/null; then
  echo "No KubeJS server scripts found in $(pwd)/server_scripts"
  exit 1
fi

mkdir -p "$TARGET_DIR"
cp -v server_scripts/*.js "$TARGET_DIR/"
echo "Updated KubeJS server scripts -> $TARGET_DIR/"
