from __future__ import annotations

from pathlib import Path
from typing import Any
import json
import math

ORE_NAMES = (
    "coal",
    "iron",
    "copper",
    "zinc",
    "tin",
    "gold",
)


def default_u8_layer(path: str) -> dict[str, Any]:
    return {
        "path": path,
        "extension": ".u8.gz",
        "dtype": "uint8",
        "min": 0,
        "max": 255,
    }


def default_manifest(
    *,
    width: int = 25_000,
    depth: int = 50_000,
    tile_size: int = 512,
    minecraft_min_x: int = -12_500,
    minecraft_min_z: int = -25_000,
    sea_level_y: int = 64,
    bng_min_easting: float | None = None,
    bng_min_northing: float | None = None,
    bng_max_easting: float | None = None,
    bng_max_northing: float | None = None,
) -> dict[str, Any]:
    padded_width = math.ceil(width / tile_size) * tile_size
    padded_depth = math.ceil(depth / tile_size) * tile_size
    ore_layers = {name: default_u8_layer(f"ores/{name}") for name in ORE_NAMES}
    return {
        "format": "uk-raster-tiles-v1",
        "tile_size": tile_size,
        "world": {
            "width": width,
            "depth": depth,
            "padded_width": padded_width,
            "padded_depth": padded_depth,
            "minecraft_min_x": minecraft_min_x,
            "minecraft_min_z": minecraft_min_z,
            "minecraft_max_x": minecraft_min_x + width - 1,
            "minecraft_max_z": minecraft_min_z + depth - 1,
        },
        "georeferencing": {
            "crs": "EPSG:27700",
            "bng_min_easting": bng_min_easting,
            "bng_min_northing": bng_min_northing,
            "bng_max_easting": bng_max_easting,
            "bng_max_northing": bng_max_northing,
            "note": "Final map cells are Minecraft x/z blocks resampled from the source GIS extent.",
        },
        "height": {
            "path": "height",
            "extension": ".r16.gz",
            "dtype": "int16_le",
            "unit": "decimetres",
            "scale_to_metres": 0.1,
            "nodata": -32768,
            "sea_level_y": sea_level_y,
        },
        "ore_layers": ore_layers,
    }


def read_manifest(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as fh:
        return json.load(fh)


def write_manifest(path: Path, manifest: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as fh:
        json.dump(manifest, fh, indent=2)
        fh.write("\n")


def validate_manifest(manifest: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if manifest.get("format") != "uk-raster-tiles-v1":
        errors.append("format must be uk-raster-tiles-v1")
    tile_size = manifest.get("tile_size")
    if not isinstance(tile_size, int) or tile_size <= 0:
        errors.append("tile_size must be a positive integer")
    world = manifest.get("world", {})
    for key in ("width", "depth", "padded_width", "padded_depth", "minecraft_min_x", "minecraft_min_z"):
        if not isinstance(world.get(key), int):
            errors.append(f"world.{key} must be an integer")
    if isinstance(tile_size, int) and isinstance(world.get("padded_width"), int):
        if world["padded_width"] % tile_size:
            errors.append("world.padded_width must be divisible by tile_size")
    if isinstance(tile_size, int) and isinstance(world.get("padded_depth"), int):
        if world["padded_depth"] % tile_size:
            errors.append("world.padded_depth must be divisible by tile_size")
    if manifest.get("height", {}).get("dtype") != "int16_le":
        errors.append("height.dtype must be int16_le")
    return errors
