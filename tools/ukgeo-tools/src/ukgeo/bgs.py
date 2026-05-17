from __future__ import annotations

from pathlib import Path
import tempfile
import zipfile


def resolve_gpkg(path: Path) -> tuple[Path, tempfile.TemporaryDirectory[str] | None]:
    if path.suffix.lower() == ".gpkg":
        return path, None
    if path.suffix.lower() != ".zip":
        raise ValueError("BGS input must be a .gpkg or .zip containing a .gpkg")
    tmp = tempfile.TemporaryDirectory()
    with zipfile.ZipFile(path) as zf:
        gpkg_names = [n for n in zf.namelist() if n.lower().endswith(".gpkg")]
        if not gpkg_names:
            tmp.cleanup()
            raise ValueError(f"No .gpkg found in {path}")
        zf.extract(gpkg_names[0], tmp.name)
        return Path(tmp.name) / gpkg_names[0], tmp


def likely_geology_fields(columns: list[str]) -> list[str]:
    needles = ("lex", "rock", "lith", "unit", "desc", "name", "rnk", "age", "theme")
    return [c for c in columns if any(n in c.lower() for n in needles)]

