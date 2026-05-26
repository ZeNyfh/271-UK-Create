#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

SOURCE_DIR="${SOURCE_DIR:-$SCRIPT_DIR/../ukgeo-tools}"
DIST_DIR="${DIST_DIR:-$SCRIPT_DIR/dist-hover}"
BUILD_WINDOWS="${BUILD_WINDOWS:-auto}"
PYTHON_BIN="${PYTHON_BIN:-python3}"
HOST_UID="$(id -u)"
HOST_GID="$(id -g)"

if [[ ! -f "$SOURCE_DIR/src/ukgeo/hover_launcher.py" ]]; then
  echo "Could not find ukgeo hover launcher at: $SOURCE_DIR/src/ukgeo/hover_launcher.py" >&2
  exit 1
fi

mkdir -p "$DIST_DIR"

verify_linux_binary() {
  local path="$1"
  if [[ ! -x "$path" ]]; then
    echo "Linux binary is missing or not executable: $path" >&2
    exit 1
  fi
  env -i HOME="${HOME:-/tmp}" DISPLAY="${DISPLAY:-}" XAUTHORITY="${XAUTHORITY:-}" PATH=/usr/bin:/bin "$path" --help >/dev/null
}

verify_windows_exe() {
  local path="$1"
  if [[ ! -s "$path" ]]; then
    echo "Windows exe is missing or empty: $path" >&2
    exit 1
  fi
  if command -v file >/dev/null 2>&1; then
    file "$path" | grep -q "PE32+ executable" || {
      echo "Windows artifact is not a 64-bit PE executable: $path" >&2
      exit 1
    }
  fi
}

build_native() {
  local name="$1"
  "$PYTHON_BIN" -m venv .venv-hover-build
  if [[ -f .venv-hover-build/bin/activate ]]; then
    # shellcheck disable=SC1091
    source .venv-hover-build/bin/activate
  else
    # shellcheck disable=SC1091
    source .venv-hover-build/Scripts/activate
  fi
  python -m pip install --upgrade pip
  python -m pip install numpy Pillow pyinstaller
  python -m PyInstaller \
    --clean \
    --onefile \
    --windowed \
    --hidden-import PIL._tkinter_finder \
    --name "$name" \
    --paths "$SOURCE_DIR/src" \
    "$SOURCE_DIR/src/ukgeo/hover_launcher.py"
  cp "dist/$name" "$DIST_DIR/$name"
  if [[ "$name" == *.exe ]]; then
    verify_windows_exe "$DIST_DIR/$name"
  else
    verify_linux_binary "$DIST_DIR/$name"
  fi
}

case "$(uname -s)" in
  Linux*)
    build_native "ukgeo-hover-linux"
    ;;
  MINGW*|MSYS*|CYGWIN*)
    build_native "ukgeo-hover.exe"
    ;;
  *)
    echo "Unsupported native build platform: $(uname -s)" >&2
    exit 1
    ;;
esac

if [[ "$(uname -s)" == Linux* && ( "$BUILD_WINDOWS" == "1" || "$BUILD_WINDOWS" == "auto" ) ]]; then
  if command -v docker >/dev/null 2>&1; then
    rm -f "$SCRIPT_DIR/dist/ukgeo-hover.exe"
    docker run --rm \
      --entrypoint /bin/sh \
      -v "$SCRIPT_DIR/../..:/src" \
      -w /src/tools/hoverpreview-tools \
      cdrx/pyinstaller-windows:python3 \
      -lc "pip install --upgrade 'numpy==1.21.6' 'Pillow==9.5.0' 'pyinstaller==5.13.2' && pyinstaller --clean --onefile --windowed --hidden-import PIL._tkinter_finder --name ukgeo-hover --paths ../ukgeo-tools/src ../ukgeo-tools/src/ukgeo/hover_launcher.py"
    docker run --rm \
      --entrypoint /bin/sh \
      -v "$SCRIPT_DIR/../..:/src" \
      -w /src/tools/hoverpreview-tools \
      cdrx/pyinstaller-windows:python3 \
      -lc "chown -R $HOST_UID:$HOST_GID dist build ukgeo-hover.spec 2>/dev/null || true"
    if [[ -f "$SCRIPT_DIR/dist/ukgeo-hover.exe" ]]; then
      cp "$SCRIPT_DIR/dist/ukgeo-hover.exe" "$DIST_DIR/ukgeo-hover.exe"
      verify_windows_exe "$DIST_DIR/ukgeo-hover.exe"
    elif [[ "$BUILD_WINDOWS" == "1" ]]; then
      echo "Windows exe build finished without creating dist/ukgeo-hover.exe." >&2
      exit 1
    else
      echo "Windows exe build finished without creating dist/ukgeo-hover.exe; skipped Windows artifact."
    fi
  elif [[ "$BUILD_WINDOWS" == "1" ]]; then
    echo "Docker is required to build the Windows exe from Linux." >&2
    exit 1
  else
    echo "Docker not found; skipped Windows exe cross-build."
  fi
fi

echo "Wrote hover binaries to $DIST_DIR"
echo "Place a hoverpreviews folder in the same directory as the executable/binary before running it."
