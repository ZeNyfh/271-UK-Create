from __future__ import annotations

from pathlib import Path
import math
import re

import geopandas as gpd
import numpy as np
import yaml
from rasterio.enums import MergeAlg
from rasterio.features import rasterize
from rasterio.transform import from_bounds
from rich.console import Console
from tqdm import tqdm

from .bgs import resolve_gpkg
from .manifest import read_manifest, write_manifest
from .tiles import write_u8_tile

console = Console()


def make_surface_geology_tiles(*, bgs: Path, rules: Path, manifest_path: Path, out: Path, debug_geotiff: Path | None = None) -> None:
    manifest = read_manifest(manifest_path)
    geo = manifest["georeferencing"]
    world = manifest["world"]
    tile_size = manifest["tile_size"]
    width = world["width"]
    depth = world["depth"]
    transform = from_bounds(
        geo["bng_min_easting"],
        geo["bng_min_northing"],
        geo["bng_max_easting"],
        geo["bng_max_northing"],
        width,
        depth,
    )
    with rules.open("r", encoding="utf-8") as fh:
        config = yaml.safe_load(fh) or {}

    classes = config.get("classes") or {}
    if not classes:
        raise ValueError("surface geology rules must define classes")

    arr = np.zeros((depth, width), dtype=np.uint8)
    gpkg, tmp = resolve_gpkg(bgs)
    try:
        for class_name, class_config in classes.items():
            class_id = int(class_config.get("id", 0))
            if class_id == 0:
                continue
            shapes = []
            for layer_name in class_config.get("layers", []):
                try:
                    frame = gpd.read_file(
                        gpkg,
                        layer=layer_name,
                        bbox=(geo["bng_min_easting"], geo["bng_min_northing"], geo["bng_max_easting"], geo["bng_max_northing"]),
                    )
                except Exception as exc:
                    console.print(f"[yellow]Skipping {layer_name}: {exc}[/yellow]")
                    continue
                if frame.empty:
                    continue
                if frame.crs and str(frame.crs).upper() != "EPSG:27700":
                    frame = frame.to_crs("EPSG:27700")
                fields = class_config.get("fields") or [c for c in frame.columns if c != frame.geometry.name]
                available_fields = [c for c in fields if c in frame.columns]
                if available_fields:
                    text = frame[available_fields].fillna("").map(str).agg(" ".join, axis=1).str.lower()
                else:
                    text = frame.geometry.astype(str).str.lower()
                keywords = class_config.get("keywords", [])
                pattern = re.compile("|".join(re.escape(k.lower()) for k in keywords)) if keywords else None
                mask = text.str.contains(pattern, na=False) if pattern else np.zeros(len(frame), dtype=bool)
                shapes.extend((geom, class_id) for geom in frame.loc[mask, frame.geometry.name] if geom is not None and not geom.is_empty)
            if shapes:
                burned = rasterize(shapes, out_shape=arr.shape, transform=transform, fill=0, dtype=np.uint8, merge_alg=MergeAlg.replace)
                arr[burned > 0] = burned[burned > 0]
                console.print(f"{class_name}: burned {len(shapes)} features as id {class_id}")

        root = out / "geology" / "surface"
        _write_tiles(arr, root, tile_size)
        manifest["surface_geology"] = {
            "path": "geology/surface",
            "extension": ".u8.gz",
            "dtype": "uint8",
            "classes": {
                str(int(cfg.get("id", 0))): {
                    "name": name,
                    "block": cfg.get("block", "minecraft:stone"),
                    "fallback_block": cfg.get("fallback_block", "minecraft:stone"),
                    "color": cfg.get("color", "#777777"),
                }
                for name, cfg in classes.items()
            },
        }
        write_manifest(manifest_path, manifest)
        if debug_geotiff:
            _write_debug(debug_geotiff, arr, transform)
    finally:
        if tmp is not None:
            tmp.cleanup()


def _write_tiles(arr: np.ndarray, root: Path, tile_size: int) -> None:
    for tile_z in tqdm(range(math.ceil(arr.shape[0] / tile_size)), desc="surface geology tile rows"):
        for tile_x in range(math.ceil(arr.shape[1] / tile_size)):
            tile = arr[tile_z * tile_size : (tile_z + 1) * tile_size, tile_x * tile_size : (tile_x + 1) * tile_size]
            if tile.shape != (tile_size, tile_size):
                padded = np.zeros((tile_size, tile_size), dtype=np.uint8)
                padded[: tile.shape[0], : tile.shape[1]] = tile
                tile = padded
            write_u8_tile(root / f"{tile_x:03d}_{tile_z:03d}.u8.gz", tile)


def _write_debug(path: Path, arr: np.ndarray, transform) -> None:
    import rasterio

    path.parent.mkdir(parents=True, exist_ok=True)
    with rasterio.open(path, "w", driver="GTiff", height=arr.shape[0], width=arr.shape[1], count=1, dtype="uint8", crs="EPSG:27700", transform=transform) as dst:
        dst.write(arr, 1)
