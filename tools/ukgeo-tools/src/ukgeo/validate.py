from __future__ import annotations

from pathlib import Path
import gzip
import math
from typing import Any

import numpy as np

from .manifest import read_manifest, validate_manifest


def validate_tiles(root: Path) -> list[str]:
    manifest_path = root / "manifest.json"
    if not manifest_path.exists():
        return [f"Missing {manifest_path}"]
    manifest = read_manifest(manifest_path)
    errors = validate_manifest(manifest)
    tile_size = int(manifest["tile_size"])
    tiles_x = math.ceil(manifest["world"]["padded_width"] / tile_size)
    tiles_z = math.ceil(manifest["world"]["padded_depth"] / tile_size)
    height = manifest["height"]
    errors.extend(_check_layer(root / height["path"], height["extension"], tiles_x, tiles_z, tile_size * tile_size * 2))
    if "surface_geology" in manifest:
        surface = manifest["surface_geology"]
        errors.extend(_check_layer(root / surface["path"], surface["extension"], tiles_x, tiles_z, tile_size * tile_size))
    if "rivers" in manifest:
        rivers = manifest["rivers"]
        errors.extend(_check_layer(root / rivers["path"], rivers["extension"], tiles_x, tiles_z, tile_size * tile_size))
    if "vegetation" in manifest:
        vegetation = manifest["vegetation"]
        cell_blocks = int(vegetation.get("cell_blocks", 1))
        veg_tiles_x = math.ceil(manifest["world"]["padded_width"] / (tile_size * cell_blocks))
        veg_tiles_z = math.ceil(manifest["world"]["padded_depth"] / (tile_size * cell_blocks))
        errors.extend(_check_layer(root / vegetation["path"], vegetation["extension"], veg_tiles_x, veg_tiles_z, tile_size * tile_size))
    for layer in manifest.get("ore_layers", {}).values():
        errors.extend(_check_layer(root / layer["path"], layer["extension"], tiles_x, tiles_z, tile_size * tile_size))
    return errors


def _check_layer(path: Path, extension: str, tiles_x: int, tiles_z: int, expected_size: int) -> list[str]:
    errors: list[str] = []
    for z in range(tiles_z):
        for x in range(tiles_x):
            tile = path / f"{x:03d}_{z:03d}{extension}"
            if not tile.exists():
                errors.append(f"Missing tile {tile}")
                continue
            with gzip.open(tile, "rb") as fh:
                size = len(fh.read())
            if size != expected_size:
                errors.append(f"{tile} has decompressed size {size}, expected {expected_size}")
    return errors


def tile_summary(root: Path) -> dict[str, Any]:
    manifest = read_manifest(root / "manifest.json")
    tile_size = int(manifest["tile_size"])
    tiles_x = math.ceil(manifest["world"]["padded_width"] / tile_size)
    tiles_z = math.ceil(manifest["world"]["padded_depth"] / tile_size)
    summary: dict[str, Any] = {
        "world": manifest["world"],
        "georeferencing": manifest["georeferencing"],
        "tile_size": tile_size,
        "tiles_x": tiles_x,
        "tiles_z": tiles_z,
        "height": _height_summary(root / manifest["height"]["path"], tiles_x, tiles_z, tile_size, int(manifest["height"]["nodata"])),
        "ores": {},
    }
    if "surface_geology" in manifest:
        surface = manifest["surface_geology"]
        summary["surface"] = _categorical_summary(root / surface["path"], tiles_x, tiles_z, tile_size, surface.get("classes", {}))
    if "rivers" in manifest:
        summary["rivers"] = _u8_summary(root / manifest["rivers"]["path"], tiles_x, tiles_z, tile_size)
    if "vegetation" in manifest:
        vegetation = manifest["vegetation"]
        cell_blocks = int(vegetation.get("cell_blocks", 1))
        veg_tiles_x = math.ceil(manifest["world"]["padded_width"] / (tile_size * cell_blocks))
        veg_tiles_z = math.ceil(manifest["world"]["padded_depth"] / (tile_size * cell_blocks))
        summary["vegetation"] = _categorical_summary(root / vegetation["path"], veg_tiles_x, veg_tiles_z, tile_size, vegetation.get("classes", {}))
    for name, layer in manifest.get("ore_layers", {}).items():
        summary["ores"][name] = _u8_summary(root / layer["path"], tiles_x, tiles_z, tile_size)
    return summary


def _height_summary(path: Path, tiles_x: int, tiles_z: int, tile_size: int, nodata: int) -> dict[str, Any]:
    total = 0
    nodata_count = 0
    valid_count = 0
    min_value: int | None = None
    max_value: int | None = None
    total_value = 0
    for z in range(tiles_z):
        for x in range(tiles_x):
            tile = path / f"{x:03d}_{z:03d}.r16.gz"
            if not tile.exists():
                continue
            with gzip.open(tile, "rb") as fh:
                arr = np.frombuffer(fh.read(), dtype="<i2")
            total += arr.size
            valid = arr[arr != nodata].astype(np.int64)
            nodata_count += arr.size - valid.size
            valid_count += valid.size
            if valid.size:
                tile_min = int(valid.min())
                tile_max = int(valid.max())
                min_value = tile_min if min_value is None else min(min_value, tile_min)
                max_value = tile_max if max_value is None else max(max_value, tile_max)
                total_value += int(valid.sum())
    return {
        "total_cells": total,
        "valid_cells": valid_count,
        "nodata_cells": nodata_count,
        "valid_percent": (valid_count / total * 100.0) if total else 0.0,
        "min_metres": (min_value * 0.1) if min_value is not None else None,
        "mean_metres": (total_value / valid_count * 0.1) if valid_count else None,
        "max_metres": (max_value * 0.1) if max_value is not None else None,
    }


def _u8_summary(path: Path, tiles_x: int, tiles_z: int, tile_size: int) -> dict[str, Any]:
    total = 0
    nonzero = 0
    max_value = 0
    for z in range(tiles_z):
        for x in range(tiles_x):
            tile = path / f"{x:03d}_{z:03d}.u8.gz"
            if not tile.exists():
                continue
            with gzip.open(tile, "rb") as fh:
                arr = np.frombuffer(fh.read(), dtype=np.uint8)
            total += arr.size
            nonzero += int((arr > 0).sum())
            if arr.size:
                max_value = max(max_value, int(arr.max()))
    return {
        "total_cells": total,
        "nonzero_cells": nonzero,
        "nonzero_percent": (nonzero / total * 100.0) if total else 0.0,
        "max": max_value,
    }


def _categorical_summary(path: Path, tiles_x: int, tiles_z: int, tile_size: int, classes: dict[str, Any]) -> dict[str, Any]:
    total = 0
    counts: dict[int, int] = {}
    for z in range(tiles_z):
        for x in range(tiles_x):
            tile = path / f"{x:03d}_{z:03d}.u8.gz"
            if not tile.exists():
                continue
            with gzip.open(tile, "rb") as fh:
                arr = np.frombuffer(fh.read(), dtype=np.uint8)
            total += arr.size
            values, value_counts = np.unique(arr, return_counts=True)
            for value, count in zip(values, value_counts, strict=True):
                counts[int(value)] = counts.get(int(value), 0) + int(count)
    class_rows = []
    for class_id, count in sorted(counts.items()):
        meta = classes.get(str(class_id), {})
        class_rows.append(
            {
                "id": class_id,
                "name": meta.get("name", "unknown"),
                "cells": count,
                "percent": (count / total * 100.0) if total else 0.0,
            }
        )
    return {"total_cells": total, "classes": class_rows}
