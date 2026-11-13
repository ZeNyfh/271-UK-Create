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
      $0 !~ /^[[:space:]]*#/ && $1 ~ "^[[:space:]]*" key "[[:space:]]*$" {
        value = substr($0, index($0, ":") + 1)
        gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
        gsub(/^"|"$/, "", value)
        print value
        exit
      }
    ' "$config_file"
  }

  # Nested keys under server: are indented two spaces in our file.
  yaml_server_value() {
    local key="$1"
    awk -F: -v key="$key" '
      $0 ~ /^server:[[:space:]]*$/ { in_server=1; next }
      in_server && $0 ~ /^[^[:space:]#]/ { in_server=0 }
      in_server && $0 !~ /^[[:space:]]*#/ && $1 ~ "^[[:space:]]+" key "[[:space:]]*$" {
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
  JAVA_DIR="$(yaml_value java_dir)"
  WORLD_DATA_DIR="$(yaml_value world_data_dir)"
  SERVER_SSH_HOST="$(yaml_server_value ssh_host)"
  SERVER_REMOTE_DIR="$(yaml_server_value remote_dir)"
  SERVER_JAVA_HOME="$(yaml_server_value java_home)"
  SERVER_NEOFORGE_VERSION="$(yaml_server_value neoforge_version)"
  SERVER_MC_VERSION="$(yaml_server_value minecraft_version)"
  SERVER_MEMORY="$(yaml_server_value memory)"
  SERVER_PORT="$(yaml_server_value port)"

  if [ -z "$INSTANCE_DIR" ] || [ -z "$MOD_DIR" ]; then
    echo "Config must define instance_dir and mod_dir in $config_file" >&2
    return 1
  fi

  if [[ "$WORLD_DATA_DIR" != /* ]]; then
    WORLD_DATA_DIR="$repo_root/$WORLD_DATA_DIR"
  fi

  export INSTANCE_DIR MOD_DIR JAVA_DIR WORLD_DATA_DIR
  export SERVER_SSH_HOST SERVER_REMOTE_DIR SERVER_JAVA_HOME
  export SERVER_NEOFORGE_VERSION SERVER_MC_VERSION SERVER_MEMORY SERVER_PORT
}
