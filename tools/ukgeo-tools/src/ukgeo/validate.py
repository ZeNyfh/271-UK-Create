from __future__ import annotations

from pathlib import Path
import math
from typing import Any

import numpy as np

from .manifest import read_manifest, validate_manifest
from .tiles import read_layer_tile, river_u8_layer


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
    errors.extend(_check_layer(root, height, tiles_x, tiles_z, tile_size))
    if "surface_geology" in manifest:
        surface = manifest["surface_geology"]
        errors.extend(_check_layer(root, surface, tiles_x, tiles_z, tile_size))
    if "rivers" in manifest:
        rivers = manifest["rivers"]
        errors.extend(_check_layer(root, river_u8_layer(rivers), tiles_x, tiles_z, tile_size))
        if "order_path" in rivers:
            errors.extend(_check_layer(root, river_u8_layer(rivers, "order_path", "order"), tiles_x, tiles_z, tile_size))
        if "half_width_path" in rivers:
            errors.extend(_check_layer(root, river_u8_layer(rivers, "half_width_path", "half_width"), tiles_x, tiles_z, tile_size))
        if "preview_radius_path" in rivers:
            errors.extend(_check_layer(root, river_u8_layer(rivers, "preview_radius_path", "preview_radius"), tiles_x, tiles_z, tile_size))
    if "vegetation" in manifest:
        vegetation = manifest["vegetation"]
        cell_blocks = int(vegetation.get("cell_blocks", 1))
        veg_tiles_x = math.ceil(manifest["world"]["padded_width"] / (tile_size * cell_blocks))
        veg_tiles_z = math.ceil(manifest["world"]["padded_depth"] / (tile_size * cell_blocks))
        errors.extend(_check_layer(root, vegetation, veg_tiles_x, veg_tiles_z, tile_size))
    if "biome_regions" in manifest:
        biome_regions = manifest["biome_regions"]
        cell_blocks = int(biome_regions.get("cell_blocks", 1))
        biome_tiles_x = math.ceil(manifest["world"]["padded_width"] / (tile_size * cell_blocks))
        biome_tiles_z = math.ceil(manifest["world"]["padded_depth"] / (tile_size * cell_blocks))
        errors.extend(_check_layer(root, biome_regions, biome_tiles_x, biome_tiles_z, tile_size))
    for layer in manifest.get("ore_layers", {}).values():
        errors.extend(_check_layer(root, layer, tiles_x, tiles_z, tile_size))
    for layer in manifest.get("animal_habitats", {}).get("entities", {}).values():
        errors.extend(_check_layer(root, layer, tiles_x, tiles_z, tile_size))
    return errors


def _check_layer(root: Path, layer: dict[str, Any], tiles_x: int, tiles_z: int, tile_size: int) -> list[str]:
    errors: list[str] = []
    for z in range(tiles_z):
        for x in range(tiles_x):
            try:
                arr = read_layer_tile(root, layer, x, z, tile_size)
            except Exception as exc:
                errors.append(f"{layer.get('path')} {x:03d}_{z:03d}: {exc}")
                continue
            expected_shape = (tile_size, tile_size)
            if arr.shape != expected_shape:
                errors.append(f"{layer.get('path')} {x:03d}_{z:03d} shape {arr.shape}, expected {expected_shape}")
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
        "height": _height_summary(root, manifest["height"], tiles_x, tiles_z, tile_size, int(manifest["height"]["nodata"])),
        "ores": {},
    }
    if "surface_geology" in manifest:
        surface = manifest["surface_geology"]
        summary["surface"] = _categorical_summary(root, surface, tiles_x, tiles_z, tile_size, surface.get("classes", {}))
    if "rivers" in manifest:
        rivers = manifest["rivers"]
        summary["rivers"] = _u8_summary(root, river_u8_layer(rivers), tiles_x, tiles_z, tile_size)
        if "order_path" in rivers:
            summary["river_order"] = _u8_summary(root, river_u8_layer(rivers, "order_path", "order"), tiles_x, tiles_z, tile_size)
        if "half_width_path" in rivers:
            summary["river_half_width"] = _u8_summary(root, river_u8_layer(rivers, "half_width_path", "half_width"), tiles_x, tiles_z, tile_size)
    if "vegetation" in manifest:
        vegetation = manifest["vegetation"]
        cell_blocks = int(vegetation.get("cell_blocks", 1))
        veg_tiles_x = math.ceil(manifest["world"]["padded_width"] / (tile_size * cell_blocks))
        veg_tiles_z = math.ceil(manifest["world"]["padded_depth"] / (tile_size * cell_blocks))
        summary["vegetation"] = _categorical_summary(root, vegetation, veg_tiles_x, veg_tiles_z, tile_size, vegetation.get("classes", {}))
    if "biome_regions" in manifest:
        biome_regions = manifest["biome_regions"]
        cell_blocks = int(biome_regions.get("cell_blocks", 1))
        br_tiles_x = math.ceil(manifest["world"]["padded_width"] / (tile_size * cell_blocks))
        br_tiles_z = math.ceil(manifest["world"]["padded_depth"] / (tile_size * cell_blocks))
        summary["biome_regions"] = _categorical_summary(root, biome_regions, br_tiles_x, br_tiles_z, tile_size, biome_regions.get("classes", {}))
    for name, layer in manifest.get("ore_layers", {}).items():
        summary["ores"][name] = _u8_summary(root, layer, tiles_x, tiles_z, tile_size)
    return summary


def _height_summary(root: Path, layer: dict[str, Any], tiles_x: int, tiles_z: int, tile_size: int, nodata: int) -> dict[str, Any]:
    total = 0
    nodata_count = 0
    valid_count = 0
    min_value: int | None = None
    max_value: int | None = None
    total_value = 0
    for z in range(tiles_z):
        for x in range(tiles_x):
            arr = read_layer_tile(root, layer, x, z, tile_size).reshape(-1)
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


def _u8_summary(root: Path, layer: dict[str, Any], tiles_x: int, tiles_z: int, tile_size: int) -> dict[str, Any]:
    total = 0
    nonzero = 0
    max_value = 0
    for z in range(tiles_z):
        for x in range(tiles_x):
            arr = read_layer_tile(root, layer, x, z, tile_size).reshape(-1)
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


def _categorical_summary(root: Path, layer: dict[str, Any], tiles_x: int, tiles_z: int, tile_size: int, classes: dict[str, Any]) -> dict[str, Any]:
    total = 0
    counts: dict[int, int] = {}
    for z in range(tiles_z):
        for x in range(tiles_x):
            arr = read_layer_tile(root, layer, x, z, tile_size).reshape(-1)
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
    return {"classes": class_rows}
