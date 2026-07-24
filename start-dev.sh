#!/usr/bin/env bash
# Build every workspace mod, stage only workspace-built mods in the Gradle run directory,
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

# This is a generated Gradle-run folder, not the user's modpack. Clear its previous jar stage
# so the dev client contains only modules built from this repository.
find "$DEV_MOD_DIR" -maxdepth 1 -type f -name '*.jar' -delete

# Realtime Localised Weather is supplied as the Gradle source set and UKGeo is its explicit
# Gradle runtime dependency, so staging either jar would create duplicate mod IDs.
for mod_dir in "$ROOT_DIR"/mods/*; do
    [[ -d "$mod_dir/build/libs" ]] || continue
    mod_name="$(basename "$mod_dir")"
    [[ "$mod_name" == "realtime-localised-weather" || "$mod_name" == "ukgeo" ]] && continue
    while IFS= read -r -d '' jar; do
        cp -f "$jar" "$DEV_MOD_DIR/"
    done < <(find "$mod_dir/build/libs" -maxdepth 1 -type f -name '*.jar' ! -name '*-sources.jar' -print0)
done

# Only stage external mods that the workspace modules require or explicitly integrate with.
# Do not broaden this list into a whole-modpack copy.
SUPPORT_JAR_PATTERNS=(
    'create-*.jar'
    'createdieselgenerators-*.jar'
    'create_aeronautics_*.jar'
    'create-aeronautics-*.jar'
    'createsimulated-*.jar'
    'letsdo-wildernature-*.jar'
    'Jade-*.jar'
    'SereneSeasons-*.jar'
    'sable-*.jar'
    'architectury-*.jar'
    'curios-*.jar'
    'GlitchCore-*.jar'
)
for pattern in "${SUPPORT_JAR_PATTERNS[@]}"; do
    while IFS= read -r -d '' jar; do
        cp -f "$jar" "$DEV_MOD_DIR/"
    done < <(find "$MOD_DIR" -maxdepth 1 -type f -name "$pattern" -print0)
done

if [[ -d "$ROOT_DIR/mods/kubejs/server_scripts" ]]; then
    mkdir -p "$DEV_PROJECT/run/kubejs/server_scripts"
    rsync -a --delete "$ROOT_DIR/mods/kubejs/server_scripts/" "$DEV_PROJECT/run/kubejs/server_scripts/"
fi

cd "$DEV_PROJECT"
exec ./gradlew classes runClient "$@"
