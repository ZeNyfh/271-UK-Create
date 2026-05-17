from __future__ import annotations

from pathlib import Path
import math

import geopandas as gpd
import numpy as np
from rasterio.enums import MergeAlg
from rasterio.features import rasterize
from rasterio.transform import from_bounds
from rich.console import Console
from shapely.geometry import LineString, MultiLineString
from tqdm import tqdm

from .bgs import resolve_gpkg
from .manifest import read_manifest, write_manifest
from .tiles import write_u8_tile

console = Console()


def make_river_tiles(
    *,
    rivers: Path,
    manifest_path: Path,
    out: Path,
    layer: str | None = None,
    width_metres: float = 30.0,
    debug_geotiff: Path | None = None,
) -> None:
    manifest = read_manifest(manifest_path)
    geo = manifest["georeferencing"]
    world = manifest["world"]
    tile_size = int(manifest["tile_size"])
    width = int(world["width"])
    depth = int(world["depth"])
    transform = from_bounds(
        geo["bng_min_easting"],
        geo["bng_min_northing"],
        geo["bng_max_easting"],
        geo["bng_max_northing"],
        width,
        depth,
    )
    gpkg, tmp = resolve_gpkg(rivers)
    try:
        layer_name = layer or _default_layer(gpkg)
        frame = gpd.read_file(
            gpkg,
            layer=layer_name,
            bbox=(geo["bng_min_easting"], geo["bng_min_northing"], geo["bng_max_easting"], geo["bng_max_northing"]),
        )
        if frame.empty:
            console.print("[yellow]No river features intersect the manifest extent.[/yellow]")
            arr = np.zeros((depth, width), dtype=np.uint8)
        else:
            if frame.crs and str(frame.crs).upper() != "EPSG:27700":
                frame = frame.to_crs("EPSG:27700")
            shapes = []
            for geom in tqdm(frame.geometry, desc="buffering rivers"):
                if geom is None or geom.is_empty:
                    continue
                if not isinstance(geom, (LineString, MultiLineString)):
                    continue
                if width_metres > 0:
                    buffered = geom.buffer(width_metres / 2.0, cap_style="round", join_style="round")
                    shapes.append((buffered, 255))
                else:
                    shapes.append((geom, 255))
            arr = rasterize(shapes, out_shape=(depth, width), transform=transform, fill=0, dtype=np.uint8, merge_alg=MergeAlg.replace, all_touched=True) if shapes else np.zeros((depth, width), dtype=np.uint8)
        root = out / "water" / "rivers"
        _write_tiles(arr, root, tile_size)
        manifest["rivers"] = {
            "path": "water/rivers",
            "extension": ".u8.gz",
            "dtype": "uint8",
            "min": 0,
            "max": 255,
            "note": "255 marks cells inside buffered river/watercourse vectors.",
        }
        write_manifest(manifest_path, manifest)
        if debug_geotiff:
            _write_debug(debug_geotiff, arr, transform)
    finally:
        if tmp is not None:
            tmp.cleanup()


def _default_layer(gpkg: Path) -> str:
    import fiona

    layers = fiona.listlayers(gpkg)
    if "watercourse_link" in layers:
        return "watercourse_link"
    for layer in layers:
        if "watercourse" in layer.lower() or "river" in layer.lower():
            return layer
    return layers[0]


def _write_tiles(arr: np.ndarray, root: Path, tile_size: int) -> None:
    for tile_z in tqdm(range(math.ceil(arr.shape[0] / tile_size)), desc="river tile rows"):
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
