#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

mapfile -t build_scripts < <(find "$ROOT_DIR/mods" -mindepth 2 -maxdepth 2 -type f -name build.sh | sort)

if [ "${#build_scripts[@]}" -eq 0 ]; then
    echo "No mod build scripts found under $ROOT_DIR/mods"
    exit 1
fi

for build_script in "${build_scripts[@]}"; do
    mod_dir="$(dirname "$build_script")"
    echo "==> Building $(basename "$mod_dir")"
    "$build_script"
done

echo "Built ${#build_scripts[@]} mod folders."
