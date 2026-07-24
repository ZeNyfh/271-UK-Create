#!/usr/bin/env bash
# Build the development classes and start the NeoForge client with this mod and UKGeo loaded.
set -euo pipefail

MOD_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE_ROOT="$(cd "$MOD_ROOT/../.." && pwd)"

# Use the same Java 21 selection as the distribution build when the workspace helper exists.
INSTANCE_CONFIG="$WORKSPACE_ROOT/scripts/load-minecraft-instance-config.sh"
if [[ -f "$INSTANCE_CONFIG" ]]; then
    # shellcheck source=/dev/null
    source "$INSTANCE_CONFIG"
    load_minecraft_instance_config "$WORKSPACE_ROOT"
fi

DEV_JAVA="${JAVA_DIR:-$HOME/.jdks/temurin-21.0.11}"
if [[ -x "$DEV_JAVA/bin/java" ]]; then
    export JAVA_HOME="$DEV_JAVA"
    export PATH="$JAVA_HOME/bin:$PATH"
fi

cd "$MOD_ROOT"
exec ./gradlew classes runClient "$@"
