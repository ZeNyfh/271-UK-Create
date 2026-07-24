#!/usr/bin/env bash

load_minecraft_instance_config() {
  local repo_root="$1"
  local config_file="$repo_root/config/minecraft-instance.yml"

  if [ ! -f "$config_file" ]; then
    echo "Missing Minecraft instance config: $config_file" >&2
    return 1
  fi

  yaml_value() {
    local key="$1"
    awk -F: -v key="$key" '
      $0 !~ /^[[:space:]]*#/ && $1 == key {
        value = substr($0, index($0, ":") + 1)
        gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
        gsub(/^"|"$/, "", value)
        print value
        exit
      }
    ' "$config_file"
  }

  INSTANCE_DIR="$(yaml_value instance_dir)"
  MOD_DIR="$(yaml_value mod_dir)"

  if [ -z "$INSTANCE_DIR" ] || [ -z "$MOD_DIR" ]; then
    echo "Config must define instance_dir and mod_dir in $config_file" >&2
    return 1
  fi

  export INSTANCE_DIR MOD_DIR
}
