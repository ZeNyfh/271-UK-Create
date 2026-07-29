#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

mapfile -t all_build_scripts < <(find "$ROOT_DIR/mods" -mindepth 2 -maxdepth 2 -type f -name build.sh | sort)

if [ "${#all_build_scripts[@]}" -eq 0 ]; then
    echo "No mod build scripts found under $ROOT_DIR/mods"
    exit 1
fi

# realtime-localised-weather compiles against ../ukgeo/build/libs, so ukgeo must finish first.
build_scripts=()
ukgeo_script=""
for build_script in "${all_build_scripts[@]}"; do
    mod_name="$(basename "$(dirname "$build_script")")"
    if [[ "$mod_name" == "ukgeo" ]]; then
        ukgeo_script="$build_script"
    else
        build_scripts+=("$build_script")
    fi
done
if [[ -n "$ukgeo_script" ]]; then
    build_scripts=("$ukgeo_script" "${build_scripts[@]}")
fi

for build_script in "${build_scripts[@]}"; do
    mod_dir="$(dirname "$build_script")"
    echo "==> Building $(basename "$mod_dir")"
    "$build_script"
done

echo "Built ${#build_scripts[@]} mod folders."
