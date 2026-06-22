#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
HOST="${HOVERPREVIEW_HOST:-127.0.0.1}"
PREVIEW_ARG="${1:-}"

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is required to run the local hover preview server." >&2
  exit 1
fi

if [[ -n "${HOVERPREVIEW_PORT:-}" ]]; then
  PORT="$HOVERPREVIEW_PORT"
else
  PORT="$(python3 - <<'PY'
import socket
for port in range(8000, 8100):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        try:
            sock.bind(("127.0.0.1", port))
        except OSError:
            continue
        print(port)
        break
else:
    raise SystemExit("No free local port found in 8000-8099")
PY
)"
fi

MANIFEST_QUERY=""
if [[ -n "$PREVIEW_ARG" ]]; then
  MANIFEST_QUERY="$(python3 - "$ROOT_DIR" "$PREVIEW_ARG" <<'PY'
import os
import sys
from pathlib import Path
from urllib.parse import quote

root = Path(sys.argv[1]).resolve()
preview = Path(sys.argv[2]).expanduser().resolve()
manifest = preview / "hover_manifest.json" if preview.is_dir() else preview
if not manifest.is_file():
    raise SystemExit(f"Could not find hover manifest: {manifest}")
try:
    rel = manifest.relative_to(root)
except ValueError:
    raise SystemExit(f"Preview manifest must be inside the repository root: {manifest}")
print("?manifest=" + quote(rel.as_posix()))
PY
)"
fi

URL="http://$HOST:$PORT/$MANIFEST_QUERY"
SERVER_PID=""

cleanup_server() {
  if [[ -z "${SERVER_PID:-}" ]]; then
    return
  fi

  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    return
  fi

  kill -TERM "$SERVER_PID" 2>/dev/null || true

  # Give the HTTP server a brief chance to exit cleanly.
  for _ in {1..20}; do
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
      break
    fi
    sleep 0.05
  done

  # If it ignored TERM, make sure it is gone before this script exits.
  if kill -0 "$SERVER_PID" 2>/dev/null; then
    kill -KILL "$SERVER_PID" 2>/dev/null || true
  fi

  wait "$SERVER_PID" 2>/dev/null || true
}

on_interrupt() {
  trap - EXIT INT TERM
  echo
  cleanup_server
  exit 130
}

on_terminate() {
  trap - EXIT INT TERM
  cleanup_server
  exit 143
}

trap cleanup_server EXIT
trap on_interrupt INT
trap on_terminate TERM

open_url() {
  if command -v xdg-open >/dev/null 2>&1; then
    xdg-open "$URL" >/dev/null 2>&1 &
  elif command -v open >/dev/null 2>&1; then
    open "$URL" >/dev/null 2>&1 &
  elif command -v powershell.exe >/dev/null 2>&1; then
    powershell.exe -NoProfile -Command "Start-Process '$URL'" >/dev/null 2>&1 &
  elif command -v cmd.exe >/dev/null 2>&1; then
    cmd.exe /C start "" "$URL" >/dev/null 2>&1 &
  else
    echo "Open $URL in your browser."
  fi
}

cd "$ROOT_DIR"
echo "Serving hover preview from $ROOT_DIR"
echo "URL: $URL"
echo "Press Ctrl-C to stop the server."
python3 -m http.server "$PORT" --bind "$HOST" >/dev/null 2>&1 &
SERVER_PID="$!"

python3 - "$URL" <<'PY'
import sys
import time
import urllib.request

url = sys.argv[1]
for _ in range(50):
    try:
        with urllib.request.urlopen(url, timeout=0.2):
            raise SystemExit(0)
    except Exception:
        time.sleep(0.1)
raise SystemExit(f"Local server did not become ready: {url}")
PY

open_url
wait "$SERVER_PID"
