#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent
MODS_ROOT = ROOT / "mods"
VERSION_RE = re.compile(r"(?m)^(mod_version\s*=\s*)([^\s#]+)(.*)$")
MOD_ID_RE = re.compile(r"(?m)^mod_id\s*=\s*(\S+)\s*$")
MOD_NAME_RE = re.compile(r"(?m)^mod_name\s*=\s*(.+?)\s*$")


def discover_mods() -> list[tuple[str, str, Path]]:
    mods: list[tuple[str, str, Path]] = []
    for path in sorted(MODS_ROOT.glob("*/gradle.properties")):
        text = path.read_text(encoding="utf-8")
        if not VERSION_RE.search(text):
            continue
        mod_id_match = MOD_ID_RE.search(text)
        mod_name_match = MOD_NAME_RE.search(text)
        label = mod_name_match.group(1) if mod_name_match else path.parent.name
        mod_id = mod_id_match.group(1) if mod_id_match else path.parent.name
        mods.append((mod_id, label, path))
    return mods


def choose_mod() -> tuple[str, str, Path] | None:
    mods = discover_mods()
    if not mods:
        raise SystemExit(f"No mod_version entries found under {MODS_ROOT.relative_to(ROOT)}")

    print("Which mod version do you want to update?")
    for index, (mod_id, label, path) in enumerate(mods, start=1):
        current = read_current_version([path])
        print(f"  {index}. {label} ({mod_id}) - {current}")

    choice = input("Mod number or id (leave blank to cancel): ").strip()
    if not choice:
        print("Cancelled.")
        return None

    if choice.isdigit():
        index = int(choice)
        if 1 <= index <= len(mods):
            return mods[index - 1]

    normalized = choice.casefold()
    for mod in mods:
        mod_id, label, path = mod
        if normalized in {mod_id.casefold(), label.casefold(), path.parent.name.casefold()}:
            return mod

    valid = ", ".join(mod_id for mod_id, _, _ in mods)
    raise SystemExit(f"Unknown mod {choice!r}. Valid mod ids: {valid}")


def read_current_version(version_files: list[Path]) -> str:
    versions: set[str] = set()
    for path in version_files:
        text = path.read_text(encoding="utf-8")
        match = VERSION_RE.search(text)
        if not match:
            raise SystemExit(f"Could not find mod_version in {path}")
        versions.add(match.group(2))
    if len(versions) != 1:
        found = ", ".join(sorted(versions))
        raise SystemExit(f"Version files disagree: {found}")
    return versions.pop()


def update_version(new_version: str, version_files: list[Path]) -> list[Path]:
    if not re.fullmatch(r"[0-9]+(?:\.[0-9]+){1,4}(?:[-+][A-Za-z0-9_.-]+)?", new_version):
        raise SystemExit(f"Refusing invalid version: {new_version!r}")
    changed: list[Path] = []
    for path in version_files:
        text = path.read_text(encoding="utf-8")
        updated, count = VERSION_RE.subn(rf"\g<1>{new_version}\3", text, count=1)
        if count != 1:
            raise SystemExit(f"Could not update mod_version in {path}")
        if updated != text:
            path.write_text(updated, encoding="utf-8")
            changed.append(path)
    return changed


def main() -> int:
    selected = choose_mod()
    if selected is None:
        return 0
    mod_id, label, version_file = selected
    version_files = [version_file]
    current = read_current_version(version_files)
    print(f"Current {label} ({mod_id}) mod version: {current}")
    new_version = input("New version (leave blank to cancel): ").strip()
    if not new_version:
        print("Cancelled.")
        return 0
    changed = update_version(new_version, version_files)
    print(f"Updated {label} ({mod_id}) mod version: {current} -> {new_version}")
    for path in changed:
        print(f"  {path.relative_to(ROOT)}")
    if not changed:
        print("No files changed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
