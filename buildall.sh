#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

mapfile -t build_scripts < <(find "$ROOT_DIR/mods" -mindepth 2 -maxdepth 2 -type f -name build.sh | sort)

if [ "${#build_scripts[@]}" -eq 0 ]; then
    echo "No mod build scripts found under $ROOT_DIR/mods"
    exit 1
fi

MAX_JOBS="${BUILD_JOBS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 1)}"
if ! [[ "$MAX_JOBS" =~ ^[1-9][0-9]*$ ]]; then
    echo "BUILD_JOBS must be a positive integer (got: $MAX_JOBS)" >&2
    exit 1
fi

active_jobs=0
failed_builds=0

reap_one() {
    if ! wait -n; then
        failed_builds=$((failed_builds + 1))
    fi
    active_jobs=$((active_jobs - 1))
}

for build_script in "${build_scripts[@]}"; do
    mod_dir="$(dirname "$build_script")"
    mod_name="$(basename "$mod_dir")"
    echo "==> Building $mod_name"
    ("$build_script" 2>&1 | sed -u "s/^/[$mod_name] /") &
    active_jobs=$((active_jobs + 1))

    if (( active_jobs >= MAX_JOBS )); then
        reap_one
    fi
done

while (( active_jobs > 0 )); do
    reap_one
done

if (( failed_builds > 0 )); then
    echo "$failed_builds mod build(s) failed." >&2
    exit 1
fi

echo "Built ${#build_scripts[@]} mod folders."
