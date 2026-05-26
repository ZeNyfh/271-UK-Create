from __future__ import annotations

from pathlib import Path
import argparse
import sys

from hoverpreview_tools.hover import open_height_hover_map


def main() -> None:
    parser = argparse.ArgumentParser(description="Open a UKGeo hover map from a sibling hoverpreviews folder.")
    parser.add_argument(
        "root",
        nargs="?",
        default=None,
        help="Optional dataset root containing hoverpreviews, or the hoverpreviews folder itself. Defaults to the executable directory.",
    )
    parser.add_argument("--previews", type=Path, default=None, help="Explicit hoverpreviews directory.")
    args = parser.parse_args()

    root = Path(args.root) if args.root else _executable_dir()
    try:
        open_height_hover_map(root=root, previews_dir=args.previews)
    except Exception as exc:
        print(f"Could not open hover map: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc


def _executable_dir() -> Path:
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path.cwd()


if __name__ == "__main__":
    main()
