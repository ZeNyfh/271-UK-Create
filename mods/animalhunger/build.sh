#!/usr/bin/env bash
set -euo pipefail

MOD_DIR="/media/zenyfh/GoodHDD/Games/Minecraft/Instances/UK Create (1)/mods"

cd "$(dirname "$0")"
./gradlew build

JAR="$(ls -t build/libs/*.jar | head -n 1)"
find "$MOD_DIR" -maxdepth 1 -type f -iname 'animalhunger-*.jar' -delete
cp -f "$JAR" "$MOD_DIR/"
echo "Copied $(basename "$JAR") -> $MOD_DIR/"
