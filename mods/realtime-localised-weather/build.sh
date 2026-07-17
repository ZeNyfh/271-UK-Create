#!/usr/bin/env sh
set -eu

JAVA_DIR="${JAVA_DIR:-$HOME/.jdks/temurin-21.0.11}"
export JAVA_HOME="$JAVA_DIR"
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew build
