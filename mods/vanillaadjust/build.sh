#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
source "$ROOT_DIR/scripts/load-minecraft-instance-config.sh"
load_minecraft_instance_config "$ROOT_DIR"
LOCAL_JAVA="$HOME/.jdks/temurin-21.0.11"
if [ -x "$LOCAL_JAVA/bin/java" ]; then
    export JAVA_HOME="$LOCAL_JAVA"
    export PATH="$JAVA_HOME/bin:$PATH"
fi

cd "$(dirname "$0")"
./gradlew clean build

JAR="$(ls -t build/libs/*.jar | head -n 1)"
rm -f "$MOD_DIR"/vanilla_adjustments-*.jar "$MOD_DIR"/vanillaadjust-*.jar
cp -f "$JAR" "$MOD_DIR/"
echo "Copied $(basename "$JAR") -> $MOD_DIR/"
