from __future__ import annotations

from pathlib import Path
import math

import geopandas as gpd
import numpy as np
from pyproj import Transformer
from rasterio.features import rasterize
from rasterio.transform import from_bounds
from rich.console import Console
from shapely.geometry.base import BaseGeometry
from tqdm import tqdm

from .bgs import resolve_gpkg
from .cop30_height import target_mask_lonlat
from .manifest import read_manifest, write_manifest
from .tiles import HEIGHT_NODATA, read_r16_tile, write_r16_tile, r16_extension

console = Console()


def mask_height_to_bgs_land(
    *,
    bgs: Path,
    manifest_path: Path,
    out: Path,
    layers: list[str] | None = None,
    buffer_metres: float = 250.0,
    max_height_metres: float = 20.0,
    preserve_height_overlays: bool = False,
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
    land_mask = np.zeros((depth, width), dtype=np.uint8)
    gpkg, tmp = resolve_gpkg(bgs)
    try:
        layer_names = layers or _default_land_layers(gpkg)
        for layer_name in layer_names:
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
            shapes = []
            for geom in tqdm(frame.geometry, desc=f"land mask {layer_name}"):
                prepared = _land_geometry(geom, buffer_metres)
                if prepared is not None:
                    shapes.append((prepared, 1))
            if shapes:
                burned = rasterize(shapes, out_shape=land_mask.shape, transform=transform, fill=0, dtype=np.uint8)
                land_mask = np.maximum(land_mask, burned)
                console.print(f"{layer_name}: burned {len(shapes)} land polygons")
    finally:
        if tmp is not None:
            tmp.cleanup()

    max_decimetres = int(round(max_height_metres * 10.0))
    removed = 0
    preserved = 0
    height_root = out / manifest["height"]["path"]
    tiles_x = math.ceil(width / tile_size)
    tiles_z = math.ceil(depth / tile_size)
    overlay_targets = _preserved_overlay_targets(manifest) if preserve_height_overlays else []
    for tile_z in tqdm(range(tiles_z), desc="masking height tile rows"):
        for tile_x in range(tiles_x):
            path = height_root / f"{tile_x:03d}_{tile_z:03d}{manifest['height'].get('extension', r16_extension())}"
            tile = read_r16_tile(path, tile_size)
            y0 = tile_z * tile_size
            x0 = tile_x * tile_size
            mask_tile = land_mask[y0 : y0 + tile_size, x0 : x0 + tile_size]
            if mask_tile.shape != tile.shape:
                padded = np.zeros(tile.shape, dtype=np.uint8)
                padded[: mask_tile.shape[0], : mask_tile.shape[1]] = mask_tile
                mask_tile = padded
            invalid = (mask_tile == 0) & (tile != HEIGHT_NODATA) & (tile <= max_decimetres)
            if overlay_targets and invalid.any():
                preserve_tile = _height_overlay_preserve_tile(manifest, tile_x, tile_z, overlay_targets)
                preserved += int((invalid & preserve_tile).sum())
                invalid &= ~preserve_tile
            if invalid.any():
                tile = tile.copy()
                tile[invalid] = HEIGHT_NODATA
                write_r16_tile(path, tile)
                removed += int(invalid.sum())

    manifest.setdefault("height_cleanup", {})["bgs_land_mask"] = {
        "source": str(bgs),
        "layers": layer_names,
        "buffer_metres": buffer_metres,
        "max_height_metres": max_height_metres,
        "preserve_height_overlays": preserve_height_overlays,
        "preserved_overlay_cells": preserved,
        "removed_cells": removed,
        "note": "Near-sea-level cells outside BGS land polygons were converted to height nodata/ocean.",
    }
    write_manifest(manifest_path, manifest)
    if debug_geotiff:
        _write_debug(debug_geotiff, land_mask, transform)
    if preserve_height_overlays:
        console.print(f"Preserved {preserved:,} height overlay cells from BGS land masking.")
    console.print(f"Converted {removed:,} low outside-land height cells to nodata/ocean.")


def _preserved_overlay_targets(manifest: dict) -> list[str]:
    targets = []
    for overlay in manifest.get("height_overlays", []):
        if overlay.get("source") == "Copernicus DEM COP30 GeoTIFF":
            target = overlay.get("target", "ireland-iom")
            if target not in targets:
                targets.append(target)
    return targets


def _height_overlay_preserve_tile(manifest: dict, tile_x: int, tile_z: int, targets: list[str]) -> np.ndarray:
    tile_size = int(manifest["tile_size"])
    world = manifest["world"]
    geo = manifest["georeferencing"]
    width = int(world["width"])
    depth = int(world["depth"])
    x_scale = (float(geo["bng_max_easting"]) - float(geo["bng_min_easting"])) / width
    z_scale = (float(geo["bng_max_northing"]) - float(geo["bng_min_northing"])) / depth
    cols = np.arange(tile_x * tile_size, tile_x * tile_size + tile_size, dtype=np.float64)
    rows = np.arange(tile_z * tile_size, tile_z * tile_size + tile_size, dtype=np.float64)
    xs = float(geo["bng_min_easting"]) + (cols + 0.5) * x_scale
    ys = float(geo["bng_max_northing"]) - (rows + 0.5) * z_scale
    lon, lat = Transformer.from_crs(geo.get("crs", "EPSG:27700"), "EPSG:4326", always_xy=True).transform(xs[None, :], ys[:, None])
    preserve = np.zeros((tile_size, tile_size), dtype=bool)
    for target in targets:
        preserve |= target_mask_lonlat(lon, lat, target)
    return preserve


def _default_land_layers(gpkg: Path) -> list[str]:
    import fiona

    layers = fiona.listlayers(gpkg)
    preferred = [
        layer for layer in layers
        if ("bedrock" in layer.lower() or "superficial" in layer.lower()) and "geology" in layer.lower()
    ]
    return preferred or [layer for layer in layers if "style" not in layer.lower() and "fault" not in layer.lower()]


def _land_geometry(geom: BaseGeometry | None, buffer_metres: float) -> BaseGeometry | None:
    if geom is None or geom.is_empty:
        return None
    if not geom.geom_type.lower().endswith("polygon"):
        return None
    if buffer_metres > 0:
        geom = geom.buffer(buffer_metres)
    return None if geom.is_empty else geom


def _write_debug(path: Path, arr: np.ndarray, transform) -> None:
    import rasterio

    path.parent.mkdir(parents=True, exist_ok=True)
    with rasterio.open(path, "w", driver="GTiff", height=arr.shape[0], width=arr.shape[1], count=1, dtype="uint8", crs="EPSG:27700", transform=transform) as dst:
        dst.write(arr, 1)
