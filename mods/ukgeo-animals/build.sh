#!/usr/bin/env bash
set -euo pipefail

MOD_DIR="/media/zenyfh/GoodHDD/Games/Minecraft/Instances/UK Create (1)/mods"
LOCAL_JAVA="$HOME/.jdks/temurin-21.0.11"
if [ -x "$LOCAL_JAVA/bin/java" ]; then
    export JAVA_HOME="$LOCAL_JAVA"
    export PATH="$JAVA_HOME/bin:$PATH"
fi

cd "$(dirname "$0")"
./gradlew build

JAR="$(ls -t build/libs/*.jar | head -n 1)"
rm -f "$MOD_DIR"/ukgeo-animals-*.jar
cp -f "$JAR" "$MOD_DIR/"
echo "Copied $(basename "$JAR") -> $MOD_DIR/"
