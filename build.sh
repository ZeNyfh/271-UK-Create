#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$ROOT_DIR/scripts/load-minecraft-instance-config.sh"
load_minecraft_instance_config "$ROOT_DIR"

cd "$(dirname "$0")/mods/ukgeo"
./gradlew build

JAR="$(ls -t build/libs/*.jar | head -n 1)"
rm -f "$MOD_DIR"/ukgeo*.jar
echo "Removed old ukgeo jar."
cp -f "$JAR" "$MOD_DIR/"
echo "Copied $(basename "$JAR") -> $MOD_DIR/"
