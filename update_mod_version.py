#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parent
UKGEO_ROOT = ROOT / "mods" / "ukgeo"
VERSION_FILES = [
    UKGEO_ROOT / "gradle.properties",
]
VERSION_RE = re.compile(r"(?m)^(mod_version\s*=\s*)([^\s#]+)(.*)$")


def read_current_version() -> str:
    versions: set[str] = set()
    for path in VERSION_FILES:
        text = path.read_text(encoding="utf-8")
        match = VERSION_RE.search(text)
        if not match:
            raise SystemExit(f"Could not find mod_version in {path}")
        versions.add(match.group(2))
    if len(versions) != 1:
        found = ", ".join(sorted(versions))
        raise SystemExit(f"Version files disagree: {found}")
    return versions.pop()


def update_version(new_version: str) -> list[Path]:
    if not re.fullmatch(r"[0-9]+(?:\.[0-9]+){1,4}(?:[-+][A-Za-z0-9_.-]+)?", new_version):
        raise SystemExit(f"Refusing invalid version: {new_version!r}")
    changed: list[Path] = []
    for path in VERSION_FILES:
        text = path.read_text(encoding="utf-8")
        updated, count = VERSION_RE.subn(rf"\g<1>{new_version}\3", text, count=1)
        if count != 1:
            raise SystemExit(f"Could not update mod_version in {path}")
        if updated != text:
            path.write_text(updated, encoding="utf-8")
            changed.append(path)
    return changed


def main() -> int:
    current = read_current_version()
    print(f"Current UKGeo mod version: {current}")
    new_version = input("New version (leave blank to cancel): ").strip()
    if not new_version:
        print("Cancelled.")
        return 0
    changed = update_version(new_version)
    print(f"Updated UKGeo mod version: {current} -> {new_version}")
    for path in changed:
        print(f"  {path.relative_to(ROOT)}")
    if not changed:
        print("No files changed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
