#!/usr/bin/env bash
# Build every workspace mod, stage the complete configured pack in the Gradle run directory,
# then start the Realtime Localised Weather NeoForge development client.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$ROOT_DIR/scripts/load-minecraft-instance-config.sh"
load_minecraft_instance_config "$ROOT_DIR"

LOCAL_JAVA="${JAVA_DIR:-$HOME/.jdks/temurin-21.0.11}"
if [[ -x "$LOCAL_JAVA/bin/java" ]]; then
    export JAVA_HOME="$LOCAL_JAVA"
    export PATH="$JAVA_HOME/bin:$PATH"
fi

"$ROOT_DIR/buildall.sh"

DEV_PROJECT="$ROOT_DIR/mods/realtime-localised-weather"
DEV_MOD_DIR="$DEV_PROJECT/run/mods"
mkdir -p "$DEV_MOD_DIR"

# The weather project supplies its own source set and UKGeo as a Gradle runtime dependency.
# Excluding their packaged jars avoids duplicate mod IDs while retaining the rest of the actual
# configured instance, including Create and other third-party dependencies.
rsync -a --delete \
    --exclude='realtime_localised_weather-*.jar' \
    --exclude='ukgeo-[0-9]*.jar' \
    "$MOD_DIR/" "$DEV_MOD_DIR/"

if [[ -d "$ROOT_DIR/mods/kubejs/server_scripts" ]]; then
    mkdir -p "$DEV_PROJECT/run/kubejs/server_scripts"
    rsync -a --delete "$ROOT_DIR/mods/kubejs/server_scripts/" "$DEV_PROJECT/run/kubejs/server_scripts/"
fi

cd "$DEV_PROJECT"
exec ./gradlew classes runClient "$@"
